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

/** Wraps Android's built-in TextToSpeech + SpeechRecognizer. Call everything on the main thread. */
class Voice(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val done = HashMap<String, () -> Unit>()
    private var rec: SpeechRecognizer? = null
    private var counter = 0

    fun init() {
        tts = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ready = true
                applyLang()
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { fire(utteranceId) }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) { fire(utteranceId) }
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

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val t = tts
        if (!ready || t == null) {
            if (onDone != null) main.post { onDone() }
            return
        }
        applyLang()
        val id = "u${counter++}"
        if (onDone != null) synchronized(done) { done[id] = onDone }
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r == TextToSpeech.ERROR) fire(id)
    }

    fun stopSpeaking() {
        tts?.stop()
        synchronized(done) { done.clear() }
    }

    /** Calls back with the recognized text, or null on error/no speech/no permission. */
    fun listen(cb: (String?) -> Unit) {
        stopListening()
        val ok = SpeechRecognizer.isRecognitionAvailable(ctx) &&
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!ok) { cb(null); return }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        var finished = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
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
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.lang(ctx))
        r.startListening(i)
    }

    private fun release(r: SpeechRecognizer) {
        main.post {
            try { r.destroy() } catch (e: Exception) { }
            if (rec === r) rec = null
        }
    }

    fun stopListening() {
        rec?.let {
            try { it.cancel(); it.destroy() } catch (e: Exception) { }
        }
        rec = null
    }

    fun stopAll() {
        stopSpeaking()
        stopListening()
    }

    fun destroy() {
        stopAll()
        tts?.shutdown()
        tts = null
    }
}
