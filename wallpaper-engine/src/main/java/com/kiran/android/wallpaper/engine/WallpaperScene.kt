package com.kiran.android.wallpaper.engine

import android.content.Context

/**
 * Base type for every wallpaper the engine can render.
 *
 * Do not subclass this directly. Extend [ShaderScene] (GPU, GLSL fragment shader) or
 * [PixelScene] (CPU, writes an ARGB pixel buffer).
 *
 * Every callback runs on the engine's render thread, never on the main thread.
 */
sealed class WallpaperScene {

    /** Frame-rate cap for this scene. Lower values save battery. */
    open val targetFps: Int get() = 30

    /** Size of the output surface in real screen pixels. */
    var surfaceWidth: Int = 0
        internal set
    var surfaceHeight: Int = 0
        internal set

    /** Last touch position, normalised to 0..1 (top-left origin), or -1 when not touching. */
    var touchX: Float = -1f
        internal set
    var touchY: Float = -1f
        internal set

    /** Launcher home-screen scroll offset, 0..1 (0.5 when the launcher doesn't report one). */
    var xOffset: Float = 0.5f
        internal set

    internal fun dispatchSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        onSurfaceChanged(width, height)
    }

    internal fun dispatchTouch(x: Float, y: Float) {
        touchX = x
        touchY = y
        onTouch(x, y)
    }

    internal fun dispatchOffset(offset: Float) {
        xOffset = offset
        onOffsetsChanged(offset)
    }

    protected open fun onSurfaceChanged(width: Int, height: Int) {}

    /** [x], [y] are normalised to 0..1, or both -1 when the finger is lifted. */
    protected open fun onTouch(x: Float, y: Float) {}

    protected open fun onOffsetsChanged(xOffset: Float) {}

    /** Called once when the scene is discarded. */
    open fun release() {}
}

/** Lets a [ShaderScene] set its own uniforms each frame. */
interface UniformScope {
    fun set(name: String, value: Float)
    fun set(name: String, x: Float, y: Float)
    fun set(name: String, x: Float, y: Float, z: Float)
    fun set(name: String, x: Float, y: Float, z: Float, w: Float)
    fun setInt(name: String, value: Int)
}

/**
 * GPU wallpaper: a GLSL ES 1.00 fragment shader drawn over the whole screen.
 *
 * The engine prepends [SHADER_HEADER] to [fragmentShader]. That header sets precision and
 * declares `vUv` plus the built-in uniforms, so the source only needs its own functions,
 * any extra uniforms, and `void main()`.
 */
abstract class ShaderScene : WallpaperScene() {

    /** Fragment shader body. Must not declare `precision`, `vUv` or the built-in uniforms. */
    abstract val fragmentShader: String

    /**
     * Set to true to sample the composite of the layers below through `uBelow`. Sample it at
     * `gl_FragCoord.xy / uResolution`. This costs an extra offscreen pass, so only enable it
     * for effects that distort or recolour what's beneath them.
     */
    open val readsBelow: Boolean get() = false

    /** Set custom uniforms here. The built-in uniforms are already set. */
    open fun onBindUniforms(uniforms: UniformScope, timeSec: Float) {}

    companion object {
        const val SHADER_HEADER = """#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;          // 0..1, bottom-left origin
uniform float uTime;       // seconds since start (wraps every hour)
uniform vec2 uResolution;  // surface size in pixels
uniform vec2 uTouch;       // touch in pixels, bottom-left origin; (-1,-1) when not touching
uniform float uOffset;     // launcher scroll offset 0..1
uniform float uLayered;    // 1.0 when drawn over other layers: skip your own background, use alpha
uniform sampler2D uBelow;  // layers below (only when readsBelow = true)
uniform float uOpacity;    // layer opacity, applied by the engine
#define main sceneMain
"""

        /**
         * Appended after the scene's source. It renames the scene's `main()` and then applies
         * opacity and alpha premultiplication, so every blend mode is a fixed GL blend function.
         */
        internal const val SHADER_FOOTER = """
#undef main
void main() {
    sceneMain();
    vec4 c = clamp(gl_FragColor, 0.0, 1.0);
    gl_FragColor = vec4(c.rgb * c.a, c.a) * uOpacity;
}
"""
    }
}

/** A [ShaderScene] that loads its fragment shader from `assets/`. */
open class AssetShaderScene(
    context: Context,
    assetPath: String,
    fps: Int = 30,
) : ShaderScene() {
    override val fragmentShader: String =
        context.assets.open(assetPath).bufferedReader().use { it.readText() }

    override val targetFps: Int = fps
}

/**
 * CPU wallpaper: [render] fills an ARGB `IntArray` that the engine uploads as a texture and
 * scales up to the screen with nearest-neighbour filtering, which gives the crisp pixel look.
 *
 * The buffer is [targetWidth] pixels wide. Its height follows the screen's aspect ratio,
 * so the pixels stay square. Row 0 is the top of the screen.
 */
abstract class PixelScene(val targetWidth: Int) : WallpaperScene() {

    var bufferWidth: Int = 0
        internal set
    var bufferHeight: Int = 0
        internal set

    internal fun dispatchBufferSize(width: Int, height: Int) {
        bufferWidth = width
        bufferHeight = height
        onBufferSizeChanged(width, height)
    }

    /** Allocate per-pixel state here. Called before the first [render] and whenever the size changes. */
    protected open fun onBufferSizeChanged(width: Int, height: Int) {}

    /** Write `bufferWidth * bufferHeight` ARGB colours into [pixels], row-major, top row first. */
    abstract fun render(pixels: IntArray, timeSec: Float)

    companion object {
        /** Buffer size for a surface: [targetWidth] wide (at most the surface width), height from the aspect ratio. */
        fun bufferSizeFor(targetWidth: Int, surfaceWidth: Int, surfaceHeight: Int): Pair<Int, Int> {
            val tw = targetWidth.coerceAtLeast(1)
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return tw to tw
            val bw = tw.coerceAtMost(surfaceWidth)
            val bh = Math.round(bw.toFloat() * surfaceHeight / surfaceWidth).coerceAtLeast(1)
            return bw to bh
        }
    }
}
