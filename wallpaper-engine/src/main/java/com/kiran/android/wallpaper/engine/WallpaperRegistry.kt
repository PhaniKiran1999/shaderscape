package com.kiran.android.wallpaper.engine

import android.content.Context

/**
 * Catalogue of available wallpapers. Register them in `Application.onCreate()` so they exist
 * in every process entry point: the activity and the wallpaper service.
 */
object WallpaperRegistry {

    enum class Kind { SHADER, PIXEL }

    class Entry(
        val id: String,
        val title: String,
        val description: String,
        val kind: Kind,
        /** Creates a fresh scene. Called once per surface (home, lock, preview, each thumbnail). */
        val factory: (Context) -> WallpaperScene,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun register(entry: Entry) {
        synchronized(entries) { entries[entry.id] = entry }
    }

    /** Registers a scene. Its [Kind] comes from the scene type the factory returns. */
    inline fun <reified T : WallpaperScene> register(
        id: String,
        title: String,
        description: String = "",
        noinline factory: (Context) -> T,
    ) = register(Entry(id, title, description, kindOf(T::class.java), factory))

    @PublishedApi
    internal fun kindOf(type: Class<*>): Kind =
        if (PixelScene::class.java.isAssignableFrom(type)) Kind.PIXEL else Kind.SHADER

    fun all(): List<Entry> = synchronized(entries) { entries.values.toList() }

    operator fun get(id: String): Entry? = synchronized(entries) { entries[id] }

    /** The entry for [id], falling back to the first registered one. */
    fun resolve(id: String?): Entry? = synchronized(entries) {
        id?.let { entries[it] } ?: entries.values.firstOrNull()
    }

    fun create(context: Context, id: String?): WallpaperScene? = resolve(id)?.factory?.invoke(context)

    internal fun clear() = synchronized(entries) { entries.clear() }
}
