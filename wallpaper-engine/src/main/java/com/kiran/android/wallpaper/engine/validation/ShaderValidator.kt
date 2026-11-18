package com.kiran.android.wallpaper.engine.validation

import android.opengl.GLES20
import com.kiran.android.wallpaper.engine.InlineShaderScene
import com.kiran.android.wallpaper.engine.gl.EglCore
import com.kiran.android.wallpaper.engine.gl.ShaderCompileException
import com.kiran.android.wallpaper.engine.render.LayerDrawContext
import com.kiran.android.wallpaper.engine.render.LayerRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Checks an untrusted fragment-shader body (written for the `ShaderScene` contract) before it
 * is shown or saved.
 *
 * 1. **Static checks** (pure): size, forbidden directives, a `main()`.
 * 2. **GPU compile and link** on an offscreen pbuffer, using the same header, footer and
 *    renderer as the live wallpaper, so a valid result here means it runs there. Compiler
 *    messages are remapped to the author's own line numbers.
 * 3. **Test render** of a few frames, including one with a touch: frame time, and whether the
 *    output is one flat colour (usually a bug).
 *
 * The result is meant to be fed back to an LLM, so messages are short, concrete and fixable.
 * [validate] is blocking; call it off the main thread. Calls are serialised on one GL thread.
 */
object ShaderValidator {

    const val MAX_SOURCE_CHARS = 12_000

    sealed interface Result {
        /** Compiles and renders. [warnings] are worth fixing but not fatal. */
        data class Valid(val avgFrameMs: Float, val warnings: List<String>) : Result

        data class Invalid(val errors: List<String>) : Result
    }

    private val glThread = Executors.newSingleThreadExecutor { r -> Thread(r, "ShaderValidator") }

    @Volatile
    private var lineOffset: Int? = null

    /** Size, directive and structure checks that need no GPU. Returns the problems found. */
    fun staticCheck(code: String): List<String> {
        val problems = mutableListOf<String>()
        if (code.isBlank()) return listOf("The shader is empty.")
        if (code.length > MAX_SOURCE_CHARS) problems += "The shader is ${code.length} characters; keep it under $MAX_SOURCE_CHARS."
        val noComments = stripComments(code)
        if (Regex("#\\s*version").containsMatchIn(noComments)) problems += "Remove the #version line; the engine adds GLSL ES 1.00 itself."
        if (Regex("\\bprecision\\s+(lowp|mediump|highp)\\s+float").containsMatchIn(noComments)) {
            problems += "Remove the precision statement; the engine declares it."
        }
        if (!Regex("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)").containsMatchIn(noComments)) problems += "Define void main()."
        for (name in BUILT_INS) {
            if (Regex("\\b(uniform|varying)\\s+\\w+\\s+$name\\b").containsMatchIn(noComments)) {
                problems += "Don't declare $name; the engine provides it."
            }
        }
        if (Regex("#\\s*(include|extension)").containsMatchIn(noComments)) problems += "#include and #extension aren't supported."
        // A global `out vec4 color;` is GLSL 3 style. (Function `out` parameters are fine.)
        if (Regex("(?m)^\\s*(layout\\s*\\([^)]*\\)\\s*)?out\\s+((lowp|mediump|highp)\\s+)?vec4\\s+\\w+\\s*;").containsMatchIn(noComments)) {
            problems += "Use GLSL ES 1.00: write to gl_FragColor, not an 'out' variable."
        }
        if (Regex("\\btexture\\s*\\(").containsMatchIn(noComments)) {
            problems += "Use texture2D(), not texture(); this is GLSL ES 1.00."
        }
        // uTouch is (-1,-1) when no finger is down. Using it unguarded draws the effect at the
        // bottom-left corner whenever the finger lifts, a common and visible bug.
        if (Regex("\\buTouch\\b").containsMatchIn(noComments) && !TOUCH_GUARD.any { it.containsMatchIn(noComments) }) {
            problems += "uTouch is (-1,-1) when no finger is down. Only use it inside if (uTouch.x >= 0.0) { ... }. " +
                "For effects that continue after a tap (ripples, bursts), use uTaps instead."
        }
        return problems
    }

    /** Ways a shader can check that uTouch is valid before using it. */
    private val TOUCH_GUARD = listOf(
        Regex("\\buTouch\\s*\\.\\s*[xy]\\s*(>=|>|<=|<|==|!=)"),
        Regex("(>=|>|<=|<)\\s*uTouch\\s*\\.\\s*[xy]\\b"),
        Regex("\\bstep\\s*\\([^;]*\\buTouch\\b"),
    )

