package com.orbit.clickwheel.library

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.util.concurrent.Executors

/** Loads album artwork off the main thread. Call [get] from the main thread only. */
object ArtCache {
    private val cache = object : LruCache<Long, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<Long>()
    private val pending = HashMap<Long, MutableList<() -> Unit>>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun get(context: Context, track: Track, onReady: () -> Unit): Bitmap? {
        val key = track.albumId
        cache.get(key)?.let { return it }
        if (key in missing) return null
        pending[key]?.let { it += onReady; return null }
        pending[key] = mutableListOf(onReady)
        val resolver = context.applicationContext.contentResolver
        executor.execute {
            val size = Size(720, 720)
            val bmp = try {
                resolver.loadThumbnail(track.uri, size, null)
            } catch (_: Exception) {
                try {
                    resolver.loadThumbnail(
                        ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, key), size, null
                    )
                } catch (_: Exception) {
                    null
                }
            }
            main.post {
                val callbacks = pending.remove(key).orEmpty()
                if (bmp != null) {
                    cache.put(key, bmp)
                    callbacks.forEach { it() }
                } else {
                    missing += key
                }
            }
        }
        return null
    }

    fun clear() {
        cache.evictAll()
        missing.clear()
    }
}
