package com.kiran.android.wallpaper.engine

import android.content.Context
import android.content.SharedPreferences
import com.kiran.android.wallpaper.engine.composition.Composition
import com.kiran.android.wallpaper.engine.composition.CompositionStore
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Stores what the live wallpaper should show (a [WallpaperTarget]) and notifies listeners
 * when it changes.
 */
object WallpaperSelection {

    fun interface Listener {
        fun onSelectionChanged(target: WallpaperTarget?)
    }

    private const val PREFS = "wallpaper_engine"
    private const val KEY_SELECTED = "selected_scene_id" // holds an encoded WallpaperTarget

    private var prefs: SharedPreferences? = null
    private val listeners = CopyOnWriteArraySet<Listener>()

    // SharedPreferences keeps only a weak reference to this, so hold it in a field.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_SELECTED) {
            val t = selected
            listeners.forEach { it.onSelectionChanged(t) }
        }
    }

    /** Also initialises [CompositionStore]. Safe to call more than once. */
    @Synchronized
    fun init(context: Context) {
        CompositionStore.init(context)
        if (prefs != null) return
        prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { it.registerOnSharedPreferenceChangeListener(prefListener) }
    }

    /** The selected target. Defaults to the first registered scene. */
    val selected: WallpaperTarget?
        get() = WallpaperTarget.parse(requirePrefs().getString(KEY_SELECTED, null))
            ?: WallpaperRegistry.all().firstOrNull()?.let { WallpaperTarget.Scene(it.id) }

    fun select(target: WallpaperTarget) {
        requirePrefs().edit().putString(KEY_SELECTED, target.encode()).apply()
    }

    fun select(sceneId: String) = select(WallpaperTarget.Scene(sceneId))

    /**
     * The composition to render for [target]. A scene becomes a one-layer composition, and a
     * deleted composition falls back to the first scene.
     */
    fun resolve(target: WallpaperTarget? = selected): Composition? = when (target) {
        is WallpaperTarget.Composition -> CompositionStore.instance[target.compositionId]
            ?: WallpaperRegistry.resolve(null)?.let { Composition.ofScene(it.id, it.title) }
        is WallpaperTarget.Scene -> WallpaperRegistry.resolve(target.sceneId)
            ?.let { Composition.ofScene(it.id, it.title) }
        null -> WallpaperRegistry.resolve(null)?.let { Composition.ofScene(it.id, it.title) }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun requirePrefs(): SharedPreferences =
        checkNotNull(prefs) { "Call WallpaperSelection.init(context) first" }
}
