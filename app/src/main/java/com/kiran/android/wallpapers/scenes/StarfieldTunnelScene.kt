package com.kiran.android.wallpapers.scenes

import android.content.Context
import com.kiran.android.wallpaper.engine.AssetShaderScene
import com.kiran.android.wallpaper.engine.UniformScope

/** Shader scene with a custom uniform: holding a finger down speeds up the flight. */
class StarfieldTunnelScene(context: Context) : AssetShaderScene(context, "shaders/tunnel.frag", fps = 60) {

    private var speed = BASE_SPEED

    override fun onBindUniforms(uniforms: UniformScope, timeSec: Float) {
        val target = if (touchX >= 0f) BOOST_SPEED else BASE_SPEED
        speed += (target - speed) * 0.05f
        uniforms.set("uSpeed", speed)
    }

    private companion object {
        const val BASE_SPEED = 0.8f
        const val BOOST_SPEED = 3.0f
    }
}
