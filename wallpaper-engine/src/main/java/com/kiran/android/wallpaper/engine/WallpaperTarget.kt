package com.kiran.android.wallpaper.engine

/** What the live wallpaper shows: one registered scene, or a saved layered composition. */
sealed interface WallpaperTarget {
    data class Scene(val sceneId: String) : WallpaperTarget
    data class Composition(val compositionId: String) : WallpaperTarget

    fun encode(): String = when (this) {
        is Scene -> "$SCENE_PREFIX$sceneId"
        is Composition -> "$COMPOSITION_PREFIX$compositionId"
    }

    companion object {
        private const val SCENE_PREFIX = "scene:"
        private const val COMPOSITION_PREFIX = "comp:"

        /** Parses [encode]'s output. A bare value (the old format) is read as a scene id. */
        fun parse(value: String?): WallpaperTarget? = when {
            value.isNullOrEmpty() -> null
            value.startsWith(SCENE_PREFIX) -> Scene(value.removePrefix(SCENE_PREFIX))
            value.startsWith(COMPOSITION_PREFIX) -> Composition(value.removePrefix(COMPOSITION_PREFIX))
            else -> Scene(value)
        }
    }
}
