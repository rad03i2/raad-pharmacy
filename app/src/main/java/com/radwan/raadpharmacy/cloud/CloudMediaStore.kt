package com.radwan.raadpharmacy.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.radwan.raadpharmacy.customer.CustomerPhotoStore
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File

class CloudMediaStore(context: Context) {
    private val appContext = context.applicationContext
    private val client = SupabaseProvider.client
    private val customerPhotos = CustomerPhotoStore(appContext)

    suspend fun uploadCustomerPhoto(customerId: String, file: File): String {
        check(com.radwan.raadpharmacy.data.PharmacyLedgerDatabase.get(appContext).dao().restoreHold() != "1") {
            "تعديل صور الزبائن معلق حتى اكتمال المصالحة بعد الاستعادة."
        }
        val profile = currentProfile()
        val path = profile.pharmacyId + "/customers/" + customerId + "/" +
            System.currentTimeMillis() + ".jpg"

        val optimized = optimizeJpeg(file.readBytes(), maxDimension = 1280, quality = 86)

        client.storage.from(BUCKET).upload(path, optimized) {
            upsert = true
            contentType = ContentType.Image.JPEG
        }

        client.from("customers").update(CustomerPhotoPathPatch(path)) {
            filter { eq("id", customerId) }
        }

        customerPhotos.saveRemote(customerId, path, optimized)
        return path
    }

    suspend fun reconcileCustomerPhotos(rows: List<CloudCustomerRow>) {
        rows.asSequence()
            .filter { it.deletedAt == null }
            .forEach { row ->
                val remote = row.photoPath
                val local = customerPhotos.file(row.id)
                when {
                    !remote.isNullOrBlank() -> runCatching { syncCustomerPhoto(row) }
                    local?.isFile == true -> runCatching {
                        uploadCustomerPhoto(row.id, local)
                    }
                }
            }
    }

    suspend fun syncCustomerPhoto(row: CloudCustomerRow) {
        val path = row.photoPath ?: return
        if (!customerPhotos.needsRemote(row.id, path)) return

        val bytes = client.storage.from(BUCKET).downloadAuthenticated(path)
        if (bytes.isNotEmpty()) {
            customerPhotos.saveRemote(row.id, path, bytes)
        }
    }

    suspend fun uploadMyAvatar(bytes: ByteArray): String {
        require(bytes.isNotEmpty())
        val profile = currentProfile()
        val path = profile.pharmacyId + "/profiles/" + profile.id + "/" +
            System.currentTimeMillis() + ".jpg"

        val optimized = optimizeJpeg(bytes, maxDimension = 720, quality = 86)

        client.storage.from(BUCKET).upload(path, optimized) {
            upsert = true
            contentType = ContentType.Image.JPEG
        }

        client.from("profiles").update(ProfileAvatarPathPatch(path)) {
            filter { eq("id", profile.id) }
        }

        saveProfilePhoto(profile.id, path, optimized)
        return path
    }

    suspend fun syncProfilePhoto(userId: String, remotePath: String?): File? {
        if (remotePath.isNullOrBlank()) return profilePhotoFile(userId).takeIf { it.isFile }
        val marker = profilePhotoMarker(userId)
        val existing = profilePhotoFile(userId)
        if (existing.isFile && marker.readTextOrNull() == remotePath) return existing

        val bytes = client.storage.from(BUCKET).downloadAuthenticated(remotePath)
        if (bytes.isEmpty()) return existing.takeIf { it.isFile }
        return saveProfilePhoto(userId, remotePath, bytes)
    }

    fun profilePhotoFile(userId: String): File =
        File(profilePhotoDir(), userId + ".jpg")

    private suspend fun currentProfile(): CloudProfileRow {
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: error("No authenticated cloud session")
        return client.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingle()
    }

    private fun optimizeJpeg(
        bytes: ByteArray,
        maxDimension: Int,
        quality: Int
    ): ByteArray {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return bytes

        val largest = maxOf(source.width, source.height)
        val scaled = if (largest > maxDimension) {
            val ratio = maxDimension.toFloat() / largest.toFloat()
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }

        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
        if (scaled !== source) scaled.recycle()
        source.recycle()
        return output.toByteArray().takeIf { it.isNotEmpty() } ?: bytes
    }

    private fun saveProfilePhoto(userId: String, path: String, bytes: ByteArray): File {
        val file = profilePhotoFile(userId)
        file.writeBytes(bytes)
        profilePhotoMarker(userId).writeText(path)
        return file
    }

    private fun profilePhotoDir(): File =
        File(appContext.filesDir, "profile_photos").apply { mkdirs() }

    private fun profilePhotoMarker(userId: String): File =
        File(profilePhotoDir(), userId + ".remote_path")

    private fun File.readTextOrNull(): String? =
        runCatching { takeIf { isFile }?.readText() }.getOrNull()

    @Serializable
    private data class CustomerPhotoPathPatch(
        @SerialName("photo_path") val photoPath: String
    )

    @Serializable
    private data class ProfileAvatarPathPatch(
        @SerialName("avatar_path") val avatarPath: String
    )

    companion object {
        const val BUCKET = "raad-media"
    }
}
