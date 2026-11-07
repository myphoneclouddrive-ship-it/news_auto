package com.digitalcrafting25.newsapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ক্লিপের জোড়ার ট্রানজিশন — HTML-এর TR_LIST / transFx()-এর মতো সাতটা, কাটের ±০.২৮ সেকেন্ডে।
 * Clip.trans = এই ক্লিপ ও পরের ক্লিপের জোড়ায় কোনটা (খালি = কোনোটি নয়); শেষ ক্লিপে কিছু হয় না।
 * প্রিভিউ (VideoEditorScreen) ও রেন্ডার (LayerFrameRenderer + RenderComposer) একই হিসাব ব্যবহার করে।
 */
object ClipTr {
    const val HALF_MS = 280L

    /** (কী, আইকন, নাম) — HTML TR_LIST-এর ক্রমে */
    val LIST: List<Triple<String, String, String>> = listOf(
        Triple("fade", "⚫", "ফেড (কালো)"),
        Triple("white", "⚪", "সাদা ঝলক"),
        Triple("wipe", "➡️", "ওয়াইপ"),
        Triple("slide", "📤", "স্লাইড"),
        Triple("zoom", "🔍", "জুম পাঞ্চ"),
        Triple("iris", "⭕", "গোল পর্দা"),
        Triple("blinds", "🪟", "ব্লাইন্ডস"),
    )

    fun valid(k: String): Boolean = LIST.any { it.first == k }
    fun label(k: String): String = LIST.firstOrNull { it.first == k }?.let { "${it.second} ${it.third}" } ?: "কোনোটি নয়"

    data class Cut(val ms: Long, val mode: String)

    /** টাইমলাইনের কাটের সময় ও ট্রানজিশন (শেষ ক্লিপের পরে কিছু নেই) */
    fun cuts(clips: List<Clip>): List<Cut> {
        if (clips.size < 2) return emptyList()
        val out = ArrayList<Cut>()
        var acc = 0L
        for (i in 0 until clips.size - 1) {
            acc += clips[i].lengthMs()
            val m = clips[i].trans
            if (m.isNotEmpty() && valid(m)) out += Cut(acc, m)
        }
        return out
    }

    /** ms-এ কোনো ট্রানজিশন চললে (মোড, u): u = (ms − কাট) ÷ ০.২৮ সে., −১..১ (HTML-এর মতো প্রথম মিলটাই) */
    fun at(cuts: List<Cut>, ms: Long): Pair<String, Float>? {
        for (c in cuts) {
            val d = ms - c.ms
            if (d > -HALF_MS && d < HALF_MS) return c.mode to d / HALF_MS.toFloat()
        }
        return null
    }

    private fun ee(x: Float) = x * x * (3f - 2f * x)

    /** স্লাইড: প্রস্থের কত অংশ ডানে সরবে (বাঁয়ে হলে ঋণাত্মক)। কাটের আগে পুরনো অংশ বাঁয়ে বেরোয়, পরে নতুন অংশ ডান থেকে ঢোকে */
    fun slideFrac(u: Float): Float {
        val a = 1f - abs(u)
        return (if (u < 0f) -1f else 1f) * ee(a)
    }

    /** জুম পাঞ্চ: কেন্দ্র ঘিরে স্কেল (কাটে ১.৬) */
    fun zoomScale(u: Float): Float = 1f + 0.6f * ee(1f - abs(u))

    /** রেন্ডারের জন্য ম্যাট্রিক্স (NDC: x ডানে, −১..১) — শুধু স্লাইড ও জুমে */
    fun matrixFor(mode: String, u: Float): Matrix? = when (mode) {
        "slide" -> Matrix().apply { setTranslate(2f * slideFrac(u), 0f) }
        "zoom" -> Matrix().apply { val s = zoomScale(u); setScale(s, s) }
        else -> null
    }

