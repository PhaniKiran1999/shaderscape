package com.kiran.android.wallpapers.scenes

import android.content.Context
import com.kiran.android.wallpaper.engine.AssetShaderScene
import com.kiran.android.wallpaper.engine.UniformScope

/**
 * Water ripples that refract the layers below. This is the sample for `readsBelow`. Touching
 * drops new ripples, a few per second while you drag, keeping the last [DROPS].
 */
class RippleScene(context: Context) : AssetShaderScene(context, "shaders/ripple.frag", fps = 60) {

    override val readsBelow: Boolean get() = true

    // x, y in GL pixels; start time; -1 = unused.
    private val drops = Array(DROPS) { floatArrayOf(0f, 0f, -1f) }
    private var next = 0
    private var lastDrop = -10f

    override fun onBindUniforms(uniforms: UniformScope, timeSec: Float) {
        if (timeSec < lastDrop) lastDrop = -10f // the engine clock wrapped
        if (touchX >= 0f && timeSec - lastDrop >= DROP_INTERVAL) {
            drops[next][0] = touchX * surfaceWidth
            drops[next][1] = (1f - touchY) * surfaceHeight
            drops[next][2] = timeSec
            next = (next + 1) % DROPS
            lastDrop = timeSec
        }
        for (i in 0 until DROPS) uniforms.set("uDrop$i", drops[i][0], drops[i][1], drops[i][2])
    }

    private companion object {
        const val DROPS = 4
        const val DROP_INTERVAL = 0.2f
    }
}
