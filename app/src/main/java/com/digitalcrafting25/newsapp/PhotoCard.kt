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
import org.json.JSONObject
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
    val rashi: String = "",  // শুভ সকাল কার্ডের রাশি (কমা দিয়ে, সর্বোচ্চ ৪টা)
    val mnBgs: Map<Int, Bitmap> = emptyMap(),   // শুভ সকালের ৫টি ইলাস্ট্রেশন
    val wx: MnWeather? = null,                  // ঢাকার আজকের আবহাওয়া
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

/** mode: "photo" | "quote" | "morning" */
fun cardTpls(mode: String) = when (mode) {
    "quote" -> QUOTE_TPLS
    "morning" -> MORNING_TPLS
    else -> TplRegistry.photoTpls()
}

/** ফোটোকার্ড মেনুর (🖼️ ট্যাব) সারি — ফটোকার্ডে ডাউনলোড করা শেয়ার্ডও থাকে; বাকি মোডে cardTpls-এর মতোই */
fun menuTpls(mode: String) = if (mode == "photo") TplRegistry.menuTpls() else cardTpls(mode)

/** ওয়েবের TPL.draw-এর হুবহু হিসাব (১০৮০×১৩৫০). scale দিলে ছোট থাম্বনেইল হয় */
object CardRender {
    const val W = 1080
    const val H = 1350

    /** ফুটারের (নিচের পাতলা বার) শুরুর y — বিজ্ঞাপন এর ঠিক ওপরে বসে; ফুটার না থাকলে কার্ডের একদম নিচে */
    fun hOf(id: String) = TplRegistry.sizeOf(id)?.second ?: if (id == "quote1") 1080 else H
    fun wOf(id: String) = TplRegistry.sizeOf(id)?.first ?: W
    fun aspect(id: String) = wOf(id).toFloat() / hOf(id)

    private val FOOT = mapOf("white" to 1270, "red" to 1300, "blue" to 1290) + MN_SPECS.associate { "morning${it.k}" to 1229 }

    /** ছবির জায়গার মাপ (টেনে সরানোর হিসাবের জন্য) */
    fun photoBox(id: String): Pair<Float, Float> = layoutPhotoBox(TplRegistry.layout(id)) ?: when (id) {
        "quote1" -> 500f to 1080f
        "white" -> 1080f to 860f
        "red" -> 1080f to 900f
        "blue" -> 1080f to 650f
        "top" -> 1080f to 910f
        else -> 1080f to 1350f
    }

