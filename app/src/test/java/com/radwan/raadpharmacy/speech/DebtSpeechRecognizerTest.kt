package com.radwan.raadpharmacy.speech

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class DebtSpeechRecognizerTest {
    private val factory = FakeFactory()
    private val recognizer = DebtSpeechRecognizer(factory)
    private val errors = mutableListOf<DebtSpeechError>()
    private val finals = mutableListOf<List<String>>()
    private var readyCount = 0
    private var processingCount = 0
    private val callbacks = DebtSpeechRecognizer.Callbacks(
        onReady = { readyCount++ },
        onEndOfSpeech = { processingCount++ },
        onFinal = { finals += it },
        onError = { errors += it }
    )

    @Test fun systemStartsWithoutForcingMissingOfflineModel() {
        assertTrue(recognizer.start(callbacks))
        val engine = factory.engines.single()
        assertEquals(0, readyCount)
        assertEquals("ar-IQ", engine.intent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(engine.intent!!.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        engine.callbackListener.onReadyForSpeech(null)
        assertEquals(1, readyCount)
        recognizer.destroy()
    }

    @Test fun localEngineWithNoInstalledArabicFallsBackBeforeListening() {
        factory.localAvailable = true
        recognizer.start(callbacks)
        val local = factory.engines.single()
        local.support!!(null)
        idle()
        assertEquals(listOf(true, false), factory.createdModes)
        assertNull(local.intent)
        assertTrue(local.destroyed)
        assertNotNull(factory.engines.last().intent)
        assertTrue(errors.isEmpty())
        recognizer.destroy()
    }

    @Test fun installedArabicUsesVerifiedLocalLocale() {
        factory.localAvailable = true
        recognizer.start(callbacks)
        val local = factory.engines.single()
        local.support!!("ar-SA")
        idle()
        assertEquals("ar-SA", local.intent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(local.intent!!.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        recognizer.destroy()
    }

    @Test fun oldAndroidUsesSystemWithoutAssumingLocalArabicSupport() {
        factory.localAvailable = true
        factory.supportCheckAvailable = false
        recognizer.start(callbacks)
        assertEquals(listOf(false), factory.createdModes)
        recognizer.destroy()
    }

    @Test fun unsupportedIraqiLocaleRetriesGenericArabicOnlyOnce() {
        recognizer.start(callbacks)
        factory.engines.last().callbackListener.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        val generic = factory.engines.last()
        assertEquals("ar", generic.intent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        generic.callbackListener.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertEquals(listOf(DebtSpeechError.LANGUAGE), errors)
        assertEquals(2, factory.engines.size)
        assertTrue(generic.destroyed)
    }

    @Test fun missingMicrophonePermissionIsNotReportedAsInvalidAmount() {
        recognizer.start(callbacks)
        factory.engines.single().callbackListener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals(listOf(DebtSpeechError.PERMISSION), errors)
        assertTrue(finals.isEmpty())
    }

    @Test fun errorBeforeReadyIsReportedAsStartupFailure() {
        recognizer.start(callbacks)
        factory.engines.single().callbackListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(listOf(DebtSpeechError.START_FAILED), errors)
    }

    @Test fun networkAndAudioFailuresKeepTheirActualCause() {
        recognizer.start(callbacks)
        factory.engines.last().callbackListener.onError(SpeechRecognizer.ERROR_NETWORK)
        recognizer.start(callbacks)
        factory.engines.last().callbackListener.onError(SpeechRecognizer.ERROR_AUDIO)
        assertEquals(listOf(DebtSpeechError.NETWORK, DebtSpeechError.AUDIO), errors)
    }

    @Test fun repeatedClickCannotStartTwoMicrophones() {
        assertTrue(recognizer.start(callbacks))
        assertFalse(recognizer.start(callbacks))
        assertEquals(1, factory.engines.size)
        recognizer.destroy()
    }

    @Test fun currencyPartialStopsCaptureButDoesNotCommitProvisionalAmount() {
        recognizer.start(callbacks)
        val engine = factory.engines.single()
        engine.callbackListener.onReadyForSpeech(null)
        engine.callbackListener.onBeginningOfSpeech()
        engine.callbackListener.onPartialResults(results("خمسة آلاف دينار عراقي"))
        assertEquals(1, engine.stopCount)
        assertEquals(1, processingCount)
        assertTrue(finals.isEmpty())
        engine.callbackListener.onResults(results("سبعة آلاف وخمسمائة دينار عراقي"))
        assertEquals(SpeechAmountParseResult.Success(7_500L),
            ArabicDebtAmountParser.parseAlternatives(finals.single()))
        assertTrue(engine.destroyed)
        engine.callbackListener.onResults(results("خمسة آلاف دينار عراقي"))
        assertEquals(1, finals.size)
    }

    @Test fun conflictingFinalAlternativesAreStillRejected() {
        recognizer.start(callbacks)
        factory.engines.single().callbackListener.onResults(results(
            "خمسة آلاف دينار عراقي", "ستة آلاف دينار عراقي"))
        assertEquals(SpeechAmountParseResult.Ambiguous,
            ArabicDebtAmountParser.parseAlternatives(finals.single()))
    }

    @Test fun initializationIsNotCutOffByTranscriptSilenceTimer() {
        recognizer.start(callbacks)
        advance(8_000)
        assertTrue(errors.isEmpty())
        assertEquals(0, factory.engines.single().stopCount)
        advance(4_000)
        assertEquals(listOf(DebtSpeechError.START_FAILED), errors)
        assertTrue(factory.engines.single().destroyed)
    }

    @Test fun supportCheckThatNeverRepliesFallsBackAndIgnoresLateReply() {
        factory.localAvailable = true
        recognizer.start(callbacks)
        val local = factory.engines.single()
        advance(4_000)
        assertEquals(listOf(true, false), factory.createdModes)
        local.support!!("ar-IQ")
        idle()
        assertNull(local.intent)
        recognizer.destroy()
    }

    @Test fun localStartupFailureFallsBackAndIgnoresOldListener() {
        factory.localAvailable = true
        recognizer.start(callbacks)
        val local = factory.engines.single()
        local.support!!("ar-IQ")
        idle()
        local.callbackListener.onError(SpeechRecognizer.ERROR_CLIENT)
        assertEquals(listOf(true, false), factory.createdModes)
        local.callbackListener.onReadyForSpeech(null)
        local.callbackListener.onResults(results("خمسة آلاف دينار عراقي"))
        assertEquals(0, readyCount)
        assertTrue(finals.isEmpty())
        recognizer.destroy()
    }

    @Test fun transcriptSilenceStopsOnceAndMissingFinalReleasesResources() {
        recognizer.start(callbacks)
        val engine = factory.engines.single()
        engine.callbackListener.onReadyForSpeech(null)
        engine.callbackListener.onBeginningOfSpeech()
        engine.callbackListener.onPartialResults(results("خمسة آلاف"))
        advance(3_000)
        assertEquals(1, engine.stopCount)
        advance(6_000)
        assertEquals(listOf(DebtSpeechError.TIMEOUT), errors)
        assertTrue(finals.isEmpty())
        assertTrue(engine.destroyed)
        assertTrue(recognizer.start(callbacks))
        recognizer.destroy()
    }

    @Test fun leavingScreenCancelsTimersAndIgnoresAllLateCallbacks() {
        recognizer.start(callbacks)
        val old = factory.engines.single()
        recognizer.destroy()
        old.callbackListener.onReadyForSpeech(null)
        old.callbackListener.onResults(results("خمسة آلاف دينار عراقي"))
        old.callbackListener.onError(SpeechRecognizer.ERROR_AUDIO)
        advance(60_000)
        assertEquals(0, readyCount)
        assertTrue(finals.isEmpty())
        assertTrue(errors.isEmpty())
        assertTrue(old.destroyed)
        assertTrue(recognizer.start(callbacks))
        recognizer.destroy()
    }

    @Test fun noServiceFailsClearlyAndAllowsAnotherAttempt() {
        factory.system = false
        assertFalse(recognizer.start(callbacks))
        assertEquals(listOf(DebtSpeechError.UNAVAILABLE), errors)
        assertTrue(factory.engines.isEmpty())
        factory.system = true
        assertTrue(recognizer.start(callbacks))
        recognizer.destroy()
    }

    private fun results(vararg texts: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*texts))
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private class FakeFactory : DebtSpeechEngineFactory {
        var system = true
        var localAvailable = false
        var supportCheckAvailable = true
        val engines = mutableListOf<FakeEngine>()
        val createdModes = mutableListOf<Boolean>()
        override fun systemAvailable() = system
        override fun onDeviceAvailable() = localAvailable
        override fun canCheckOnDeviceLanguage() = supportCheckAvailable
        override fun create(onDevice: Boolean): DebtSpeechEngine {
            createdModes += onDevice
            return FakeEngine().also { engines += it }
        }
    }

    private class FakeEngine : DebtSpeechEngine {
        lateinit var callbackListener: RecognitionListener
        var support: ((String?) -> Unit)? = null
        var intent: Intent? = null
        var stopCount = 0
        var destroyed = false
        override fun setListener(listener: RecognitionListener) { this.callbackListener = listener }
        override fun checkInstalledArabicLanguage(result: (String?) -> Unit) { support = result }
        override fun start(intent: Intent) { this.intent = intent }
        override fun stop() { stopCount++ }
        override fun cancel() = Unit
        override fun destroy() { destroyed = true }
    }
}
