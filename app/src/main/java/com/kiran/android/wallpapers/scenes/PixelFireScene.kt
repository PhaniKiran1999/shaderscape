package com.kiran.android.wallpapers.scenes

import com.kiran.android.wallpaper.engine.Palette
import com.kiran.android.wallpaper.engine.PixelScene

/**
 * The classic "spreading fire" cellular effect. The bottom row is held at maximum heat,
 * and each cell above copies the cell below it with a random sideways jitter and decay.
 * Touch adds heat.
 */
class PixelFireScene : PixelScene(targetWidth = 80) {

    override val targetFps = 30

    private var w = 0
    private var h = 0
    private var heat = IntArray(0)
    private var seed = 0x2545F491

    private val palette = Palette.gradient(
        MAX_HEAT + 1,
        0xFF070707.toInt(), 0xFF2F0F07.toInt(), 0xFF771F07.toInt(), 0xFFAF3F07.toInt(),
        0xFFD7670F.toInt(), 0xFFDF9F1F.toInt(), 0xFFCFB72F.toInt(), 0xFFEFEFC7.toInt(),
        0xFFFFFFFF.toInt(),
    )

    override fun onBufferSizeChanged(width: Int, height: Int) {
        w = width
        h = height
        heat = IntArray(width * height)
        for (x in 0 until width) heat[(height - 1) * width + x] = MAX_HEAT
    }

    override fun render(pixels: IntArray, timeSec: Float) {
        if (touchX >= 0f) ignite((touchX * w).toInt(), (touchY * h).toInt())

        for (x in 0 until w) {
            for (y in 1 until h) {
                val src = y * w + x
                val r = nextRandom() and 3
                val dst = src - w - r + 1
                if (dst < 0) continue
                heat[dst] = (heat[src] - (r and 1)).coerceAtLeast(0)
            }
        }
        for (i in heat.indices) pixels[i] = palette[heat[i]]
    }

    private fun ignite(cx: Int, cy: Int) {
        for (dy in -2..2) for (dx in -3..3) {
            val x = cx + dx
            val y = cy + dy
            if (x in 0 until w && y in 0 until h) heat[y * w + x] = MAX_HEAT
        }
    }

    private fun nextRandom(): Int {
        var s = seed
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        seed = s
        return s
    }

    private companion object {
        const val MAX_HEAT = 36
    }
}
