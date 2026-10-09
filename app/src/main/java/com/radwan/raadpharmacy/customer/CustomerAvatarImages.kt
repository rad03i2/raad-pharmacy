package com.radwan.raadpharmacy.customer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/** Bounded thumbnails reused while lists scroll. All file access stays on IO. */
internal object CustomerAvatarImages {
    private val images = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun cached(customerId: String, revision: Long): Bitmap? = images.get("$customerId:$revision")
    fun load(context: Context, customerId: String, revision: Long): Bitmap? {
        cached(customerId, revision)?.let { return it }
        val file = CustomerPhotoStore(context).file(customerId) ?: return null
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        images.put("$customerId:$revision", bitmap)
        return bitmap
    }
}
