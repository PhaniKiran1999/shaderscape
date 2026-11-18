package com.kiran.android.wallpapers.skill

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kiran.android.wallpaper.engine.WallpaperSetter
import com.kiran.android.wallpaper.engine.WallpaperTarget
import com.kiran.android.wallpaper.engine.composition.Composition
import com.kiran.android.wallpaper.engine.composition.CompositionStore
import com.kiran.android.wallpapers.MainActivity
import com.kiran.android.wallpapers.ui.ScenePreview
import com.kiran.android.wallpapers.ui.theme.WallpapersTheme

/**
 * Where the user decides about an agent-made draft: try it live (touch works), then Save, Set
 * as wallpaper, open it in the layer editor, or Discard. Not exported: it opens only through
 * the PendingIntent that [WallpaperSkillService] hands to the agent.
 */
class PreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val draftId = intent.getStringExtra(EXTRA_DRAFT_ID)
        val store = CompositionStore.instance
        // After saving, the draft becomes a saved composition with the same id.
        val composition = draftId?.let { store.getDraft(it) ?: store[it] }
        if (composition == null) {
            Toast.makeText(this, "This preview has expired.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            WallpapersTheme {
                PreviewScreen(
                    composition = composition,
                    initiallySaved = store[composition.id] != null,
                    onSave = { store.promoteDraft(composition.id) != null || store[composition.id] != null },
                    onSet = {
                        if (store[composition.id] == null) store.promoteDraft(composition.id)
                        when (WallpaperSetter.apply(this, WallpaperTarget.Composition(composition.id))) {
                            WallpaperSetter.Result.UPDATED -> toast("Wallpaper updated")
                            WallpaperSetter.Result.PICKER_OPENED -> Unit
                            WallpaperSetter.Result.FAILED -> toast("Live wallpapers aren't supported here")
                        }
                    },
                    onEdit = {
                        if (store[composition.id] == null) store.promoteDraft(composition.id)
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_EDIT_COMPOSITION, composition.id)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        finish()
                    },
                    onDiscard = {
                        store.deleteDraft(composition.id)
                        finish()
                    },
                )
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_DRAFT_ID = "draft_id"
    }
}

@Composable
private fun PreviewScreen(
    composition: Composition,
    initiallySaved: Boolean,
    onSave: () -> Boolean,
    onSet: () -> Unit,
    onEdit: () -> Unit,
    onDiscard: () -> Unit,
) {
    var saved by rememberSaveable { mutableStateOf(initiallySaved) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ScenePreview(composition = composition, interactive = true, modifier = Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(composition.title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(
                if (saved) "Saved to My wallpapers" else "Made by your agent · touch to try it · not saved yet",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val overlay = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.4f),
                    contentColor = Color.White,
                )
                if (!saved) {
                    OutlinedButton(onClick = onDiscard, colors = overlay, modifier = Modifier.weight(1f)) { Text("Discard") }
                    OutlinedButton(onClick = { saved = onSave() }, colors = overlay, modifier = Modifier.weight(1f)) { Text("Save") }
                } else {
                    OutlinedButton(onClick = onEdit, colors = overlay, modifier = Modifier.weight(1f)) { Text("Add photo / layers") }
                }
            }
            Button(onClick = {
                onSet()
                saved = true
            }, modifier = Modifier.fillMaxWidth()) { Text("Set as wallpaper") }
        }
    }
}
