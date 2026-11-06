package com.digitalcrafting25.newsapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** একটা কার্ড আঁকতে যা যা লাগে (ওয়েবের renderCard-এর data) */
class CardData(
    val photo: Bitmap?,
    val head: String,
    val cat: String,
    val date: String,
    val url: String,
    val tag: String,
    val cta: String,
    val siteName: String,
    val logo: Bitmap?,
    val icon: Bitmap?,
    val iconRound: Boolean,
    val fx: Float = 0.5f,   // ছবি টেনে সরানোর অবস্থান (০–১), মাঝখানে = ০.৫
    val fy: Float = 0.5f,
    val name: String = "",   // বক্তব্য কার্ডে বক্তার নাম
    val role: String = "",   // বক্তার পদবি
)

/** ধাপ ১: ওয়েবের ৫টি ডিফল্ট ফোটোকার্ড টেমপ্লেট (id to নাম) */
val CARD_TPLS = listOf(
    "dark" to "ডার্ক গ্রেডিয়েন্ট",
    "white" to "সাদা প্যানেল",
    "red" to "রেড নিউজ",
    "blue" to "ব্লু নিউজ",
    "top" to "উপরে শিরোনাম",
)

/** ধাপ ৩: বক্তব্য কার্ড (১০৮০×১০৮০) */
val QUOTE_TPLS = listOf("quote1" to "বক্তব্য কার্ড (লাল)")

/** mode: "photo" | "quote" */
fun cardTpls(mode: String) = if (mode == "quote") QUOTE_TPLS else CARD_TPLS

/** ওয়েবের TPL.draw-এর হুবহু হিসাব (১০৮০×১৩৫০). scale দিলে ছোট থাম্বনেইল হয় */
object CardRender {
    const val W = 1080
    const val H = 1350

    /** ফুটারের (নিচের পাতলা বার) শুরুর y — বিজ্ঞাপন এর ঠিক ওপরে বসে; ফুটার না থাকলে কার্ডের একদম নিচে */
    fun hOf(id: String) = if (id == "quote1") 1080 else H
    fun aspect(id: String) = W.toFloat() / hOf(id)

    private val FOOT = mapOf("white" to 1270, "red" to 1300, "blue" to 1290)

    /** ছবির জায়গার মাপ (টেনে সরানোর হিসাবের জন্য) */
    fun photoBox(id: String): Pair<Float, Float> = when (id) {
        "quote1" -> 500f to 1080f
        "white" -> 1080f to 860f
        "red" -> 1080f to 900f
        "blue" -> 1080f to 650f
        "top" -> 1080f to 910f
        else -> 1080f to 1350f
    }

