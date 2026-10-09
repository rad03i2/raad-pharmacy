package com.radwan.raadpharmacy.notifications

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object PixabaySoundAssets {
    const val NOTIFICATION_SOURCE_PAGE =
        "https://www.myinstants.com/en/instant/iphone-notification-71441/"
    const val OPERATION_SOURCE_PAGE =
        "https://pixabay.com/sound-effects/som-matricula-464025/"

    private const val OPERATION_DOWNLOAD_URL =
        "https://cdn.pixabay.com/download/audio/2026/01/10/audio_fefb11cbc5.mp3?filename=u_oepgi4ep3v-som_matricula-464025.mp3"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val client by lazy { HttpClient(CIO) }

    fun prefetch(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { ensureCached(app) } }
    }

    suspend fun ensureCached(context: Context) = mutex.withLock {
        val dir = soundDir(context)
        if (!operationFile(context).isFile) {
            download(OPERATION_DOWNLOAD_URL, operationFile(context))
        }
        dir
    }

    fun playNotification(context: Context, silent: Boolean = false) {
        if (silent) return
        val app = context.applicationContext
        FeedbackSoundPlayer.play(app, com.radwan.raadpharmacy.R.raw.iphone_notification_myinstants, notification = true)
    }

    fun playOperation(context: Context) {
        val app = context.applicationContext
        val file = operationFile(app)
        if (file.isFile) {
            FeedbackSoundPlayer.playFile(app, file, notification = false)
        } else {
            scope.launch {
                runCatching { ensureCached(app) }
                    .onSuccess {
                        operationFile(app).takeIf { it.isFile }?.let { cached ->
                            FeedbackSoundPlayer.playFile(app, cached, notification = false)
                        }
                    }
            }
        }
    }

    fun operationFile(context: Context): File =
        File(soundDir(context), "pixabay_operation_som_matricula.mp3")

    private fun soundDir(context: Context): File =
        File(context.applicationContext.filesDir, "licensed_sounds").apply { mkdirs() }

    private suspend fun download(url: String, destination: File) {
        val bytes: ByteArray = client.get(url).body()
        require(bytes.isNotEmpty()) { "Empty sound download" }
        val temp = File(destination.parentFile, destination.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(destination)) {
            destination.writeBytes(temp.readBytes())
            temp.delete()
        }
    }
}
