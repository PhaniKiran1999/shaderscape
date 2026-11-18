package com.kiran.android.wallpapers.scenes

import android.content.Context
import com.kiran.android.wallpaper.engine.AssetShaderScene
import com.kiran.android.wallpaper.engine.UniformScope
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Lens flare with the light pinned at the top-right.
 *
 * At rest, every ghost lens is converged into the light. When you touch and move, the ghosts
 * come out of the light and string out towards your finger, with the farthest one following
 * your fingertip. When you let go, they glide back and merge into the light again.
 */
class LensFlareScene(context: Context) : AssetShaderScene(context, "shaders/lensflare.frag", fps = 60) {

    private var angle = Float.NaN   // ghost axis direction, GL coordinates (y up)
    private var length = 0f         // current axis length in pixels; 0 = fully converged

    override fun onBindUniforms(uniforms: UniformScope, timeSec: Float) {
        val w = surfaceWidth.toFloat()
        val h = surfaceHeight.toFloat()
        val minDim = minOf(w, h)
        // Light position in GL pixels (bottom-left origin).
        val lx = LIGHT_X * w
        val ly = (1f - LIGHT_Y) * h

        if (angle.isNaN()) angle = atan2(h / 2f - ly, w / 2f - lx) // default: towards the centre

        var targetLength = 0f
        if (touchX >= 0f) {
            val tx = touchX * w
            val ty = (1f - touchY) * h
            val dist = hypot(tx - lx, ty - ly)
            if (dist > DEAD_ZONE * minDim) {
                // Point at the finger, and stretch so the farthest ghost sits under it.
                val target = atan2(ty - ly, tx - lx)
                angle += atan2(sin(target - angle), cos(target - angle)) * TURN_EASE
                targetLength = dist / FARTHEST_GHOST
            }
        }
        // Come out briskly, converge back a little slower.
        val ease = if (targetLength > length) OUT_EASE else IN_EASE
        length += (targetLength - length) * ease
        if (length < 0.5f) length = 0f

        uniforms.set("uLight", lx, ly)
        uniforms.set("uAxis", cos(angle) * length, sin(angle) * length)
        uniforms.set("uAxisAngle", angle)
        // Ghosts fade in as they leave the light, so a converged flare is just the light.
        uniforms.set("uGhostFade", (length / (FADE_DISTANCE * minDim)).coerceIn(0f, 1f))
    }

    private companion object {
        // Pinned light, normalised with a top-left origin.
        const val LIGHT_X = 0.84f
        const val LIGHT_Y = 0.13f
        const val DEAD_ZONE = 0.04f
        // The farthest ghost in lensflare.frag sits at 2.45 × uAxis.
        const val FARTHEST_GHOST = 2.45f
        const val FADE_DISTANCE = 0.06f
        const val TURN_EASE = 0.25f
        const val OUT_EASE = 0.2f
        const val IN_EASE = 0.08f
    }
}
