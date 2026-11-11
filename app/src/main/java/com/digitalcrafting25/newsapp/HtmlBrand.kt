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
        var adBottom = 0f
        var tick = 0f
        for (l in active) when (l) {
            is AdLayer -> if (l.isTop) top = max(top, h * l.heightFrac) else adBottom = max(adBottom, h * l.heightFrac)
            is TickerLayer -> tick = max(tick, h * (l.barHeightFrac + l.bottomFrac))
            else -> {}
        }
        // HTML: স্ক্রলিং লেখা নিচের বিজ্ঞাপনের ঠিক ওপরে বসে, তাই দুটোর উচ্চতা যোগ হয় (আগে শুধু বড়টা ধরা হতো)
        return top to (h - adBottom - tick)
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
            typeface = BrandFont.get(700)
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
     * HTML ticker(): নিচের বিজ্ঞাপনের ঠিক ওপরে বসা বার (ব্যাকগ্রাউন্ড ৮৫% ঘন), লেখা মাঝারি (500) ওজন, মাপ = বারের ০.৫২;
     * লেখা + ৮টা ফাঁকা জায়গা বারবার জুড়ে সীমাহীন লুপে ডান থেকে বামে সরে (প্রস্থ × গতি প্রতি সেকেন্ডে)।
     * ms = টাইমলাইনের সময়। আঁকা জায়গা ফেরত দেয় (না আঁকলে null)।
     */
    fun drawTicker(cv: Canvas, w: Float, h: Float, l: TickerLayer, ms: Long, active: List<Layer>): RectF? {
        val t = l.text.replace('\n', ' ').trim()
        if (t.isEmpty()) return null
        var adBottom = 0f
        for (a in active) if (a is AdLayer && !a.isTop) adBottom = max(adBottom, h * a.heightFrac)
        val barH = (h * l.barHeightFrac).roundToInt().toFloat().coerceAtLeast(2f)
        val bottom = h - adBottom - h * l.bottomFrac
        val top = bottom - barH
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = barH * 0.52f
            typeface = BrandFont.get(500)
            color = l.color
        }
        val s = "$t        "
        val pw = max(tp.measureText(s), 1f)
        val off = ((ms - l.startMs).coerceAtLeast(0L) / 1000f * w * l.speed) % pw
        val fm = tp.fontMetrics
        val baseline = top + barH / 2f - (fm.ascent + fm.descent) / 2f
        cv.save()
        cv.clipRect(0f, top, w, bottom)
        cv.drawRect(0f, top, w, bottom, Paint().apply { color = argb(0.85f, l.bgColor or 0xFF000000.toInt()) })
        var x = -off
        while (x < w) {
            cv.drawText(s, x, baseline, tp)
            x += pw
        }
        cv.restore()
        return RectF(0f, top, w, bottom)
    }

    /**
     * HTML drawLogo(): কোণা বাছা থাকলে (corner != null) ফাঁক = প্রস্থের ২%; ওপরের কোণা ওপরের বিজ্ঞাপনের নিচে,
     * নিচের কোণা নিচের বিজ্ঞাপন/স্ক্রলিং লেখার ওপরে। না হলে (টেনে বসানো) x, y কেন্দ্র ধরে।
     */
    fun logoRect(w: Float, h: Float, l: ImageLayer, bmpW: Int, bmpH: Int, topInset: Float, bottomBase: Float): RectF {
        val lw = w * l.widthFrac
        val lh = lw * bmpH / max(bmpW, 1).toFloat()
        val c = l.corner
        if (l.isLogo && c != null) {
            val m = w * 0.02f
            val right = c == Corner.TOP_RIGHT || c == Corner.BOTTOM_RIGHT
            val x = if (right) w - lw - m else m
            val y = if (c == Corner.TOP_LEFT || c == Corner.TOP_RIGHT) topInset + m else bottomBase - lh - m
            return RectF(x, y, x + lw, y + lh)
        }
        val x = w * l.x - lw / 2f
        val y = h * l.y - lh / 2f
        return RectF(x, y, x + lw, y + lh)
    }

    /**
     * HTML nameDraw: কালো (৭৮%) পট্টি + পাশে রঙিন দাগ, ওপরে বোল্ড নাম, নিচে হালকা পদবি;
     * fade চালু থাকলে পাশ থেকে স্লাইড হয়ে ঢোকে ও বেরোয় (easeInOutCubic)।
     * ms = টাইমলাইনের সময়; লেয়ার চালু থাকার সময়েই ডাকতে হবে।
     */
    fun drawNameBar(cv: Canvas, w: Float, h: Float, l: NameBarLayer, ms: Long, nameBase: Float, totalMs: Long = -1L): RectF? {
        val a = l.name.trim()
        if (a.isEmpty()) return null
        val b = l.title.trim()
        val du = (l.endMs - l.startMs).coerceAtLeast(1L).toDouble()
        // HTML: "আবার দেখান" চালু থাকলে প্রথমবার ১০ সেকেন্ডে আসে (লেয়ারের নিজের শুরু নয়)
        var lt = (ms - l.firstShowMs(totalMs)).toDouble()
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
        val p1 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = f1; typeface = BrandFont.get(700); color = Color.WHITE }
        val p2 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = f2; typeface = BrandFont.get(400); color = Color.rgb(0xDD, 0xDD, 0xDD) }
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
