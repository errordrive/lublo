package com.lublo.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var a11y: TextView
    private lateinit var log: TextView
    private lateinit var base: EditText
    private lateinit var model: EditText
    private lateinit var key: EditText
    private lateinit var steps: EditText
    private lateinit var lang: Spinner
    private lateinit var task: EditText
    private val langs = arrayOf("en-US", "bn-BD", "hi-IN", "en-GB", "ar-SA")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        fun tv(t: String, size: Float = 14f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, dp(12), 0, dp(2))
        }
        fun et(hintText: String, v: String, pw: Boolean = false) = EditText(this).apply {
            setHint(hintText); setText(v); setSingleLine(true)
            if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

        col.addView(tv("Lublo", 26f))
        a11y = tv("")
        col.addView(a11y)
        col.addView(btn("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btn("2. Allow microphone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })

        col.addView(tv("Task"))
        task = et("e.g. Turn on Wi-Fi / Wi-Fi অন করো", "")
        col.addView(task)
        col.addView(btn("▶ Run task") { runTyped() })
        col.addView(btn("🎤 Speak a task") { speak() })
        log = tv("")
        col.addView(log)

        col.addView(tv("Settings", 18f))
        col.addView(tv("API base URL"))
        base = et("https://…/v1", Prefs.baseUrl(this)); col.addView(base)
        col.addView(tv("Model"))
        model = et("model id", Prefs.model(this)); col.addView(model)
        col.addView(tv("API key"))
        key = et("sk-…", Prefs.apiKey(this), pw = true); col.addView(key)
        col.addView(tv("Voice language"))
        lang = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs)
            setSelection(langs.indexOf(Prefs.lang(this@MainActivity)).coerceAtLeast(0))
        }
        col.addView(lang)
        col.addView(tv("Max steps per task"))
        steps = et("25", Prefs.maxSteps(this).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        col.addView(steps)
        col.addView(btn("Save settings") { save(); toast("Saved") })
        col.addView(tv("Tip: press Volume Up + Volume Down together to talk to the agent from any app. Press again to stop it."))

        setContentView(ScrollView(this).apply { addView(col) })

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        log.text = Bus.last
        Bus.listener = { log.text = it }
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    private fun refresh() {
        a11y.text = if (AgentService.instance != null) "● Accessibility service: ON"
        else "○ Accessibility service: OFF"
    }

    private fun save() {
        Prefs.save(
            this, base.text.toString(), model.text.toString(), key.text.toString(),
            langs[lang.selectedItemPosition], steps.text.toString().toIntOrNull() ?: 25
        )
    }

    private fun runTyped() {
        save()
        val t = task.text.toString().trim()
        val s = AgentService.instance
        if (s == null) toast("Enable the accessibility service first")
        else if (t.isNotEmpty()) s.runTask(t)
    }

    private fun speak() {
        save()
        val s = AgentService.instance
        if (s == null) toast("Enable the accessibility service first") else s.listenForCommand()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
