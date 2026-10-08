package com.radwan.raadpharmacy.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/** Reuse small decoded avatars across settings visits; decoding happens on IO. */
internal object CloudTeamAvatars {
    private val images = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun cached(file: File?, revision: Long): Bitmap? =
        file?.let { images.get(it.absolutePath + ":" + revision) }

    fun load(file: File?, revision: Long): Bitmap? {
        cached(file, revision)?.let { return it }
        val image = file?.takeIf(File::isFile) ?: return null
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            BitmapFactory.decodeFile(image.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        images.put(image.absolutePath + ":" + revision, bitmap)
        return bitmap
    }
    fun clear() = images.evictAll()
}
