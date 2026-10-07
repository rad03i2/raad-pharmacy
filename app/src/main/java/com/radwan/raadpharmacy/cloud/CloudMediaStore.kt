package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.customer.CustomerPhotoStore
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

class CloudMediaStore(context: Context) {
    private val appContext = context.applicationContext
    private val client = SupabaseProvider.client
    private val customerPhotos = CustomerPhotoStore(appContext)

    suspend fun uploadCustomerPhoto(customerId: String, file: File): String {
        val profile = currentProfile()
        val path = profile.pharmacyId + "/customers/" + customerId + "/" +
            System.currentTimeMillis() + ".jpg"

        client.storage.from(BUCKET).upload(path, file.readBytes()) {
            upsert = true
            contentType = ContentType.Image.JPEG
        }

        client.from("customers").update(CustomerPhotoPathPatch(path)) {
            filter { eq("id", customerId) }
        }

        customerPhotos.saveRemote(customerId, path, file.readBytes())
        return path
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

        client.storage.from(BUCKET).upload(path, bytes) {
            upsert = true
            contentType = ContentType.Image.JPEG
        }

        client.from("profiles").update(ProfileAvatarPathPatch(path)) {
            filter { eq("id", profile.id) }
        }

        saveProfilePhoto(profile.id, path, bytes)
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
