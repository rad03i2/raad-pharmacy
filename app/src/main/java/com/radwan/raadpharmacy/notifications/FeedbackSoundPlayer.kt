package com.radwan.raadpharmacy.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/** One short sound at a time; no sleeping worker and no playback after cancellation. */
internal object FeedbackSoundPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    fun play(context: Context, resourceId: Int, notification: Boolean = false) {
        val app = context.applicationContext
        main.post {
            stopCurrent()
            val current = MediaPlayer()
            player = current
            try {
                current.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (notification) AudioAttributes.USAGE_NOTIFICATION
                        else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
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

    fun stop() { main.post { stopCurrent() } }

    private fun stopCurrent() { player?.let(::release) }
    private fun release(current: MediaPlayer) {
        if (player === current) player = null
        runCatching { current.release() }
    }
}
