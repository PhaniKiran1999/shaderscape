package com.kiran.android.wallpaper.engine.render

import android.opengl.GLES20
import android.util.Log
import com.kiran.android.wallpaper.engine.composition.BlendMode
import com.kiran.android.wallpaper.engine.composition.Layer
import com.kiran.android.wallpaper.engine.gl.FULLSCREEN_VERTEX_SHADER
import com.kiran.android.wallpaper.engine.gl.FullscreenQuad
import com.kiran.android.wallpaper.engine.gl.RenderTarget
import com.kiran.android.wallpaper.engine.gl.ShaderProgram

/**
 * Draws a stack of layers, bottom to top, into the current window surface.
 *
 * - **Fast path:** when no layer reads below, every layer blends straight onto the window
 *   with its GL blend function.
 * - **Read-below path:** layers composite into offscreen target A. A layer that reads below
 *   gets A as `uBelow`. It draws into B, which first receives a copy of A, so its blend mode
 *   and opacity still apply. Then A and B swap. A final blit copies A to the window.
 *
 * Layer renderers are owned by the caller (`RenderThread` reuses them across edits). This
 * class only orders, blends and composites them.
 */
internal class CompositionRenderer {

    class Entry(val layer: Layer, val renderer: LayerRenderer)

    private var entries: List<Entry> = emptyList()
    private val ctx = LayerDrawContext()
    private val quad = FullscreenQuad()
    private var blit: ShaderProgram? = null
    private var a: RenderTarget? = null
    private var b: RenderTarget? = null
    private var width = 0
    private var height = 0

    val animated: Boolean get() = entries.any { it.layer.visible && it.renderer.animated }

    fun setEntries(list: List<Entry>) {
        entries = list
        if (needsOffscreen()) ensureTargets() else releaseTargets()
    }

    fun resize(width: Int, height: Int) {
        if (this.width == width && this.height == height) return
        this.width = width
        this.height = height
        releaseTargets()
        if (needsOffscreen()) ensureTargets()
    }

    fun draw(timeSec: Float) {
        GLES20.glViewport(0, 0, width, height)
        if (!needsOffscreen()) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            clear()
            drawLayers(timeSec, below = null)
            GLES20.glDisable(GLES20.GL_BLEND)
            return
        }
        ensureTargets()
        var front = a!!
        var back = b!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, front.framebuffer)
        clear()
        var first = true
        for (e in entries) {
            if (!e.layer.visible || e.layer.opacity <= 0f) continue
            if (e.renderer.readsBelow) {
                // B = A, then this layer (sampling A) blends over B. Swap so B is the new composite.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, back.framebuffer)
                GLES20.glDisable(GLES20.GL_BLEND)
                copy(front.texture)
                drawLayer(e, timeSec, layered = !first, belowTexture = front.texture)
                val t = front; front = back; back = t
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, front.framebuffer)
                drawLayer(e, timeSec, layered = !first, belowTexture = 0)
            }
            first = false
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        copy(front.texture)
        a = front
        b = back
    }

    fun release() {
        releaseTargets()
        blit?.release()
        blit = null
    }

    private fun drawLayers(timeSec: Float, below: Int?) {
        var first = true
        for (e in entries) {
            if (!e.layer.visible || e.layer.opacity <= 0f) continue
            drawLayer(e, timeSec, layered = !first, belowTexture = below ?: 0)
            first = false
        }
    }

    private fun drawLayer(e: Entry, timeSec: Float, layered: Boolean, belowTexture: Int) {
        val (src, dst) = BlendFuncs.of(e.layer.blend)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(src, dst)
        ctx.opacity = e.layer.opacity
        ctx.layered = layered
        ctx.belowTexture = belowTexture
        ctx.width = width
        ctx.height = height
        try {
            e.renderer.draw(timeSec, ctx)
        } catch (ex: RuntimeException) {
            Log.e(TAG, "Layer ${e.layer.id} failed to draw", ex)
        }
    }

    private fun clear() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun copy(texture: Int) {
        val p = blit ?: ShaderProgram(FULLSCREEN_VERTEX_SHADER, COPY_FRAGMENT_SHADER).also { blit = it }
        p.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(p.uniform("uTexture"), 0)
        quad.draw(p.attribute("aPosition"))
    }

    private fun needsOffscreen() = entries.any { it.layer.visible && it.renderer.readsBelow }

    private fun ensureTargets() {
        if (width <= 0 || height <= 0) return
        if (a == null) a = RenderTarget(width, height)
        if (b == null) b = RenderTarget(width, height)
    }

    private fun releaseTargets() {
        a?.release(); a = null
        b?.release(); b = null
    }

    private companion object {
        const val TAG = "CompositionRenderer"
        const val COPY_FRAGMENT_SHADER = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTexture;
void main() { gl_FragColor = texture2D(uTexture, vUv); }
"""
    }
}

/** GL blend functions for premultiplied-alpha layer output. */
internal object BlendFuncs {
    fun of(mode: BlendMode): Pair<Int, Int> = when (mode) {
        BlendMode.NORMAL -> GLES20.GL_ONE to GLES20.GL_ONE_MINUS_SRC_ALPHA
        BlendMode.ADD -> GLES20.GL_ONE to GLES20.GL_ONE
        BlendMode.SCREEN -> GLES20.GL_ONE to GLES20.GL_ONE_MINUS_SRC_COLOR
        BlendMode.MULTIPLY -> GLES20.GL_DST_COLOR to GLES20.GL_ONE_MINUS_SRC_ALPHA
    }
}
