package app.folio.core.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache

/**
 * Byte-bounded LRU cache of rendered pages, shared across documents.
 * Bitmaps are never recycled manually because they may still be on screen; eviction lets GC reclaim them.
 */
class PageBitmapCache(maxBytes: Int) {

    private val lru = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(key: String): Bitmap? = lru.get(key)

    fun put(key: String, bitmap: Bitmap) {
        // Don't let a single oversized bitmap flush the entire cache.
        if (bitmap.allocationByteCount <= lru.maxSize() / 2) lru.put(key, bitmap)
    }

    fun evictPrefix(prefix: String) {
        lru.snapshot().keys.filter { it.startsWith(prefix) }.forEach { lru.remove(it) }
    }

    /** Called on memory pressure. [fraction] = portion of the budget to keep. */
    fun trimTo(fraction: Float) {
        lru.trimToSize((lru.maxSize() * fraction.coerceIn(0f, 1f)).toInt())
    }

    fun clear() = lru.evictAll()

    companion object {
        fun key(sessionId: String, pageIndex: Int, widthPx: Int) = "$sessionId:$pageIndex:$widthPx"

        /** About 1/6 of the per-app heap, capped at 160 MB. */
        fun defaultBudgetBytes(context: Context): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val heapBytes = am.memoryClass.toLong() * 1024 * 1024
            return (heapBytes / 6).coerceAtMost(160L * 1024 * 1024).toInt()
        }
    }
}
