package se.kampe.tvlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * Wallpapers of their own for inputs and dock apps (design/wallpaper-mockups.html, P2), added
 * with `./tv.sh wallpaper add "<name>" <image>...`. The images are in files/wallpapers/, listed
 * in files/wallpapers/index as `<name>\t<file>\t<original file name>` lines; the name is an
 * input's or app's label (or an app's package name), matched ignoring case.
 *
 * Images are rendered in [Wallpaper.ITEM] on a background thread and kept in a small cache.
 */
class ItemWallpapers(context: Context) {

    private val dir = File(context.filesDir, "wallpapers")
    private val index = File(dir, "index")
    private var indexModified = -1L
    private var files: Map<String, List<File>> = emptyMap()

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Wallpaper.Result>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Wallpaper.Result) =
            value.background.byteCount + (value.glass?.byteCount ?: 0)
    }

    /** The image files for the first of [names] that has any, or an empty list. */
    fun filesFor(names: List<String>): List<File> {
        reload()
        return names.firstNotNullOfOrNull { files[it.trim().lowercase()] } ?: emptyList()
    }

    /** Renders [file] (or takes it from the cache) and passes it to [done] on the main thread. */
    fun load(file: File, done: (Wallpaper.Result?) -> Unit) {
        cache.get(file.name)?.let { done(it); return }
        worker.execute {
            val result = render(file)
            main.post { done(result) }
        }
    }

    /** Renders every listed image in the background, so the first focus fades at once. */
    fun prefetch() {
        reload()
        files.values.flatten().distinct().forEach { file ->
            worker.execute { if (cache.get(file.name) == null) render(file) }
        }
    }

    private fun render(file: File): Wallpaper.Result? {
        cache.get(file.name)?.let { return it }
        val result = try {
            Wallpaper.fromFile(file, Wallpaper.ITEM)
        } catch (e: Exception) {
            Log.w(TAG, "Can't render $file: $e")
            null
        }
        result?.let { cache.put(file.name, it) } ?: Log.w(TAG, "Can't decode $file")
        return result
    }

    /** Re-reads the index if it changed (`tv.sh wallpaper` edits it while Home may be open). */
    private fun reload() {
        val modified = if (index.exists()) index.lastModified() else 0L
        if (modified == indexModified) return
        indexModified = modified
        files = if (modified == 0L) {
            emptyMap()
        } else {
            index.readLines()
                .map { it.split('\t') }
                .filter { it.size >= 2 && it[0].isNotBlank() }
                .map { it[0].trim().lowercase() to File(dir, it[1].trim()) }
                .filter { (_, file) -> file.exists() }
                .groupBy({ it.first }, { it.second })
        }
    }

    private companion object {
        const val TAG = "ItemWallpapers"
        const val CACHE_BYTES = 40 * 1024 * 1024 // about 16 images at 960x540 plus their glass
    }
}
