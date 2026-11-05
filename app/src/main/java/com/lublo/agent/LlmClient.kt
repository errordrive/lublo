package com.lublo.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal OpenAI-compatible chat client (no libraries). */
object LlmClient {
    fun chat(base: String, key: String, model: String, system: String, user: String): String {
        val url = URL(base.trimEnd('/') + "/chat/completions")
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_tokens", 500)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(160)}")
            val msg = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            return msg.optString("content", "")
        } finally {
            c.disconnect()
        }
    }
}
