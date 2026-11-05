package com.lublo.agent

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/** The observe -> think -> act loop. Runs on its own thread. */
class Agent(private val svc: AgentService) {
    @Volatile var running = false
    @Volatile private var cancelled = false
    private var thread: Thread? = null
    val pluginEngine = PluginEngine(svc)

    companion object {
        private val RISKY = Regex(
            "(?i)\\b(pay|buy|purchase|place order|send|delete|transfer|uninstall|erase|factory reset)\\b" +
                "|পাঠান|কিনুন|মুছুন|পেমেন্ট|भेजें|खरीदें"
        )

        private const val SYSTEM = """You control an Android phone through accessibility actions to complete the user's task.
Each turn you receive the task, your previous actions, and the current screen as a list of elements:
[id] Class "text" {flags} @centerX,centerY
Reply with ONE JSON object only. No markdown, no explanation outside JSON.

Actions:
{"action":"tap","id":N}
{"action":"long_press","id":N}
{"action":"type","id":N,"text":"...","submit":false}
{"action":"scroll","direction":"down|up|left|right","id":N}   (id optional; "down" reveals content further down)
{"action":"tap_xy","x":N,"y":N}   (only when no element fits)
{"action":"swipe","x1":N,"y1":N,"x2":N,"y2":N}
{"action":"back"}  {"action":"home"}  {"action":"recents"}  {"action":"notifications"}
{"action":"open_app","name":"Settings"}
{"action":"wait"}
{"action":"ask","question":"..."}   (ask the user for missing info; they answer by voice)
{"action":"finish","message":"short result, spoken aloud"}

Plugins (extra tools to get info or control the device):
{"action":"open_app","name":"<app>"}   open any installed app
{"action":"dial_phone","number":"+1234567890"}  make a phone call
{"action":"send_message","number":"+...","text":"..."}  send SMS
{"action":"lookup_contact","name":"<name>"}  read contacts
{"action":"add_contact","name":"<name>","phone":"+..."}  add a contact
{"action":"shell","command":"<shell cmd>"}  run shell command (Termux if installed)
{"action":"write_file","path":"<path>","content":"..."}  write a file
{"action":"read_file","path":"<path>"}  read a file
{"action":"set_wallpaper","url":"<https image url>"}  set wallpaper
{"action":"set_theme","mode":"dark|light"}  change theme
{"action":"get_battery"} {"action":"get_wifi"} {"action":"get_cellular"}  device status
{"action":"get_current_app"}  visible app
{"action":"get_clipboard"} / {"action":"set_clipboard","text":"..."}  clipboard
{"action":"get_active_tasks"}  running apps

Internet plugins (free APIs that run automatically when you mention them):
weather(city), news(topic), crypto(symbol), time(timezone), wiki(query), fetch(url)

Rules:
- Add "thought" (max 15 words) to every action.
- Add "risky":true to any action that spends money, sends a message or email, deletes data,
  installs apps, makes calls, or is irreversible.
- Start with open_app when a specific app is needed. Use only ids from the CURRENT screen.
- If the screen did not change after an action, try a different approach. Scroll to find items not visible.
- Never read or type passwords. If login, captcha or payment is needed, use ask or finish.
- Reply and speak in the user's language. Call finish as soon as the task is done.
- Context matters: remember the user's task across turns; break big tasks into steps.
- Be friendly and concise — you are a helpful assistant.

Screen size: ${dm.widthPixels}x${dm.heightPixels}px."""
    }

