package com.radwan.raadpharmacy.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class DebtSpeechError {
    NO_MATCH, TIMEOUT, BUSY, PERMISSION, UNAVAILABLE,
    START_FAILED, LANGUAGE, NETWORK, AUDIO, SERVER, OTHER
}

/** One foreground session. Only final results are allowed to become an amount. */
class DebtSpeechRecognizer internal constructor(
    private val factory: DebtSpeechEngineFactory,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    constructor(context: Context) : this(AndroidDebtSpeechEngineFactory(context.applicationContext))

    private var engine: DebtSpeechEngine? = null
    private var callbacks = Callbacks()
    private var active = false
    private var generation = 0L
    private var ready = false
    private var processing = false
    private var onDevice = false
    private var language = "ar-IQ"
    private var lastPartial = ""
    private var watchdog: Runnable? = null
    private var silenceTimer: Runnable? = null
    private var sessionLimit: Runnable? = null

    data class Callbacks(
        val onPreparing: () -> Unit = {},
        val onReady: () -> Unit = {},
        val onSpeechStarted: () -> Unit = {},
        val onPartial: (String) -> Unit = {},
        val onFinal: (List<String>) -> Unit = {},
        val onEndOfSpeech: () -> Unit = {},
        val onError: (DebtSpeechError) -> Unit = {}
    )

    fun isRecognitionAvailable(): Boolean = factory.systemAvailable() || factory.onDeviceAvailable()

    fun start(callbacks: Callbacks): Boolean {
        checkMainThread()
        if (active) return false
        this.callbacks = callbacks
        active = true
        ready = false
        processing = false
        lastPartial = ""
        callbacks.onPreparing()
        if (!isRecognitionAvailable()) {
            finishError(DebtSpeechError.UNAVAILABLE)
            return false
        }

        // Engine availability does not mean an Arabic model is installed.
        // API 31/32 cannot verify this; use the system service there instead.
        if (factory.canCheckOnDeviceLanguage() && factory.onDeviceAvailable()) {
            val token = newAttempt()
            val local = runCatching { factory.create(onDevice = true) }.getOrNull()
            if (local == null) {
                startSystem()
            } else {
                engine = local
                armWatchdog(4_000L) { startSystem() }
                runCatching {
                    local.checkInstalledArabicLanguage { installed ->
                        handler.post {
                            if (isCurrent(token)) {
                                if (installed == null) startSystem()
                                else listen(local, token, true, installed)
                            }
                        }
                    }
                }.onFailure { startSystem() }
            }
        } else {
            startSystem()
        }
        return active
    }

    private fun startSystem(requestedLanguage: String = "ar-IQ") {
        if (!active) return
        val token = newAttempt()
        callbacks.onPreparing()
        if (!factory.systemAvailable()) {
            finishError(DebtSpeechError.UNAVAILABLE)
            return
        }
        val system = runCatching { factory.create(onDevice = false) }.getOrNull()
        if (system == null) {
            finishError(DebtSpeechError.UNAVAILABLE)
        } else {
            engine = system
            listen(system, token, false, requestedLanguage)
        }
    }

    private fun listen(current: DebtSpeechEngine, token: Long, local: Boolean, locale: String) {
        clearTimers()
        onDevice = local
        language = locale
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isCurrent(token)) return
                ready = true
                callbacks.onReady()
                armWatchdog(8_000L) { stopListening() }
                sessionLimit = Runnable { if (isCurrent(token)) stopListening() }
                    .also { handler.postDelayed(it, 30_000L) }
            }

            override fun onBeginningOfSpeech() {
                if (!isCurrent(token)) return
                clearWatchdog()
                callbacks.onSpeechStarted()
            }

            override fun onEndOfSpeech() {
                if (isCurrent(token)) awaitFinal()
            }

            override fun onError(error: Int) {
                if (!isCurrent(token)) return
                val languageError = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (onDevice && (languageError || !ready)) {
                    startSystem()
                } else if (!onDevice && languageError && language == "ar-IQ") {
                    startSystem("ar")
                } else {
                    val mapped = if (!ready && (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        DebtSpeechError.START_FAILED
                    } else mapError(error)
                    finishError(mapped)
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isCurrent(token)) return
                val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty().filter { it.isNotBlank() }
                if (alternatives.isEmpty()) finishError(DebtSpeechError.NO_MATCH)
                else {
                    val completed = callbacks
                    cancel()
                    completed.onFinal(alternatives)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isCurrent(token) || processing) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
                callbacks.onPartial(text)
                if (!isCurrent(token)) return
                if (ArabicDebtAmountParser.hasCurrencyEndMarker(text) &&
                    ArabicDebtAmountParser.parse(text) != SpeechAmountParseResult.NotFound) {
                    stopListening()
                } else if (text != lastPartial) {
                    lastPartial = text
                    silenceTimer?.let(handler::removeCallbacks)
                    silenceTimer = Runnable { if (isCurrent(token)) stopListening() }
                        .also { handler.postDelayed(it, 3_000L) }
                }
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        armWatchdog(12_000L) {
            if (local) startSystem() else finishError(DebtSpeechError.START_FAILED)
        }
        runCatching {
            current.setListener(listener)
            current.start(recognitionIntent(locale, local))
        }.onFailure { error ->
            if (error is SecurityException) finishError(DebtSpeechError.PERMISSION)
            else if (local) startSystem()
            else finishError(DebtSpeechError.START_FAILED)
        }
    }

    fun stopListening() {
        checkMainThread()
        if (!active || processing) return
        if (!ready) {
            finishError(DebtSpeechError.START_FAILED)
            return
        }
        awaitFinal()
        runCatching { engine?.stop() }.onFailure { finishError(DebtSpeechError.AUDIO) }
    }

    private fun awaitFinal() {
        if (processing) return
        processing = true
        clearTimers()
        callbacks.onEndOfSpeech()
        armWatchdog(6_000L) { finishError(DebtSpeechError.TIMEOUT) }
    }

    fun cancel() {
        checkMainThread()
        active = false
        newAttempt()
        callbacks = Callbacks()
    }

    fun destroy() = cancel()

    private fun newAttempt(): Long {
        generation++ // Invalidate callbacks before cancelling the old engine.
        clearTimers()
        val old = engine
        engine = null
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
        ready = false
        processing = false
        lastPartial = ""
        return generation
    }

    private fun finishError(error: DebtSpeechError) {
        if (!active) return
        val completed = callbacks
        cancel()
        completed.onError(error)
    }

    private fun isCurrent(token: Long) = active && token == generation

    private fun armWatchdog(delay: Long, action: () -> Unit) {
        clearWatchdog()
        val token = generation
        watchdog = Runnable { if (isCurrent(token)) action() }
            .also { handler.postDelayed(it, delay) }
    }

    private fun clearWatchdog() {
        watchdog?.let(handler::removeCallbacks)
        watchdog = null
    }

    private fun clearTimers() {
        clearWatchdog()
        silenceTimer?.let(handler::removeCallbacks)
        sessionLimit?.let(handler::removeCallbacks)
        silenceTimer = null
        sessionLimit = null
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun mapError(error: Int): DebtSpeechError = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> DebtSpeechError.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> DebtSpeechError.TIMEOUT
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> DebtSpeechError.BUSY
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> DebtSpeechError.PERMISSION
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> DebtSpeechError.LANGUAGE
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> DebtSpeechError.NETWORK
        SpeechRecognizer.ERROR_AUDIO -> DebtSpeechError.AUDIO
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> DebtSpeechError.SERVER
        else -> DebtSpeechError.OTHER
    }

    internal companion object {
        fun recognitionIntent(language: String, offline: Boolean) =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            }
    }
}
