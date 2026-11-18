package com.kiran.android.wallpaper.engine.composition

import java.util.UUID

/** How a layer combines with everything below it. */
enum class BlendMode { NORMAL, ADD, SCREEN, MULTIPLY }

/** What a layer draws. */
sealed interface LayerSource {
    /** A scene from `WallpaperRegistry` (shader or pixel). */
    data class Scene(val sceneId: String) : LayerSource

    /** An image file in app storage (see `CompositionStore.importImage`). */
    data class Image(val path: String, val parallax: Boolean = true) : LayerSource
}

data class Layer(
    val source: LayerSource,
    val blend: BlendMode = BlendMode.NORMAL,
    val opacity: Float = 1f,
    val visible: Boolean = true,
    val id: String = newId(),
)

/**
 * A layered wallpaper: [layers] are ordered **bottom to top**. A plain scene is the
 * one-layer composition from [ofScene].
 */
data class Composition(
    val id: String,
    val title: String,
    val layers: List<Layer>,
) {
    val visibleLayers: List<Layer> get() = layers.filter { it.visible && it.opacity > 0f }

    val isFull: Boolean get() = layers.size >= MAX_LAYERS

    fun imagePaths(): Set<String> =
        layers.mapNotNullTo(HashSet()) { (it.source as? LayerSource.Image)?.path }

    companion object {
        /** Every full-screen layer costs a pass per frame, so stacks are capped. */
        const val MAX_LAYERS = 5

        fun ofScene(sceneId: String, title: String = sceneId) = Composition(
            id = "scene:$sceneId",
            title = title,
            layers = listOf(Layer(LayerSource.Scene(sceneId), id = "scene")),
        )
    }
}

internal fun newId(): String = UUID.randomUUID().toString()
