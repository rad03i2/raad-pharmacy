package com.radwan.raadpharmacy.backup

import com.radwan.raadpharmacy.data.CustomerEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceDriveBackupTest {
    private class Reply(val status: Int, val body: String) : HttpURLConnection(URL("https://www.googleapis.com/")) {
        val sent = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = sent
    }
    private fun bytes() = PortableBackup.encode(BackupCapture(listOf(CustomerEntity("c1", "أحمد", null, "", "", 0, "", 1000)), emptyList(), emptyList(), 1, false), JSONObject(), 5000)
    @Test fun uploadsSameVerifiedPortableFileIntoAnIndependentDeviceFolder() {
        val payload = bytes(); val replies = ArrayDeque(listOf(
            Reply(200, "{\"files\":[]}"), Reply(200, "{\"files\":[]}"), Reply(200, "{\"id\":\"root\"}"),
            Reply(200, "{\"files\":[]}"), Reply(200, "{\"id\":\"devicefolder\"}"),
            Reply(200, "{\"id\":\"copy\",\"size\":${payload.size},\"md5Checksum\":\"${DeviceDriveBackup.md5(payload)}\"}"),
            Reply(200, "{\"files\":[]}")
        ))
        val calls = mutableListOf<Pair<String, Reply>>()
        val client = DeviceDriveBackup("test-token") { path, _ -> replies.removeFirst().also { calls.add(path to it) } }
        assertEquals("copy", client.upload("device-123", PortableBackup.name(5000, 1), payload))
        val upload = calls.single { it.first.startsWith("upload/") }.second.sent.toString("UTF-8")
        assertTrue(upload.contains(String(payload)))
        assertTrue(upload.contains("devicefolder")); assertTrue(upload.contains("raadDevice"))
        assertTrue(calls.last().first.contains("device-123"))
        assertTrue(replies.isEmpty())
    }
    @Test fun incorrectRemoteChecksumNeverAcknowledgesOrPrunesPreviousBackups() {
        val replies = ArrayDeque(listOf(
            Reply(200, "{\"files\":[]}"), Reply(200, "{\"files\":[{\"id\":\"root\"}]}"),
            Reply(200, "{\"files\":[{\"id\":\"folder\"}]}"), Reply(200, "{\"id\":\"bad\",\"size\":1,\"md5Checksum\":\"wrong\"}")
        ))
        val client = DeviceDriveBackup("test-token") { _, _ -> replies.removeFirst() }
        assertThrows(IllegalStateException::class.java) { client.upload("device-123", PortableBackup.name(5000, 1), bytes()) }
        assertTrue(replies.isEmpty())
    }
    @Test fun expiredAuthorizationAndMissingDriveServiceAreReportedWithoutRawServerDetails() {
        val expired = DeviceDriveBackup("test-token") { _, _ -> Reply(401, "{}") }
        assertThrows(DriveAuthorizationNeeded::class.java) { expired.email() }
        val disabled = DeviceDriveBackup("test-token") { _, _ -> Reply(403, "{\"error\":{\"reason\":\"accessNotConfigured\"}}") }
        val error = assertThrows(IllegalStateException::class.java) { disabled.email() }
        assertTrue(error.message!!.contains("غير مفعّلة")); assertFalse(error.message!!.contains("accessNotConfigured"))
    }
    @Test fun automaticAuthorizationUsesOnlyAppCreatedDriveFilesAndPinsSelectedAccount() {
        val request = DeviceDriveBackup.request("backup@example.com")
        assertEquals("backup@example.com", request.account!!.name)
        assertEquals(listOf("https://www.googleapis.com/auth/drive.file"), request.requestedScopes.map { it.scopeUri })
    }
}
