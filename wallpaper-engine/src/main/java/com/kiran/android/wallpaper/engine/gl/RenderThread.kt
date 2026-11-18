package com.kiran.android.wallpaper.engine.gl

import android.content.Context
import android.content.pm.ApplicationInfo
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.kiran.android.wallpaper.engine.WallpaperRegistry
import com.kiran.android.wallpaper.engine.composition.Composition
import com.kiran.android.wallpaper.engine.composition.Layer
import com.kiran.android.wallpaper.engine.composition.LayerSource
import com.kiran.android.wallpaper.engine.render.CompositionRenderer
import com.kiran.android.wallpaper.engine.render.ImageLayerRenderer
import com.kiran.android.wallpaper.engine.render.LayerRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A dedicated GL thread for one output surface: the wallpaper engine or one preview view.
 * It renders a [Composition], which is a stack of layers.
 *
 * - Public methods can be called from any thread. Work is posted to this thread's looper.
 * - Layer renderers are pooled by layer id and source. Editing opacity, blend, visibility or
 *   order reuses them, so shaders and photos aren't reloaded and scenes keep their state.
 * - Frames are driven by [Choreographer], capped at the fastest scene's FPS (and [maxFps]),
 *   and stop completely while invisible. A composition with no animated layer (photos only)
 *   is drawn once per change.
 */
