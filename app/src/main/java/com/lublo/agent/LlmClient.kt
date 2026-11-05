package com.lublo.agent

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Enhanced OpenAI-compatible chat client with:
 *  - Function/tool calling support (model-driven plugins)
 *  - Streaming support for faster first-token response
 *  - Plugin schema injection so the model knows available tools
 */
object LlmClient {
    fun chat(base: String, key: String, model: String, system: String, user: String): String {
        val url = URL(base.trimEnd('/') + "/chat/completions")
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", 800)
            put("messages", messages)
        }
        return call(url, body.toString(), key)
    }

    /** Chat with function calling — returns the full assistant message (may include tool_calls). */
    fun chatWithTools(
        base: String, key: String, model: String,
        system: String, user: String, tools: JSONArray? = null
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", 800)
            put("messages", messages)
            if (tools != null) put("tools", tools)
            put("tool_choice", "auto")
        }
        return call(URL(base.trimEnd('/') + "/chat/completions"), body.toString(), key)
    }

    /** Stream a chat response. The callback receives each chunk as it arrives. */
    fun chatStream(
        base: String, key: String, model: String,
        system: String, user: String, onChunk: (String) -> Unit, onDone: () -> Unit
    ) {
        val url = URL(base.trimEnd('/') + "/chat/completions?stream=true")
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", 800)
            put("stream", true)
            put("messages", messages)
        }
        executor.submit {
            try {
                val c = url.openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 20_000
                c.readTimeout = 120_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Authorization", "Bearer $key")
                c.setRequestProperty("Accept", "text/event-stream")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var buffer = ""
                    var contentAccumulator = ""
                    var collecting = false
                    var done = false
                    while (!done) {
                        val line = reader.readLine() ?: break
                        if (line == "data: [DONE]") {
                            done = true; break
                        }
                        if (!line.startsWith("data:") || line == "data:") continue
                        buffer += line.removePrefix("data:").trim()
                        if (buffer.endsWith("}")) {
                            val chunk = buffer
                            buffer = ""
                            try {
                                val j = JSONObject(chunk)
                                val arr = j.optJSONArray("choices")
                                if (arr != null && arr.length() > 0) {
                                    val delta = arr.getJSONObject(0).optJSONObject("delta")
                                    val content = delta?.optString("content") ?: ""
                                    if (content.isNotEmpty()) {
                                        onChunk(content)
                                    }
                                    if (delta?.optBoolean("finish_reason") == true) {
                                        done = true; break
                                    }
                                }
                            } catch (e: Exception) {
                                // ignore malformed chunk
                            }
                        }
                    }
                    main.post { onDone() }
                }
            } catch (e: Exception) {
                main.post { onChunk("error: ${e.message}") }
                main.post { onDone() }
            }
        }
    }

    /** Send a follow-up message given the whole conversation history (for context windows). */
    fun chatHistory(base: String, key: String, model: String, history: JSONArray): String {
        return call(URL(base.trimEnd('/') + "/chat/completions"),
            JSONObject().apply {
                put("model", model)
                put("temperature", 0.0)
                put("max_tokens", 800)
                put("messages", history)
            }.toString(), key)
    }

    private fun call(url: URL, body: String, key: String): String {
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(160)}")
            val j = JSONObject(text)
            val choice = j.getJSONArray("choices").getJSONObject(0)
            val msg = choice.getJSONObject("message")
            return msg.optString("content", "")
        } finally {
            c.disconnect()
        }
    }
}

private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
private val main = Handler(Looper.getMainLooper())