    fun start(task: String) {
        if (running) return
        running = true
        cancelled = false
        thread = Thread {
            try {
                loop(task)
            } catch (e: Exception) {
                if (!cancelled) svc.say("Something went wrong.", "Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                running = false
                svc.onAgentDone()
            }
        }.also { it.start() }
    }

    fun cancel() {
        cancelled = true
        svc.releaseWaiters()
        thread?.interrupt()
    }

    private fun loop(task: String) {
        val max = Prefs.maxSteps(svc)
        val history = ArrayList<String>()
        var lastSig = ""
        var repeats = 0
        val dm = svc.resources.displayMetrics
        val system = SYSTEM + "\nScreen size: ${dm.widthPixels}x${dm.heightPixels}px."
        svc.status("Working…")

        // If our own app is in front, go home first so we act on the real target app.
        if (svc.rootInActiveWindow?.packageName?.toString() == svc.packageName) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Thread.sleep(900)
        }

        for (step in 1..max) {
            if (cancelled) return
            val s = ScreenReader.read(svc)
            if (s == null) {
                history.add("(screen not readable)")
                Thread.sleep(800)
                continue
            }
            val note = if (repeats >= 2)
                "\nWARNING: you repeated the same action ${repeats + 1} times with no progress. Try something different, ask the user, or finish."
            else ""
            val user = "TASK: $task\nSTEP: $step/$max\nPREVIOUS ACTIONS:\n" +
                (history.takeLast(10).joinToString("\n").ifEmpty { "(none)" }) +
                note + "\n\nCURRENT SCREEN (app ${s.pkg}):\n${s.text}"

            val reply = try {
                LlmClient.chat(Prefs.baseUrl(svc), Prefs.apiKey(svc), Prefs.model(svc), system, user)
            } catch (e: Exception) {
                if (cancelled) return
                svc.say("I can't reach the AI service.", "API error: ${e.message}")
                return
            }
            if (cancelled) return

            val a = parse(reply)
            if (a == null) {
                history.add("$step. (your reply was not valid JSON; reply with one JSON object only)")
                continue
            }
            val act = a.optString("action")
            val id = a.optInt("id", -1)
            val label = s.labels[id] ?: ""

            if (act == "finish") {
                svc.say(a.optString("message", "Done."))
                return
            }
            if (act == "ask") {
                val q = a.optString("question", "What should I do?")
                val ans = svc.ask(q)
                if (cancelled) return
                history.add("$step. ask \"$q\" -> user said: ${ans ?: "(no answer)"}")
                continue
            }
            // Plugin/tool action (open_app with name arg, dial, shell, read, status, etc.)
            if (act == "open_app" && a.has("name")) {
                svc.status(a.optString("thought").ifBlank { "opening ${a.optString("name")}" })
                val result = pluginEngine?.run(act, a) ?: "unknown plugin"
                history.add("$step. open_app -> $result")
                Thread.sleep(900)
                continue
            }

            val sig = JSONObject(a.toString()).apply { remove("thought") }.toString()
            if (sig == lastSig) repeats++ else repeats = 0
            lastSig = sig
            if (repeats >= 5) {
                svc.say("I'm stuck, so I stopped.")
                return
            }

            val risky = a.optBoolean("risky") ||
                ((act == "tap" || act == "long_press") && label.isNotEmpty() && RISKY.containsMatchIn(label))
            if (risky) {
                val what = a.optString("thought").ifBlank { "$act $label" }
                if (!svc.confirm("Confirm: $what. Say yes or no.")) {
                    history.add("$step. $act $label -> USER DECLINED. Do not do this; choose another way or finish.")
                    continue
                }
                if (cancelled) return
            }

            svc.status(a.optString("thought").ifBlank { "$act $label" })
            val result = exec(act, a, s, id)
            history.add("$step. $act${if (label.isNotEmpty()) " \"$label\"" else ""}" +
                "${if (act == "type") " '${a.optString("text").take(40)}'" else ""} -> $result")
            Thread.sleep(if (act == "open_app") 1800 else 900)
        }
        svc.say("I stopped after $max steps without finishing.")
    }

    private fun parse(raw: String): JSONObject? {
        val t = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        val s = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (s < 0 || end <= s) return null
        return try { JSONObject(t.substring(s, end + 1)) } catch (ex: Exception) { null }
    }

    private fun exec(act: String, a: JSONObject, s: Screen, id: Int): String {
        val node = s.nodes[id]
        return when (act) {
            "tap" -> if (node == null) "bad id" else tap(id, node, s)
            "long_press" -> if (node == null) "bad id" else longPress(id, node, s)
            "type" -> typeText(a, node)
            "scroll" -> scroll(a, s, node)
            "tap_xy" -> if (svc.tapAt(a.optInt("x"), a.optInt("y"))) "ok" else "failed"
            "swipe" -> if (svc.swipe(a.optInt("x1"), a.optInt("y1"), a.optInt("x2"), a.optInt("y2"))) "ok" else "failed"
            "back" -> glob(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> glob(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> glob(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> glob(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "open_app" -> pluginEngine.run(act, a)
            "wait" -> { Thread.sleep(1500); "ok" }
            else -> "unknown action '$act'"
        }
    }

    private fun glob(g: Int) = if (svc.performGlobalAction(g)) "ok" else "failed"

    private fun tap(id: Int, node: AccessibilityNodeInfo, s: Screen): String {
        var n: AccessibilityNodeInfo? = node
        var d = 0
        while (n != null && !n.isClickable && d < 5) { n = n.parent; d++ }
        if (n != null && n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ok"
        val c = s.centers[id] ?: return "failed"
        return if (svc.tapAt(c[0], c[1])) "ok (gesture)" else "failed"
    }

    private fun longPress(id: Int, node: AccessibilityNodeInfo, s: Screen): String {
        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "ok"
        val c = s.centers[id] ?: return "failed"
        return if (svc.tapAt(c[0], c[1], 800)) "ok (gesture)" else "failed"
    }

    private fun typeText(a: JSONObject, node: AccessibilityNodeInfo?): String {
        val target = node ?: svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "no text field (tap a field first or give its id)"
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.optString("text"))
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "failed to set text"
        if (a.optBoolean("submit")) {
            Thread.sleep(300)
            return if (Build.VERSION.SDK_INT >= 30 &&
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            ) "ok, submitted" else "ok, typed (tap the search/enter button to submit)"
        }
        return "ok"
    }

    private fun scroll(a: JSONObject, s: Screen, node: AccessibilityNodeInfo?): String {
        val dir = a.optString("direction", "down").lowercase()
        val fwd = dir == "down" || dir == "right"
        val target = node ?: s.nodes.entries.sortedBy { it.key }.map { it.value }.firstOrNull { it.isScrollable }
        if (target != null) {
            val act = if (fwd) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target.performAction(act)) return "ok"
        }
        val dm = svc.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val ok = when (dir) {
            "down" -> svc.swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10)
            "up" -> svc.swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10)
            "right" -> svc.swipe(w * 8 / 10, h / 2, w * 2 / 10, h / 2)
            else -> svc.swipe(w * 2 / 10, h / 2, w * 8 / 10, h / 2)
        }
        return if (ok) "ok (swipe)" else "failed"
    }
}
