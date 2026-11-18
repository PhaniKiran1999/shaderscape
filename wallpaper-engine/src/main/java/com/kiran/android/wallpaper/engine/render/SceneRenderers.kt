package com.kiran.android.wallpaper.engine.render

import android.opengl.GLES20
import com.kiran.android.wallpaper.engine.PixelScene
import com.kiran.android.wallpaper.engine.ShaderScene
import com.kiran.android.wallpaper.engine.UniformScope
import com.kiran.android.wallpaper.engine.WallpaperScene
import com.kiran.android.wallpaper.engine.gl.FULLSCREEN_VERTEX_SHADER
import com.kiran.android.wallpaper.engine.gl.FullscreenQuad
import com.kiran.android.wallpaper.engine.gl.ShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/** Per-draw inputs from the compositor to a layer. */
internal class LayerDrawContext {
    var opacity = 1f
    /** True when this layer is drawn over other layers (not the bottom one). */
    var layered = false
    /** GL texture holding the composite of the layers below, or 0 when unavailable. */
    var belowTexture = 0
    var width = 0
    var height = 0
}

/**
 * Draws one layer. Every method runs on the render thread with the GL context current.
 * Output must be premultiplied alpha with opacity applied, so the compositor can use fixed
 * blend functions.
 */
internal interface LayerRenderer {
    /** The scene this layer animates, if any: it receives touch and offsets. */
    val scene: WallpaperScene? get() = null
    val readsBelow: Boolean get() = false
    /** Animated layers need continuous frames. Static ones redraw only when something changes. */
    val animated: Boolean get() = true

    fun init()
    fun resize(width: Int, height: Int)
    fun draw(timeSec: Float, ctx: LayerDrawContext)
    fun release()
    fun onOffset(xOffset: Float) {}

    companion object {
        fun forScene(scene: WallpaperScene): LayerRenderer = when (scene) {
            is ShaderScene -> ShaderSceneRenderer(scene)
            is PixelScene -> PixelSceneRenderer(scene)
        }
    }
}

/** GPU path: runs the scene's fragment shader over the whole target. */
internal class ShaderSceneRenderer(override val scene: ShaderScene) : LayerRenderer, UniformScope {

    private lateinit var program: ShaderProgram
    private val quad = FullscreenQuad()
    private var width = 0
    private var height = 0

    override val readsBelow: Boolean get() = scene.readsBelow

    override fun init() {
        program = ShaderProgram(
            FULLSCREEN_VERTEX_SHADER,
            ShaderScene.SHADER_HEADER + scene.fragmentShader + ShaderScene.SHADER_FOOTER,
        )
    }

