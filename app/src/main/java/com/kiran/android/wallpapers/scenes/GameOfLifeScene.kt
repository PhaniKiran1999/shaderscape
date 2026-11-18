package com.kiran.android.wallpapers.scenes

import com.kiran.android.wallpaper.engine.Palette
import com.kiran.android.wallpaper.engine.PixelScene
import kotlin.random.Random

/**
 * Conway's Game of Life on a wrap-around grid. Live cells change colour with age, and dead
 * cells leave a fading trail. The board is reseeded every so often so it never dies out.
 * Touch draws new cells.
 */
class GameOfLifeScene : PixelScene(targetWidth = 72) {

    override val targetFps = 30

    private var w = 0
    private var h = 0
    private var age = IntArray(0)    // 0 = dead, otherwise generations alive
    private var next = IntArray(0)
    private var glow = IntArray(0)   // trail brightness 0..255 for dead cells
    private var lastStep = -1f
    private var generation = 0
    private val random = Random(System.nanoTime())

    private val aliveColors = Palette.gradient(
        64, 0xFFE8FFFF.toInt(), 0xFF7CF7FF.toInt(), 0xFF4A6CFF.toInt(), 0xFFB24DFF.toInt(),
    )
    private val trailColors = Palette.gradient(256, 0xFF04050B.toInt(), 0xFF1A1F4D.toInt(), 0xFF3A2470.toInt())

    override fun onBufferSizeChanged(width: Int, height: Int) {
        w = width
        h = height
        age = IntArray(width * height)
        next = IntArray(width * height)
        glow = IntArray(width * height)
        for (i in age.indices) if (random.nextFloat() < 0.28f) age[i] = 1
    }

    override fun render(pixels: IntArray, timeSec: Float) {
        if (touchX >= 0f) spawn((touchX * w).toInt(), (touchY * h).toInt(), radius = 2, density = 0.6f)

        if (lastStep < 0f || timeSec < lastStep || timeSec - lastStep >= STEP_SECONDS) {
            step()
            lastStep = timeSec
        }

        for (i in age.indices) {
            val a = age[i]
            pixels[i] = if (a > 0) aliveColors[minOf(a, aliveColors.size - 1)] else trailColors[glow[i]]
        }
    }

    private fun step() {
        var population = 0
        for (y in 0 until h) {
            val up = ((y - 1 + h) % h) * w
            val row = y * w
            val down = ((y + 1) % h) * w
            for (x in 0 until w) {
                val left = (x - 1 + w) % w
                val right = (x + 1) % w
                var n = 0
                if (age[up + left] > 0) n++
                if (age[up + x] > 0) n++
                if (age[up + right] > 0) n++
                if (age[row + left] > 0) n++
                if (age[row + right] > 0) n++
                if (age[down + left] > 0) n++
                if (age[down + x] > 0) n++
                if (age[down + right] > 0) n++

                val i = row + x
                val alive = age[i] > 0
                next[i] = when {
                    alive && (n == 2 || n == 3) -> age[i] + 1
                    !alive && n == 3 -> 1
                    else -> 0
                }
                if (alive && next[i] == 0) glow[i] = 255
                else if (next[i] == 0) glow[i] = glow[i] * 7 / 8
                if (next[i] > 0) population++
            }
        }
        val t = age; age = next; next = t

        generation++
        if (population < age.size / 30 || generation % 240 == 0) {
            repeat(4) { spawn(random.nextInt(w), random.nextInt(h), radius = 4, density = 0.45f) }
        }
    }

    private fun spawn(cx: Int, cy: Int, radius: Int, density: Float) {
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (random.nextFloat() >= density) continue
            val x = ((cx + dx) % w + w) % w
            val y = ((cy + dy) % h + h) % h
            val i = y * w + x
            if (age[i] == 0) age[i] = 1
        }
    }

    private companion object {
        const val STEP_SECONDS = 0.12f
    }
}
