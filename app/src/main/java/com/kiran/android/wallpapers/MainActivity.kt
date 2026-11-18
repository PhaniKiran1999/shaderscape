package com.kiran.android.wallpapers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kiran.android.wallpapers.ui.ComposerScreen
import com.kiran.android.wallpapers.ui.DetailScreen
import com.kiran.android.wallpapers.ui.GalleryScreen
import com.kiran.android.wallpapers.ui.theme.WallpapersTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val editId = intent.getStringExtra(EXTRA_EDIT_COMPOSITION)
        setContent {
            WallpapersTheme {
                WallpapersNavigation(initialRoute = editId?.let { "composer:$it" } ?: "gallery")
            }
        }
    }

    companion object {
        /** Opens the layer editor for this saved composition (used by the agent preview). */
        const val EXTRA_EDIT_COMPOSITION = "edit_composition"
    }
}

/**
 * Three screens with simple state-based navigation. The route is a string so it survives
 * process death: "gallery", "detail:<sceneId>", "composer:" (new) or "composer:<compositionId>".
 */
@Composable
private fun WallpapersNavigation(initialRoute: String) {
    var route by rememberSaveable { mutableStateOf(initialRoute) }
    val goHome = { route = "gallery" }
    when {
        route.startsWith("detail:") -> {
            BackHandler(onBack = goHome)
            DetailScreen(sceneId = route.removePrefix("detail:"), onBack = goHome)
        }
        route.startsWith("composer:") -> {
            // ComposerScreen handles Back itself (it tidies up unsaved photos).
            ComposerScreen(compositionId = route.removePrefix("composer:").ifEmpty { null }, onDone = goHome)
        }
        else -> GalleryScreen(
            onOpenScene = { route = "detail:$it" },
            onCreate = { route = "composer:" },
            onEditComposition = { route = "composer:$it" },
        )
    }
}