    private fun p(color: Int, alpha: Float = 1f) = Paint().apply {
        this.color = color
        this.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    }

    /**
     * ভিডিওর ওপরের ওভারলে অংশ (w×h ফ্রেমে): ফেড/সাদা ঝলক/ওয়াইপ/গোল পর্দা/ব্লাইন্ডস,
     * আর জুম পাঞ্চের অন্ধকার। স্লাইডে কিছু আঁকা হয় না (শুধু ভিডিও সরে)।
     */
    fun drawOverlay(cv: Canvas, w: Float, h: Float, mode: String, u: Float) {
        val a = 1f - abs(u)
        when (mode) {
            "wipe" -> {
                val black = p(Color.BLACK)
                if (u < 0f) cv.drawRect(0f, 0f, w * (1f + u), h, black) else cv.drawRect(w * u, 0f, w, h, black)
                val ex = if (u < 0f) w * (1f + u) else w * u
                cv.drawRect(ex - max(2f, w * 0.004f), 0f, ex - max(2f, w * 0.004f) + max(3f, w * 0.008f), h, p(Color.WHITE, 0.85f))
            }
            "slide" -> {}
            "zoom" -> cv.drawRect(0f, 0f, w, h, p(Color.BLACK, a * a * 0.9f))
            "iris" -> {
                val r = hypot(w, h) / 2f * ee(1f - a)
                val path = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addRect(0f, 0f, w, h, Path.Direction.CW)
                    addCircle(w / 2f, h / 2f, r, Path.Direction.CW)
                }
                cv.drawPath(path, p(Color.BLACK))
            }
            "blinds" -> {
                val n = 8
                val bh = h / n
                val cov = min(1f, ee(a) * 1.05f)
                val black = p(Color.BLACK)
                for (k in 0 until n) cv.drawRect(0f, k * bh, w, k * bh + bh * cov + (if (cov > 0f) 0.6f else 0f), black)
            }
            else -> cv.drawRect(0f, 0f, w, h, p(if (mode == "white") Color.WHITE else Color.BLACK, a))
        }
    }
}

/**
 * পরপর দুই ব্যানারের মাঝে ট্রানজিশন (HTML adState/adDrawGen-এর `downup`: আগেরটা নিচে নামে, তারপর নতুনটা নিচ থেকে ওঠে)।
 * শুধু ব্যানারের পট্টিতে, ভিডিওতে নয়। একই সারি (ওপর/নিচ), আগেরটার শেষ ও নতুনটার শুরুর ফাঁক ০.২ সে.-এর কম হলে।
 * সময় = min(১ সে., নতুনটার দৈর্ঘ্য÷২, আগেরটার দৈর্ঘ্য÷২), কমপক্ষে ০.১ সে.।
 */
object AdTr {
    class State(val prev: AdLayer, val k: Float)

    fun at(all: List<Layer>, l: AdLayer, ms: Long): State? {
        if (l.uri == null || ms < l.startMs) return null
        var prev: AdLayer? = null
        for (o in all) if (o is AdLayer && o.id != l.id && o.isTop == l.isTop && o.uri != null && abs(o.endMs - l.startMs) < 200L) prev = o
        val p = prev ?: return null
        val d = max(100L, min(1000L, min((l.endMs - l.startMs) / 2, (p.endMs - p.startMs) / 2)))
        val k = ((ms - l.startMs) / d.toFloat()).coerceIn(0f, 1f)
        return if (k >= 1f) null else State(p, k)
    }

    /** (আগেরটা দেখাবে কিনা, পট্টির উচ্চতার কত অংশ নিচে সরে থাকবে) */
    fun shift(k: Float): Pair<Boolean, Float> {
        fun e(q: Float) = if (q < 0.5f) 2f * q * q else 1f - (-2f * q + 2f).let { it * it } / 2f
        return if (k < 0.5f) true to e(k * 2f) else false to (1f - e((k - 0.5f) * 2f))
    }
}
