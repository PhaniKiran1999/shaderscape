package com.kiran.android.wallpaper.engine

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/** Selects a scene or composition and makes sure the engine's live wallpaper is the active one. */
object WallpaperSetter {

    enum class Result {
        /** The engine was already the live wallpaper, so the scene swapped in place. */
        UPDATED,
        /** The system live-wallpaper picker was opened for the user to confirm. */
        PICKER_OPENED,
        /** No picker could be opened on this device. */
        FAILED,
    }

    fun serviceComponent(context: Context) =
        ComponentName(context, ProceduralWallpaperService::class.java)

    fun isActive(context: Context): Boolean =
        WallpaperManager.getInstance(context).wallpaperInfo?.component == serviceComponent(context)

    fun apply(context: Context, sceneId: String): Result = apply(context, WallpaperTarget.Scene(sceneId))

    /** Selects [target]. Opens the system picker unless the engine is already the live wallpaper. */
    fun apply(context: Context, target: WallpaperTarget): Result {
        WallpaperSelection.init(context)
        WallpaperSelection.select(target)
        if (isActive(context)) return Result.UPDATED

        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, serviceComponent(context))
        val chooser = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
        for (intent in listOf(direct, chooser)) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return Result.PICKER_OPENED
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Cannot open ${intent.action}", e)
            }
        }
        return Result.FAILED
    }

    private const val TAG = "WallpaperSetter"
}
