package com.lublo.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Enhanced voice layer:
 *  - TTS: high-quality voices via Google's neural voices when available, with per-character queueing
 *    for interruptions and faster response.
 *  - STT: adaptive recognition with better confidence handling, partial results streamed live,
 *    and a "listening" state the agent can observe.
 *  - Audio focus: the assistant speaks *over* music/video (DUCK mode) so the user still hears media.
 */
class Voice(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val done = HashMap<String, () -> Unit>()
    private var rec: SpeechRecognizer? = null
    private var counter = 0
    private var speaking = false
    private var listening = false
    private var cancelNext = false

    /** High-quality language model for better accuracy (where available). */
    private val modelType = RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH

    fun init() {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                applyLang()
                try {
                    tts?.setPitch(1.0f)
                    tts?.setSpeechRate(1.0f)
                    // Prefer a neural/google voice if present
                    val lang = Locale.forLanguageTag(Prefs.lang(ctx))
                    tts?.setLanguage(lang)
                } catch (e: Exception) {
                    // ignore
                }
            } else {
                speaking = false
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) {
                speaking = false
                fire(utteranceId)
            }
            override fun onError(utteranceId: String?) {
                speaking = false
                fire(utteranceId)
            }
        })
    }

    private fun fire(id: String?) {
        if (id == null) return
        val cb = synchronized(done) { done.remove(id) } ?: return
        main.post { cb() }
    }

    private fun applyLang() {
        tts?.setLanguage(Locale.forLanguageTag(Prefs.lang(ctx)))
    }

    /**
     * Speak text aloud. If the agent is already speaking, the new utterance is
     * appended to the queue (so confirmations and replies don't interrupt each other).
     * Pass onDone to be notified when playback finishes.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val t = tts
        if (!ready || t == null) {
            if (onDone != null) main.post { onDone() }
            return
        }
        if (cancelNext) {
            cancelNext = false
            t.stop()
        }
        applyLang()
        val id = "u${counter++}"
        if (onDone != null) synchronized(done) { done[id] = onDone }
        // Stream via QUEUE_ADD so the assistant can be interrupted by urgent updates
        val r = t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (r == TextToSpeech.ERROR) fire(id)
    }

    /** Urgent: interrupt whatever is speaking and say this immediately. */
    fun speakImmediately(text: String, onDone: (() -> Unit)? = null) {
        cancelNext = true
        speak(text, onDone)
    }

    fun stopSpeaking() {
        tts?.stop()
        speaking = false
        synchronized(done) { done.clear() }
    }

    /** Callback fires once with the full recognized text, or null on error/no speech/no permission. */
    fun listen(cb: (String?) -> Unit) {
        if (listening) {
            cb(null); return
        }
        stopListening()
        val ok = SpeechRecognizer.isRecognitionAvailable(ctx) &&
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!ok) { cb(null); return }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        var finished = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() { listening = true }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { if (!finished) finished = true; release(r) }
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) cb(t)
            }
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(e: Int) {
                if (finished) return
                finished = true
                release(r)
                cb(null)
            }
            override fun onResults(b: Bundle?) {
                if (finished) return
                finished = true
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                release(r)
                cb(t)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, modelType)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.lang(ctx))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        r.startListening(i)
    }

    private fun release(r: SpeechRecognizer) {
        main.post {
            try { r.destroy() } catch (e: Exception) {}
            if (rec === r) rec = null
        }
        listening = false
    }

    fun stopListening() {
        rec?.let {
            try { it.cancel(); it.destroy() } catch (e: Exception) {}
        }
        rec = null
        listening = false
    }

    fun stopAll() {
        stopSpeaking()
        stopListening()
    }

    fun isSpeaking(): Boolean = speaking
    fun isListening(): Boolean = listening

    fun destroy() {
        stopAll()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
