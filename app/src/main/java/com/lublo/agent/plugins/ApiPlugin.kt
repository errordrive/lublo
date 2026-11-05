package com.lublo.agent.plugins

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Free online APIs the agent can query (no paid keys required).
 * All endpoints are public/free tiers. Configure your own keys in Prefs if desired.
 */
object ApiPlugin {
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    fun interface Callback { fun onResult(plugin: String, result: String) }

    /** Dispatch async. Callers must handle the callback. */
    fun execute(plugin: String, url: String, method: String = "GET", json: String? = null, cb: Callback) {
        executor.execute {
            try {
                val resp = call(url, method, json)
                cb.onResult(plugin, resp)
            } catch (e: Exception) {
                cb.onResult(plugin, "error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun call(urlStr: String, method: String, body: String?): String {
        val url = URL(urlStr)
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 25_000
        c.readTimeout = 60_000
        c.doOutput = body != null
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Lublo Agent)")
        body?.let { b -> c.outputStream.use { out -> out.write(b.toByteArray(Charsets.UTF_8)) } }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}: ${text.take(200)}")
        return text
    }

    /** Weather (wttr.in - free, no key). Returns concise weather for a city. */
    fun weather(city: String, cb: Callback) {
        val q = URLEncoder.encode(city, "UTF-8")
        val url = "https://wttr.in/$q?format=j1"
        execute("weather", url) { _, raw ->
            try {
                val j = JSONObject(raw)
                val cur = j.getJSONArray("current_condition").getJSONObject(0)
                val w = j.getJSONArray("weather").getJSONObject(0)
                val t = cur.optString("temp_C")
                val feels = cur.optString("FeelsLikeC")
                val d = cur.getJSONArray("weatherDesc").getJSONObject(0).optString("value")
                val hu = cur.optString("humidity")
                val ws = cur.optString("windspeedKmph")
                cb.onResult("weather", "Weather in $city: $t°C (feels like $feels°C), $d, humidity ${hu}%, wind ${ws} km/h. Today max: ${w.optString("maxtempC")}°C")
            } catch (e: Exception) {
                cb.onResult("weather", "weather: could not parse (${e.message})")
            }
        }
    }

    /** News (newsapi.org free tier — set NEWS_API_KEY for real usage). */
    private const val NEWS_API_KEY = "free" // set a real NewsAPI key here for live news
    fun news(topic: String, cb: Callback) {
        val q = URLEncoder.encode(topic, "UTF-8")
        if (NEWS_API_KEY == "free") {
            cb.onResult("news", "news: free tier limited — set a NewsAPI key in settings for live news. Try 'https://newsapi.org/v2/everything?q=$q&language=en'.")
            return
        }
        val url = "https://newsapi.org/v2/everything?q=$q&language=en&pageSize=5"
        execute("news", url) { _, raw ->
            try {
                val j = JSONObject(raw)
                val arr = j.getJSONArray("articles")
                val lines = StringBuilder()
                for (i in 0 until minOf(arr.length(), 5)) {
                    val a = arr.getJSONObject(i)
                    lines.append("${a.getString("title")}\n- ${a.optString("description", "").take(140)}\n${a.optString("url", "…")}\n")
                }
                cb.onResult("news", "News about $topic:\n$lines")
            } catch (e: Exception) {
                cb.onResult("news", "news error: ${e.message}")
            }
        }
    }

    /** Crypto prices (coincap.io free public API). */
    fun crypto(symbol: String, cb: Callback) {
        val s = URLEncoder.encode(symbol.uppercase(), "UTF-8")
        val url = "https://api.coingecko.com/api/v3/coins/$s"
        execute("crypto", url) { _, raw ->
            try {
                val j = JSONObject(raw)
                val n = j.optString("name")
                val market = j.getJSONObject("market_data")
                val priceUsd = market.optJSONObject("current_price")?.optDouble("usd") ?: 0.0
                val chg = market.optDouble("price_change_percentage_24h", 0.0)
                cb.onResult("crypto", "$n ($s): $priceUsd USD, 24h ${chg}%")
            } catch (e: Exception) {
                cb.onResult("crypto", "crypto error: ${e.message}")
            }
        }
    }

    /** Current time & date in a timezone. */
    fun time(zone: String, cb: Callback) {
        try {
            val url = "https://worldtimeapi.org/api/timezone/$zone"
            execute("time", url) { _, raw ->
                try {
                    val j = JSONObject(raw)
                    val off = j.getString("utc_offset")
                    val cur = j.getString("datetime")
                    cb.onResult("time", "Current time in $zone: $cur  (offset $off)")
                } catch (e: Exception) {
                    cb.onResult("time", "time error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            cb.onResult("time", "time error: ${e.message}")
        }
    }

    /** Simple web fetch of a URL (for when model wants to read a page). Uses free public mirror via corsproxy.io. */
    fun fetch(url: String, cb: Callback) {
        val proxy = "https://api.allorigins.win/raw?url=" + URLEncoder.encode(url, "UTF-8")
        execute("fetch", proxy) { _, raw ->
            try {
                val j = JSONObject(raw)
                if (j.has("error")) { cb.onResult("fetch", "fetch error: ${j.getString("error")}") }
                else { cb.onResult("fetch", j.getString("contents").take(3000)) }
            } catch (e: Exception) {
                cb.onResult("fetch", raw.take(2000))
            }
        }
    }

    /** Wikipedia summary (free). */
    fun wiki(query: String, cb: Callback) {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$q"
        execute("wiki", url) { _, raw ->
            try {
                val j = JSONObject(raw)
                cb.onResult("wiki", "${j.optString("title", query)}: ${j.optString("extract", "").take(600)}")
            } catch (e: Exception) {
                cb.onResult("wiki", "wiki error: ${e.message}")
            }
        }
    }
}
