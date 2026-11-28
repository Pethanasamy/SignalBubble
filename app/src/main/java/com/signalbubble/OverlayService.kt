package com.signalbubble

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var popup: TextView? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    private fun lp(x: Int, y: Int, touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    private fun bg(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (bubble != null) return START_STICKY
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = TextView(this).apply { text = "AI"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(36, 36, 36, 36); background = bg(Color.parseColor("#CC1565C0"), 200f) }
        val p = lp(20, 400, true)
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> { val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    p.x = ox + dx; p.y = oy + dy; wm.updateViewLayout(b, p) }
                MotionEvent.ACTION_UP -> if (!moved) analyze(b)
            }
            true
        }
        wm.addView(b, p); bubble = b
        return START_STICKY
    }

    private fun analyze(b: TextView) {
        val prefs = getSharedPreferences("s", MODE_PRIVATE)
        val sym = prefs.getString("sym", "BTCUSDT")!!; val tf = prefs.getString("tf", "1m")!!
        b.text = "…"
        Thread {
            var txt = ""; var col = 0
            try {
                val r = Engine.analyze(sym, tf)
                txt = (if (r.up) "▲ UP " else "▼ DOWN ") + r.pct + "%\n$sym $tf · ${r.ups}↑ ${r.downs}↓ of ${r.total}" + r.note
                col = if (r.up) Color.parseColor("#E61B8F3A") else Color.parseColor("#E6C62828")
            } catch (e: Exception) { txt = "Error: " + e.message.orEmpty().take(120); col = Color.DKGRAY }
            ui.post { b.text = "AI"; show(txt, col) }
        }.start()
    }

    private fun show(t: String, c: Int) {
        popup?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        val v = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 18f; setPadding(48, 32, 48, 32); background = bg(c, 40f) }
        wm.addView(v, lp(60, 250, false)); popup = v
        ui.postDelayed({ try { wm.removeView(v) } catch (_: Exception) {}; if (popup === v) popup = null }, 7000)
    }

    override fun onDestroy() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        popup?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null; super.onDestroy()
    }
}