    /** ad দিলে ওয়েবের renderCard-এর নিয়মে বসে: কার্ডের মাপ একই থাকে, ছবি ক্রপ হয়ে ছোট হয় (চ্যাপ্টা হয় না), ফুটার নিজের জায়গায় */
    /** লেআউটের প্রথম "খবরের ছবি" এলিমেন্টের মাপ */
    fun layoutPhotoBox(S: JSONObject?): Pair<Float, Float>? {
        val a = S?.optJSONArray("els") ?: return null
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            if (e.optString("type") == "photo") return e.optDouble("w", 1080.0).toFloat() to e.optDouble("h", 1350.0).toFloat()
        }
        return null
    }

    /** বিজ্ঞাপন বসানোর হিসাব (কোন সারিতে একটানা ফাঁকা পট্টি): টেমপ্লেট/শিরোনাম না বদলালে একবার হিসাব করে বারবার কাজে লাগানো যায় (ছবি টানার সময় ল্যাগ কমাতে) */
    class AdPlan(val y0: Int, val rects: List<RectF>)

    /** টেমপ্লেটে নিজের ব্যানার-স্লট (adSlot) থাকলে তার উচ্চতা (px, কার্ডের নিজের মাপে); নইলে null */
    fun adSlotH(id: String, ov: TplOv? = null): Int? {
        val lay = ov?.s ?: TplRegistry.layout(id) ?: return null
        if (!lay.optBoolean("adSlot")) return null
        val w = lay.optInt("w", 1080).coerceAtLeast(1)
        return lay.optInt("adH", (w * AD_H / AD_W.toFloat()).roundToInt()).coerceAtLeast(1)
    }

    fun adPlan(id: String, d: CardData, ov: TplOv? = null): AdPlan {
        if (adSlotH(id, ov) != null) return AdPlan(-1, emptyList())   // স্লট-টেমপ্লেটে কিছু সরে না, হিসাব লাগে না
        val H = TplRegistry.sizeOf(id, ov)?.second ?: hOf(id)
        val W = TplRegistry.sizeOf(id, ov)?.first ?: wOf(id)
        val adH = AD_H
        val sp0 = FOOT[id] ?: H
        // টেমপ্লেট আধা-মাপে স্বচ্ছ ক্যানভাসে এঁকে (ছবি বাদ) দেখা: কোন একটানা পট্টি একদম ফাঁক
        val an = 0.5f
        val pb = Bitmap.createBitmap((W * an).toInt(), (sp0 * an).toInt(), Bitmap.Config.ARGB_8888)
        val pc = Canvas(pb); pc.scale(an, an)
        val rec = CardPainter(pc, d).also { it.ov = ov; it.skipPhoto = true; if (!isMorningId(id)) it.draw(id) }
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
        return AdPlan(y0, rects)
    }

    fun render(id: String, d: CardData, scale: Float = 1f, ad: Bitmap? = null, ov: TplOv? = null, plan: AdPlan? = null): Bitmap {
        val H = TplRegistry.sizeOf(id, ov)?.second ?: hOf(id)   // কার্ডের উচ্চতা (বক্তব্য কার্ড ১০৮০, বাকিগুলো ১৩৫০)
        val W = TplRegistry.sizeOf(id, ov)?.first ?: wOf(id)
        val bmp = Bitmap.createBitmap((W * scale).roundToInt(), (H * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        c.drawColor(Color.WHITE)
        if (ad == null) { CardPainter(c, d).also { it.ov = ov }.draw(id); return bmp }

        // ব্যানার-স্লট টেমপ্লেট: কার্ডের মাপ বাড়ে না, কিছু ছোট/চাপাও হয় না। ফুটার-বারের ঠিক ওপরের নির্ধারিত পট্টিতে (adY) শুধু ব্যানার বসে
        val slot = adSlotH(id, ov)
        if (slot != null) {
            CardPainter(c, d).also { it.ov = ov }.draw(id)
            val lay = ov?.s ?: TplRegistry.layout(id)
            val ay = (lay?.optInt("adY", H - slot) ?: (H - slot)).toFloat()   // ব্যানারের ওপরের y (ফুটার-বারের ঠিক ওপরে)
            c.drawBitmap(ad, null, RectF(0f, ay, W.toFloat(), ay + slot), Paint(Paint.FILTER_BITMAP_FLAG))
            return bmp
        }

        val adH = AD_H
        val sp0 = FOOT[id] ?: H
        val topH = sp0 - adH
        val wf = W.toFloat()

        val pl = plan ?: adPlan(id, d, ov)
        val rects = pl.rects
        val y0 = pl.y0

        val p = CardPainter(c, d).also { it.ov = ov }
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

val GLYPHS = listOf("globe", "fb", "yt", "ig", "phone", "mail", "pin")

private fun hex(s: String): Int = runCatching { Color.parseColor(s) }.getOrDefault(Color.WHITE)
private fun hexA(s: String, a: Float): Int {
    val v = hex(s)
    return Color.argb((a * 255).roundToInt().coerceIn(0, 255), Color.red(v), Color.green(v), Color.blue(v))
}

private class CardPainter(val c: Canvas, val d: CardData) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    var skipPhoto = false
    var ov: TplOv? = null
    private var curId = ""
    /** ওয়েবের tcolors(): সেটিংসের টেমপ্লেট-মোডালে বদলানো রঙ (tpls[id].c[key]); না থাকলে আসল ডিফল্ট */
    private fun tc(k: String, d: String): String {
        val ts = ov?.ts ?: TplRegistry.tsOf(curId)
        val v = ts?.optJSONObject("c")?.optString(k).orEmpty()
        return if (Regex("^#[0-9a-fA-F]{6}$").matches(v)) v else d
    }
    private fun face(bold: Boolean) { p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
    val photoRects = ArrayList<RectF>()
    fun coverPhoto(x: Float, y: Float, w: Float, h: Float) { val sv = skipPhoto; skipPhoto = false; cover(d.photo, x, y, w, h); skipPhoto = sv }
    fun draw(id: String) {
        curId = id
        val lay = ov?.s ?: TplRegistry.layout(id)
        if (lay != null) { drawLayout(id, lay); return }
        when (id) {
            "white" -> white()
            "red" -> red()
            "blue" -> blue()
            "top" -> top()
            "quote1" -> quote1()
            else -> if (isMorningId(id)) morning(id) else dark()
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

    // ---------- এডিট করা / কাস্টম টেমপ্লেট (ওয়েবের cbDrawEl, হুবহু হিসাব) ----------
    private fun jd(o: JSONObject, k: String, dflt: Double = 0.0): Float = (if (o.has(k) && !o.isNull(k)) o.optDouble(k, dflt) else dflt).toFloat()
    private fun js(o: JSONObject, k: String, dflt: String = ""): String = if (o.has(k) && !o.isNull(k)) o.optString(k, dflt) else dflt
    private fun jc(o: JSONObject, k: String): Char = js(o, k).firstOrNull() ?: 'l'

    fun drawLayout(id: String, S: JSONObject) {
        val w = S.optInt("w", 1080).toFloat(); val h = S.optInt("h", 1350).toFloat()
        rect(0f, 0f, w, h, hex(js(S, "bg", "#111111")))
        val els = S.optJSONArray("els") ?: return
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            c.save()
            try { drawEl(id, e) } catch (_: Throwable) {}
            c.restore()
        }
    }

    private fun drawEl(id: String, e: JSONObject) {
        val x = jd(e, "x"); val y = jd(e, "y"); val w = jd(e, "w"); val h = jd(e, "h")
        when (js(e, "type")) {
            "photo" -> cover(d.photo, x, y, w, h)
            "rect" -> {
                val al = jd(e, "alpha", 100.0) / 100f
                val col = js(e, "color", "#000000")
                p.style = Paint.Style.FILL; p.shader = null
                when (js(e, "grad", "none")) {
                    "down", "up" -> {
                        val down = js(e, "grad") == "down"
                        p.color = Color.BLACK
                        p.shader = LinearGradient(0f, if (down) y else y + h, 0f, if (down) y + h else y,
                            intArrayOf(hexA(col, 0f), hexA(col, .88f), hex(col)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
                    }
                    "two" -> {
                        p.color = Color.BLACK
                        p.shader = LinearGradient(0f, y, 0f, y + h, hex(col), hex(js(e, "color2", col)), Shader.TileMode.CLAMP)
                    }
                    else -> p.color = hex(col)
                }
                p.alpha = (255 * al).roundToInt().coerceIn(0, 255)
                if (js(e, "shape") == "circle") c.drawOval(RectF(x, y, x + w, y + h), p) else c.drawRect(x, y, x + w, y + h, p)
                p.shader = null; p.alpha = 255
            }
            "headline" -> headline(d.head, x, y, w, h, hex(js(e, "c1", "#ffffff")), if (e.optBoolean("two")) hex(js(e, "c2", "#ffe600")) else null, e.optInt("size", 78))
            "logo" -> logoEl(id, x, y, w, h, jc(e, "align"), js(e, "mode"), js(e, "shape"), hex(js(e, "color", "#ffffff")))
            "social" -> {
                val ico = js(e, "ico").ifBlank { if (js(e, "net") == "yt") "yt" else "fb" }
                iconLine(x, y, w, h, ico, js(e, "text").trim(), hex(js(e, "color", "#ffffff")), e.optInt("size", 30).toFloat(), jc(e, "align"), 12f, e.optBoolean("fx"))
            }
            "icon" -> {
                val sz = min(w, h); val al = jc(e, "align")
                glyph(js(e, "ico", "globe"), if (al == 'l') x else if (al == 'r') x + w - sz else x + (w - sz) / 2, y + (h - sz) / 2, sz, hex(js(e, "color", "#ffffff")))
            }
            "image" -> {
                val im = TplRegistry.image(js(e, "data")) ?: return
                p.shader = null; p.alpha = (255 * jd(e, "alpha", 100.0) / 100f).roundToInt().coerceIn(0, 255)
                if (js(e, "fit") == "cover") {
                    val k = max(w / im.width, h / im.height); val dw = im.width * k; val dh = im.height * k
                    c.save(); c.clipRect(x, y, x + w, y + h)
                    c.drawBitmap(im, null, RectF(x + (w - dw) / 2, y + (h - dh) / 2, x + (w + dw) / 2, y + (h + dh) / 2), p); c.restore()
                } else {
                    val k = min(w / im.width, h / im.height); val iw = im.width * k; val ih = im.height * k
                    c.drawBitmap(im, null, RectF(x + (w - iw) / 2, y + (h - ih) / 2, x + (w + iw) / 2, y + (h + ih) / 2), p)
                }
                p.alpha = 255
            }
            "text" -> {
                val src = js(e, "src", "catdate")
                val s = when (src) { "catdate" -> catDate; "date" -> d.date; "cat" -> d.cat; "url" -> d.url; "tag" -> d.tag; "cta" -> d.cta; "custom" -> js(e, "text"); else -> "" }
                if (s.isEmpty()) return
                val icon = js(e, "icon")
                fun box() { if (e.optBoolean("box")) rect(x, y, w, h, hexA(js(e, "bg", "#000000"), jd(e, "balpha", 100.0) / 100f)) }
                val col = hex(js(e, "color", "#ffffff"))
                val al0 = if (e.has("align")) jc(e, "align") else 'l'
                if (icon in GLYPHS) { box(); iconLine(x, y, w, h, icon, s, col, e.optInt("size", 30).toFloat(), al0, 16f, e.optBoolean("fx")); return }
                box()
                var fs = e.optInt("size", 30).toFloat(); val pad = 16f
                val bold = !(src == "cat" || src == "date" || src == "catdate")
                face(bold); p.textSize = fs
                while (!e.optBoolean("fx") && fs > 12 && p.measureText(s) > w - pad * 2) { fs -= 2; p.textSize = fs }
                face(true)
                val align = when (al0) { 'c' -> Paint.Align.CENTER; 'r' -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
                val xx = when (al0) { 'c' -> x + w / 2; 'r' -> x + w - pad; else -> x + pad }
                txt(s, xx, y + h / 2 + 1, align, col, fs, null, bold)
            }
        }
    }

    /** ওয়েবের logoSlot(force, shape): এলিমেন্টে বেছে নেওয়া লোগো ধরন, আর টেমপ্লেটের লোগো মোড/সাইজ (tpls[id]) */
    private fun logoEl(id: String, x: Float, y: Float, w: Float, h: Float, align: Char, force: String, shape: String, lt: Int) {
        val ts = ov?.ts ?: TplRegistry.tsOf(id)
        val m = ts?.optString("mode", "auto")?.ifBlank { "auto" } ?: "auto"
        val hasWide = d.logo != null; val hasIcon = d.icon != null
        val pref = if (id == "white") listOf("icon", "wide") else listOf("wide", "icon")
        val auto = when {
            m == "none" || m == "text" -> m
            (m == "wide" && hasWide) || (m == "icon" && hasIcon) -> m
            else -> pref.firstOrNull { (it == "wide" && hasWide) || (it == "icon" && hasIcon) } ?: "text"
        }
        val mode = if (force.isNotEmpty() && auto != "none" && ((force == "wide" && hasWide) || (force == "icon" && hasIcon) || force == "text")) force else auto
        if (mode == "none") return
        val sc = (ts?.optInt("size", 100) ?: 100).let { if (it <= 0) 100 else it } / 100f
        val nw = w * sc; val nh = h * sc
        val X = when (align) { 'r' -> x + w - nw; 'c' -> x + (w - nw) / 2; else -> x }
        val Y = y + (h - nh) / 2
        fun at(bw: Float) = when (align) { 'r' -> X + nw - bw; 'c' -> X + (nw - bw) / 2; else -> X }
        when (mode) {
            "wide" -> {
                val lg = d.logo ?: return
                val k = min(nw / lg.width, nh / lg.height); val dw = lg.width * k; val dh = lg.height * k
                p.shader = null
                c.drawBitmap(lg, null, RectF(at(dw), Y + (nh - dh) / 2, at(dw) + dw, Y + (nh - dh) / 2 + dh), p)
            }
            "icon" -> {
                val ic = d.icon ?: return
                val s = min(nw, nh); val dx = at(s); val dy = Y + (nh - s) / 2
                val sq = (shape.ifBlank { if (d.iconRound) "round" else "square" }) == "square"
                c.save()
                if (sq) c.clipRect(dx, dy, dx + s, dy + s) else c.clipPath(Path().apply { addCircle(dx + s / 2, dy + s / 2, s / 2, Path.Direction.CW) })
                cover(ic, dx, dy, s, s)
                c.restore()
            }
            else -> {
                val name = d.siteName.ifBlank { Cfg.SITE }
                face(true); p.textSize = nh * 0.5f
                val full = p.measureText(name); val dw = min(nw, full)
                p.shader = null; p.style = Paint.Style.FILL; p.color = lt; p.textAlign = Paint.Align.LEFT
                if (full > nw) p.textScaleX = nw / full
                val fm = p.fontMetrics
                c.drawText(name, at(dw), Y + nh / 2 - (fm.ascent + fm.descent) / 2, p)
                p.textScaleX = 1f
            }
        }
    }

    /** আইকন + লেখা একসাথে (ওয়েবের cbIconLine); জায়গা কম পড়লে লেখা ছোট হয় */
    private fun iconLine(x: Float, y: Float, w: Float, h: Float, ico: String, text: String, col: Int, size: Float, align: Char, pad: Float, fixed: Boolean) {
        var fs = size
        face(false)
        fun ic() = (fs * 1.35f).roundToInt().toFloat()
        fun gap() = (fs * 0.4f).roundToInt().toFloat()
        p.textSize = fs
        var tw = if (text.isNotEmpty()) p.measureText(text) else 0f
        while (!fixed && fs > 12 && ic() + (if (text.isNotEmpty()) gap() + tw else 0f) > w - pad * 2) {
            fs -= 2; p.textSize = fs; tw = if (text.isNotEmpty()) p.measureText(text) else 0f
        }
        face(true)
        val icS = ic(); val gp = gap()
        val total = icS + (if (text.isNotEmpty()) gp + tw else 0f)
        val sx = when (align) { 'c' -> x + (w - total) / 2; 'r' -> x + w - pad - total; else -> x + pad }
        val cy = y + h / 2
        glyph(ico, sx, cy - icS / 2, icS, col)
        if (text.isNotEmpty()) txt(text, sx + icS + gp, cy + 1, Paint.Align.LEFT, col, fs, null, false)
    }

    /** ওয়েবের cbGlyph: কোডে আঁকা আইকন */
    private fun glyph(kind: String, x: Float, y: Float, s: Float, col: Int) {
        val lw = max(2f, s / 12f); val cx = x + s / 2; val cy = y + s / 2
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = lw; color = col; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        val fl = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = col }
        fun rr(rx: Float, ry: Float, rw: Float, rh: Float, r: Float) { c.drawRoundRect(RectF(rx, ry, rx + rw, ry + rh), r, r, st) }
        when (kind) {
            "globe" -> {
                val r = s / 2 - lw / 2; st.strokeWidth = lw * 0.85f
                c.drawCircle(cx, cy, r, st)
                c.drawOval(RectF(cx - r * 0.42f, cy - r, cx + r * 0.42f, cy + r), st)
                val hw = r * 0.866f
                c.drawLine(cx - r, cy, cx + r, cy, st); c.drawLine(cx - hw, cy - r / 2, cx + hw, cy - r / 2, st); c.drawLine(cx - hw, cy + r / 2, cx + hw, cy + r / 2, st)
            }
            "yt" -> {
                val bh = s * 0.78f
                rr(x + lw / 2, cy - bh / 2, s - lw, bh, bh * 0.28f)
                val tri = Path().apply { moveTo(x + s * 0.40f, cy - bh * 0.26f); lineTo(x + s * 0.40f, cy + bh * 0.26f); lineTo(x + s * 0.68f, cy); close() }
                c.drawPath(tri, fl)
            }
            "ig" -> {
                rr(x + lw / 2, y + lw / 2, s - lw, s - lw, s * 0.28f)
                c.drawCircle(cx, cy, s * 0.21f, st)
                c.drawCircle(x + s * 0.74f, y + s * 0.26f, max(1.5f, lw * 0.55f), fl)
            }
            "phone" -> {
                rr(x + s * 0.24f, y + lw / 2, s * 0.52f, s - lw, s * 0.12f)
                c.drawLine(cx - s * 0.09f, y + s * 0.82f, cx + s * 0.09f, y + s * 0.82f, st)
            }
            "mail" -> {
                rr(x + lw / 2, y + s * 0.17f, s - lw, s * 0.66f, s * 0.08f)
                val pa = Path().apply { moveTo(x + s * 0.08f, y + s * 0.24f); lineTo(cx, y + s * 0.57f); lineTo(x + s * 0.92f, y + s * 0.24f) }
                c.drawPath(pa, st)
            }
            "pin" -> {
                val pa = Path().apply {
                    moveTo(cx, y + s * 0.96f)
                    cubicTo(cx - s * 0.46f, y + s * 0.6f, cx - s * 0.34f, y + s * 0.06f, cx, y + s * 0.06f)
                    cubicTo(cx + s * 0.34f, y + s * 0.06f, cx + s * 0.46f, y + s * 0.6f, cx, y + s * 0.96f)
                    close()
                }
                c.drawPath(pa, st)
                c.drawCircle(cx, y + s * 0.38f, s * 0.11f, st)
            }
            else -> {
                rr(x + lw / 2, y + lw / 2, s - lw, s - lw, s * 0.24f)
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; typeface = Typeface.DEFAULT_BOLD; textSize = s * 0.78f; textAlign = Paint.Align.CENTER }
                c.drawText("f", x + s * 0.55f, y + s * 0.84f, tp)
            }
        }
    }

    private val tagBg = Color.argb(153, 0, 0, 0)   // rgba(0,0,0,.6)

    // ---------- ৫টি টেমপ্লেট ----------
    fun dark() {
        val head = hex(tc("head", "#ffd400")); val band = tc("band", "#1e1e6e"); val shade = tc("shade", "#000000"); val sub = hex(tc("sub", "#dddddd")); val lt = hex(tc("lt", "#ffffff"))
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
        val head1 = hex(tc("head1", "#d50000")); val head2 = hex(tc("head2", "#1a3ea8")); val panel = hex(tc("panel", "#f2f2f2")); val foot = hex(tc("foot", "#e2e2e2"))
        val ft = hex(tc("ft", "#333333")); val lt = hex(tc("lt", "#222222"))
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
        val bg = hex(tc("bg", "#b30012")); val head1 = hex(tc("head1", "#ffffff")); val head2 = hex(tc("head2", "#ffe600")); val foot = hex(tc("foot", "#8e000d"))
        val ft = hex(tc("ft", "#ffffff")); val lt = hex(tc("lt", "#ffffff"))
        cover(d.photo, 0f, 0f, 1080f, 900f)
        rect(0f, 900f, 1080f, 450f, bg)
        logoSlot(30f, 30f, 260f, 110f, 'l', listOf("wide", "icon"), lt)
        if (d.tag.isNotBlank()) chip(d.tag, 1050f, 30f, tagBg, Color.WHITE, 26f, 'r')
        headline(d.head, 50f, 925f, 980f, 340f, head1, head2)
        rect(0f, 1300f, 1080f, 50f, foot)
        bar(1326f, ft)
    }

    fun blue() {
        val bg1 = hex(tc("bg1", "#0d3b8e")); val bg2 = hex(tc("bg2", "#08224f")); val head1 = hex(tc("head1", "#ffffff")); val head2 = hex(tc("head2", "#ffe600"))
        val chipc = hex(tc("chipc", "#6a1bb1")); val ft = hex(tc("ft", "#ffffff")); val lt = hex(tc("lt", "#ffffff"))
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
        val bg = hex(tc("bg", "#1c2d52")); val head = hex(tc("head", "#ffe600")); val ft = hex(tc("ft", "#ffffff")); val lt = hex(tc("lt", "#ffffff"))
        rect(0f, 0f, 1080f, 440f, bg)
        logoSlot(790f, 24f, 260f, 90f, 'r', listOf("wide", "icon"), lt)
        headline(d.head, 50f, 130f, 980f, 300f, head)
        cover(d.photo, 0f, 440f, 1080f, 910f)
        chip(catDate, 0f, 1270f, Color.argb(166, 0, 0, 0), Color.WHITE, 28f)
        if (d.tag.isNotBlank()) chip(d.tag, 1080f, 452f, tagBg, Color.WHITE, 24f, 'r')
        txt(d.url, 1050f, 1310f, Paint.Align.RIGHT, ft, 28f)
    }

    // ---------- 🌅 শুভ সকাল (ওয়েবের mnDraw-এর হুবহু হিসাব) ----------
    /** লেখা আঁকে; জায়গা কম পড়লে ফন্ট ছোট হয়; লেখার প্রস্থ ফেরত দেয় (ওয়েবের mnT) */
    private fun mnT(s: String, x: Float, y: Float, size: Float, col: Int, al: Paint.Align, bold: Boolean, maxW: Float? = null): Float {
        p.shader = null; p.style = Paint.Style.FILL; face(bold)
        p.color = col; p.textAlign = al; p.textScaleX = 1f
        var fs = size; p.textSize = fs
        if (maxW != null) while (fs > 12f && p.measureText(s) > maxW) { fs -= 1f; p.textSize = fs }
        val w = p.measureText(s)
        val fm = p.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, p)
        p.textAlign = Paint.Align.LEFT
        face(true)
        return w
    }

    private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, col: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = col
        c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, p)
    }

    /** আধখানা সূর্য + দিগন্ত + রশ্মি + ▲/▼ */
    private fun mnSunIcon(cx: Float, cy: Float, r: Float, col: Int, up: Boolean) {
        p.shader = null; p.color = col
        p.style = Paint.Style.FILL
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f, true, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - r - 14, cy + 1, cx + r + 14, cy + 1, p)
        for (i in 1..5) {
            val a = Math.PI + i * Math.PI / 6
            c.drawLine(
                cx + (Math.cos(a) * (r + 7)).toFloat(), cy + (Math.sin(a) * (r + 7)).toFloat(),
                cx + (Math.cos(a) * (r + 17)).toFloat(), cy + (Math.sin(a) * (r + 17)).toFloat(), p,
            )
        }
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        val tri = Path()
        if (up) { tri.moveTo(cx, cy + 14); tri.lineTo(cx - 8, cy + 27); tri.lineTo(cx + 8, cy + 27) }
        else { tri.moveTo(cx, cy + 27); tri.lineTo(cx - 8, cy + 14); tri.lineTo(cx + 8, cy + 14) }
        tri.close()
        c.drawPath(tri, p)
    }

    private fun mnCloud(cx: Float, cy: Float, s: Float, col: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = col
        c.drawCircle(cx - 0.2f * s, cy + 0.02f * s, 0.18f * s, p)
        c.drawCircle(cx + 0.02f * s, cy - 0.1f * s, 0.25f * s, p)
        c.drawCircle(cx + 0.25f * s, cy + 0.04f * s, 0.16f * s, p)
        c.drawRoundRect(RectF(cx - 0.38f * s, cy, cx - 0.38f * s + 0.79f * s, cy + 0.2f * s), 0.1f * s, 0.1f * s, p)
    }

    /** আবহাওয়ার আইকন: sun | part | cloud | rain */
    private fun mnWxIcon(cx: Float, cy: Float, kind: String) {
        fun sun(x: Float, y: Float, r: Float) {
            val col = hex("#fbb52c")
            p.shader = null; p.color = col; p.style = Paint.Style.FILL
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                c.drawLine(
                    x + (Math.cos(a) * (r + 7)).toFloat(), y + (Math.sin(a) * (r + 7)).toFloat(),
                    x + (Math.cos(a) * (r + 15)).toFloat(), y + (Math.sin(a) * (r + 15)).toFloat(), p,
                )
            }
            p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        }
        when (kind) {
            "sun" -> sun(cx, cy, 18f)
            "part" -> { sun(cx - 10, cy - 10, 13f); mnCloud(cx + 6, cy + 8, 56f, hex("#9fb4c4")) }
            else -> {
                mnCloud(cx, cy - (if (kind == "rain") 6f else 0f), 62f, hex("#9fb4c4"))
                if (kind == "rain") {
                    p.shader = null; p.color = hex("#3d7fd1")
                    p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND
                    for (o in intArrayOf(-16, 0, 16)) c.drawLine(cx + o + 3, cy + 14, cx + o - 3, cy + 26, p)
                    p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
                }
            }
        }
    }

    /** ওপরে ইলাস্ট্রেশন (১০৮০×৭৩৯), নিচে তথ্যের প্যানেল, একদম নিচে লোগো + ওয়েবসাইট */
    fun morning(id: String) {
        val sp = MN_SPECS.firstOrNull { "morning${it.k}" == id } ?: MN_SPECS[0]
        val ac = hex(sp.ac); val tn = hex(sp.tint); val ln = hex(sp.ln)
        val yy = 739f
        val tdy = MnCal.today()
        val bg = d.mnBgs[sp.k]
        if (bg != null) { p.shader = null; c.drawBitmap(bg, null, RectF(0f, 0f, 1080f, yy), p) } else rect(0f, 0f, 1080f, yy, ac)
        rect(0f, yy, 1080f, 611f, Color.WHITE)

        val bl = MnCal.bangla(tdy); val hj = MnCal.hijri(tdy); val se = MnCal.SE[bl.m]
        // বার + ঋতু
        mnT(MnCal.WD[MnCal.wd(tdy)], 52f, yy + 52, 56f, ac, Paint.Align.LEFT, true, 560f)
        p.textSize = 38f; face(true)
        val sw = p.measureText(se) + 68
        rrect(1027 - sw, yy + 20, sw, 65f, 32.5f, ac)
        mnT(se, 1027 - sw / 2, yy + 53, 38f, Color.WHITE, Paint.Align.CENTER, true)

        // বাংলা | ইংরেজি | আরবি তারিখ
        rrect(40f, yy + 108, 1000f, 140f, 30f, tn)
        rect(373f, yy + 130, 2f, 98f, ln); rect(707f, yy + 130, 2f, 98f, ln)
        val cols = ArrayList<List<Any>>()
        cols.add(listOf(206f, "বাংলা তারিখ", MnCal.n(bl.d) + " " + MnCal.BM[bl.m], MnCal.n(bl.y) + " বঙ্গাব্দ"))
        cols.add(listOf(540f, "ইংরেজি তারিখ", MnCal.n(tdy.dayOfMonth) + " " + MnCal.EM[tdy.monthValue - 1], MnCal.n(tdy.year) + " খ্রিষ্টাব্দ"))
        if (hj != null) cols.add(listOf(873f, "আরবি তারিখ", MnCal.n(hj.d) + " " + MnCal.HM[hj.m - 1], MnCal.n(hj.y) + " হিজরি"))
        cols.forEach { col ->
            val x = col[0] as Float
            mnT(col[1] as String, x, yy + 136, 26f, hex("#6b6b6b"), Paint.Align.CENTER, false, 320f)
            mnT(col[2] as String, x, yy + 181, 40f, hex("#151515"), Paint.Align.CENTER, true, 318f)
            mnT(col[3] as String, x, yy + 221, 27f, hex("#555555"), Paint.Align.CENTER, false, 318f)
        }

        // সূর্যোদয় / সূর্যাস্ত
        listOf(Triple(40f, "সূর্যোদয়", true), Triple(550f, "সূর্যাস্ত", false)).forEach { (x, lb, up) ->
            rrect(x, yy + 268, 490f, 87f, 26f, tn)
            mnSunIcon(x + 63, yy + 312, 22f, ac, up)
            mnT(lb, x + 112, yy + 296, 23f, hex("#6b6b6b"), Paint.Align.LEFT, false, 360f)
            mnT(MnCal.clock(MnCal.sun(tdy, up)), x + 112, yy + 331, 35f, hex("#151515"), Paint.Align.LEFT, true, 360f)
        }

        // আজ যাদের দিনটি ভালো যাবে (রাশি) — লিখলে তবেই আসে
        val rs = d.rashi.split(Regex("[,،、\\s]+")).filter { it.isNotEmpty() }.take(4)
        if (rs.isNotEmpty()) {
            mnT("আজ যাদের দিনটি ভালো যাবে", 54f, yy + 389, 30f, hex("#222222"), Paint.Align.LEFT, false, 480f)
            var x = 54f
            for (r in rs) {
                p.textSize = 32f; face(true)
                val w = p.measureText(r) + 56
                if (x + w > 535) break
                rrect(x, yy + 417, w, 58f, 29f, ac)
                mnT(r, x + w / 2, yy + 447, 32f, Color.WHITE, Paint.Align.CENTER, true)
                x += w + 20
            }
        }

        // ঢাকার আবহাওয়া
        val w = d.wx
        if (w != null && w.day == MnCal.iso(tdy)) {
            val (cond, ic) = MnCal.wx(w.code)
            rrect(550f, yy + 373, 490f, 107f, 26f, tn)
            mnWxIcon(607f, yy + 430, ic)
            mnT("আজকের আবহাওয়া, ঢাকা", 660f, yy + 396, 23f, hex("#6b6b6b"), Paint.Align.LEFT, false, 360f)
            val tw = mnT(MnCal.n(w.max) + "°সে", 660f, yy + 434, 42f, hex("#111111"), Paint.Align.LEFT, true, 200f)
            mnT(cond, 660f + tw + 14, yy + 436, 28f, ac, Paint.Align.LEFT, true, 1020f - (660f + tw + 14))
            mnT(
                "সর্বনিম্ন " + MnCal.n(w.min) + "°" + (if (w.rain != null) " | বৃষ্টির সম্ভাবনা " + MnCal.n(w.rain) + "%" else ""),
                660f, yy + 466, 22f, hex("#444444"), Paint.Align.LEFT, false, 365f,
            )
        }

        // নিচের বার: লোগো (বা সাইটের নাম) বাঁয়ে, ওয়েবসাইট ডানে
        rect(0f, 1229f, 1080f, 121f, hex("#f6f6f6")); rect(0f, 1229f, 1080f, 2f, hex("#e2e2e2"))
        logoSlot(40f, 1241f, 420f, 98f, 'l', listOf("wide", "icon"), hex("#222222"))
        val site = d.url.replace(Regex("^https?://"), "").trimEnd('/')
        if (site.isNotBlank()) mnT(site, 1040f, 1291f, 31f, hex("#222222"), Paint.Align.RIGHT, true, 520f)
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
