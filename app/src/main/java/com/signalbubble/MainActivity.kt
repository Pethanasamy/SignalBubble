package com.signalbubble

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("s", MODE_PRIVATE)
        val tfs = listOf("1m", "3m", "5m", "15m", "30m", "1h")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val pair = EditText(this).apply { hint = "Pair (Binance symbol)"; setText(p.getString("sym", "BTCUSDT")) }
        val sp = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, tfs)
            setSelection(tfs.indexOf(p.getString("tf", "1m")).coerceAtLeast(0)) }
        val start = Button(this).apply { text = "Start bubble" }
        val stop = Button(this).apply { text = "Stop bubble" }
        col.addView(TextView(this).apply { text = "Signal Bubble"; textSize = 26f; gravity = Gravity.CENTER })
        col.addView(pair); col.addView(sp); col.addView(start); col.addView(stop)
        col.addView(TextView(this).apply { text = "Signal = share of 100+ indicator votes. Not a guaranteed prediction. Test on demo."; setPadding(0, 48, 0, 0) })
        setContentView(col)
        start.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return@setOnClickListener
            }
            p.edit().putString("sym", pair.text.toString().trim().uppercase()).putString("tf", tfs[sp.selectedItemPosition]).apply()
            startService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Bubble started. Open Pocket Option.", Toast.LENGTH_SHORT).show()
        }
        stop.setOnClickListener { stopService(Intent(this, OverlayService::class.java)) }
    }
}
