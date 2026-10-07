package com.radwan.raadpharmacy.customer

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class CustomerPhotoStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun createTemporaryCameraUri(): Uri {
        val dir = File(appContext.cacheDir, "customer_photos_tmp").apply { mkdirs() }
        val file = File(dir, "customer-" + System.currentTimeMillis() + ".jpg")
        return FileProvider.getUriForFile(
            appContext,
            appContext.packageName + ".fileprovider",
            file
        )
    }

    fun save(customerId: String, source: Uri): File {
        val dir = File(appContext.filesDir, "customer_photos").apply { mkdirs() }
        val destination = File(dir, customerId + ".jpg")
        appContext.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "تعذر فتح الصورة." }
            FileOutputStream(destination, false).use { output ->
                input.copyTo(output)
            }
        }
        prefs.edit().putString(customerId, destination.absolutePath).apply()
        return destination
    }

    fun file(customerId: String): File? {
        val path = prefs.getString(customerId, null) ?: return null
        return File(path).takeIf { it.isFile }
    }

    fun remove(customerId: String) {
        file(customerId)?.delete()
        prefs.edit().remove(customerId).apply()
    }

    companion object {
        private const val PREFS = "customer_photo_store_v1"
    }
}
