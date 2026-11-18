package com.kiran.android.wallpapers.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderTidierTest {

    @Test
    fun removesFencesDirectivesAndBuiltInDeclarations() {
        val raw = "```glsl\n#version 300 es\nprecision mediump float;\nuniform float uTime;\nuniform vec2 uResolution;\nuniform float uMine;\nvoid main() { gl_FragColor = vec4(uMine); }\n```"
        val t = ShaderTidier.tidy(raw)
        assertEquals("uniform float uMine;\nvoid main() { gl_FragColor = vec4(uMine); }\n", t.code)
        assertEquals(4, t.notes.size)
    }

    @Test
    fun leavesCleanCodeAlone() {
        val code = "void main() {\n    gl_FragColor = vec4(1.0);\n}\n"
        val t = ShaderTidier.tidy(code)
        assertEquals(code, t.code)
        assertTrue(t.notes.isEmpty())
    }
}
