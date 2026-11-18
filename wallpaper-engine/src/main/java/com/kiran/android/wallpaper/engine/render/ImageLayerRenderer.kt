package com.kiran.android.wallpaper.engine.render

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import com.kiran.android.wallpaper.engine.gl.FULLSCREEN_VERTEX_SHADER
import com.kiran.android.wallpaper.engine.gl.FullscreenQuad
import com.kiran.android.wallpaper.engine.gl.ShaderProgram
import java.io.File

/**
 * Draws a stored photo scaled to cover the target (cropping the overflow). When [parallax]
 * is on and the photo is wider than the screen, it pans with the launcher's page offset.
 * Static: it only needs redrawing when something changes.
 */
internal class ImageLayerRenderer(
    private val path: String,
    private val parallax: Boolean,
) : LayerRenderer {

    override val animated: Boolean get() = false

    private lateinit var program: ShaderProgram
    private val quad = FullscreenQuad()
    private var texture = 0
    private var imageWidth = 0
    private var imageHeight = 0
    private var width = 0
    private var height = 0
    private var pan = 0.5f

    override fun init() {
        program = ShaderProgram(FULLSCREEN_VERTEX_SHADER, IMAGE_FRAGMENT_SHADER)
        texture = createTexture(GLES20.GL_LINEAR)
        imageWidth = 0 // a fresh texture needs uploading in resize()
        imageHeight = 0
    }

    override fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
        if (imageWidth == 0) upload(width, height)
    }

    override fun onOffset(xOffset: Float) {
        if (parallax) pan = xOffset
    }

    override fun draw(timeSec: Float, ctx: LayerDrawContext) {
        if (imageWidth == 0) return
        val fit = CoverFit.compute(imageWidth, imageHeight, width, height, pan)
        program.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(program.uniform("uTexture"), 0)
        GLES20.glUniform4f(program.uniform("uFit"), fit[0], fit[1], fit[2], fit[3])
        GLES20.glUniform1f(program.uniform("uOpacity"), ctx.opacity)
        quad.draw(program.attribute("aPosition"))
    }

    override fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        program.release()
    }

    /** Decodes at roughly the surface size (photos were pre-rotated on import) and uploads. */
    private fun upload(targetW: Int, targetH: Int) {
        val file = File(path)
        require(file.exists()) { "Image missing: $path" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        // Keep at least surface resolution on the short axis, so the cover crop stays sharp.
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Could not decode $path")
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            imageWidth = bitmap.width
            imageHeight = bitmap.height
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        // Bitmap row 0 is uploaded at texture t = 0 but vUv.y = 0 is the bottom, so flip V.
        const val IMAGE_FRAGMENT_SHADER = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTexture;
uniform vec4 uFit;       // xy = uv scale, zw = uv offset
uniform float uOpacity;
void main() {
    vec2 uv = vec2(vUv.x, 1.0 - vUv.y) * uFit.xy + uFit.zw;
    vec4 c = texture2D(uTexture, uv);
    gl_FragColor = vec4(c.rgb * c.a, c.a) * uOpacity;
}
"""
    }
}

/** "Cover" scaling: fills the target with the image, cropping whichever axis overflows. */
internal object CoverFit {
    /**
     * Returns `[scaleU, scaleV, offsetU, offsetV]` mapping target UV (0..1) to image UV.
     * [pan] (0..1) picks which part of an over-wide image shows. A tall image is centred.
     */
    fun compute(imageW: Int, imageH: Int, targetW: Int, targetH: Int, pan: Float): FloatArray {
        if (imageW <= 0 || imageH <= 0 || targetW <= 0 || targetH <= 0) return floatArrayOf(1f, 1f, 0f, 0f)
        val imageAspect = imageW.toFloat() / imageH
        val targetAspect = targetW.toFloat() / targetH
        return if (imageAspect > targetAspect) {
            val su = targetAspect / imageAspect
            floatArrayOf(su, 1f, (1f - su) * pan.coerceIn(0f, 1f), 0f)
        } else {
            val sv = imageAspect / targetAspect
            floatArrayOf(1f, sv, 0f, (1f - sv) / 2f)
        }
    }
}
