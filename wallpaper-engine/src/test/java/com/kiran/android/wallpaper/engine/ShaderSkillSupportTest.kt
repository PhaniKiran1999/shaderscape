package com.kiran.android.wallpaper.engine

import com.kiran.android.wallpaper.engine.composition.Composition
import com.kiran.android.wallpaper.engine.composition.CompositionJson
import com.kiran.android.wallpaper.engine.composition.CompositionStore
import com.kiran.android.wallpaper.engine.composition.Layer
import com.kiran.android.wallpaper.engine.composition.LayerSource
import com.kiran.android.wallpaper.engine.validation.ShaderValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShaderSkillSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val good = """
        // glowing rings
        void main() {
            vec2 p = gl_FragCoord.xy / uResolution;
            gl_FragColor = vec4(p, 0.5 + 0.5 * sin(uTime), 1.0);
        }
    """.trimIndent()

    @Test
    fun shaderLayer_jsonRoundTrip_andAnimated() {
        val c = Composition("a", "Agent", listOf(Layer(LayerSource.Shader(good), id = "s")))
        assertEquals(c, CompositionJson.decode(CompositionJson.encode(c)))
        assertTrue(c.isAnimated)
        assertFalse(Composition("b", "Photo", listOf(Layer(LayerSource.Image("/x.jpg")))).isAnimated)
    }

    @Test
    fun inlineScene_detectsReadsBelow() {
        assertFalse(InlineShaderScene(good).readsBelow)
        assertTrue(InlineShaderScene("void main(){ gl_FragColor = texture2D(uBelow, vUv); }").readsBelow)
    }

    @Test
    fun staticCheck_acceptsGoodShader() {
        assertEquals(emptyList<String>(), ShaderValidator.staticCheck(good))
        // A function `out` parameter is valid GLSL ES 1.00.
        assertEquals(emptyList<String>(), ShaderValidator.staticCheck("void f(out vec4 c){c=vec4(1.0);}\nvoid main(){f(gl_FragColor);}"))
    }

    @Test
    fun staticCheck_flagsCommonLlmMistakes() {
        val bad = """
            #version 300 es
            precision highp float;
            uniform float uTime;
            out vec4 fragColor;
            void main() { fragColor = texture(uBelow, vUv); }
        """.trimIndent()
        val problems = ShaderValidator.staticCheck(bad).joinToString("\n")
        assertTrue(problems, problems.contains("#version"))
        assertTrue(problems, problems.contains("precision"))
        assertTrue(problems, problems.contains("uTime"))
        assertTrue(problems, problems.contains("gl_FragColor"))
        assertTrue(problems, problems.contains("texture2D"))
        assertTrue(ShaderValidator.staticCheck("").single().contains("empty"))
        assertTrue(ShaderValidator.staticCheck("float x = 1.0;").single().contains("main"))
        assertTrue(ShaderValidator.staticCheck("void main(){}" + " ".repeat(ShaderValidator.MAX_SOURCE_CHARS)).single().contains("characters"))
    }

    @Test
    fun staticCheck_requiresTouchGuard_andAcceptsTaps() {
        // The bug from a real session: unguarded uTouch jumps to the bottom-left when the finger lifts.
        val unguarded = "void main(){ float d = distance(gl_FragCoord.xy, uTouch); gl_FragColor = vec4(d); }"
        assertTrue(ShaderValidator.staticCheck(unguarded).single().contains("uTaps"))
        listOf(
            "void main(){ vec3 c = vec3(0.0); if (uTouch.x >= 0.0) c += 1.0; gl_FragColor = vec4(c, 1.0); }",
            "void main(){ float on = step(0.0, uTouch.x); gl_FragColor = vec4(on * distance(gl_FragCoord.xy, uTouch)); }",
            "void main(){ if (0.0 <= uTouch.x) gl_FragColor = vec4(1.0); }",
            // uTaps needs no guard beyond its own z check.
            "void main(){ float h = 0.0; for (int i = 0; i < 4; i++) { if (uTaps[i].z >= 0.0) h += 1.0; } gl_FragColor = vec4(h); }",
        ).forEach { assertEquals(it, emptyList<String>(), ShaderValidator.staticCheck(it)) }
        assertTrue(ShaderValidator.staticCheck("uniform vec3 uTaps[4];\nvoid main(){}").single().contains("uTaps"))
    }

    @Test
    fun staticCheck_ignoresCommentedOutDirectives() {
        assertEquals(emptyList<String>(), ShaderValidator.staticCheck("// #version 300 es\n/* precision highp float; */\nvoid main(){gl_FragColor=vec4(1.0);}"))
    }

    @Test
    fun remapLog_bothDriverFormats() {
        val adreno = "ERROR: 0:18: 'foo' : undeclared identifier\nERROR: 0:18: '=' : cannot convert\nERROR: 2 compilation errors.  No code generated."
        val remapped = ShaderValidator.remapLog(adreno, offset = 15)
        assertEquals("Line 3: 'foo' : undeclared identifier", remapped[0])
        assertEquals("Line 3: '=' : cannot convert", remapped[1])

        val nvidia = "0(20) : error C1008: undefined variable \"bar\""
        assertEquals("Line 5: C1008: undefined variable \"bar\"", ShaderValidator.remapLog(nvidia, offset = 15).single())

        // Errors in the engine's own header/footer keep their message without a bogus line number.
        assertEquals("'x' : redefinition", ShaderValidator.remapLog("ERROR: 0:4: 'x' : redefinition", offset = 15).single())
    }

    @Test
    fun drafts_saveGetPromotePrune() {
        val store = CompositionStore(tmp.newFolder("s"))
        val d1 = Composition("d1", "Neon", listOf(Layer(LayerSource.Shader(good))))
        store.saveDraft(d1, now = 1_000)
        store.saveDraft(d1.copy(title = "Neon 2"), now = 5_000)
        assertEquals("Neon 2", store.getDraft("d1")?.title)
        assertEquals(1_000, store.drafts().single().createdAt) // creation time kept on update
        assertTrue(store.all().isEmpty()) // drafts aren't saved compositions

        // Drafts survive a restart.
        assertEquals(store.drafts(), CompositionStore(store.imagesDir.parentFile!!).drafts())

        val promoted = store.promoteDraft("d1", title = "Kept")
        assertEquals("Kept", promoted?.title)
        assertNull(store.getDraft("d1"))
        assertEquals(listOf("d1"), store.all().map { it.id })
        assertNull(store.promoteDraft("missing"))

        store.saveDraft(Composition("old", "Old", emptyList()), now = 0)
        store.saveDraft(Composition("new", "New", emptyList()), now = 90_000)
        assertEquals(1, store.pruneDrafts(maxAgeMs = 60_000, now = 100_000))
        assertEquals(listOf("new"), store.drafts().map { it.composition.id })
    }

    @Test
    fun pruneImages_keepsDraftImages() {
        val store = CompositionStore(tmp.newFolder("s2"))
        store.imagesDir.mkdirs()
        val img = File(store.imagesDir, "draft.jpg").apply { writeText("x") }
        store.saveDraft(Composition("d", "D", listOf(Layer(LayerSource.Image(img.absolutePath)))))
        store.pruneImages()
        assertTrue(img.exists())
        store.deleteDraft("d")
        assertFalse(img.exists())
    }
}