    /** ad দিলে ওয়েবের renderCard-এর নিয়মে বসে: কার্ডের মাপ একই থাকে, ছবি ক্রপ হয়ে ছোট হয় (চ্যাপ্টা হয় না), ফুটার নিজের জায়গায় */
    fun render(id: String, d: CardData, scale: Float = 1f, ad: Bitmap? = null): Bitmap {
        val H = hOf(id)   // কার্ডের উচ্চতা (বক্তব্য কার্ড ১০৮০, বাকিগুলো ১৩৫০)
        val bmp = Bitmap.createBitmap((W * scale).roundToInt(), (H * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        c.drawColor(Color.WHITE)
        if (ad == null) { CardPainter(c, d).draw(id); return bmp }

        val adH = AD_H
        val sp0 = FOOT[id] ?: H
        val topH = sp0 - adH
        val wf = W.toFloat()

        // ১) টেমপ্লেট আধা-মাপে স্বচ্ছ ক্যানভাসে এঁকে (ছবি বাদ) দেখা: কোন একটানা পট্টি একদম ফাঁকা
        val an = 0.5f
        val pb = Bitmap.createBitmap((W * an).toInt(), (sp0 * an).toInt(), Bitmap.Config.ARGB_8888)
        val pc = Canvas(pb); pc.scale(an, an)
        val rec = CardPainter(pc, d).also { it.skipPhoto = true; it.draw(id) }
        val rects = rec.photoRects.toList()
        var y0 = -1
        if (rects.isNotEmpty()) {
            val w = pb.width; val h = pb.height
            val px = IntArray(w * h); pb.getPixels(px, 0, w, 0, 0, w, h)
            val clear = BooleanArray(h) { r ->
                var ok = true; val o = r * w
                for (x in 0 until w) if ((px[o + x] ushr 24) > 2) { ok = false; break }
                ok
            }
            val need = (adH * an).toInt()
            var y = h - need
            while (y >= 0 && y0 < 0) {
                var ok = true
                for (k in 0 until need) if (!clear[y + k]) { ok = false; break }
                if (ok) y0 = (y / an).toInt()
                y--
            }
        }
        pb.recycle()

        val p = CardPainter(c, d)
        if (y0 >= 0) {
            fun mapY(v: Float) = if (v <= y0) v else if (v >= y0 + adH) v - adH else y0.toFloat()
            rects.forEach { r -> val a = mapY(r.top); val b = mapY(r.bottom); if (b > a) p.coverPhoto(r.left, a, r.width(), b - a) }
            p.skipPhoto = true
            c.save(); c.clipRect(0f, 0f, wf, y0.toFloat()); p.draw(id); c.restore()
            c.save(); c.clipRect(0f, y0.toFloat(), wf, (sp0 - adH).toFloat()); c.translate(0f, -adH.toFloat()); p.draw(id); c.restore()
            p.skipPhoto = false
        } else {   // ফাঁকা পট্টি না পেলে পুরনো নিয়মে ওপরের অংশ সামান্য চাপা
            c.save(); c.clipRect(0f, 0f, wf, topH.toFloat()); c.scale(1f, topH / sp0.toFloat()); p.draw(id); c.restore()
        }
        c.drawBitmap(ad, null, RectF(0f, topH.toFloat(), wf, sp0.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        if (sp0 < H) { c.save(); c.clipRect(0f, sp0.toFloat(), wf, H.toFloat()); p.draw(id); c.restore() }   // ফুটার নিজের জায়গাতেই
        return bmp
    }
}

private fun hex(s: String): Int = runCatching { Color.parseColor(s) }.getOrDefault(Color.WHITE)
private fun hexA(s: String, a: Float): Int {
    val v = hex(s)
    return Color.argb((a * 255).roundToInt().coerceIn(0, 255), Color.red(v), Color.green(v), Color.blue(v))
}

private class CardPainter(val c: Canvas, val d: CardData) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    var skipPhoto = false
    private fun face(bold: Boolean) { p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
    val photoRects = ArrayList<RectF>()
    fun coverPhoto(x: Float, y: Float, w: Float, h: Float) { val sv = skipPhoto; skipPhoto = false; cover(d.photo, x, y, w, h); skipPhoto = sv }
    fun draw(id: String) {
        when (id) {
            "white" -> white()
            "red" -> red()
            "blue" -> blue()
            "top" -> top()
            "quote1" -> quote1()
            else -> dark()
        }
    }
    private val catDate: String get() = if (d.cat.isBlank()) d.date else d.cat + " | " + d.date

    // ---------- ছোট হেল্পার (ওয়েবের txt / chip / drawCover / wrapLines / headline) ----------
    private fun rect(x: Float, y: Float, w: Float, h: Float, col: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = col
        c.drawRect(x, y, x + w, y + h, p)
    }

    private fun vGrad(y0: Float, y1: Float, colors: IntArray, pos: FloatArray?, x0: Float = 0f) {
        p.style = Paint.Style.FILL; p.color = Color.BLACK
        p.shader = LinearGradient(0f, y0, 0f, y1, colors, pos, Shader.TileMode.CLAMP)
        c.drawRect(x0, y0, CardRender.W.toFloat(), y1, p)
        p.shader = null
    }

    private fun cover(im: Bitmap?, x: Float, y: Float, w: Float, h: Float) {
        if (im == null) {   // ছবি না থাকলে ডিফল্ট গ্রেডিয়েন্ট (ওয়েবের samplePhoto)
            p.style = Paint.Style.FILL; p.color = Color.BLACK
            p.shader = LinearGradient(x, y, x + w, y + h, hex("#5b7fa6"), hex("#c9a46c"), Shader.TileMode.CLAMP)
            c.drawRect(x, y, x + w, y + h, p); p.shader = null
            return
        }
        val isPhoto = im === d.photo
        if (isPhoto && skipPhoto) { photoRects.add(RectF(x, y, x + w, y + h)); return }
        val fx = if (isPhoto) d.fx else 0.5f
        val fy = if (isPhoto) d.fy else 0.5f
        val k = max(w / im.width, h / im.height)
        val dw = im.width * k; val dh = im.height * k
        val l = x + (w - dw) * fx; val t = y + (h - dh) * fy
        c.save(); c.clipRect(x, y, x + w, y + h)
        p.shader = null
        c.drawBitmap(im, null, RectF(l, t, l + dw, t + dh), p)
        c.restore()
    }

    private fun txt(text: String, x: Float, y: Float, align: Paint.Align, col: Int, size: Float, maxW: Float? = null, bold: Boolean = true) {
        p.shader = null; p.style = Paint.Style.FILL
        face(bold)
        p.textSize = size; p.color = col; p.textAlign = align
        val full = p.measureText(text)
        if (maxW != null && full > maxW) p.textScaleX = maxW / full
        val fm = p.fontMetrics
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2, p)
        p.textScaleX = 1f
        p.textAlign = Paint.Align.LEFT
        face(true)
    }

    /** align: 'l' | 'c' | 'r' */
    private fun chip(text: String, x: Float, y: Float, bg: Int, col: Int, size: Float, align: Char = 'l') {
        p.textSize = size
        val w = p.measureText(text) + size * 1.1f
        val h = size * 1.7f
        val xx = when (align) { 'r' -> x - w; 'c' -> x - w / 2; else -> x }
        rect(xx, y, w, h, bg)
        txt(text, xx + size * 0.55f, y + h / 2 + 1, Paint.Align.LEFT, col, size)
    }

    private fun wrap(text: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (w in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val t = if (line.isNotEmpty()) "$line $w" else w
            if (line.isNotEmpty() && p.measureText(t) > maxW) { out.add(line); line = w } else line = t
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    /** শিরোনাম সর্বোচ্চ ৩ লাইনে; না ধরলে ফন্ট ছোট হয় (ওয়েবের headline) */
    private fun headline(text: String, x: Float, y: Float, w: Float, h: Float, col1: Int, col2: Int? = null, max: Int = 78) {
        val lo = 12; val maxL = 3
        fun lhf(n: Int) = if (n >= 3) 1.2f else 1.38f
        var fs = max
        var lines: List<String> = emptyList()
        while (fs >= lo) {
            p.textSize = fs.toFloat(); lines = wrap(text, w)
            if (lines.size <= maxL && lines.size * fs * lhf(lines.size) <= h) break
            fs -= 2
        }
        if (fs < lo) {
            fs = lo; p.textSize = fs.toFloat()
            lines = wrap(text, w).take(maxL)
            if (lines.size == maxL) {
                var last = lines[maxL - 1]
                while (last.isNotEmpty() && p.measureText("$last…") > w) last = last.dropLast(1)
                lines = lines.toMutableList().also { it[maxL - 1] = "$last…" }
            }
        }
        val n = lines.size
        val lh = fs * lhf(n)
        val top = y + (h - n * lh) / 2
        val hi = if (col2 != null) ceil(n / 2.0).toInt() else n
        lines.forEachIndexed { i, l ->
            txt(l, x + w / 2, top + lh * (i + 0.5f), Paint.Align.CENTER, if (i < hi) col1 else (col2 ?: col1), fs.toFloat())
        }
    }

    private fun bar(y: Float, col: Int, withCta: Boolean = true) {
        txt(d.date, 24f, y, Paint.Align.LEFT, col, 26f)
        if (withCta && d.cta.isNotBlank()) txt(d.cta, 540f, y, Paint.Align.CENTER, col, 26f)
        txt(d.url, 1056f, y, Paint.Align.RIGHT, col, 26f)
    }

    /** স্লটে লোগো/আইকন/সাইটের নাম বসায়। pref = টেমপ্লেটের পছন্দের ক্রম, align: 'l' | 'c' | 'r' */
    private fun logoSlot(x: Float, y: Float, w: Float, h: Float, align: Char, pref: List<String>, lt: Int) {
        val mode = pref.firstOrNull { (it == "wide" && d.logo != null) || (it == "icon" && d.icon != null) } ?: "text"
        fun at(bw: Float) = when (align) { 'r' -> x + w - bw; 'c' -> x + (w - bw) / 2; else -> x }
        when (mode) {
            "wide" -> {
                val lg = d.logo!!
                val k = min(w / lg.width, h / lg.height)
                val dw = lg.width * k; val dh = lg.height * k
                val xx = at(dw); val yy = y + (h - dh) / 2
                p.shader = null
                c.drawBitmap(lg, null, RectF(xx, yy, xx + dw, yy + dh), p)
            }
            "icon" -> {
                val ic = d.icon!!
                val s = min(w, h)
                val dx = at(s); val dy = y + (h - s) / 2
                c.save()
                if (d.iconRound) {
                    val path = Path().apply { addCircle(dx + s / 2, dy + s / 2, s / 2, Path.Direction.CW) }
                    c.clipPath(path)
                } else c.clipRect(dx, dy, dx + s, dy + s)
                cover(ic, dx, dy, s, s)
                c.restore()
            }
            else -> {
                val name = d.siteName.ifBlank { Cfg.SITE }
                p.textSize = h * 0.5f
                val full = p.measureText(name)
                val dw = min(w, full)
                p.shader = null; p.style = Paint.Style.FILL; p.color = lt; p.textAlign = Paint.Align.LEFT
                if (full > w) p.textScaleX = w / full
                val fm = p.fontMetrics
                c.drawText(name, at(dw), y + h / 2 - (fm.ascent + fm.descent) / 2, p)
                p.textScaleX = 1f
            }
        }
    }

    private val tagBg = Color.argb(153, 0, 0, 0)   // rgba(0,0,0,.6)

    // ---------- ৫টি টেমপ্লেট ----------
    fun dark() {
        val head = hex("#ffd400"); val band = "#1e1e6e"; val shade = "#000000"; val sub = hex("#dddddd"); val lt = hex("#ffffff")
        cover(d.photo, 0f, 0f, 1080f, 1350f)
        vGrad(650f, 1350f, intArrayOf(hexA(shade, 0f), hexA(shade, .88f), hex(shade)), floatArrayOf(0f, .45f, 1f))
        rect(0f, 880f, 1080f, 60f, hexA(band, .6f))
        txt(catDate, 540f, 911f, Paint.Align.CENTER, Color.WHITE, 30f)
        headline(d.head, 60f, 960f, 960f, 290f, head)
        rect(440f, 1268f, 200f, 3f, Color.argb(89, 255, 255, 255))
        txt(d.url, 540f, 1305f, Paint.Align.CENTER, sub, 30f)
        logoSlot(760f, 40f, 280f, 120f, 'r', listOf("wide", "icon"), lt)
        if (d.tag.isNotBlank()) chip(d.tag, 30f, 40f, tagBg, Color.WHITE, 26f)
    }

    fun white() {
        val head1 = hex("#d50000"); val head2 = hex("#1a3ea8"); val panel = hex("#f2f2f2"); val foot = hex("#e2e2e2")
        val ft = hex("#333333"); val lt = hex("#222222")
        cover(d.photo, 0f, 0f, 1080f, 860f)
        rect(0f, 860f, 1080f, 490f, panel)
        p.shader = null; p.style = Paint.Style.FILL; p.color = panel
        c.drawCircle(540f, 860f, 105f, p)
        logoSlot(450f, 800f, 180f, 120f, 'c', listOf("icon", "wide"), lt)
        if (d.tag.isNotBlank()) chip(d.tag, 30f, 780f, tagBg, Color.WHITE, 26f)
        headline(d.head, 60f, 985f, 960f, 270f, head1, head2)
        rect(0f, 1270f, 1080f, 80f, foot)
        txt(catDate, 40f, 1310f, Paint.Align.LEFT, ft, 30f)
        txt(d.url, 1040f, 1310f, Paint.Align.RIGHT, ft, 30f)
    }

    fun red() {
        val bg = hex("#b30012"); val head1 = hex("#ffffff"); val head2 = hex("#ffe600"); val foot = hex("#8e000d")
        val ft = hex("#ffffff"); val lt = hex("#ffffff")
        cover(d.photo, 0f, 0f, 1080f, 900f)
        rect(0f, 900f, 1080f, 450f, bg)
        logoSlot(30f, 30f, 260f, 110f, 'l', listOf("wide", "icon"), lt)
        if (d.tag.isNotBlank()) chip(d.tag, 1050f, 30f, tagBg, Color.WHITE, 26f, 'r')
        headline(d.head, 50f, 925f, 980f, 340f, head1, head2)
        rect(0f, 1300f, 1080f, 50f, foot)
        bar(1326f, ft)
    }

    fun blue() {
        val bg1 = hex("#0d3b8e"); val bg2 = hex("#08224f"); val head1 = hex("#ffffff"); val head2 = hex("#ffe600")
        val chipc = hex("#6a1bb1"); val ft = hex("#ffffff"); val lt = hex("#ffffff")
        cover(d.photo, 0f, 0f, 1080f, 650f)
        vGrad(650f, 1350f, intArrayOf(bg1, bg2), null)
        logoSlot(30f, 30f, 260f, 110f, 'l', listOf("wide", "icon"), lt)
        if (d.tag.isNotBlank()) chip(d.tag, 0f, 592f, tagBg, Color.WHITE, 26f)
        headline(d.head, 50f, 700f, 980f, 380f, head1, head2)
        if (d.cta.isNotBlank()) chip(d.cta, 540f, 1098f, chipc, Color.WHITE, 30f, 'c')
        rect(0f, 1290f, 1080f, 60f, Color.argb(64, 0, 0, 0))
        bar(1320f, ft, false)
    }

    fun top() {
        val bg = hex("#1c2d52"); val head = hex("#ffe600"); val ft = hex("#ffffff"); val lt = hex("#ffffff")
        rect(0f, 0f, 1080f, 440f, bg)
        logoSlot(790f, 24f, 260f, 90f, 'r', listOf("wide", "icon"), lt)
        headline(d.head, 50f, 130f, 980f, 300f, head)
        cover(d.photo, 0f, 440f, 1080f, 910f)
        chip(catDate, 0f, 1270f, Color.argb(166, 0, 0, 0), Color.WHITE, 28f)
        if (d.tag.isNotBlank()) chip(d.tag, 1080f, 452f, tagBg, Color.WHITE, 24f, 'r')
        txt(d.url, 1050f, 1310f, Paint.Align.RIGHT, ft, 28f)
    }

    /** বক্তব্য কার্ড: ছবি বাঁয়ে, লাল প্যানেল ডানে (ওয়েবের quote1) */
    fun quote1() {
        val px = 500f
        val bg1 = hex("#e30000"); val bg2 = hex("#6e0000"); val tx = hex("#ffffff"); val sub = hex("#f1d6d6"); val lt = hex("#b30012")
        cover(d.photo, 0f, 0f, px, 1080f)
        vGrad(0f, 1080f, intArrayOf(bg1, bg2), null, x0 = px)
        rect(px - 1, 0f, 3f, 1080f, Color.argb(140, 255, 255, 255))
        rect(px, 56f, 1080f - px, 78f, Color.WHITE)
        logoSlot(px + 52, 62f, 476f, 66f, 'c', listOf("wide", "icon"), lt)
        if (d.url.isNotBlank()) {
            p.shader = null; p.style = Paint.Style.FILL; p.color = Color.argb(140, 255, 255, 255)
            c.drawPath(Path().apply { moveTo(px + 330, 140f); lineTo(px + 330, 164f); lineTo(px + 348, 152f); close() }, p)
            txt(d.url.replace(Regex("^https?://"), ""), px + 362, 153f, Paint.Align.LEFT, Color.argb(178, 255, 255, 255), 28f, maxW = 180f, bold = false)
        }
        // বড় উদ্ধৃতি-চিহ্ন
        p.shader = null; p.style = Paint.Style.FILL; p.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        p.textSize = 220f; p.color = Color.argb(153, 255, 255, 255); p.textAlign = Paint.Align.LEFT
        c.drawText("\u201C", px + 34, 400f, p)
        face(true)
        // বক্তব্য (ফন্ট ৬৪ থেকে ছোট হয়ে ৪৫০ চওড়া × ৪৬০ উঁচু ঘরে ধরানো)
        val x = px + 70; val w = 450f; val y0 = 410f; val h = 460f
        var fs = 64
        var ls: List<String> = emptyList()
        face(true)
        while (fs >= 22) {
            p.textSize = fs.toFloat(); ls = wrap(d.head, w)
            if (ls.size * fs * 1.42f <= h) break
            fs -= 2
        }
        if (fs < 22) fs = 22
        val lh = fs * 1.42f
        val top = y0 + max(0f, (h - ls.size * lh) / 2)
        ls.forEachIndexed { i, l -> txt(l, x, top + lh * (i + 0.5f), Paint.Align.LEFT, tx, fs.toFloat(), maxW = w) }
        // নাম ও পদবি (পদবি সর্বোচ্চ ২ লাইন, দরকারে ফন্ট একটু ছোট)
        if (d.name.isNotBlank() || d.role.isNotBlank()) {
            val mw = 480f
            var rf = 34
            var rl: List<String> = emptyList()
            if (d.role.isNotBlank()) {
                face(false)
                while (rf >= 24) {
                    p.textSize = rf.toFloat(); rl = wrap(d.role, mw)
                    if (rl.size <= 2) break
                    rf -= 2
                }
                if (rl.size > 2) {
                    rl = rl.take(2).toMutableList()
                    var last = rl[1]
                    while (last.isNotEmpty() && p.measureText("$last…") > mw) last = last.dropLast(1)
                    rl = listOf(rl[0], "$last…")
                }
                face(true)
            }
            val rlh = (rf * 1.25f).roundToInt()
            val nh = if (d.name.isNotBlank()) 50 else 0
            val total = nh + rl.size * rlh
            val start = 1038f - total
            val lineTop = min(912f, start - 14)
            rect(1056f, lineTop, 3f, 1056f - lineTop, Color.WHITE)
            if (d.name.isNotBlank()) txt(d.name, 1036f, start + nh / 2f, Paint.Align.RIGHT, Color.WHITE, 40f, maxW = mw)
            rl.forEachIndexed { i, l -> txt(l, 1036f, start + nh + rlh * (i + 0.5f), Paint.Align.RIGHT, sub, rf.toFloat(), maxW = mw, bold = false) }
        }
    }
}
