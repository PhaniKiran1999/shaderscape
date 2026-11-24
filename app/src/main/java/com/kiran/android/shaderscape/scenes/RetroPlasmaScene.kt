package com.kiran.android.shaderscape.scenes

import com.kiran.android.wallpaper.engine.Palette
import com.kiran.android.wallpaper.engine.PixelScene
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Old-school demoscene plasma: sine lookup tables summed per pixel, mapped through a
 * 32-colour cycling palette for chunky colour bands. Touch moves the swirl's centre.
 */
class RetroPlasmaScene : PixelScene(targetWidth = 96) {

    override val targetFps = 30

    private val sinTable = FloatArray(TABLE_SIZE) { sin(it * 2.0 * PI / TABLE_SIZE).toFloat() }
    private val palette = Palette.gradient(
        32,
        0xFF2B0F54.toInt(), 0xFFAB1F65.toInt(), 0xFFFF4F69.toInt(), 0xFFFF8142.toInt(),
        0xFFFFDA45.toInt(), 0xFF3368DC.toInt(), 0xFF49E7EC.toInt(), 0xFF2B0F54.toInt(),
    )
    private var centerX = 0.5f
    private var centerY = 0.5f

    private fun fastSin(v: Float): Float = sinTable[(v * TABLE_SCALE).toInt() and (TABLE_SIZE - 1)]

    override fun render(pixels: IntArray, timeSec: Float) {
        val w = bufferWidth
        val h = bufferHeight
        if (w == 0) return

        val targetX = if (touchX >= 0f) touchX else 0.5f + 0.3f * fastSin(timeSec * 0.3f)
        val targetY = if (touchY >= 0f) touchY else 0.5f + 0.3f * fastSin(timeSec * 0.23f + 1.3f)
        centerX += (targetX - centerX) * 0.08f
        centerY += (targetY - centerY) * 0.08f
        val cx = centerX * w
        val cy = centerY * h
        val shift = (timeSec * 12f).toInt()

        var i = 0
        for (y in 0 until h) {
            val fy = y.toFloat()
            val rowWave = fastSin(fy * 0.07f - timeSec * 1.3f)
            for (x in 0 until w) {
                val fx = x.toFloat()
                val dx = fx - cx
                val dy = fy - cy
                val v = fastSin(fx * 0.11f + timeSec) +
                    rowWave +
                    fastSin((fx + fy) * 0.05f + timeSec * 0.7f) +
                    fastSin(sqrt(dx * dx + dy * dy) * 0.18f - timeSec * 1.7f)
                pixels[i++] = palette[(((v + 4f) * 4f).toInt() + shift) and 31]
            }
        }
    }

    private companion object {
        const val TABLE_SIZE = 1024
        const val TABLE_SCALE = (TABLE_SIZE / (2.0 * PI)).toFloat()
    }
}