internal class RenderThread(
    context: Context,
    private val name: String,
    private val maxFps: Int = Int.MAX_VALUE,
) : Choreographer.FrameCallback {

    private class Slot(val renderer: LayerRenderer) {
        var ready = false
        var failed = false
        var width = 0
        var height = 0
    }

    private val appContext = context.applicationContext
    private val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val thread = HandlerThread(name).also { it.start() }
    private val handler = Handler(thread.looper)
    private var choreographer: Choreographer? = null

    private var egl: EglCore? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var composition: Composition? = null
    private val pool = LinkedHashMap<String, Slot>()
    private val compositor = CompositionRenderer()
    private var width = 0
    private var height = 0
    private var xOffset = 0.5f
    private var visible = false
    private var dirty = true
    private var frameScheduled = false
    private var lastDrawNanos = 0L
    private val startNanos = System.nanoTime()

    // Debug-only draw timing: CPU time to submit a frame, not GPU time.
    private var statNanos = 0L
    private var statFrames = 0
    private var statSince = SystemClock.uptimeMillis()

    @Volatile
    private var quitting = false

    init {
        handler.post { choreographer = Choreographer.getInstance() }
    }

    // ---- Public API (any thread) ----

    fun setComposition(newComposition: Composition?) = post {
        composition = newComposition
        rebuildPool()
        syncGl()
        requestDraw()
    }

    fun surfaceCreated(surface: Surface) = post {
        try {
            val core = egl ?: EglCore().also { egl = it }
            eglSurface = core.createWindowSurface(surface)
            core.makeCurrent(eglSurface)
            syncGl()
            requestDraw()
        } catch (e: RuntimeException) {
            Log.e(TAG, "[$name] EGL setup failed", e)
        }
    }

    fun surfaceChanged(width: Int, height: Int) = post {
        this.width = width
        this.height = height
        syncGl()
        requestDraw()
    }

    /** Blocks until GL resources are released, because the caller's Surface is invalid once it returns. */
    fun surfaceDestroyed() = postAndWait {
        if (hasSurface()) {
            egl?.makeCurrent(eglSurface)
            releaseGl()
            egl?.destroySurface(eglSurface)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    fun setVisible(isVisible: Boolean) = post {
        if (visible == isVisible) return@post
        visible = isVisible
        Log.d(TAG, "[$name] ${if (isVisible) "resume" else "pause"}")
        if (isVisible) {
            lastDrawNanos = 0L
            requestDraw()
        }
    }

    fun touch(x: Float, y: Float) = post {
        pool.values.forEach { it.renderer.scene?.dispatchTouch(x, y) }
    }

    fun offset(offset: Float) = post {
        xOffset = offset
        pool.values.forEach {
            it.renderer.scene?.dispatchOffset(offset)
            it.renderer.onOffset(offset)
        }
        requestDraw()
    }

    /** Releases everything and stops the thread. Blocks until done. */
    fun quit() {
        if (quitting) return
        postAndWait {
            choreographer?.removeFrameCallback(this)
            if (hasSurface()) {
                egl?.makeCurrent(eglSurface)
                releaseGl()
                egl?.destroySurface(eglSurface)
            }
            pool.values.forEach { it.renderer.scene?.release() }
            pool.clear()
            eglSurface = EGL14.EGL_NO_SURFACE
            egl?.release()
            egl = null
        }
        quitting = true
        thread.quitSafely()
    }

    // ---- Render thread only ----

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (quitting || !visible || !hasSurface()) return
        val animated = compositor.animated
        if (!animated && !dirty) return

        if (animated) {
            val interval = 1_000_000_000L / targetFps()
            // 2 ms of slack so a 30 fps cap on a 60 Hz display reliably lands on every second vsync.
            if (!dirty && lastDrawNanos != 0L && frameTimeNanos - lastDrawNanos < interval - 2_000_000L) {
                scheduleFrame()
                return
            }
        }
        lastDrawNanos = frameTimeNanos
        dirty = false
        drawFrame(frameTimeNanos)
        if (animated) scheduleFrame()
    }

    private fun drawFrame(frameTimeNanos: Long) {
        val core = egl ?: return
        if (!core.makeCurrent(eglSurface)) return
        val t0 = System.nanoTime()
        if (pool.values.none { it.ready }) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        } else {
            val seconds = ((frameTimeNanos - startNanos).coerceAtLeast(0L) / 1e9) % TIME_WRAP_SEC
            compositor.draw(seconds.toFloat())
        }
        core.swapBuffers(eglSurface)
        if (debuggable) recordStats(System.nanoTime() - t0)
    }

    private fun targetFps(): Int {
        val visibleIds = composition?.visibleLayers?.mapTo(HashSet()) { it.id } ?: emptySet<String>()
        val fps = pool.entries
            .filter { (key, _) -> key.substringBefore('|') in visibleIds }
            .mapNotNull { it.value.renderer.scene?.targetFps }
            .maxOrNull() ?: 30
        return minOf(fps, maxFps).coerceAtLeast(1)
    }

    /** Matches the composition's layers to pooled renderers, creating or retiring as needed. */
    private fun rebuildPool() {
        val layers = composition?.layers ?: emptyList()
        val next = LinkedHashMap<String, Slot>()
        for (layer in layers) {
            val key = keyOf(layer)
            val slot = pool.remove(key) ?: createSlot(layer) ?: continue
            next[key] = slot
        }
        // Retire renderers no longer used.
        if (pool.isNotEmpty()) {
            if (hasSurface()) egl?.makeCurrent(eglSurface)
            pool.values.forEach { retire(it) }
        }
        pool.clear()
        pool.putAll(next)
    }

    private fun createSlot(layer: Layer): Slot? = try {
        val renderer = when (val src = layer.source) {
            is LayerSource.Scene -> {
                val scene = WallpaperRegistry.create(appContext, src.sceneId) ?: return null
                scene.dispatchOffset(xOffset)
                LayerRenderer.forScene(scene)
            }
            is LayerSource.Image -> ImageLayerRenderer(src.path, src.parallax).also { it.onOffset(xOffset) }
        }
        Slot(renderer)
    } catch (e: RuntimeException) {
        Log.e(TAG, "[$name] could not create layer ${layer.source}", e)
        null
    }

    /** Initialises and sizes renderers that need it, then hands the ordered stack to the compositor. */
    private fun syncGl() {
        val core = egl ?: return
        if (!hasSurface()) return
        core.makeCurrent(eglSurface)
        for (slot in pool.values) {
            if (!slot.ready && !slot.failed) {
                try {
                    slot.renderer.init()
                    slot.ready = true
                } catch (e: RuntimeException) {
                    slot.failed = true
                    Log.e(TAG, "[$name] layer init failed", e)
                }
            }
            if (slot.ready && width > 0 && height > 0 && (slot.width != width || slot.height != height)) {
                try {
                    slot.renderer.scene?.dispatchSurfaceChanged(width, height)
                    slot.renderer.resize(width, height)
                    slot.width = width
                    slot.height = height
                } catch (e: RuntimeException) {
                    slot.failed = true
                    slot.ready = false
                    Log.e(TAG, "[$name] layer resize failed", e)
                }
            }
        }
        if (width > 0 && height > 0) compositor.resize(width, height)
        val layers = composition?.layers ?: emptyList()
        compositor.setEntries(layers.mapNotNull { layer ->
            pool[keyOf(layer)]?.takeIf { it.ready }?.let { CompositionRenderer.Entry(layer, it.renderer) }
        })
    }

    /** Frees GL objects but keeps scenes, so their state survives a surface change. */
    private fun releaseGl() {
        for (slot in pool.values) {
            if (slot.ready) runCatching { slot.renderer.release() }
            slot.ready = false
            slot.failed = false
            slot.width = 0
            slot.height = 0
        }
        compositor.setEntries(emptyList())
        compositor.release()
    }

    private fun retire(slot: Slot) {
        if (slot.ready && hasSurface()) runCatching { slot.renderer.release() }
        slot.renderer.scene?.release()
    }

    private fun requestDraw() {
        dirty = true
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (frameScheduled || quitting || !visible || !hasSurface()) return
        val c = choreographer ?: return
        c.postFrameCallback(this)
        frameScheduled = true
    }

    private fun recordStats(nanos: Long) {
        statNanos += nanos
        statFrames++
        val now = SystemClock.uptimeMillis()
        if (now - statSince >= 5000) {
            Log.d(
                TAG,
                "[$name] avg draw %.2f ms over %d frames, %d layers".format(
                    statNanos / 1e6 / statFrames, statFrames, compositorLayerCount(),
                ),
            )
            statNanos = 0
            statFrames = 0
            statSince = now
        }
    }

    private fun compositorLayerCount() = composition?.visibleLayers?.size ?: 0

    private fun hasSurface() = eglSurface != EGL14.EGL_NO_SURFACE

    private fun post(block: () -> Unit) {
        if (!quitting) handler.post(block)
    }

    private fun postAndWait(block: () -> Unit) {
        if (quitting) return
        val latch = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "[$name] timed out waiting for the render thread")
        }
    }

    private companion object {
        const val TAG = "RenderThread"
        const val TIME_WRAP_SEC = 3600.0
        const val WAIT_TIMEOUT_MS = 2000L

        /** Same id and same source keep the same renderer. Changing a layer's source rebuilds it. */
        fun keyOf(layer: Layer) = "${layer.id}|${layer.source}"
    }
}
