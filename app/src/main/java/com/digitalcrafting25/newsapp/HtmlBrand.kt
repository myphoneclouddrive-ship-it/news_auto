package com.digitalcrafting25.newsapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ট্যাগ ও নাম/পদবির বার — HTML-এর `drawTag()` ও `nameDraw()`-এর হুবহু স্টাইল।
 * প্রিভিউ (Compose Canvas) ও রেন্ডার (LayerFrameRenderer) দুই জায়গাই এই একই কোড দিয়ে আঁকে।
 * মাপ/ফাঁক সব w (প্রস্থ) ও h (উচ্চতা)-র অনুপাতে, তাই ছোট প্রিভিউতেও একই চেহারা।
 */
object HtmlBrand {
    private const val REF_W = 1280f

    /** (ওপরের বিজ্ঞাপনের নিচ-সীমা tH, নিচের বিজ্ঞাপন/টিকারের ওপর-সীমা) — HTML-এর tH ও (h − bH − kH) */
    fun insets(active: List<Layer>, h: Float): Pair<Float, Float> {
        var top = 0f
        var bot = h
        for (l in active) when (l) {
            is AdLayer -> if (l.isTop) top = max(top, h * l.heightFrac) else bot = min(bot, h - h * l.heightFrac)
            is TickerLayer -> bot = min(bot, h - h * (l.bottomFrac + l.barHeightFrac))
            else -> {}
        }
        return top to bot
    }

    private fun argb(a: Float, c: Int) = Color.argb((a.coerceIn(0f, 1f) * 255).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))

    /** HTML drawTag: কোণায় আঠা-লাগা গ্রেডিয়েন্ট পট্টি + ছায়াসহ বোল্ড লেখা। আঁকা জায়গার বাউন্ড ফেরত দেয় (না আঁকলে null) */
    fun drawTag(cv: Canvas, w: Float, h: Float, l: TagLayer, topInset: Float, bottomBase: Float): RectF? {
        val t = l.text.trim()
        if (t.isEmpty()) return null
        val s = w / REF_W
        val fs = max(10f * s, (w * l.sizeFrac).roundToInt().toFloat())
        val m = (w * 0.015f).roundToInt().toFloat()
        val right = l.corner == Corner.TOP_RIGHT || l.corner == Corner.BOTTOM_RIGHT
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fs
            typeface = Typeface.DEFAULT_BOLD
        }
        val tw = tp.measureText(t)
        val px = fs * 0.7f
        val py = fs * 0.4f
        val bw = ceil(tw + px * 2)
        val bh = ceil(fs + py * 2)
        val x = if (right) w - bw else 0f
        val isTop = l.corner == Corner.TOP_LEFT || l.corner == Corner.TOP_RIGHT
        val y = if (isTop) topInset + m else bottomBase - bh - m

        if (l.bgColor != 0) {
            val c1 = argb(0.6f, l.bgColor)
            val c0 = argb(0f, l.bgColor)
            val shader = if (right) {
                LinearGradient(x, 0f, x + bw, 0f, intArrayOf(c0, c1, c1), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
            } else {
                LinearGradient(x, 0f, x + bw, 0f, intArrayOf(c1, c1, c0), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
            }
            cv.drawRect(x, y, x + bw, y + bh, Paint().apply { this.shader = shader })
        }

        tp.color = l.color
        tp.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        tp.setShadowLayer(fs * 0.15f, 0f, fs * 0.04f, argb(0.55f, Color.BLACK))
        val fm = tp.fontMetrics
        val cy = y + bh / 2f + fs * 0.04f
        cv.drawText(t, if (right) x + bw - px else x + px, cy - (fm.ascent + fm.descent) / 2f, tp)
        return RectF(x, y, x + bw, y + bh)
    }

    /**
     * HTML nameDraw: কালো (৭৮%) পট্টি + পাশে রঙিন দাগ, ওপরে বোল্ড নাম, নিচে হালকা পদবি;
     * fade চালু থাকলে পাশ থেকে স্লাইড হয়ে ঢোকে ও বেরোয় (easeInOutCubic)।
     * ms = টাইমলাইনের সময়; লেয়ার চালু থাকার সময়েই ডাকতে হবে।
     */
    fun drawNameBar(cv: Canvas, w: Float, h: Float, l: NameBarLayer, ms: Long, nameBase: Float): RectF? {
        val a = l.name.trim()
        if (a.isEmpty()) return null
        val b = l.title.trim()
        val du = (l.endMs - l.startMs).coerceAtLeast(1L).toDouble()
        var lt = (ms - l.startMs).toDouble()
        if (l.repeatSec > 0) lt %= max(l.repeatSec * 1000.0, du + 1000.0)
        if (lt < 0.0 || lt > du) return null
        var k = 1.0
        if (l.fade) {
            val an = min(1200.0, du / 2.0)
            k = min(lt / an, (du - lt) / an).coerceIn(0.0, 1.0)
        }
        val e = if (k < 0.5) 4 * k * k * k else 1 - (-2 * k + 2).pow(3.0) / 2
        val s = w / REF_W
        val bh = (h * l.sizeFrac).roundToInt().toFloat()
        val x0 = w * 0.04f
        val f1 = (bh * 0.4f).roundToInt().toFloat()
        val f2 = (bh * 0.26f).roundToInt().toFloat()
        val p1 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = f1; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE }
        val p2 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = f2; typeface = Typeface.DEFAULT; color = Color.rgb(0xDD, 0xDD, 0xDD) }
        var tw = p1.measureText(a)
        if (b.isNotEmpty()) tw = max(tw, p2.measureText(b))
        val pad = bh * 0.28f
        val bar = max(6f * s, bh * 0.08f)
        val bw = min(w * 0.92f - x0, tw + bar + pad * 2)
        val y = nameBase - bh - h * 0.02f - h * l.liftFrac
        val ef = e.toFloat()
        val x = if (l.right) w - x0 - bw + (1 - ef) * (bw + x0) else x0 - (1 - ef) * (bw + x0)
        val alpha = if (l.fade) min(1f, ef * 1.5f) else 1f

        cv.save()
        cv.clipRect(x, y, x + bw, y + bh)
        cv.drawRect(x, y, x + bw, y + bh, Paint().apply { color = argb(0.78f * alpha, Color.BLACK) })
        val barX = if (l.right) x + bw - bar else x
        cv.drawRect(barX, y, barX + bar, y + bh, Paint().apply { color = argb(alpha, l.accent or 0xFF000000.toInt()) })
        val tx = if (l.right) x + pad else x + bar + pad
        p1.alpha = (alpha * 255).roundToInt()
        p2.alpha = (alpha * 255 * (0xDD / 255f)).roundToInt().coerceIn(0, 255)
        val m1 = (p1.fontMetrics.ascent + p1.fontMetrics.descent) / 2f
        cv.drawText(a, tx, y + bh * (if (b.isNotEmpty()) 0.35f else 0.5f) - m1, p1)
        if (b.isNotEmpty()) {
            val m2 = (p2.fontMetrics.ascent + p2.fontMetrics.descent) / 2f
            cv.drawText(b, tx, y + bh * 0.72f - m2, p2)
        }
        cv.restore()
        return RectF(x, y, x + bw, y + bh)
    }
}
