package com.lublo.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Enhanced floating AI assistant bubble (Jervis-like).
 * - Animated pulse when thinking/listening
 * - Draggable conversation mini-panel
 * - Confirm yes/no controls
 */
class Overlay(private val svc: AgentService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = svc.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val root = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        setPadding(dp(8), 0, dp(8), 0)
    }

    // Thinking/conversation mini panel
    private val panel = TextView(svc).apply {
        text = ""
        setTextColor(Color.WHITE)
        textSize = 13f
        maxLines = 6
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xFF1E1E26.toInt())
        }
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(12), 0, 0)
        }
    }

    // The AI bubble (Jervis-like pulse)
    private val bubble = TextView(svc).apply {
        text = "\uD83D\uDD35"
        textSize = 30f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF6C63FF.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }
        elevation = dp(6).toFloat()
        setOnClickListener { svc.toggleVoice() }
        setOnTouchListener(animateClick(this))
    }

    private val thinking = TextView(svc).apply {
        text = "\uD83D\uDFA2"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#00D2FF"))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x8000D2FF.toInt())
        }
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            setMargins(dp(6), dp(6), dp(6), dp(8))
        }
    }

    private val stop = btn("⏹", 0xFFD32F2F.toInt()).apply { visibility = View.GONE }

    private val mic = btn("🎤", 0xFF3D5AFE.toInt())

    private val yes = btn("✔", 0xFF2E7D32.toInt())
    private val no = btn("✖", 0xFFD32F2F.toInt())
    private val confirmRow = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        addView(no); addView(yes)
    }

    private val row = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(stop); addView(thinking); addView(bubble); addView(mic)
    }

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.END or Gravity.BOTTOM
        x = dp(12)
        y = dp(100)
    }

    private var shown = false
    private var confirmCb: ((Boolean) -> Unit)? = null
    private var thinkingAnim: Animation? = null

    init {
        root.addView(panel)
        root.addView(confirmRow)
        root.addView(row)

        stop.setOnClickListener { svc.stopAll() }
        yes.setOnClickListener { answer(true) }
        no.setOnClickListener { answer(false) }

        thinkingAnim = AnimationUtils.loadAnimation(svc, R.anim.pulse)
        thinkingAnim?.repeatCount = Animation.INFINITE

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        val dy = e.rawY - sy
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        if (moved) {
                            lp.x -= dx.toInt(); lp.y -= dy.toInt()
                            sx = e.rawX; sy = e.rawY
                            try { wm.updateViewLayout(root, lp) } catch (ex: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) svc.toggleVoice()
                }
                return true
            }
        })
    }

    private fun animateClick(view: View): View.OnTouchListener {
        return View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    (v.background as GradientDrawable).setColor(0xFF4B45C9.toInt())
                    v.scaleX = 0.9f; v.scaleY = 0.9f
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val default = if (v == bubble) 0xFF6C63FF.toInt() else 0xFF3D5AFE.toInt()
                    (v.background as GradientDrawable).setColor(default)
                    v.scaleX = 1f; v.scaleY = 1f
                    false
                }
                else -> false
            }
        }
    }

    private fun btn(label: String, color: Int) = TextView(svc).apply {
        text = label
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }

    private fun answer(v: Boolean) {
        val cb = confirmCb
        hideConfirm()
        cb?.invoke(v)
    }

    fun show() {
        if (shown) return
        try { wm.addView(root, lp); shown = true } catch (e: Exception) {}
    }

    fun hide() {
        if (!shown) return
        try { wm.removeView(root); thinking.clearAnimation(); shown = false } catch (e: Exception) {}
    }

    fun setStatus(s: String) {
        panel.text = s
        panel.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
    }

    fun setRunning(r: Boolean) { stop.visibility = if (r) View.VISIBLE else View.GONE }

    fun setListening(l: Boolean) {
        thinking.visibility = if (l) View.VISIBLE else View.GONE
        if (l) thinking.startAnimation(thinkingAnim)
        (bubble.background as GradientDrawable).setColor(if (l) 0xFFFF6D00.toInt() else 0xFF6C63FF.toInt())
    }

    fun showConfirm(cb: (Boolean) -> Unit) {
        confirmCb = cb
        confirmRow.visibility = View.VISIBLE
    }

    fun hideConfirm() {
        confirmCb = null
        confirmRow.visibility = View.GONE
        thinking.visibility = View.GONE
        thinking.clearAnimation()
        (bubble.background as GradientDrawable).setColor(0xFF6C63FF.toInt())
    }
}