    /**
     * Turns raw compiler output into short messages with the author's line numbers. Drivers
     * report `ERROR: 0:LINE: message` or `0(LINE) : error ...`. [offset] is how many lines the
     * engine's header adds before the author's line 1.
     */
    fun remapLog(log: String, offset: Int): List<String> {
        val out = mutableListOf<String>()
        for (raw in log.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!line.contains("error", ignoreCase = true) && !line.startsWith("ERROR")) continue
            val m = LINE_A.find(line) ?: LINE_B.find(line)
            if (m == null) {
                out += line.removePrefix("ERROR:").trim()
                continue
            }
            val glLine = m.groupValues[1].toInt()
            val userLine = glLine - offset
            val message = line.substring(m.range.last + 1).trim().trimStart(':').trim()
                .removePrefix("error").trim().trimStart(':').trim()
            out += if (userLine >= 1) "Line $userLine: $message" else message
        }
        return out.distinct().take(MAX_ERRORS).ifEmpty { listOf(log.trim().take(300)) }
    }

    /** Full check: static, compile and link, then a test render. Blocking. */
    fun validate(code: String, width: Int = 270, height: Int = 600, frames: Int = 6): Result {
        val problems = staticCheck(code)
        if (problems.isNotEmpty()) return Result.Invalid(problems)
        return glThread.submit<Result> { gpuCheck(code, width, height, frames) }.get()
    }

    private fun gpuCheck(code: String, width: Int, height: Int, frames: Int): Result {
        val egl = EglCore(offscreen = true)
        val surface = egl.createPbufferSurface(width, height)
        try {
            egl.makeCurrent(surface)
            val offset = lineOffset ?: calibrate().also { lineOffset = it }
            val scene = InlineShaderScene(code)
            val renderer = LayerRenderer.forScene(scene)
            try {
                renderer.init()
            } catch (e: ShaderCompileException) {
                return Result.Invalid(remapLog(e.log, offset))
            }
            try {
                scene.dispatchSurfaceChanged(width, height)
                renderer.resize(width, height)
                val below = if (scene.readsBelow) checkerTexture() else 0
                val ctx = LayerDrawContext().apply {
                    this.width = width
                    this.height = height
                    belowTexture = below
                    layered = scene.readsBelow
                }
                GLES20.glViewport(0, 0, width, height)
                var totalNanos = 0L
                val warnings = mutableListOf<String>()
                for (i in 0 until frames) {
                    // Exercise the touch path on one frame, as interactive shaders branch on it.
                    if (i == frames / 2) scene.dispatchTouch(0.5f, 0.5f) else scene.dispatchTouch(-1f, -1f)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    val t0 = System.nanoTime()
                    renderer.draw(1.5f + i * 0.4f, ctx)
                    GLES20.glFinish()
                    if (i > 0) totalNanos += System.nanoTime() - t0 // frame 0 includes warm-up
                    val err = GLES20.glGetError()
                    if (err != GLES20.GL_NO_ERROR) return Result.Invalid(listOf("GL error 0x${Integer.toHexString(err)} while drawing."))
                }
                if (below != 0) GLES20.glDeleteTextures(1, intArrayOf(below), 0)
                val avgMs = totalNanos / 1e6f / (frames - 1).coerceAtLeast(1)
                if (avgMs > SLOW_MS) warnings += "Slow: %.1f ms per frame at %dx%d. Simplify loops or noise octaves.".format(avgMs, width, height)
                if (isFlat(width, height)) warnings += "The output is a single flat colour. Check that colours depend on position (vUv/gl_FragCoord) and time (uTime)."
                return Result.Valid(avgMs, warnings)
            } finally {
                runCatching { renderer.release() }
            }
        } finally {
            egl.destroySurface(surface)
            egl.release()
        }
    }

    /** Learns how this driver numbers lines: compiles a body with an error on its line 2. */
    private fun calibrate(): Int {
        val probe = InlineShaderScene("void main() {\n    undefinedThing = 1.0;\n}\n")
        return try {
            LayerRenderer.forScene(probe).init()
            DEFAULT_OFFSET
        } catch (e: ShaderCompileException) {
            val m = LINE_A.find(e.log) ?: LINE_B.find(e.log)
            m?.groupValues?.get(1)?.toIntOrNull()?.let { it - 2 } ?: DEFAULT_OFFSET
        }
    }

    private fun isFlat(width: Int, height: Int): Boolean {
        val n = 16
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        var first: Int? = null
        for (i in 0 until n) {
            val x = (width * (i % 4 + 0.5f) / 4).toInt()
            val y = (height * (i / 4 + 0.5f) / 4).toInt()
            buf.clear()
            GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            val v = buf.getInt(0)
            if (first == null) first = v else if (v != first) return false
        }
        return true
    }

    /** A checkerboard standing in for the layers below while validating a read-below shader. */
    private fun checkerTexture(): Int {
        val size = 64
        val bytes = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until size) for (x in 0 until size) {
            val c = if ((x / 8 + y / 8) % 2 == 0) 200.toByte() else 60.toByte()
            bytes.put(c).put(c).put(c).put(255.toByte())
        }
        bytes.position(0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        return ids[0]
    }

    private fun stripComments(s: String) = s.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ").replace(Regex("//[^\\n]*"), "")

    private val BUILT_INS = listOf("uTime", "uResolution", "uTouch", "uTaps", "uOffset", "uLayered", "uBelow", "uOpacity", "vUv")
    private val LINE_A = Regex("\\b\\d+:(\\d+):")       // ERROR: 0:12: ...
    private val LINE_B = Regex("\\b\\d+\\((\\d+)\\)")   // 0(12) : error ...
    private const val MAX_ERRORS = 6
    private const val SLOW_MS = 25f

    // Header lines before the author's line 1, if calibration can't tell. Kept in sync by calibrate().
    private const val DEFAULT_OFFSET = 15
}
