package com.radwan.raadpharmacy.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/** One short sound at a time; no sleeping worker and no playback after cancellation. */
internal object FeedbackSoundPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    fun play(context: Context, resourceId: Int, notification: Boolean = false) {
        val app = context.applicationContext
        main.post {
            stopCurrent()
            val current = MediaPlayer()
            player = current
            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(
                        if (notification) AudioAttributes.USAGE_NOTIFICATION
                        else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                requestFocus(app, attributes)
                current.setAudioAttributes(attributes)
                current.setVolume(1.0f, 1.0f)
                current.setDataSource(app, soundResourceUri(app, resourceId))
                current.setOnPreparedListener { prepared ->
                    if (player === prepared) runCatching { prepared.start() }.onFailure { release(prepared) }
                }
                current.setOnCompletionListener { release(it) }
                current.setOnErrorListener { failed, _, _ -> release(failed); true }
                current.prepareAsync()
            } catch (_: Exception) {
                release(current)
            }
        }
    }

    fun playFile(context: Context, file: File, notification: Boolean = false) {
        if (!file.isFile) return
        val app = context.applicationContext
        main.post {
            stopCurrent()
            val current = MediaPlayer()
            player = current
            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(
                        if (notification) AudioAttributes.USAGE_NOTIFICATION
                        else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                requestFocus(app, attributes)
                current.setAudioAttributes(attributes)
                current.setVolume(1.0f, 1.0f)
                current.setDataSource(file.absolutePath)
                current.setOnPreparedListener { prepared ->
                    if (player === prepared) {
                        runCatching { prepared.start() }
                            .onFailure { release(prepared) }
                    }
                }
                current.setOnCompletionListener { release(it) }
                current.setOnErrorListener { failed, _, _ -> release(failed); true }
                current.prepareAsync()
            } catch (_: Exception) {
                release(current)
            }
        }
    }

    fun stop() { main.post { stopCurrent() } }

    private fun requestFocus(context: Context, attributes: AudioAttributes) {
        val manager = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .build()
        audioManager = manager
        focusRequest = request
        runCatching { manager.requestAudioFocus(request) }
    }

    private fun stopCurrent() {
        player?.let(::release)
        abandonFocus()
    }

    private fun release(current: MediaPlayer) {
        if (player === current) {
            player = null
            abandonFocus()
        }
        runCatching { current.release() }
    }

    private fun abandonFocus() {
        val manager = audioManager
        val request = focusRequest
        if (manager != null && request != null) {
            runCatching { manager.abandonAudioFocusRequest(request) }
        }
        audioManager = null
        focusRequest = null
    }
}
