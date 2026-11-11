package com.digitalcrafting25.newsapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.UUID

/** নতুন পোস্ট ও ফোটোকার্ডের বাড়তি অংশের সাধারণ সহায়ক (ওয়েবের একই নামের ফাংশনের নেটিভ রূপ) */
object PostUtil {
    const val AI_TAG = "AI জেনারেটেড"   // ওয়েবের FC_AI_TAG
    val RW_COST: Int get() = Rates.get("rw")   // AI দিয়ে লেখার খরচ (Worker-এর RW_COST; অ্যাডমিন অ্যাপের 💰 রেট থেকে বদলায়)
    val MP_COST: Int get() = Rates.get("mp")   // নিজে পোস্ট তৈরির খরচ (Worker-এর MP_COST)
    const val SPLIT_AT = 1800           // ওয়েবের SPLIT_AT: এর চেয়ে বড় পেস্ট করা লেখা দুই ভাগে লেখা হয়

    /** Worker-এর sid নিয়ম: ^[a-f0-9]{16,64}$ */
    fun newSid(): String = UUID.randomUUID().toString().replace("-", "")

    fun toDataUrl(b: Bitmap, quality: Int = 90): String {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, quality, o)
        return "data:image/jpeg;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    fun fromDataUrl(s: String): Bitmap? = runCatching {
        val i = s.indexOf("base64,")
        val raw = if (i >= 0) s.substring(i + 7) else s
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** বড় হলে সর্বোচ্চ [maxSide] পিক্সেলে নামিয়ে সাদা ব্যাকগ্রাউন্ডে আঁকে (ওয়েবের setImage) */
    fun shrink(src: Bitmap, maxSide: Int = 1600, ai: Boolean = false): Bitmap {
        val k = minOf(1f, maxSide / maxOf(src.width, src.height).toFloat())
        val w = (src.width * k).toInt().coerceAtLeast(1)
        val h = (src.height * k).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        if (ai) drawBadge(c, w)
        return out
    }

