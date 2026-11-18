package com.kiran.android.wallpaper.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineUnitTest {

    private class TestPixel : PixelScene(targetWidth = 10) {
        override fun render(pixels: IntArray, timeSec: Float) = pixels.fill(0xFF112233.toInt())
    }

    private class TestShader : ShaderScene() {
        override val fragmentShader = "void main() { gl_FragColor = vec4(1.0); }"
    }

    @After
    fun tearDown() = WallpaperRegistry.clear()

    @Test
    fun bufferSize_followsAspectRatio() {
        assertEquals(100 to 222, PixelScene.bufferSizeFor(100, 1080, 2400))
        assertEquals(100 to 100, PixelScene.bufferSizeFor(100, 500, 500))
    }

    @Test
    fun bufferSize_neverWiderThanSurface_andHandlesZero() {
        assertEquals(50 to 100, PixelScene.bufferSizeFor(100, 50, 100))
        assertEquals(64 to 64, PixelScene.bufferSizeFor(64, 0, 0))
        assertEquals(1 to 1, PixelScene.bufferSizeFor(0, 0, 0))
    }

    @Test
    fun registry_detectsKind_andKeepsOrder() {
        WallpaperRegistry.register("s", "Shader") { TestShader() }
        WallpaperRegistry.register("p", "Pixel") { TestPixel() }

        assertEquals(listOf("s", "p"), WallpaperRegistry.all().map { it.id })
        assertEquals(WallpaperRegistry.Kind.SHADER, WallpaperRegistry["s"]?.kind)
        assertEquals(WallpaperRegistry.Kind.PIXEL, WallpaperRegistry["p"]?.kind)
        assertEquals("s", WallpaperRegistry.resolve("missing")?.id)
        assertEquals("p", WallpaperRegistry.resolve("p")?.id)
    }

    @Test
    fun registry_emptyResolvesToNull() {
        assertNull(WallpaperRegistry.resolve(null))
    }

    @Test
    fun palette_gradientHitsEndpoints() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val p = Palette.gradient(5, black, white)
        assertEquals(5, p.size)
        assertEquals(black, p.first())
        assertEquals(white, p.last())
        assertEquals(Palette.argb(128, 128, 128), p[2])
    }

    @Test
    fun palette_multiStop() {
        val p = Palette.gradient(3, 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        assertEquals(0xFF00FF00.toInt(), p[1])
    }

    @Test
    fun pixelScene_bufferDispatch() {
        val scene = TestPixel()
        scene.dispatchBufferSize(10, 20)
        val pixels = IntArray(scene.bufferWidth * scene.bufferHeight)
        scene.render(pixels, 0f)
        assertEquals(200, pixels.size)
        assertEquals(0xFF112233.toInt(), pixels[199])
    }
}
