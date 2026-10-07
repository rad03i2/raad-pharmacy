package com.radwan.raadpharmacy.customer

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

object CustomerPhotoUpdates {
    private val _revision = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revision: StateFlow<Map<String, Long>> = _revision.asStateFlow()

    fun bump(customerId: String) {
        _revision.value = _revision.value.toMutableMap().apply {
            put(customerId, System.currentTimeMillis())
        }
    }
}

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
        val destination = destination(customerId)
        appContext.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "تعذر فتح الصورة." }
            FileOutputStream(destination, false).use { output ->
                input.copyTo(output)
            }
        }
        prefs.edit()
            .putString(customerId, destination.absolutePath)
            .remove(remotePathKey(customerId))
            .apply()
        CustomerPhotoUpdates.bump(customerId)
        return destination
    }

    fun saveRemote(customerId: String, remotePath: String, bytes: ByteArray): File {
        val destination = destination(customerId)
        destination.writeBytes(bytes)
        prefs.edit()
            .putString(customerId, destination.absolutePath)
            .putString(remotePathKey(customerId), remotePath)
            .apply()
        CustomerPhotoUpdates.bump(customerId)
        return destination
    }

    fun file(customerId: String): File? {
        val path = prefs.getString(customerId, null) ?: return null
        return File(path).takeIf { it.isFile }
    }

    fun remotePath(customerId: String): String? =
        prefs.getString(remotePathKey(customerId), null)

    fun needsRemote(customerId: String, remotePath: String): Boolean =
        file(customerId) == null || remotePath(customerId) != remotePath

    fun remove(customerId: String) {
        file(customerId)?.delete()
        prefs.edit()
            .remove(customerId)
            .remove(remotePathKey(customerId))
            .apply()
        CustomerPhotoUpdates.bump(customerId)
    }

    private fun destination(customerId: String): File {
        val dir = File(appContext.filesDir, "customer_photos").apply { mkdirs() }
        return File(dir, customerId + ".jpg")
    }

    private fun remotePathKey(customerId: String): String = "remote_path_$customerId"

    companion object {
        private const val PREFS = "customer_photo_store_v1"
    }
}
