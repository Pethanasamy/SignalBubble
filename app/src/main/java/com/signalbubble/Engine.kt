package com.signalbubble

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.sqrt

data class Candle(val o: Double, val h: Double, val l: Double, val c: Double)
data class Result(val up: Boolean, val pct: Int, val ups: Int, val downs: Int, val total: Int, val note: String)

object Engine {
    private fun get(u: String): String {
        val cn = URL(u).openConnection() as HttpURLConnection
        cn.connectTimeout = 8000; cn.readTimeout = 8000; cn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (cn.responseCode != 200) throw Exception("HTTP " + cn.responseCode)
        return cn.inputStream.bufferedReader().readText()
    }

    // XXXUSDT -> Binance (4 servers), anything else (AAPL, TSLA...) -> Yahoo Finance
    fun fetch(sym: String, tf: String): Pair<List<Candle>, Long> {
        if (sym.endsWith("USDT")) {
            val q = "/api/v3/klines?symbol=$sym&interval=$tf&limit=300"
            var err = ""
            for (h in listOf("https://data-api.binance.vision", "https://api.binance.com", "https://api1.binance.com", "https://api.binance.us")) {
                try {
                    val a = JSONArray(get(h + q))
                    val c = (0 until a.length()).map { val k = a.getJSONArray(it)
                        Candle(k.getString(1).toDouble(), k.getString(2).toDouble(), k.getString(3).toDouble(), k.getString(4).toDouble()) }
                    return Pair(c, a.getJSONArray(a.length() - 1).getLong(6))
                } catch (e: Exception) { err = e.message.orEmpty() }
            }
            throw Exception("Binance failed: $err")
        }
        val range = when (tf) { "1m" -> "1d"; "5m" -> "5d"; else -> "1mo" }
        val j = JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/$sym?interval=$tf&range=$range"))
        val r = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = r.getJSONArray("timestamp"); val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.getJSONArray("open"); val h = q.getJSONArray("high"); val l = q.getJSONArray("low"); val c = q.getJSONArray("close")
        val out = ArrayList<Candle>()
        for (i in 0 until c.length()) if (!c.isNull(i) && !o.isNull(i) && !h.isNull(i) && !l.isNull(i))
            out.add(Candle(o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i)))
        return Pair(out, ts.getLong(ts.length() - 1) * 1000)
    }

    private fun ema(x: List<Double>, p: Int): List<Double> {
        val k = 2.0 / (p + 1); var e = x[0]
        return x.map { e = it * k + e * (1 - k); e }
    }
    private fun sg(d: Double) = if (d > 0) 1 else if (d < 0) -1 else 0

    fun votes(c: List<Candle>): List<Int> {
        val cl = c.map { it.c }; val h = c.map { it.h }; val l = c.map { it.l }
        val last = cl.last(); val v = ArrayList<Int>()
        // RSI 3..30 (28)
        for (p in 3..30) { val ch = cl.takeLast(p + 1).zipWithNext { a, b -> b - a }
            val g = ch.filter { it > 0 }.sum(); val ls = -ch.filter { it < 0 }.sum()
            val r = if (g + ls == 0.0) 50.0 else 100 * g / (g + ls)
            v += if (r < 30) 1 else if (r > 70) -1 else 0 }
        // EMA position, EMA slope, SMA position (30)
        for (p in listOf(5, 8, 10, 13, 20, 21, 30, 50, 100, 200)) {
            val e = ema(cl, p); v += sg(last - e.last()); v += sg(e.last() - e[e.size - 2]); v += sg(last - cl.takeLast(p).average()) }
        // EMA crosses (6)
        for ((f, s) in listOf(5 to 10, 8 to 21, 9 to 26, 12 to 26, 20 to 50, 50 to 200)) v += sg(ema(cl, f).last() - ema(cl, s).last())
        // MACD variants (3)
        for ((f, s, g) in listOf(Triple(6, 13, 5), Triple(12, 26, 9), Triple(5, 35, 5))) {
            val m = ema(cl, f).zip(ema(cl, s)) { a, b -> a - b }; v += sg(m.last() - ema(m, g).last()) }
        // Stochastic 5..21 step 2 (9)
        for (p in 5..21 step 2) { val hh = h.takeLast(p).max(); val ll = l.takeLast(p).min()
            val k = 100 * (last - ll) / (hh - ll + 1e-12); v += if (k < 20) 1 else if (k > 80) -1 else 0 }
        // Bollinger (3)
        for (p in listOf(10, 20, 30)) { val w = cl.takeLast(p); val m = w.average()
            val sd = sqrt(w.map { (it - m) * (it - m) }.average())
            v += if (last < m - 2 * sd) 1 else if (last > m + 2 * sd) -1 else sg(last - m) }
        // Momentum 2..20 (19)
        for (p in 2..20) v += sg(last - cl[cl.size - 1 - p])
        // CCI (3)
        val tp = c.map { (it.h + it.l + it.c) / 3 }
        for (p in listOf(14, 20, 30)) { val w = tp.takeLast(p); val m = w.average(); val md = w.map { abs(it - m) }.average()
            val x = (tp.last() - m) / (0.015 * md + 1e-12); v += if (x < -100) 1 else if (x > 100) -1 else 0 }
        // Williams %R (3)
        for (p in listOf(7, 14, 21)) { val hh = h.takeLast(p).max(); val ll = l.takeLast(p).min()
            val w = -100 * (hh - last) / (hh - ll + 1e-12); v += if (w < -80) 1 else if (w > -20) -1 else 0 }
        // Linear regression slope (5)
        for (p in listOf(5, 10, 20, 30, 50)) { val y = cl.takeLast(p); val xm = (p - 1) / 2.0; val ym = y.average()
            var n = 0.0; var d = 0.0; y.forEachIndexed { i, yy -> n += (i - xm) * (yy - ym); d += (i - xm) * (i - xm) }; v += sg(n / d) }
        // Last 3 candle directions (3)
        for (n in 1..3) v += sg(last - c[c.size - n].o)
        // Donchian midline (3)
        for (p in listOf(10, 20, 50)) v += sg(last - (h.takeLast(p).max() + l.takeLast(p).min()) / 2)
        return v
    }

    fun analyze(symbol: String, interval: String): Result {
        val (c, last) = fetch(symbol, interval)
        if (c.size < 60) throw Exception("Only ${c.size} candles. Market closed or wrong symbol?")
        val v = votes(c)
        val u = v.count { it > 0 }; val d = v.count { it < 0 }
        val age = (System.currentTimeMillis() - last) / 60000
        val note = if (age > 20) "\nLast candle ${age}m old - market closed?" else ""
        return Result(u >= d, (100.0 * maxOf(u, d) / maxOf(1, u + d)).toInt(), u, d, v.size, note)
    }
}
