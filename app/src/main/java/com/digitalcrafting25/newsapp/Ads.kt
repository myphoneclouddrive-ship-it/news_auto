package com.digitalcrafting25.newsapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Base64
import kotlin.math.max
import kotlin.math.min

const val AD_W = 1080
const val AD_H = 120
const val AD_MAX = 10

/** ওয়েবের cfg.ads[i]: mode = "upload" (img = JPEG data URL) বা "build" (লেখা + রং + ঐচ্ছিক লোগো) */
data class Ad(
    val id: String,
    val on: Boolean = false,
    val mode: String = "upload",
    val img: String = "",
    val logo: String = "",
    val t1: String = "",
    val t2: String = "",
    val t3: String = "",
    val bg1: String = "#c2185b",
    val bg2: String = "#8e0e4b",
    val c1: String = "#ffffff",
    val c2: String = "#ffd400",
) {
    val hasContent get() = img.isNotBlank() || t1.isNotBlank() || t2.isNotBlank() || t3.isNotBlank()
    /** কার্ডে বসানোর মতো তৈরি কিনা */
    val ok get() = if (mode == "build") (t1.isNotBlank() || t2.isNotBlank() || t3.isNotBlank()) else img.isNotBlank()
}

fun newAdId(): String =
    java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 46656).toLong(), 36).padStart(3, '0')

/** শেষ ব্যবহৃত বিজ্ঞাপনের পরেরটা (না থাকলে প্রথমটা) — ওয়েবের adNextAd */
fun nextAd(ads: List<Ad>, last: String): Ad? {
    val l = ads.filter { it.ok }
    if (l.isEmpty()) return null
    val i = l.indexOfFirst { it.id == last }
    return l[(i + 1) % l.size]
}

/** ব্যানার আঁকা (ওয়েবের drawAd-এর হুবহু হিসাব) — পরে ফোটোকার্ডেও এটাই কাজে লাগবে */
object AdRender {
    fun decode(url: String): Bitmap? {
        if (url.isBlank()) return null
        return runCatching {
            val b = Base64.decode(url.substringAfter("base64,"), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        }.getOrNull()
    }

    private fun col(s: String, dflt: Int): Int = runCatching { Color.parseColor(s.trim()) }.getOrDefault(dflt)

    /** w×h মাপে আঁকে (ডিফল্ট ১০৮০×১২০). কিছু আঁকার না থাকলে হালকা ধূসর ব্যানার */
    fun render(a: Ad, w: Int = AD_W, h: Int = AD_H): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sx = w / AD_W.toFloat(); val sy = h / AD_H.toFloat()
        c.scale(sx, sy)
        c.drawColor(Color.WHITE)
        if (a.mode != "build") {
            decode(a.img)?.let { c.drawBitmap(it, null, android.graphics.RectF(0f, 0f, AD_W.toFloat(), AD_H.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
            return bmp
        }
        val fw = AD_W.toFloat(); val fh = AD_H.toFloat()
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, fw, 0f, col(a.bg1, 0xFFC2185B.toInt()), col(a.bg2, 0xFF8E0E4B.toInt()), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, fw, fh, bg)
        var right = fw
        decode(a.logo)?.let { lg ->   // ডানে সাদা বক্সে লোগো
            val bw = 200f; right = fw - bw
            c.drawRect(right, 0f, fw, fh, Paint().apply { color = Color.WHITE })
            val k = min((bw - 30f) / lg.width, (fh - 24f) / lg.height)
            val dw = lg.width * k; val dh = lg.height * k
            c.drawBitmap(lg, null, android.graphics.RectF(right + (bw - dw) / 2, (fh - dh) / 2, right + (bw - dw) / 2 + dw, (fh - dh) / 2 + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        class Part(val lines: List<String>, val color: Int, val fs: Float)
        val parts = listOf(
            Triple(a.t1, col(a.c1, Color.WHITE), 48f),
            Triple(a.t2, col(a.c2, 0xFFFFD400.toInt()), 66f),
            Triple(a.t3, col(a.c1, Color.WHITE), 34f),
        ).filter { it.first.isNotBlank() }.map { Part(it.first.split("|").map { s -> s.trim() }, it.second, it.third) }
        if (parts.isEmpty()) return bmp
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT }
        val gap = 26f; val avail = right - 40f
        fun measure(k: Float): List<Float> = parts.map { pt ->
            p.textSize = pt.fs * k
            pt.lines.maxOf { p.measureText(it) }
        }
        var ws = measure(1f)
        val total = ws.sum() + gap * (parts.size - 1)
        val k = min(1f, avail / total)
        if (k < 1f) ws = measure(k)
        var x = 20f + (avail - (ws.sum() + gap * (parts.size - 1))) / 2
        parts.forEachIndexed { i, pt ->
            val fs = pt.fs * k; val lh = fs * 1.15f
            val top = fh / 2 - (pt.lines.size * lh) / 2
            p.textSize = fs; p.color = pt.color
            val fm = p.fontMetrics
            pt.lines.forEachIndexed { j, l ->
                val mid = top + lh * (j + 0.5f) + 2f
                c.drawText(l, x, mid - (fm.ascent + fm.descent) / 2, p)
            }
            x += ws[i] + gap
        }
        return bmp
    }
}
