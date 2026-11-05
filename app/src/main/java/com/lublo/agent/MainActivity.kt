package com.lublo.agent

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Scroller
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : android.app.Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var scrollView: CustomScrollView? = null
    private var messages: LinearLayout? = null
    private lateinit var inputArea: View
    private lateinit var taskInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var status: TextView
    private var isListening = false

    private val langs = arrayOf("en-US", "bn-BD", "hi-IN", "en-GB", "ar-SA")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.drawable.screen_background_dark)
        setContentView(buildUI())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun buildUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColorRes(R.color.background))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        root.addView(buildHeader())

        // Chat messages area
        scrollView = CustomScrollView(this).apply {
            overScrollMode = OVER_SCROLL_NEVER
            addView(buildMessages())
        }
        messages = scrollView?.getChildAt(0) as? LinearLayout
        root.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Status / agent state
        status = TextView(this).apply {
            text = "○ Accessibility service: OFF"
            setTextColor(getColorRes(R.color.textSecondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        // Input area
        inputArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColorRes(R.color.surface))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(8)
        }

        taskInput = EditText(this).apply {
            hintText = "Ask Lublo anything... (e.g. 'Open YouTube and search Lo-fi music')"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(getColorRes(R.color.textPrimary))
            setHintTextColor(getColorRes(R.color.textMuted))
            setBackgroundColor(getColorRes(0xFF252530))
            setPadding(dp(16), dp(12), dp(12), dp(12))
            setSingleLine(false)
            maxLines = 4
            setEms(30)
            setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        }

        micBtn = buildCircleBtn("🎤", 0xFF6C63FF.toInt()).apply {
            setOnClickListener { speak() }
        }

        sendBtn = buildCircleBtn("➤", 0xFF00D2FF.toInt()).apply {
            setOnClickListener { runTyped() }
        }

        inputArea.addView(taskInput, LinearLayout.LayoutParams(0, dp(50), 1f))
        inputArea.addView(micBtn, LinearLayout.LayoutParams(dp(50), dp(50)))
        inputArea.addView(sendBtn, LinearLayout.LayoutParams(dp(50), dp(50)))
        root.addView(inputArea, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))

        refresh()
        return root
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColorRes(R.color.surface))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // Animated logo (concentric circles = Jervis-like pulse)
        val logoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val logoOuter = TextView(this).apply {
            text = "L"
            textSize = 34f
            setTextColor(getColorRes(R.color.primaryLight))
            setPadding(dp(0), dp(0), dp(8), dp(0))
        }
        val logoInner = TextView(this).apply {
            text = "L"
            textSize = 24f
            setTextColor(getColorRes(R.color.accent))
            setPadding(dp(0), dp(0), dp(0), dp(0))
        }
        logoContainer.addView(logoOuter)
        logoContainer.addView(logoInner)
        animateLogo(logoContainer)

        val title = TextView(this).apply {
            text = "Lublo"
            textSize = 26f
            setTextColor(getColorRes(R.color.textPrimary))
            setPadding(dp(12), dp(0), dp(0), dp(0))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        header.addView(logoContainer)
        header.addView(title)

        return header
    }

    private fun animateLogo(container: LinearLayout) {
        val anim = ObjectAnimator.ofFloat(container, View.SCALE_X, 1f, 1.15f, 1f)
        anim.duration = 2000
        anim.repeatCount = ObjectAnimator.INFINITE
        anim.repeatMode = ObjectAnimator.REVERSE
        anim.start()
    }

    private fun buildMessages(): View {
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        // Welcome message
        addView(createBubble("Hello! I'm Lublo. \uD83D\uDC4B", false))
        addView(createBubble("I can control your phone, open apps, search the web, make calls, and help with daily tasks. Try: 'Open YouTube and search Lo-fi beats' or 'What's the weather?'", false))
        return messages!!
    }

    private fun createBubble(text: String, isUser: Boolean): View {
        val bubble = TextView(this).apply {
            text = text
            textSize = 14.5f
            setTextColor(if (isUser) getColorRes(R.color.white) else getColorRes(R.color.textPrimary))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setMaxWidth(dp(300))
            background = if (isUser)
                getResources().getDrawable(R.drawable.bubble_user, theme)
            else
                getResources().getDrawable(R.drawable.bubble_assistant, theme)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
                setMargins(dp(0), dp(8), if (isUser) dp(8) else dp(0), dp(0))
            }
        }
        return bubble
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun getColorRes(id: Int): Int {
        return resources.getColor(id, theme)
    }

    private fun buildCircleBtn(label: String, color: Int): TextView {
        return TextView(this).apply {
            text = label
            textSize = 22f
            setTextColor(getColorRes(R.color.white))
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.rounded_button, theme)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            elevation = dp(4)
        }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        main.post {
            val bubble = createBubble(text, isUser)
            messages?.addView(bubble)
            scrollView?.smoothScrollTo(0, scrollView?.childCount?.let { it * dp(60) } ?: 0)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        Bus.listener = {
            status.text = it
            if (!it.startsWith("Task:")) {
                addMessage(it, false)
            }
        }
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    private fun refresh() {
        status.text = if (AgentService.instance != null) {
            "● Accessibility service: ON"
        } else {
            "○ Accessibility service: OFF"
        }
    }

    private fun save() {
        Prefs.save(this, Prefs.baseUrl(this), Prefs.model(this),
            Prefs.apiKey(this), Prefs.lang(this), Prefs.maxSteps(this))
    }

    private fun runTyped() {
        val t = taskInput.text.toString().trim()
        if (t.isEmpty()) return
        save()
        addMessage(t, true)
        taskInput.text?.clear()
        val s = AgentService.instance
        if (s == null) {
            addMessage("Enable the accessibility service first (tap the status below to open settings).", false)
        } else {
            s.runTask(t)
        }
    }

    private fun speak() {
        save()
        val s = AgentService.instance
        if (s == null) {
            addMessage("Enable the accessibility service first.", false)
        } else {
            s.listenForCommand()
        }
    }

}

class CustomScrollView @kotlin.jvm.JvmOverloads constructor(
    ctx: android.content.Context,
    private val maxScroll: Int = 10000
) : android.widget.ScrollView(ctx) {
    private var scroller = Scroller(ctx)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(0, scroller.currY)
            post { computeScroll() }
        }
    }

    fun smoothScrollTo(x: Int, y: Int) {
        scroller.startScroll(0, scroller.currY, 0, y.coerceAtMost(maxScroll) - scroller.currY, 500)
        invalidate()
    }
}