    /** ✓ AI Generated চিহ্ন (ওয়েবের drawBadge) */
    fun drawBadge(c: Canvas, w: Int) {
        val f = maxOf(20, Math.round(w * 0.03f)).toFloat()
        val bh = f * 1.9f; val x = w * 0.025f; val y = w * 0.025f
        val label = "AI Generated"
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = f; isFakeBoldText = true }
        val cr = bh * 0.36f; val cx = x + bh / 2; val cy = y + bh / 2
        val bw = bh / 2 + cr + f * 0.45f + p.measureText(label) + f * 0.8f
        p.color = Color.argb(173, 0, 0, 0)
        c.drawRoundRect(RectF(x, y, x + bw, y + bh), bh / 2, bh / 2, p)
        p.color = Color.parseColor("#22c55e")
        c.drawCircle(cx, cy, cr, p)
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = cr * 0.28f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply {
            moveTo(cx - cr * 0.45f, cy); lineTo(cx - cr * 0.1f, cy + cr * 0.38f); lineTo(cx + cr * 0.5f, cy - cr * 0.38f)
        }
        c.drawPath(path, st)
        p.color = Color.WHITE
        val fm = p.fontMetrics
        c.drawText(label, cx + cr + f * 0.45f, cy - (fm.ascent + fm.descent) / 2, p)
    }

    /** ছবির সারি: প্রতি সারিতে কয়টা (ওয়েবের LAYOUT) */
    private val LAYOUT = mapOf(2 to listOf(2), 3 to listOf(1, 2), 4 to listOf(2, 2), 5 to listOf(2, 3), 6 to listOf(3, 3))

    /** কোলাজের একটি ঘরের ছবি: নিজের জুম (১–৪) ও পজিশন (fx, fy = ০–১) আলাদা */
    data class CollageItem(val bmp: Bitmap, val zoom: Float = 1f, val fx: Float = 0.5f, val fy: Float = 0.5f)

    /** ২–৬টি ছবির ঘরগুলোর মাপ ([s]×[s] ক্যানভাসে)। ২টি ছবির ক্ষেত্রে [vertical] = উপর-নিচ */
    fun collageCells(n: Int, vertical: Boolean, s: Float = 1200f): List<RectF> {
        val rows = if (n == 2 && vertical) listOf(1, 1) else LAYOUT[n] ?: listOf(n)
        val rh = s / rows.size
        val out = ArrayList<RectF>()
        rows.forEachIndexed { ri, cnt ->
            val cw = s / cnt
            for (j in 0 until cnt) out.add(RectF(j * cw, ri * rh, (j + 1) * cw, (ri + 1) * rh))
        }
        return out
    }

    /** প্রতিটি ছবির আলাদা জুম/পজিশন রেখে কোলাজ ([s]×[s]) */
    fun collageItems(items: List<CollageItem>, vertical: Boolean = false, s: Int = 1200): Bitmap {
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        val cells = collageCells(items.size, vertical, s.toFloat())
        items.forEachIndexed { i, it ->
            val r = cells.getOrNull(i) ?: return@forEachIndexed
            drawCover(c, it.bmp, r.left, r.top, r.width(), r.height(), it.fx, it.fy, it.zoom)
        }
        return out
    }

    /** ২–৬টি ছবি মিলিয়ে ১২০০×১২০০ কোলাজ (সব ছবি মাঝখান থেকে, জুম ছাড়া) */
    fun collage(imgs: List<Bitmap>, vertical: Boolean = false): Bitmap =
        collageItems(imgs.map { CollageItem(it) }, vertical)

    /** ছবি ঘর ভরে বসায়। [zoom] > ১ হলে আরও বড় করে, [fx]/[fy] দিয়ে কোন অংশ দেখা যাবে ঠিক হয় */
    fun drawCover(c: Canvas, im: Bitmap, x: Float, y: Float, w: Float, h: Float, fx: Float = 0.5f, fy: Float = 0.5f, zoom: Float = 1f) {
        val k = maxOf(w / im.width, h / im.height) * zoom.coerceAtLeast(1f)
        val dw = im.width * k; val dh = im.height * k
        c.save(); c.clipRect(x, y, x + w, y + h)
        val l = x + (w - dw) * fx; val t = y + (h - dh) * fy
        c.drawBitmap(im, null, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
    }

    fun flipH(b: Bitmap): Bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { preScale(-1f, 1f) }, true)

    /** WordPress-এ যাওয়া ফিচার ইমেজ (ওয়েবের makeFeatured): সেটিংসের মাপ অনুযায়ী */
    fun featured(photo: Bitmap, cfg: CardCfg): String {
        if (cfg.fiMode != "fixed") return toDataUrl(photo, 92)
        val presets = mapOf("1200x630" to (1200 to 630), "1200x675" to (1200 to 675), "1280x720" to (1280 to 720), "1080x1080" to (1080 to 1080), "1200x900" to (1200 to 900))
        val (w, h) = if (cfg.fiPreset == "custom") cfg.fiW.coerceIn(200, 4000) to cfg.fiH.coerceIn(200, 4000)
        else presets[cfg.fiPreset] ?: (1200 to 630)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        if (cfg.fiFit == "cover") drawCover(c, photo, 0f, 0f, w.toFloat(), h.toFloat())
        else c.drawBitmap(photo, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)   // চ্যাপ্টা/লম্বা করে পুরো মাপে
        return toDataUrl(out, 92)
    }

    /** সর্বোচ্চ ২টি ক্যাটাগরি, কমা দিয়ে (ওয়েবের catParts) */
    fun catParts(s: String): List<String> =
        s.split(',', '،', '、').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(2)

    // ───────── লেখা ↔ HTML ─────────
    // এডিটরে সহজ চিহ্ন: ## উপশিরোনাম, • তালিকা, **বোল্ড**, *ইটালিক*, ফাঁকা লাইনে নতুন অনুচ্ছেদ

    private fun unescape(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val tagRe = Regex("(?is)<[^>]+>")
    private val urlRe = Regex("(?i)^https?://\\S+$")
    private val olItemRe = Regex("^[0-9০-৯]+\\.\\s+(.*)$")

    /** Worker-এর body_html → এডিটরের লেখা (ওয়েবের cleanHtml-এর অনুমোদিত ট্যাগ: p h3 ul ol li strong em a br blockquote) */
    fun htmlToEditable(html: String): String {
        var t = html.replace("\r", "")
        t = t.replace(Regex("(?is)<(strong|b)\\b[^>]*>(.*?)</\\1>"), "**$2**")
        t = t.replace(Regex("(?is)<(em|i)\\b[^>]*>(.*?)</\\1>"), "*$2*")
        // লিংক: [লেখা](url) — শুধু http/https থাকলে; নইলে শুধু লেখা
        t = t.replace(Regex("(?is)<a\\b([^>]*)>(.*?)</a>")) { m ->
            val href = unescape(Regex("(?is)href\\s*=\\s*([\"'])(.*?)\\1").find(m.groupValues[1])?.groupValues?.get(2)?.trim().orEmpty())
            val txt = m.groupValues[2].replace(tagRe, "").replace("[", "(").replace("]", ")")
            if (urlRe.matches(href) && txt.isNotBlank()) "[" + txt + "](" + href.replace(")", "%29").replace("\"", "%22") + ")" else m.groupValues[2]
        }
        // উদ্ধৃতি: প্রতি অনুচ্ছেদ "> " দিয়ে; একই উদ্ধৃতির অনুচ্ছেদের মাঝে একলা ">" লাইন
        t = t.replace(Regex("(?is)<blockquote\\b[^>]*>(.*?)</blockquote>")) { m ->
            val paras = m.groupValues[1].split(Regex("(?is)</p>|<br\\s*/?>|</div>")).map { it.replace(tagRe, "").replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
            if (paras.isEmpty()) "" else "\n\n" + paras.joinToString("\n>\n") { "> $it" } + "\n\n"
        }
        // নম্বর তালিকা: "1. লেখা"
        t = t.replace(Regex("(?is)<ol\\b[^>]*>(.*?)</ol>")) { m ->
            val items = Regex("(?is)<li\\b[^>]*>(.*?)</li>").findAll(m.groupValues[1]).map { it.groupValues[1].replace(tagRe, "").replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.toList()
            if (items.isEmpty()) "" else "\n\n" + items.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n") + "\n\n"
        }
        t = t.replace(Regex("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]>"), "\n\n## $1\n\n")
        t = t.replace(Regex("(?is)<li\\b[^>]*>(.*?)</li>"), "\n• $1")
        t = t.replace(Regex("(?is)</?(ul|ol)\\b[^>]*>"), "\n\n")
        t = t.replace(Regex("(?is)<br\\s*/?>"), "\n")
        t = t.replace(Regex("(?is)</p>|</div>"), "\n\n")
        t = t.replace(tagRe, "")
        t = unescape(t)
        t = t.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n")
        return t.trim()
    }

    private fun inl(s: String): String {
        var r = esc(s)
        r = r.replace(Regex("\\[([^\\]\\n]+)\\]\\((https?://[^)\\s\"]+)\\)"), "<a href=\"$2\">$1</a>")
        r = r.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        r = r.replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])"), "<em>$1</em>")
        return r
    }

    /** এডিটরের লেখা → পরিষ্কার HTML (ওয়ার্ডপ্রেসে এটাই যায়; ওয়েবের cleanHtml-এর অনুমোদিত ট্যাগই ব্যবহার) */
    fun editableToHtml(text: String): String {
        val out = StringBuilder()
        fun isH(l: String) = l.startsWith("##")
        fun isUl(l: String) = l.startsWith("•") || l.startsWith("- ")
        fun isOl(l: String) = olItemRe.matches(l)
        fun isQ(l: String) = l.startsWith(">")
        text.replace("\r", "").split(Regex("\\n\\s*\\n")).forEach { blk ->
            val lines = blk.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@forEach
            var i = 0
            while (i < lines.size) {
                val l = lines[i]
                when {
                    isH(l) -> { out.append("<h3>").append(inl(l.trimStart('#').trim())).append("</h3>"); i++ }
                    isUl(l) -> {
                        out.append("<ul>")
                        while (i < lines.size && isUl(lines[i])) {
                            out.append("<li>").append(inl(lines[i].removePrefix("•").removePrefix("-").trim())).append("</li>"); i++
                        }
                        out.append("</ul>")
                    }
                    isOl(l) -> {
                        out.append("<ol>")
                        while (i < lines.size && isOl(lines[i])) {
                            out.append("<li>").append(inl(olItemRe.find(lines[i])!!.groupValues[1].trim())).append("</li>"); i++
                        }
                        out.append("</ol>")
                    }
                    isQ(l) -> {
                        val paras = ArrayList<String>(); var cur = ArrayList<String>()
                        while (i < lines.size && isQ(lines[i])) {
                            val c = lines[i].removePrefix(">").trim()
                            if (c.isEmpty()) { if (cur.isNotEmpty()) { paras.add(cur.joinToString("<br>")); cur = ArrayList() } } else cur.add(inl(c))
                            i++
                        }
                        if (cur.isNotEmpty()) paras.add(cur.joinToString("<br>"))
                        out.append("<blockquote>").append(paras.joinToString("") { "<p>$it</p>" }).append("</blockquote>")
                    }
                    else -> {
                        val para = ArrayList<String>()
                        while (i < lines.size && !isH(lines[i]) && !isUl(lines[i]) && !isOl(lines[i]) && !isQ(lines[i])) { para.add(lines[i]); i++ }
                        out.append("<p>").append(para.joinToString("<br>") { inl(it) }).append("</p>")
                    }
                }
            }
        }
        return out.toString()
    }

    /** লম্বা লেখা মাঝামাঝি ভেঙে দুই ভাগ (ওয়েবের splitText) */
    fun splitText(s: String): Pair<String, String> {
        val mid = s.length / 2.0
        fun cut(re: Regex): Int {
            var best = -1; var bd = 1e9
            re.findAll(s).forEach { m -> val pos = m.range.last + 1; val d = Math.abs(pos - mid); if (d < bd) { bd = d; best = pos } }
            return best
        }
        var i = cut(Regex("\\n\\s*\\n"))
        if (i < s.length * 0.25 || i > s.length * 0.75) i = cut(Regex("\\n"))
        if (i < s.length * 0.25 || i > s.length * 0.75) i = cut(Regex("[।?!]\\s+"))
        if (i < 1 || i >= s.length) i = mid.toInt()
        return s.substring(0, i).trim() to s.substring(i).trim()
    }

    /** দ্বিতীয় অংশে আগের অংশের মতো অনুচ্ছেদ থাকলে বাদ (ওয়েবের mergeBodies); এডিটর-লেখার ওপর চলে */
    fun mergeBodies(a: String, b: String): String {
        fun norm(t: String) = Regex("[\\u0980-\\u09FFa-z0-9]+").findAll(t.lowercase()).map { it.value }.toSet()
        val seen = a.split(Regex("\\n\\s*\\n")).map { norm(it) }.toMutableList()
        val keep = ArrayList<String>()
        b.split(Regex("\\n\\s*\\n")).forEach { para ->
            val w = norm(para)
            fun sim(x: Set<String>, y: Set<String>): Double =
                if (x.isEmpty() || y.isEmpty()) 0.0 else x.count { it in y }.toDouble() / minOf(x.size, y.size)
            if (w.isNotEmpty() && seen.any { sim(w, it) >= 0.75 }) return@forEach
            seen.add(w); keep.add(para)
        }
        return (a.trim() + "\n\n" + keep.joinToString("\n\n")).trim()
    }

    fun bn(n: Number): String = if (Lang.isEn) n.toString() else n.toString().map { if (it in '0'..'9') "০১২৩৪৫৬৭৮৯"[it - '0'] else it }.joinToString("")
}
