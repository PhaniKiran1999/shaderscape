package com.kiran.android.wallpapers.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kiran.android.wallpaper.engine.SceneView
import com.kiran.android.wallpaper.engine.WallpaperRegistry
import com.kiran.android.wallpaper.engine.composition.Composition

/** Compose wrapper around the engine's [SceneView], showing a layered composition. */
@Composable
fun ScenePreview(
    composition: Composition,
    modifier: Modifier = Modifier,
    maxFps: Int = Int.MAX_VALUE,
    interactive: Boolean = false,
) {
    AndroidView(
        factory = { context ->
            SceneView(context).apply {
                this.maxFps = maxFps
                this.interactive = interactive
                this.composition = composition
            }
        },
        update = { it.composition = composition },
        modifier = modifier,
    )
}

/** Shortcut for a single registered scene. */
@Composable
fun ScenePreview(
    sceneId: String,
    modifier: Modifier = Modifier,
    maxFps: Int = Int.MAX_VALUE,
    interactive: Boolean = false,
) {
    val composition = remember(sceneId) {
        Composition.ofScene(sceneId, WallpaperRegistry[sceneId]?.title ?: sceneId)
    }
    ScenePreview(composition, modifier, maxFps, interactive)
}
