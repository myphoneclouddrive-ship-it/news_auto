package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * রেন্ডারের সময় প্রতিটা ফ্রেমের জন্য লেয়ারগুলো একটা স্বচ্ছ পুরো-ফ্রেম ছবিতে আঁকে।
 * মাপ/অবস্থান `LayerOverlay` (প্রিভিউ)-এর মতোই ফ্রেমের ভগ্নাংশে, তাই প্রিভিউ আর ভিডিও মেলে।
 * প্রিভিউয়ে যেসব মাপ dp-তে (প্যাডিং ইত্যাদি) সেগুলো এখানে ফ্রেমের উচ্চতার আনুপাতিক (u)।
 *
 * - সক্রিয় লেয়ারের সেট না বদলালে (আর স্ক্রলিং লেখা না থাকলে) আগের ছবিই ফেরত যায়, নতুন করে আঁকা হয় না
 * - দুটো বিটম্যাপ পালাক্রমে ব্যবহার হয়, যাতে Media3 বোঝে ছবি বদলেছে
 */
class LayerFrameRenderer(
    ctx: Context,
    private val w: Int,
    private val h: Int,
    private val base: Bitmap?,      // পুরোনো শিরোনাম-বার/লোগো (সবসময় আছে), না থাকলে null
    allLayers: List<Layer>,
    private val repeatStopMs: Long = Long.MAX_VALUE,
    // ফেইসবুক ব্যান্ড: ক্যানভাস canvasH উচ্চতার, লেয়ারগুলো yOff নিচে সরে ১২৮০×h ফ্রেমের ভেতরে আঁকা হয়; বাকি জায়গা সাদা ব্যান্ড
    private val canvasH: Int = h,
    private val yOff: Int = 0,
    private val fbTop: String = "",
    private val fbBottom: String = "",
    private val fbColor: Int = Color.BLACK,
    // ইন্ট্রো/আউট্রো কার্ডের সময়: ওখানে স্ক্রলিং লেখা/বিজ্ঞাপন/ট্যাগ/লোগো এবং পুরোনো লোগো-বার আঁকা হয় না (HTML-এর মতো)
    private val cardRanges: List<LongRange> = emptyList(),
) {
    private val layers: List<Layer> = allLayers.filterNot { it.isAudio }
    private val images = HashMap<Long, Bitmap>()
    private val bufs = arrayOf(
        Bitmap.createBitmap(w, canvasH, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(w, canvasH, Bitmap.Config.ARGB_8888),
    )
    private var cur = -1
    private var lastKey: List<Long>? = null
    private var lastInCard = false
    private val u = h / 220f

    val isEmpty: Boolean get() = layers.isEmpty() && base == null && yOff == 0

    init {
        val maxSide = max(w, h)
        for (l in layers) {
            val uri = when (l) {
                is ImageLayer -> l.uri
                is AdLayer -> l.uri
                else -> null
            }
            if (uri != null) decode(ctx, uri, maxSide)?.let { images[l.id] = it }
        }
    }

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val iw = info.size.width
            val ih = info.size.height
            val s = max(iw, ih)
            if (s > maxSide) {
                val f = maxSide / s.toFloat()
                dec.setTargetSize(max(1, (iw * f).roundToInt()), max(1, (ih * f).roundToInt()))
            }
        }
    }.getOrNull()

    /** টাইমলাইনের ms-এ ফ্রেমের ওপরের ছবি */
    fun frameAt(ms: Long): Bitmap {
        val active = layers.filter { it.isActiveAt(ms, repeatStopMs, cardRanges) }
        val key = active.map { it.id }
        val inCard = cardRanges.any { ms in it }
        val animated = active.any { it is TickerLayer || it is NameBarLayer }   // নাম-বারের স্লাইড ফ্রেমে ফ্রেমে বদলায়
        if (!animated && cur >= 0 && key == lastKey && inCard == lastInCard) return bufs[cur]

        cur = if (cur == 0) 1 else 0
        lastKey = key
        lastInCard = inCard
        val bmp = bufs[cur]
        bmp.eraseColor(Color.TRANSPARENT)
        val cv = Canvas(bmp)
        if (yOff > 0) {
            // ইন্ট্রো/আউট্রোতে ব্যান্ড নেই (কার্ড পুরো ক্যানভাস জুড়ে); বাকি সময় সাদা ব্যান্ড + লেখা
            if (!inCard) {
                cv.drawRect(0f, 0f, w.toFloat(), yOff.toFloat(), fill(Color.WHITE))
                cv.drawRect(0f, (yOff + h).toFloat(), w.toFloat(), canvasH.toFloat(), fill(Color.WHITE))
                drawBandText(cv, fbTop, 0f, yOff.toFloat())
                drawBandText(cv, fbBottom, (yOff + h).toFloat(), yOff.toFloat())
            }
            cv.save()
            cv.translate(0f, yOff.toFloat())
        }
        if (!inCard) base?.let { cv.drawBitmap(it, 0f, 0f, null) }
        val (topInset, bottomBase) = HtmlBrand.insets(active, h.toFloat())
        for (l in active) {
            when (l) {
                is TextLayer -> drawText(cv, l)
                is TagLayer -> HtmlBrand.drawTag(cv, w.toFloat(), h.toFloat(), l, topInset, bottomBase)
                is ImageLayer -> drawImage(cv, l, topInset, bottomBase)
                is AdLayer -> drawAd(cv, l)
                is TickerLayer -> HtmlBrand.drawTicker(cv, w.toFloat(), h.toFloat(), l, ms, active)
                is NameBarLayer -> HtmlBrand.drawNameBar(cv, w.toFloat(), h.toFloat(), l, ms, bottomBase)
                is OverlayLayer -> cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill(l.color, l.alpha))
                // ব্রাইটনেস: সাদা/কালোর স্বচ্ছ আস্তরণ দিয়ে আনুমানিক (ধাপ ৭-এ আরও ভালো করা যাবে)
                is AdjustLayer -> if (l.brightness != 0f) {
                    val c = if (l.brightness > 0f) Color.WHITE else Color.BLACK
                    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill(c, kotlin.math.abs(l.brightness)))
                }
                is AudioLayer -> {}
            }
        }
        if (yOff > 0) cv.restore()
        return bmp
    }

    /** ব্যান্ডের লেখা (HTML ftxt): সর্বোচ্চ ২ লাইন, মাঝে, বোল্ড; ১ লাইনে ব্যান্ডের ০.৪২, ২ লাইনে ০.৩ মাপ; চওড়া হলে ০.৯৪ প্রস্থে আঁটা পর্যন্ত ছোট */
    private fun drawBandText(cv: Canvas, text: String, top: Float, bandH: Float) {
        val ls = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(2)
        if (ls.isEmpty()) return
        val tp = textPaint(fbColor, bandH * (if (ls.size > 1) 0.3f else 0.42f), true).apply { textAlign = Paint.Align.CENTER }
        while (tp.textSize > 10f && ls.maxOf { tp.measureText(it) } > w * 0.94f) tp.textSize -= 2f
        val lh = tp.textSize * 1.2f
        val fm = tp.fontMetrics
        val first = top + bandH / 2f - lh * (ls.size - 1) / 2f
        ls.forEachIndexed { i, line ->
            // textBaseline 'middle'-এর কাছাকাছি: বেসলাইন = কেন্দ্র − (ascent+descent)/2
            cv.drawText(line, w / 2f, first + i * lh - (fm.ascent + fm.descent) / 2f, tp)
        }
    }

    private fun fill(color: Int, alpha: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (alpha < 1f) this.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
    }

    private fun textPaint(color: Int, size: Float, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun drawText(cv: Canvas, l: TextLayer) {
        if (l.text.isEmpty()) return
        val tp = textPaint(l.color, h * l.sizeFrac, l.bold)
        val padH = 4 * u
        val padV = 2 * u
        val maxTextW = (w * 0.95f - 2 * padH).coerceAtLeast(1f)
        val tw = ceil(min(StaticLayout.getDesiredWidth(l.text, tp), maxTextW)).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(l.text, 0, l.text.length, tp, tw)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        val bw = tw + 2 * padH
        val bh = layout.height + 2 * padV
        val left = w * l.x - bw / 2f
        val top = h * l.y - bh / 2f
        cv.save()
        if (l.rotation != 0f) cv.rotate(l.rotation, w * l.x, h * l.y)
        if (l.bgColor != 0) cv.drawRect(left, top, left + bw, top + bh, fill(l.bgColor))
        cv.save()
        cv.translate(left + padH, top + padV)
        layout.draw(cv)
        cv.restore()
        cv.restore()
    }

    private fun drawImage(cv: Canvas, l: ImageLayer, topInset: Float, bottomBase: Float) {
        val b = images[l.id] ?: return
        val r = HtmlBrand.logoRect(w.toFloat(), h.toFloat(), l, b.width, b.height, topInset, bottomBase)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (l.opacity.coerceIn(0f, 1f) * 255).roundToInt()
        }
        cv.save()
        if (l.rotation != 0f) cv.rotate(l.rotation, r.centerX(), r.centerY())
        cv.drawBitmap(b, null, r, p)
        cv.restore()
    }

    /** HTML strip()/fit()-এর মতো: পুরো প্রস্থের সাদা পট্টি (w × উচ্চতা), ছবি তার ভেতরে অনুপাত ঠিক রেখে মাঝে বসে — দুই পাশ ফাঁকা থাকে না */
    private fun drawAd(cv: Canvas, l: AdLayer) {
        val b = images[l.id] ?: return
        val sh = h * l.heightFrac
        val top = if (l.isTop) 0f else h - sh
        cv.drawRect(0f, top, w.toFloat(), top + sh, Paint().apply { color = android.graphics.Color.WHITE })
        val r = minOf(w / b.width.toFloat(), sh / b.height.toFloat())
        val dw = b.width * r
        val dh = b.height * r
        val left = (w - dw) / 2f
        val y = top + (sh - dh) / 2f
        cv.drawBitmap(b, null, RectF(left, y, left + dw, y + dh), Paint(Paint.FILTER_BITMAP_FLAG))
    }
}

/** Media3-র জন্য: প্রতিটা ফ্রেমের সময়ে `LayerFrameRenderer` থেকে ছবি নেয় */
@UnstableApi
class LayerFrameOverlay(private val renderer: LayerFrameRenderer) : BitmapOverlay() {
    override fun getBitmap(presentationTimeUs: Long): Bitmap = renderer.frameAt(presentationTimeUs / 1000L)
}
