package com.lublo.agent

import android.content.Context
import android.os.Handler
import android.os.Looper

object Prefs {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("lublo", Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String = sp(c).getString("base", "https://api.apinex.bond/v1") ?: ""
    fun model(c: Context): String = sp(c).getString("model", "free/hy4") ?: ""
    fun apiKey(c: Context): String = sp(c).getString("key", "") ?: ""
    fun lang(c: Context): String = sp(c).getString("lang", "en-US") ?: "en-US"
    fun maxSteps(c: Context): Int = sp(c).getInt("steps", 25)

    fun save(c: Context, base: String, model: String, key: String, lang: String, steps: Int) {
        sp(c).edit()
            .putString("base", base.trim())
            .putString("model", model.trim())
            .putString("key", key.trim())
            .putString("lang", lang)
            .putInt("steps", steps.coerceIn(3, 60))
            .apply()
    }
}

/** Tiny status bus so the activity can show what the agent is doing. */
object Bus {
    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var last: String = ""
    private val h = Handler(Looper.getMainLooper())
    fun post(s: String) {
        last = s
        h.post { listener?.invoke(s) }
    }
}
