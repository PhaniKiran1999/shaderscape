package com.kiran.android.wallpaper.engine

import android.opengl.GLES20
import com.kiran.android.wallpaper.engine.composition.BlendMode
import com.kiran.android.wallpaper.engine.composition.Composition
import com.kiran.android.wallpaper.engine.composition.CompositionJson
import com.kiran.android.wallpaper.engine.composition.CompositionStore
import com.kiran.android.wallpaper.engine.composition.Layer
import com.kiran.android.wallpaper.engine.composition.LayerSource
import com.kiran.android.wallpaper.engine.render.BlendFuncs
import com.kiran.android.wallpaper.engine.render.CoverFit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompositionUnitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(id: String = "c1", imagePath: String = "/x/photo.jpg") = Composition(
        id = id,
        title = "Mine",
        layers = listOf(
            Layer(LayerSource.Image(imagePath, parallax = false), id = "l1"),
            Layer(LayerSource.Scene("aurora"), BlendMode.SCREEN, 0.7f, id = "l2"),
            Layer(LayerSource.Scene("lens-flare"), BlendMode.ADD, visible = false, id = "l3"),
            Layer(LayerSource.Scene("ripple"), BlendMode.MULTIPLY, id = "l4"),
        ),
    )

    @Test
    fun json_roundTrip_keepsEverything() {
        val c = sample()
        assertEquals(c, CompositionJson.decode(CompositionJson.encode(c)))
        assertEquals(listOf(c, sample("c2")), CompositionJson.listFromString(CompositionJson.listToString(listOf(c, sample("c2")))))
    }

    @Test
    fun json_unknownBlendFallsBack_andUnknownLayerTypeIsDropped() {
        val text = """{"id":"x","title":"t","layers":[
            {"id":"a","type":"scene","sceneId":"plasma","blend":"WEIRD","opacity":3},
            {"id":"b","type":"hologram"}]}"""
        val c = CompositionJson.decode(text)
        assertEquals(1, c.layers.size)
        assertEquals(BlendMode.NORMAL, c.layers[0].blend)
        assertEquals(1f, c.layers[0].opacity)
    }

    @Test
    fun composition_helpers() {
        val c = sample()
        assertEquals(listOf("l1", "l2", "l4"), c.visibleLayers.map { it.id })
        assertEquals(setOf("/x/photo.jpg"), c.imagePaths())
        assertFalse(c.isFull)
        val single = Composition.ofScene("plasma", "Plasma")
        assertEquals("scene:plasma", single.id)
        assertEquals(LayerSource.Scene("plasma"), single.layers.single().source)
    }

    @Test
    fun target_encodeParse_andLegacyValue() {
        val s = WallpaperTarget.Scene("fire")
        val c = WallpaperTarget.Composition("abc")
        assertEquals(s, WallpaperTarget.parse(s.encode()))
        assertEquals(c, WallpaperTarget.parse(c.encode()))
        assertEquals(WallpaperTarget.Scene("plasma"), WallpaperTarget.parse("plasma"))
        assertNull(WallpaperTarget.parse(null))
        assertNull(WallpaperTarget.parse(""))
    }

    @Test
    fun store_saveReplaceDelete_persistsAcrossInstances() {
        val dir = tmp.newFolder("store")
        val store = CompositionStore(dir)
        var events = 0
        store.addListener { events++ }

        store.save(sample("a"))
        store.save(sample("b"))
        store.save(sample("a").copy(title = "Renamed"))
        assertEquals(listOf("a", "b"), store.all().map { it.id })
        assertEquals("Renamed", store["a"]?.title)

        val reopened = CompositionStore(dir)
        assertEquals(store.all(), reopened.all())

        store.delete("a")
        assertEquals(listOf("b"), store.all().map { it.id })
        assertEquals(4, events)
    }

    @Test
    fun store_pruneDeletesOnlyUnreferencedImages() {
        val dir = tmp.newFolder("store2")
        val store = CompositionStore(dir)
        store.imagesDir.mkdirs()
        val used = File(store.imagesDir, "used.jpg").apply { writeText("x") }
        val draft = File(store.imagesDir, "draft.jpg").apply { writeText("x") }
        val orphan = File(store.imagesDir, "orphan.jpg").apply { writeText("x") }
        store.save(sample("a", used.absolutePath))

        store.pruneImages(keep = setOf(draft.absolutePath))
        assertTrue(used.exists())
        assertTrue(draft.exists())
        assertFalse(orphan.exists())

        store.delete("a")
        assertFalse(used.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun store_rejectsTooManyLayers() {
        val layers = List(Composition.MAX_LAYERS + 1) { Layer(LayerSource.Scene("plasma")) }
        CompositionStore(tmp.newFolder("store3")).save(Composition("big", "Big", layers))
    }

    @Test
    fun coverFit_wideImagePansTallImageCentres() {
        // 2:1 image on a 1:2 screen: show a quarter of the width, panned.
        assertArrayEquals(floatArrayOf(0.25f, 1f, 0f, 0f), CoverFit.compute(2000, 1000, 500, 1000, 0f), 1e-5f)
        assertArrayEquals(floatArrayOf(0.25f, 1f, 0.75f, 0f), CoverFit.compute(2000, 1000, 500, 1000, 1f), 1e-5f)
        // 1:4 image on a 1:2 screen: show half the height, centred.
        assertArrayEquals(floatArrayOf(1f, 0.5f, 0f, 0.25f), CoverFit.compute(500, 2000, 500, 1000, 0.5f), 1e-5f)
        // Same aspect: identity.
        assertArrayEquals(floatArrayOf(1f, 1f, 0f, 0f), CoverFit.compute(1080, 2400, 540, 1200, 0.5f), 1e-5f)
    }

    @Test
    fun blendFuncs_premultipliedMapping() {
        assertEquals(GLES20.GL_ONE to GLES20.GL_ONE_MINUS_SRC_ALPHA, BlendFuncs.of(BlendMode.NORMAL))
        assertEquals(GLES20.GL_ONE to GLES20.GL_ONE, BlendFuncs.of(BlendMode.ADD))
        assertEquals(GLES20.GL_ONE to GLES20.GL_ONE_MINUS_SRC_COLOR, BlendFuncs.of(BlendMode.SCREEN))
        assertEquals(GLES20.GL_DST_COLOR to GLES20.GL_ONE_MINUS_SRC_ALPHA, BlendFuncs.of(BlendMode.MULTIPLY))
    }
}
