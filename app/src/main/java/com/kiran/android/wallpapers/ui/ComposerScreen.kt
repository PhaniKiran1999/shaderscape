package com.kiran.android.wallpapers.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiran.android.wallpaper.engine.ShaderScene
import com.kiran.android.wallpaper.engine.WallpaperRegistry
import com.kiran.android.wallpaper.engine.WallpaperSetter
import com.kiran.android.wallpaper.engine.WallpaperTarget
import com.kiran.android.wallpaper.engine.composition.BlendMode
import com.kiran.android.wallpaper.engine.composition.Composition
import com.kiran.android.wallpaper.engine.composition.CompositionJson
import com.kiran.android.wallpaper.engine.composition.CompositionStore
import com.kiran.android.wallpaper.engine.composition.Layer
import com.kiran.android.wallpaper.engine.composition.LayerSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val CompositionSaver = Saver<Composition, String>(
    save = { CompositionJson.encode(it) },
    restore = { CompositionJson.decode(it) },
)

/**
 * Layer editor: a live, interactive preview on top, and the layer stack below (top layer
 * first). Unsaved edits survive rotation and process death.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerScreen(compositionId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = CompositionStore.instance
    val scope = rememberCoroutineScope()

    var draft by rememberSaveable(compositionId, stateSaver = CompositionSaver) {
        mutableStateOf(
            compositionId?.let { store[it] }
                ?: Composition(UUID.randomUUID().toString(), "My wallpaper ${store.all().size + 1}", emptyList()),
        )
    }
    var isSaved by rememberSaveable(compositionId) { mutableStateOf(compositionId != null) }
    var importing by remember { mutableStateOf(false) }
    var showEffects by remember { mutableStateOf(false) }

    fun exit() {
        store.pruneImages() // drops photos imported for unsaved edits
        onDone()
    }
    BackHandler { exit() }

    fun save(): Composition {
        store.save(draft)
        store.pruneImages()
        isSaved = true
        return draft
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { store.importImage(context, uri) } }
            importing = false
            result.onSuccess { path ->
                // Photos go to the bottom: they're backgrounds.
                draft = draft.copy(layers = listOf(Layer(LayerSource.Image(path))) + draft.layers)
            }.onFailure {
                Toast.makeText(context, "Couldn't load that image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---- Live preview ----
        Box(
            Modifier
                .fillMaxWidth()
                .weight(0.55f)
                .background(Color.Black),
        ) {
            ScenePreview(composition = draft, interactive = true, modifier = Modifier.fillMaxSize())
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { exit() }) { Text("← Back", color = Color.White) }
                Text(
                    draft.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isSaved) {
                    TextButton(onClick = {
                        store.delete(draft.id)
                        onDone()
                    }) { Text("Delete", color = Color(0xFFFF8A80)) }
                }
            }
            if (draft.layers.isEmpty()) {
                Text(
                    if (importing) "Loading photo…" else "Add a photo and some effects",
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // ---- Layer stack ----
        Surface(Modifier.fillMaxWidth().weight(0.45f)) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !draft.isFull && !importing,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (importing) "Loading…" else "+ Photo") }
                    FilledTonalButton(
                        onClick = { showEffects = true },
                        enabled = !draft.isFull,
                        modifier = Modifier.weight(1f),
                    ) { Text("+ Effect") }
                    Text(
                        "${draft.layers.size}/${Composition.MAX_LAYERS}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    // Shown top layer first, as in design tools.
                    val indices = draft.layers.indices.reversed().toList()
                    items(indices, key = { draft.layers[it].id }) { index ->
                        LayerRow(
                            layer = draft.layers[index],
                            canMoveUp = index < draft.layers.lastIndex,
                            canMoveDown = index > 0,
                            onChange = { updated ->
                                draft = draft.copy(layers = draft.layers.toMutableList().also { it[index] = updated })
                            },
                            onMove = { delta ->
                                val to = index + delta
                                draft = draft.copy(layers = draft.layers.toMutableList().also {
                                    val l = it.removeAt(index)
                                    it.add(to, l)
                                })
                            },
                            onDelete = {
                                draft = draft.copy(layers = draft.layers.toMutableList().also { it.removeAt(index) })
                            },
                        )
                        HorizontalDivider()
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            save()
                            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                        },
                        enabled = draft.layers.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                    Button(
                        onClick = {
                            val saved = save()
                            when (WallpaperSetter.apply(context, WallpaperTarget.Composition(saved.id))) {
                                WallpaperSetter.Result.UPDATED ->
                                    Toast.makeText(context, "Wallpaper updated", Toast.LENGTH_SHORT).show()
                                WallpaperSetter.Result.PICKER_OPENED -> Unit
                                WallpaperSetter.Result.FAILED ->
                                    Toast.makeText(context, "Live wallpapers aren't supported here", Toast.LENGTH_LONG).show()
                            }
                        },
                        enabled = draft.layers.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Set wallpaper") }
                }
            }
        }
    }

    if (showEffects) {
        ModalBottomSheet(onDismissRequest = { showEffects = false }) {
            Text(
                "Add an effect layer",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(WallpaperRegistry.all(), key = { it.id }) { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Distortion layers replace what's below, so NORMAL. Light effects
                                // over existing layers default to SCREEN.
                                val readsBelow = runCatching {
                                    val probe = entry.factory(context)
                                    ((probe as? ShaderScene)?.readsBelow == true).also { probe.release() }
                                }.getOrDefault(false)
                                val blend = if (draft.layers.isEmpty() || readsBelow) BlendMode.NORMAL else BlendMode.SCREEN
                                draft = draft.copy(layers = draft.layers + Layer(LayerSource.Scene(entry.id), blend))
                                showEffects = false
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayerRow(
    layer: Layer,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (Layer) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val name = when (val s = layer.source) {
        is LayerSource.Image -> "Photo"
        is LayerSource.Scene -> WallpaperRegistry[s.sceneId]?.title ?: s.sceneId
        is LayerSource.Shader -> "Custom shader"
    }
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = layer.visible, onCheckedChange = { onChange(layer.copy(visible = it)) })
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onMove(+1) }, enabled = canMoveUp) { Text("▲") }
            TextButton(onClick = { onMove(-1) }, enabled = canMoveDown) { Text("▼") }
            TextButton(onClick = onDelete) { Text("✕") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            BlendPicker(layer.blend) { onChange(layer.copy(blend = it)) }
            Spacer(Modifier.width(8.dp))
            Slider(
                value = layer.opacity,
                onValueChange = { onChange(layer.copy(opacity = it)) },
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(layer.opacity * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(44.dp).padding(start = 8.dp),
            )
        }
        val source = layer.source
        if (source is LayerSource.Image) {
            // A photo wider than the screen can follow the launcher's page swipes, or stay centred.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text(
                    "Position",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilterChip(
                    selected = source.parallax,
                    onClick = { onChange(layer.copy(source = source.copy(parallax = true))) },
                    label = { Text("Pan with pages") },
                )
                FilterChip(
                    selected = !source.parallax,
                    onClick = { onChange(layer.copy(source = source.copy(parallax = false))) },
                    label = { Text("Centre") },
                )
            }
        }
    }
}

@Composable
private fun BlendPicker(value: BlendMode, onPick: (BlendMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(value.name.lowercase().replaceFirstChar { it.uppercase() } + " ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BlendMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onPick(mode)
                        open = false
                    },
                )
            }
        }
    }
}
