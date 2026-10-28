package com.findphone.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Одна «сесія» розпізнавання мови через системний SpeechRecognizer.
 * Після завершення сесії викликається [onSessionEnded] із паузою до наступного запуску.
 * Працювати лише з головного потоку.
 */
class PhraseListener(
    private val context: Context,
    private val prefs: Prefs,
    private val onHeard: () -> Unit,
    private val onSessionEnded: (Long) -> Unit
) : RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var heard = false
    private var beepMutedByUs = false

    private val timeoutRunnable = Runnable { end(500L) }
    private val unmuteRunnable = Runnable { setBeepMuted(false) }

    fun start(timeoutMs: Long) {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onSessionEnded(10_000L)
            return
        }
        active = true
        heard = false
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            r.setRecognitionListener(this)
            recognizer = r
            setBeepMuted(true)
            r.startListening(buildIntent())
            handler.postDelayed(unmuteRunnable, 800)
            handler.postDelayed(timeoutRunnable, timeoutMs)
        } catch (e: Exception) {
            end(5_000L)
        }
    }

    /** Тихо зупиняє сесію, не викликаючи [onSessionEnded]. */
    fun cancel() {
        cleanup()
    }

    private fun end(nextDelayMs: Long) {
        if (!active) return
        cleanup()
        onSessionEnded(nextDelayMs)
    }

    private fun cleanup() {
        active = false
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(unmuteRunnable)
        setBeepMuted(false)
        val r = recognizer
        recognizer = null
        if (r != null) {
            try { r.cancel() } catch (e: Exception) { }
            try { r.destroy() } catch (e: Exception) { }
        }
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

    private fun check(bundle: Bundle?) {
        if (heard || bundle == null) return
        val list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val target = normalize(prefs.phrase)
        if (target.isEmpty()) return
        if (list.any { normalize(it).contains(target) }) {
            heard = true
            onHeard()
        }
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Багато телефонів пищать при кожному запуску розпізнавання.
     * Намагаємось на мить вимкнути звук сповіщень (лише якщо дзвінок у звичайному режимі).
     */
    private fun setBeepMuted(mute: Boolean) {
        if (mute) {
            if (beepMutedByUs || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            beepMutedByUs = true
        } else {
            if (!beepMutedByUs) return
            beepMutedByUs = false
        }
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (stream in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                audio.adjustStreamVolume(stream, dir, 0)
            } catch (e: Exception) {
                // «Не турбувати» не дає змінювати — нічого страшного
            }
        }
    }

    // ---------- RecognitionListener ----------

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onPartialResults(partialResults: Bundle?) {
        if (active) check(partialResults)
    }

    override fun onResults(results: Bundle?) {
        if (!active) return
        check(results)
        end(300L)
    }

    override fun onError(error: Int) {
        if (!active) return
        val delay = when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 3_000L
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 15_000L
            SpeechRecognizer.ERROR_CLIENT -> 1_000L
            10 -> 10_000L        // ERROR_TOO_MANY_REQUESTS
            12, 13 -> 30_000L    // мова не підтримується / недоступна
            else -> 300L         // тиша, нічого не розпізнано тощо
        }
        end(delay)
    }
}
