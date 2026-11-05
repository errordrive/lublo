package com.lublo.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Floating, draggable mic bubble + status text + stop + yes/no. Uses the accessibility overlay window (no extra permission). */
class Overlay(private val svc: AgentService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = svc.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val root = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
    }
    private val status = TextView(svc).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        maxLines = 4
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xCC202124.toInt()) }
        visibility = View.GONE
    }
    private val mic = btn("🎤", 0xFF3D5AFE.toInt())
    private val stop = btn("⏹", 0xFFD32F2F.toInt()).apply { visibility = View.GONE }
    private val yes = btn("✔", 0xFF2E7D32.toInt())
    private val no = btn("✖", 0xFFD32F2F.toInt())
    private val confirmRow = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        addView(no); addView(yes)
    }
    private val row = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(stop); addView(mic)
    }
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.END or Gravity.BOTTOM
        x = dp(8)
        y = dp(120)
    }
    private var shown = false
    private var confirmCb: ((Boolean) -> Unit)? = null

    init {
        status.layoutParams = LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(status)
        root.addView(confirmRow)
        root.addView(row)
        stop.setOnClickListener { svc.stopAll() }
        yes.setOnClickListener { answer(true) }
        no.setOnClickListener { answer(false) }
        mic.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        val dy = e.rawY - sy
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        if (moved) {
                            lp.x -= dx.toInt(); lp.y -= dy.toInt()
                            sx = e.rawX; sy = e.rawY
                            try { wm.updateViewLayout(root, lp) } catch (ex: Exception) { }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) svc.toggleVoice()
                }
                return true
            }
        })
    }

    private fun btn(label: String, color: Int) = TextView(svc).apply {
        text = label
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
    }

    private fun answer(v: Boolean) {
        val cb = confirmCb
        hideConfirm()
        cb?.invoke(v)
    }

    fun show() {
        if (shown) return
        try { wm.addView(root, lp); shown = true } catch (e: Exception) { }
    }

    fun hide() {
        if (!shown) return
        try { wm.removeView(root) } catch (e: Exception) { }
        shown = false
    }

    fun setStatus(s: String) {
        status.text = s
        status.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
    }

    fun setRunning(r: Boolean) { stop.visibility = if (r) View.VISIBLE else View.GONE }

    fun setListening(l: Boolean) {
        (mic.background as GradientDrawable).setColor(if (l) 0xFFFF6D00.toInt() else 0xFF3D5AFE.toInt())
    }

    fun showConfirm(cb: (Boolean) -> Unit) {
        confirmCb = cb
        confirmRow.visibility = View.VISIBLE
    }

    fun hideConfirm() {
        confirmCb = null
        confirmRow.visibility = View.GONE
    }
}
