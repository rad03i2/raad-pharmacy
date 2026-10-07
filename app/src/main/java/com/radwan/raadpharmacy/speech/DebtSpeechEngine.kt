package com.radwan.raadpharmacy.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import java.util.Locale

/** Platform boundary for regression tests of actual startup failures. */
internal interface DebtSpeechEngine {
    fun setListener(listener: RecognitionListener)
    fun checkInstalledArabicLanguage(result: (String?) -> Unit)
    fun start(intent: Intent)
    fun stop()
    fun cancel()
    fun destroy()
}

internal interface DebtSpeechEngineFactory {
    fun systemAvailable(): Boolean
    fun onDeviceAvailable(): Boolean
    fun canCheckOnDeviceLanguage(): Boolean
    fun create(onDevice: Boolean): DebtSpeechEngine
}

internal class AndroidDebtSpeechEngineFactory(private val context: Context) : DebtSpeechEngineFactory {
    override fun systemAvailable() = SpeechRecognizer.isRecognitionAvailable(context)
    override fun onDeviceAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    override fun canCheckOnDeviceLanguage() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    override fun create(onDevice: Boolean): DebtSpeechEngine {
        val recognizer = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else SpeechRecognizer.createSpeechRecognizer(context)
        return AndroidDebtSpeechEngine(context, recognizer)
    }
}

private class AndroidDebtSpeechEngine(
    private val context: Context,
    private val recognizer: SpeechRecognizer
) : DebtSpeechEngine {
    override fun setListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
    override fun start(intent: Intent) = recognizer.startListening(intent)
    override fun stop() = recognizer.stopListening()
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()

    override fun checkInstalledArabicLanguage(result: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            result(null)
            return
        }
        recognizer.checkRecognitionSupport(
            DebtSpeechRecognizer.recognitionIntent("ar-IQ", true),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    // Pending/supported-but-not-installed models cannot listen yet.
                    val arabic = recognitionSupport.installedOnDeviceLanguages
                        .map { it.replace('_', '-') }
                        .filter { Locale.forLanguageTag(it).language == "ar" }
                    result(arabic.firstOrNull { it.equals("ar-IQ", ignoreCase = true) }
                        ?: arabic.firstOrNull())
                }
                override fun onError(error: Int) = result(null)
            }
        )
    }
}
