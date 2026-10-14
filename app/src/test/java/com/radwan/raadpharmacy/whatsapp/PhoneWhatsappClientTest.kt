package com.radwan.raadpharmacy.whatsapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneWhatsappClientTest {
    private class LocalReply(private val payload: String) : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        val request = CompletableFuture<Pair<List<String>, String>>()
        private val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 8_000
                    val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers += line
                    }
                    val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val chars = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(chars, read, length - read)
                        check(count > 0)
                        read += count
                    }
                    request.complete(headers to String(chars))
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(response.toByteArray(Charsets.UTF_8)); write(bytes); flush() }
                }
            } catch (error: Exception) { request.completeExceptionally(error) }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); worker.join(1_000) }
    }

    @Test fun nativeStatusRequestUsesTheSecretAndRejectsAnUnrelatedService() = runBlocking {
        LocalReply("""{"protocol":"unrelated","state":"connected"}""").use { local ->
            val client = PhoneWhatsappClient({ "a".repeat(64) }, local.port)
            var rejected = false
            try { client.status() } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            val (headers, _) = local.request.get(8, TimeUnit.SECONDS)
            assertTrue(headers.any { it == "Authorization: Bearer ${"a".repeat(64)}" })
            assertTrue(headers.first().startsWith("GET /status "))
        }
    }

    @Test fun theNativeClientTransfersTheChosenRecipientAndOneMediaPayloadWithTheSameAttemptId() = runBlocking {
        val id = "11223344-5566-4777-8888-9900aabbccdd"
        val phone = "9647701234567"
        val response = JSONObject().put("protocol", PHONE_WHATSAPP_PROTOCOL).put("job", JSONObject()
            .put("id", id).put("phone", phone).put("senderPhone", "9647709999999")
            .put("state", "submitted").put("updatedAt", 123L)).toString()
        LocalReply(response).use { local ->
            val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2)
            val result = PhoneWhatsappClient({ "a".repeat(64) }, local.port)
                .sendTrial(id, phone, PhoneWhatsappTestImage.Payload(image, image))
            assertEquals("submitted", result.state)
            assertEquals(id, result.id)
            assertEquals("9647709999999", result.senderPhone)
            val (headers, raw) = local.request.get(8, TimeUnit.SECONDS)
            assertTrue(headers.first().startsWith("POST /test "))
            val body = JSONObject(raw)
            assertEquals(id, body.getString("requestId"))
            assertEquals(phone, body.getString("phone"))
            assertArrayEquals(image, android.util.Base64.decode(body.getString("imageBase64"), android.util.Base64.NO_WRAP))
            assertFalse(body.has("message"))
        }
    }
}
