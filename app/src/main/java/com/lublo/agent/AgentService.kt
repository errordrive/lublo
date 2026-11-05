package com.lublo.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AgentService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentService? = null
        private val YES = Regex(
            "(?i)\\b(yes|yeah|yep|ok|okay|sure|confirm|proceed|go ahead|haan|han)\\b|হ্যাঁ|হ্যা|জি|ঠিক আছে|হ্যাঁ|हाँ|हां"
        )
        private val STOP = Regex("(?i)^\\s*(stop|cancel|abort)\\s*$|থামো|বন্ধ|रुको")
    }

    val main = Handler(Looper.getMainLooper())
    private lateinit var voice: Voice
    private lateinit var overlay: Overlay
    val agent by lazy { Agent(this) }
    private val taskQueue = TaskQueue(this)
    private val waiters = CopyOnWriteArrayList<CountDownLatch>()
    private val keysDown = HashSet<Int>()
    private var combo = false

    override fun onServiceConnected() {
        instance = this
        voice = Voice(this).also { it.init() }
        overlay = Overlay(this).also { it.show() }
        Bus.post("Service ready. Press both volume keys, or tap the mic bubble.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        agent.cancel()
        taskQueue.shutdown()
        main.post {
            voice.destroy()
            overlay.hide()
        }
        return super.onUnbind(intent)
    }

    /** Volume Up + Volume Down together: start listening, or stop if the agent is running. */
    override fun onKeyEvent(ev: KeyEvent): Boolean {
        val k = ev.keyCode
        if (k != KeyEvent.KEYCODE_VOLUME_UP && k != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (ev.action == KeyEvent.ACTION_DOWN) {
            keysDown.add(k)
            if (keysDown.size == 2 && !combo) {
                combo = true
                toggleVoice()
            }
            return combo
        }
        keysDown.remove(k)
        val was = combo
        if (keysDown.isEmpty()) combo = false
        return was
    }

    // ---------- public controls (main thread) ----------

    fun toggleVoice() {
        main.post { if (agent.running) stopAll() else listenForCommand() }
    }

    fun listenForCommand() {
        main.post {
            voice.stopAll()
            status("Listening…")
            overlay.setListening(true)
            voice.listen { text ->
                overlay.setListening(false)
                when {
                    text.isNullOrBlank() -> say("I didn't catch that.")
                    STOP.containsMatchIn(text) -> stopAll()
                    else -> runTask(text)
                }
            }
        }
    }

    fun runTask(task: String) {
        if (Prefs.apiKey(this).isBlank()) {
            say("Please set your API key in the Lublo app first.")
            return
        }
        main.post { overlay.setRunning(true) }
        status("Task: $task")
        agent.start(task)
    }

    /** Run a task autonomously in the background while the agent stays available. */
    fun runInBackground(task: String) {
        if (Prefs.apiKey(this).isBlank()) {
            taskQueue.enqueue { say("Please set your API key in the Lublo app first.") }
            return
        }
        taskQueue.enqueue {
            status("Background task: $task")
            agent.start(task)
        }
        main.post { status("Working in the background...") }
    }

    fun stopAll() {
        agent.cancel()
        main.post {
            voice.stopAll()
            overlay.setRunning(false)
            overlay.setListening(false)
            overlay.hideConfirm()
            status("Stopped.")
        }
    }

    fun onAgentDone() {
        main.post { overlay.setRunning(false) }
        main.postDelayed({ if (!agent.running) overlay.setStatus("") }, 7000)
    }

    fun releaseWaiters() {
        waiters.forEach { it.countDown() }
        waiters.clear()
        main.post { voice.stopAll(); overlay.hideConfirm() }
    }

    // ---------- feedback ----------

    fun status(s: String) {
        Bus.post(s)
        main.post { overlay.setStatus(s) }
    }

    fun say(spoken: String, shown: String = spoken) {
        status(shown)
        main.post { voice.speak(spoken) }
    }

    // ---------- blocking helpers for the agent thread ----------

    fun ask(question: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        waiters.add(latch)
        main.post {
            status(question)
            voice.speak(question) {
                voice.listen { r -> result = r; latch.countDown() }
            }
        }
        latch.await(60, TimeUnit.SECONDS)
        waiters.remove(latch)
        return result
    }

    /** Voice yes/no, or the on-screen buttons. Defaults to NO on timeout/ambiguity. */
    fun confirm(prompt: String): Boolean {
        val latch = CountDownLatch(1)
        val answer = AtomicBoolean(false)
        waiters.add(latch)
        main.post {
            status(prompt)
            overlay.showConfirm { yes ->
                answer.set(yes)
                latch.countDown()
                voice.stopAll()
            }
            voice.speak(prompt) {
                voice.listen { r ->
                    if (r != null) {
                        answer.set(YES.containsMatchIn(r) && !STOP.containsMatchIn(r))
                        latch.countDown()
                    }
                }
            }
        }
        latch.await(60, TimeUnit.SECONDS)
        waiters.remove(latch)
        main.post { overlay.hideConfirm() }
        return answer.get()
    }

    // ---------- actions ----------

    private fun gesture(p: Path, dur: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur.coerceAtLeast(1))).build()
        val accepted = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, main)
        if (!accepted) return false
        latch.await(4, TimeUnit.SECONDS)
        return ok
    }

    fun tapAt(x: Int, y: Int, hold: Long = 60): Boolean =
        gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, hold)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Long = 350): Boolean =
        gesture(Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }, dur)

    fun openApp(name: String): String {
        if (name.isBlank()) return "no app name"
        val pm = packageManager
        val list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val q = name.trim().lowercase()
        val match = list.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: list.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "no installed app matches '$name'"
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return "cannot launch"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(i)
            "ok, opened ${match.loadLabel(pm)}"
        } catch (e: Exception) {
            "failed to open: ${e.message}"
        }
    }
}
