package com.kiran.android.shaderscape

import android.app.Application
import com.kiran.android.wallpaper.engine.AssetShaderScene
import com.kiran.android.wallpaper.engine.WallpaperRegistry
import com.kiran.android.wallpaper.engine.WallpaperSelection
import com.kiran.android.shaderscape.scenes.GameOfLifeScene
import com.kiran.android.shaderscape.scenes.LensFlareScene
import com.kiran.android.shaderscape.scenes.PixelFireScene
import com.kiran.android.shaderscape.scenes.RetroPlasmaScene
import com.kiran.android.shaderscape.scenes.RippleScene
import com.kiran.android.shaderscape.scenes.StarfieldTunnelScene

/**
 * Registers every wallpaper. This runs before both the activity and the wallpaper service,
 * so the registry is always populated.
 */
class ShaderscapeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WallpaperSelection.init(this) // also initialises CompositionStore

        // GPU: GLSL fragment shaders
        WallpaperRegistry.register("plasma", "Plasma", "Flowing sine plasma. Tap for ripples.") {
            AssetShaderScene(it, "shaders/plasma.frag")
        }
        WallpaperRegistry.register("aurora", "Aurora", "Noise curtains over a starry sky.") {
            AssetShaderScene(it, "shaders/aurora.frag")
        }
        WallpaperRegistry.register("tunnel", "Starfield Tunnel", "Hold to warp faster.") {
            StarfieldTunnelScene(it)
        }
        WallpaperRegistry.register("lens-flare", "Lens Flare", "Touch and drag to draw the lenses out of the light.") {
            LensFlareScene(it)
        }
        WallpaperRegistry.register("ripple", "Ripples", "Rain rings that refract the layers below. Touch to drop.") {
            RippleScene(it)
        }

        // CPU: pixel buffers
        WallpaperRegistry.register("fire", "Pixel Fire", "Cellular fire. Touch to add heat.") {
            PixelFireScene()
        }
        WallpaperRegistry.register("life", "Game of Life", "Conway's cellular automaton. Touch to seed.") {
            GameOfLifeScene()
        }
        WallpaperRegistry.register("retro-plasma", "Retro Plasma", "Demoscene plasma in 32 colours.") {
            RetroPlasmaScene()
        }
    }
}
