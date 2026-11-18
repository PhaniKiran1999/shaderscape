package com.kiran.android.wallpapers.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiran.android.wallpaper.engine.WallpaperRegistry
import com.kiran.android.wallpaper.engine.composition.Composition
import com.kiran.android.wallpaper.engine.composition.CompositionStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onOpenScene: (String) -> Unit,
    onCreate: () -> Unit,
    onEditComposition: (String) -> Unit,
) {
    val entries = remember { WallpaperRegistry.all() }
    val store = CompositionStore.instance
    var saved by remember { mutableStateOf(store.all()) }
    DisposableEffect(store) {
        val listener = CompositionStore.Listener { saved = store.all() }
        store.addListener(listener)
        onDispose { store.removeListener(listener) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Wallpapers") }) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("My wallpapers") }
            item(key = "create") { CreateCard(onCreate) }
            items(saved, key = { "comp-${it.id}" }) { c ->
                CompositionCard(c, onClick = { onEditComposition(c.id) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Effects") }
            items(entries, key = { it.id }) { entry ->
                PreviewCard(
                    title = entry.title,
                    subtitle = when (entry.kind) {
                        WallpaperRegistry.Kind.SHADER -> "Shader · GPU"
                        WallpaperRegistry.Kind.PIXEL -> "Pixel · CPU"
                    },
                    onClick = { onOpenScene(entry.id) },
                ) { modifier -> ScenePreview(sceneId = entry.id, maxFps = 15, modifier = modifier) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun CompositionCard(c: Composition, onClick: () -> Unit) {
    val n = c.layers.size
    PreviewCard(title = c.title, subtitle = "$n layer${if (n == 1) "" else "s"}", onClick = onClick) { modifier ->
        ScenePreview(composition = c, maxFps = 15, modifier = modifier)
    }
}

@Composable
private fun CreateCard(onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("+", fontSize = 48.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Create", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "Photo + effects",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("New layered", style = MaterialTheme.typography.titleSmall)
            Text("wallpaper", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreviewCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    preview: @Composable (Modifier) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Box {
            Column {
                preview(Modifier.fillMaxWidth().aspectRatio(9f / 16f))
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Drawn on top of the preview so taps reach Compose instead of the TextureView.
            Box(Modifier.matchParentSize().clickable(onClick = onClick))
        }
    }
}
