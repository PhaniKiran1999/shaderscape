package com.kiran.android.wallpapers.ui

import android.widget.Toast
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kiran.android.wallpaper.engine.WallpaperRegistry
import com.kiran.android.wallpaper.engine.WallpaperSetter

@Composable
fun DetailScreen(sceneId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val entry = remember(sceneId) { WallpaperRegistry[sceneId] }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ScenePreview(sceneId = sceneId, interactive = true, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(20.dp),
        ) {
            Text(entry?.title ?: sceneId, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            val description = entry?.description
            if (!description.isNullOrEmpty()) {
                Text(
                    description,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.4f),
                    contentColor = Color.White,
                ),
                modifier = Modifier.weight(1f),
            ) { Text("Back") }
            Button(
                onClick = {
                    when (WallpaperSetter.apply(context, sceneId)) {
                        WallpaperSetter.Result.UPDATED ->
                            Toast.makeText(context, "Wallpaper updated", Toast.LENGTH_SHORT).show()
                        WallpaperSetter.Result.PICKER_OPENED -> Unit
                        WallpaperSetter.Result.FAILED ->
                            Toast.makeText(context, "Live wallpapers aren't supported here", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Set wallpaper") }
        }
    }
}
