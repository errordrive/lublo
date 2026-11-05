package com.lublo.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Multi-provider TTS for natural-sounding speech:
 *  1. Android built-in TextToSpeech (fastest, free, good quality on most devices)
 *  2. Free cloud TTS endpoint (Google Translate-style TTS mirror) as fallback for more human-like voice
 *
 * Callers must check build/install on a real device for API endpoints.
 * Note: this object is currently unused by the main voice path (Voice.kt);
 * it is kept compilable for the "Natural voice" toggle in MainActivity.
 */
object VoiceTts {
    var useCloudTts = false  // switch to free cloud TTS for more human-like voice

    private var appCtx: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingUtterances = ArrayDeque<Pair<String, (() -> Unit)?>>()
    private var currentQueueId = 0
    private var speakingId: String? = null

    fun initAndroid(ctx: Context, callback: () -> Unit) {
        appCtx = ctx.applicationContext
        if (tts != null) { callback(); return }
        tts = TextToSpeech(appCtx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setLanguage(Locale.US)
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        synchronized(pendingUtterances) {
                            speakingId = null
                            val next = pendingUtterances.removeFirstOrNull()
                            if (next != null) speakImpl(next.first, next.second)
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        synchronized(pendingUtterances) {
                            speakingId = null
                            val next = pendingUtterances.removeFirstOrNull()
                            if (next != null) speakImpl(next.first, next.second)
                        }
                    }
                })
            }
            callback()
        }
    }

    /** Queue text for speech. Returns an id for canceling. */
    fun speak(text: String, onDone: (() -> Unit)? = null): String {
        val id = "u${currentQueueId++}"
        synchronized(pendingUtterances) {
            if (ttsReady) {
                if (speakingId != null) {
                    pendingUtterances.addLast(Pair(text, onDone))
                    return id
                }
                speakImpl(text, onDone)
            } else {
                pendingUtterances.addLast(Pair(text, onDone))
            }
        }
        return id
    }

    private fun speakImpl(text: String, onDone: (() -> Unit)?) {
        speakingId = text.hashCode().toString()
        if (useCloudTts) {
            // Try free cloud TTS (mirror endpoint)
            speakCloud(text, onDone)
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, speakingId)
        }
        // onDone is called by the utterance progress listener when done
    }

    /**
     * Free cloud TTS using a public Google Translate TTS mirror.
     * URL pattern: https://translate.google.com/translate_tts?ie=UTF-8&q=TEXT&tl=LANG&client=tw-ob
     * Note: mirrors come and go; this is a fallback for more natural voice.
     */
    private fun speakCloud(text: String, onDone: (() -> Unit)?) {
        try {
            val encoded = java.net.URLEncoder.encode(text, "UTF-8")
            val lang = appCtx?.let { Prefs.lang(it) } ?: "en-US"
            val langTag = lang.replace("-", "_")
            val url = URL("https://translate.google.com/translate_tts?ie=UTF-8&q=$encoded&tl=$langTag&client=tw-ob")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val os = ByteArrayOutputStream()
            conn.inputStream.copyTo(os)
            os.close()
            if (conn.responseCode !in 200..299) {
                throw IOException("TTS HTTP ${conn.responseCode}")
            }
            // Write the mp3 to a temp file and play it
            val tmp = java.io.File.createTempFile("tts_", ".mp3")
            tmp.deleteOnExit()
            tmp.outputStream().use { os.writeTo(it) }
            playMp3(tmp, onDone)
        } catch (e: Exception) {
            // Fallback to Android TTS silently (and stop retrying cloud, to avoid a loop)
            useCloudTts = false
            speakAndroid(text, onDone)
        }
    }

    private fun playMp3(file: java.io.File, onDone: (() -> Unit)?) {
        try {
            val mp = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { player -> player.release(); onDone?.invoke() }
                start()
            }
        } catch (e: Exception) {
            onDone?.invoke()
        }
    }

    private fun speakAndroid(text: String, onDone: (() -> Unit)?) {
        // Direct device TTS (no cloud round-trip, no recursion into speakImpl).
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, speakingId)
    }

    fun cancel(id: String) {
        tts?.stop()
        speakingId = null
        synchronized(pendingUtterances) {
            pendingUtterances.removeIf { it.first.hashCode().toString() == id }
        }
    }

    fun destroy() {
        tts?.shutdown()
        tts = null
        ttsReady = false
        synchronized(pendingUtterances) {
            pendingUtterances.clear()
            speakingId = null
        }
    }
}
