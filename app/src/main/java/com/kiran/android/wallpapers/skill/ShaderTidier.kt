package com.kiran.android.wallpapers.skill

/**
 * Fixes harmless, common LLM formatting mistakes before validation, so small models don't burn
 * retries on them: markdown fences, `#version`/`precision` lines, and redeclared engine
 * uniforms. Real errors are left for the validator to report.
 */
object ShaderTidier {

    data class Tidied(val code: String, val notes: List<String>)

    private val FENCE = Regex("^```[a-zA-Z]*\\s*\\n([\\s\\S]*?)\\n?```\\s*$")
    private val VERSION = Regex("(?m)^\\s*#\\s*version[^\\n]*\\n?")
    private val PRECISION = Regex("(?m)^\\s*precision\\s+(lowp|mediump|highp)\\s+float\\s*;[^\\n]*\\n?")
    private val BUILT_IN_DECL = Regex(
        "(?m)^\\s*(uniform|varying)\\s+\\w+\\s+(uTime|uResolution|uTouch|uTaps|uOffset|uLayered|uOpacity|vUv)\\s*(\\[\\s*\\d+\\s*\\])?\\s*;[^\\n]*\\n?",
    )

    fun tidy(raw: String): Tidied {
        val notes = mutableListOf<String>()
        var code = raw.trim()
        FENCE.find(code)?.let {
            code = it.groupValues[1]
            notes += "Removed markdown code fences."
        }
        if (VERSION.containsMatchIn(code)) {
            code = VERSION.replace(code, "")
            notes += "Removed #version (the engine adds it)."
        }
        if (PRECISION.containsMatchIn(code)) {
            code = PRECISION.replace(code, "")
            notes += "Removed the precision line (the engine adds it)."
        }
        if (BUILT_IN_DECL.containsMatchIn(code)) {
            code = BUILT_IN_DECL.replace(code, "")
            notes += "Removed declarations of built-in uniforms."
        }
        return Tidied(code.trim() + "\n", notes)
    }
}
