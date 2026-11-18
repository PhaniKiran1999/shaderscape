package com.kiran.android.wallpaper.engine.composition

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Saved user compositions plus the photos they use, kept under [dir]:
 * `compositions.json` and `images/`. Thread-safe. Listeners are called on the thread that
 * made the change.
 *
 * **Drafts** (`drafts.json`) are compositions nobody has chosen to keep yet, e.g. ones an agent
 * made for the user to preview. They aren't listed by [all]. [promoteDraft] turns one into a
 * saved composition, and [pruneDrafts] drops old ones.
 */
class CompositionStore(private val dir: File) {

    fun interface Listener {
        fun onCompositionsChanged()
    }

    private val file = File(dir, "compositions.json")
    val imagesDir = File(dir, "images")
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val draftsFile = File(dir, "drafts.json")
    private var cache: List<Composition> = load()
    private var drafts: List<Draft> = loadDrafts()

    /** A composition awaiting the user's decision. [createdAt] is epoch millis. */
    data class Draft(val composition: Composition, val createdAt: Long)

    @Synchronized
    fun all(): List<Composition> = cache

    @Synchronized
    operator fun get(id: String): Composition? = cache.firstOrNull { it.id == id }

    /** Inserts or replaces [c] (matched by id). */
    fun save(c: Composition) {
        require(c.layers.size <= Composition.MAX_LAYERS) { "At most ${Composition.MAX_LAYERS} layers" }
        synchronized(this) {
            val i = cache.indexOfFirst { it.id == c.id }
            cache = if (i >= 0) cache.toMutableList().also { it[i] = c } else cache + c
            persist()
        }
        notifyListeners()
    }

    fun delete(id: String) {
        synchronized(this) {
            cache = cache.filterNot { it.id == id }
            persist()
        }
        pruneImages()
        notifyListeners()
    }

    // ---- Drafts ----

    @Synchronized
    fun drafts(): List<Draft> = drafts

    @Synchronized
    fun getDraft(id: String): Composition? = drafts.firstOrNull { it.composition.id == id }?.composition

    /** Inserts or replaces a draft (matched by id). The original creation time is kept. */
    fun saveDraft(c: Composition, now: Long = System.currentTimeMillis()) {
        require(c.layers.size <= Composition.MAX_LAYERS) { "At most ${Composition.MAX_LAYERS} layers" }
        synchronized(this) {
            val created = drafts.firstOrNull { it.composition.id == c.id }?.createdAt ?: now
            drafts = drafts.filterNot { it.composition.id == c.id } + Draft(c, created)
            persistDrafts()
        }
    }

    fun deleteDraft(id: String) {
        synchronized(this) {
            drafts = drafts.filterNot { it.composition.id == id }
            persistDrafts()
        }
        pruneImages()
    }

    /** Moves a draft into the saved compositions, optionally retitled. Returns it, or null if gone. */
    fun promoteDraft(id: String, title: String? = null): Composition? {
        val promoted = synchronized(this) {
            val d = drafts.firstOrNull { it.composition.id == id } ?: return null
            drafts = drafts - d
            persistDrafts()
            d.composition.let { if (title != null) it.copy(title = title) else it }
        }
        save(promoted)
        return promoted
    }

    /** Drops drafts created before [now] − [maxAgeMs]. Returns how many were removed. */
    fun pruneDrafts(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Int {
        val removed = synchronized(this) {
            val keep = drafts.filter { now - it.createdAt <= maxAgeMs }
            val n = drafts.size - keep.size
            if (n > 0) {
                drafts = keep
                persistDrafts()
            }
            n
        }
        if (removed > 0) pruneImages()
        return removed
    }

    /** Deletes stored images that no saved composition or draft uses, apart from [keep]. */
    fun pruneImages(keep: Set<String> = emptySet()) {
        val used = synchronized(this) {
            cache.flatMapTo(HashSet()) { it.imagePaths() } + drafts.flatMap { it.composition.imagePaths() }
        } + keep
        imagesDir.listFiles()?.forEach { f ->
            if (f.absolutePath !in used && !f.delete()) Log.w(TAG, "Could not delete $f")
        }
    }

    /**
     * Copies the picked image into [imagesDir] with EXIF orientation applied, downsampled so
     * its long side is at most [maxSide]. Returns the stored file's path. Blocking: run off
     * the main thread.
     */
    fun importImage(context: Context, uri: Uri, maxSide: Int = defaultMaxSide(context)): String {
        imagesDir.mkdirs()
        val bitmap = decodeOriented(context.contentResolver, uri, maxSide)
        val out = File(imagesDir, "${newId()}.jpg")
        try {
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } finally {
            bitmap.recycle()
        }
        return out.absolutePath
    }

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    private fun notifyListeners() = listeners.forEach { it.onCompositionsChanged() }

    private fun load(): List<Composition> = try {
        if (file.exists()) CompositionJson.listFromString(file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Could not read $file", e)
        emptyList()
    }

    private fun loadDrafts(): List<Draft> = try {
        if (!draftsFile.exists()) emptyList() else {
            val arr = org.json.JSONArray(draftsFile.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Draft(CompositionJson.fromJson(o.getJSONObject("composition")), o.optLong("createdAt"))
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not read $draftsFile", e)
        emptyList()
    }

    private fun persist() = writeAtomically(file, CompositionJson.listToString(cache))

    private fun persistDrafts() = writeAtomically(
        draftsFile,
        org.json.JSONArray().apply {
            drafts.forEach {
                put(org.json.JSONObject().put("createdAt", it.createdAt).put("composition", CompositionJson.toJson(it.composition)))
            }
        }.toString(),
    )

    private fun writeAtomically(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not write $target" }
        }
    }

    companion object {
        private const val TAG = "CompositionStore"

        @Volatile
        private var shared: CompositionStore? = null

        /** Process-wide store under `filesDir/wallpaper_engine`. Safe to call more than once. */
        fun init(context: Context): CompositionStore = shared ?: synchronized(this) {
            shared ?: CompositionStore(File(context.applicationContext.filesDir, "wallpaper_engine"))
                .also { shared = it }
        }

        val instance: CompositionStore
            get() = checkNotNull(shared) { "Call CompositionStore.init(context) first" }

        private fun defaultMaxSide(context: Context): Int {
            val dm = context.resources.displayMetrics
            return (2 * max(dm.widthPixels, dm.heightPixels)).coerceIn(1024, 4096)
        }

        private fun decodeOriented(cr: ContentResolver, uri: Uri, maxSide: Int): Bitmap {
            if (Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder applies EXIF orientation itself.
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { decoder, info, _ ->
                    val scale = maxSide.toFloat() / max(info.size.width, info.size.height)
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("Could not decode $uri")
            val degrees = cr.openInputStream(uri).use { stream ->
                when (stream?.let { ExifInterface(it) }
                    ?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
            if (degrees == 0f) return raw
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
            if (rotated !== raw) raw.recycle()
            return rotated
        }
    }
}