    override fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    override fun draw(timeSec: Float, ctx: LayerDrawContext) {
        program.use()
        set("uTime", timeSec)
        set("uResolution", width.toFloat(), height.toFloat())
        if (scene.touchX >= 0f) {
            // Convert the normalised top-left touch to GL pixel coordinates (bottom-left origin).
            set("uTouch", scene.touchX * width, (1f - scene.touchY) * height)
        } else {
            set("uTouch", -1f, -1f)
        }
        set("uOffset", scene.xOffset)
        bindTaps(timeSec)
        set("uLayered", if (ctx.layered) 1f else 0f)
        set("uOpacity", ctx.opacity)
        if (ctx.belowTexture != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ctx.belowTexture)
            setInt("uBelow", 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
        scene.onBindUniforms(this, timeSec)
        quad.draw(program.attribute("aPosition"))
    }

    override fun release() = program.release()

    // Recent taps: x, y (GL pixels) and the time they happened, newest last. start < 0 = empty.
    private val tapX = FloatArray(ShaderScene.MAX_TAPS)
    private val tapY = FloatArray(ShaderScene.MAX_TAPS)
    private val tapStart = FloatArray(ShaderScene.MAX_TAPS) { -1f }
    private val tapUniform = FloatArray(ShaderScene.MAX_TAPS * 3)

    /** Stamps new taps with the current time and uploads `uTaps` (newest first, z = age in seconds). */
    private fun bindTaps(timeSec: Float) {
        while (scene.pendingTaps.isNotEmpty()) {
            val (nx, ny) = scene.pendingTaps.removeFirst()
            for (i in 0 until ShaderScene.MAX_TAPS - 1) {
                tapX[i] = tapX[i + 1]; tapY[i] = tapY[i + 1]; tapStart[i] = tapStart[i + 1]
            }
            val last = ShaderScene.MAX_TAPS - 1
            tapX[last] = nx * width
            tapY[last] = (1f - ny) * height
            tapStart[last] = timeSec
        }
        for (k in 0 until ShaderScene.MAX_TAPS) {
            val i = ShaderScene.MAX_TAPS - 1 - k // newest first
            val age = timeSec - tapStart[i]
            val live = tapStart[i] >= 0f && age >= 0f && age <= ShaderScene.TAP_LIFETIME_SEC
            tapUniform[k * 3] = if (live) tapX[i] else -1f
            tapUniform[k * 3 + 1] = if (live) tapY[i] else -1f
            tapUniform[k * 3 + 2] = if (live) age else -1f
        }
        val loc = program.uniform("uTaps")
        if (loc >= 0) GLES20.glUniform3fv(loc, ShaderScene.MAX_TAPS, tapUniform, 0)
    }

    override fun set(name: String, value: Float) = GLES20.glUniform1f(program.uniform(name), value)
    override fun set(name: String, x: Float, y: Float) = GLES20.glUniform2f(program.uniform(name), x, y)
    override fun set(name: String, x: Float, y: Float, z: Float) =
        GLES20.glUniform3f(program.uniform(name), x, y, z)
    override fun set(name: String, x: Float, y: Float, z: Float, w: Float) =
        GLES20.glUniform4f(program.uniform(name), x, y, z, w)
    override fun setInt(name: String, value: Int) = GLES20.glUniform1i(program.uniform(name), value)
}

/**
 * CPU path: the scene writes ARGB ints, which are uploaded as a texture and scaled up
 * with GL_NEAREST.
 */
internal class PixelSceneRenderer(override val scene: PixelScene) : LayerRenderer {

    private lateinit var program: ShaderProgram
    private val quad = FullscreenQuad()
    private var texture = 0
    private var pixels = IntArray(0)
    private var upload: IntBuffer? = null
    private var bufferWidth = 0
    private var bufferHeight = 0

    override fun init() {
        program = ShaderProgram(FULLSCREEN_VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
        texture = createTexture(GLES20.GL_NEAREST)
        bufferWidth = 0 // a fresh texture needs re-allocating in resize()
        bufferHeight = 0
    }

    override fun resize(width: Int, height: Int) {
        val (bw, bh) = PixelScene.bufferSizeFor(scene.targetWidth, width, height)
        if (bw == bufferWidth && bh == bufferHeight) return
        bufferWidth = bw
        bufferHeight = bh
        pixels = IntArray(bw * bh)
        upload = ByteBuffer.allocateDirect(bw * bh * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bw, bh, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        scene.dispatchBufferSize(bw, bh)
    }

    override fun draw(timeSec: Float, ctx: LayerDrawContext) {
        val buffer = upload ?: return
        scene.render(pixels, timeSec)
        buffer.position(0)
        buffer.put(pixels)
        buffer.position(0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, bufferWidth, bufferHeight,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
        )
        program.use()
        GLES20.glUniform1i(program.uniform("uTexture"), 0)
        GLES20.glUniform1f(program.uniform("uOpacity"), ctx.opacity)
        quad.draw(program.attribute("aPosition"))
    }

    override fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        program.release()
    }

    private companion object {
        // On little-endian Android an ARGB int is stored as the bytes B,G,R,A, so GL reads it
        // as (r=B, g=G, b=R). Swizzling with .bgr restores the original colour.
        // Buffer row 0 is the top of the screen, but GL texture row 0 is the bottom, so flip V.
        // The output is premultiplied, with opacity applied, as the compositor expects.
        const val BLIT_FRAGMENT_SHADER = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTexture;
uniform float uOpacity;
void main() {
    vec4 c = texture2D(uTexture, vec2(vUv.x, 1.0 - vUv.y));
    gl_FragColor = vec4(c.bgr * c.a, c.a) * uOpacity;
}
"""
    }
}

internal fun createTexture(filter: Int): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    return ids[0]
}
