package com.kiran.android.wallpaper.engine

/** Colour helpers for [PixelScene]s. Pure Kotlin, so they're cheap to call on the render thread. */
object Palette {

    fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** [size] colours interpolated linearly through evenly spaced ARGB [stops]. */
    fun gradient(size: Int, vararg stops: Int): IntArray {
        require(size > 0 && stops.isNotEmpty())
        if (stops.size == 1 || size == 1) return IntArray(size) { stops[0] }
        return IntArray(size) { i ->
            val pos = i.toFloat() / (size - 1) * (stops.size - 1)
            val idx = pos.toInt().coerceAtMost(stops.size - 2)
            lerp(stops[idx], stops[idx + 1], pos - idx)
        }
    }

    fun lerp(c0: Int, c1: Int, t: Float): Int {
        fun ch(c: Int, shift: Int) = (c ushr shift) and 0xFF
        fun mix(shift: Int) = Math.round(ch(c0, shift) + (ch(c1, shift) - ch(c0, shift)) * t)
        return argb(mix(16), mix(8), mix(0), mix(24))
    }
}
