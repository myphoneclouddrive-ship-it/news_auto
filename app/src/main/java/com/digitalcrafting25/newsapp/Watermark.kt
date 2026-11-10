package com.digitalcrafting25.newsapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ওয়াটারমার্ক আঁকা — প্রিভিউ (Compose Canvas-এর nativeCanvas) ও রেন্ডার (LayerFrameRenderer) দুই জায়গায় একই কোড।
 * মাপ সব ফ্রেমের ছোট দিক (min(w, h)) ধরে, তাই ১৬:৯ / ৯:১৬ / ১:১ সব মাপে একই আকারের দেখায়।
 */
object WatermarkDraw {
    /**
     * @param selectColor 0 না হলে এই রঙে বাছা-লেয়ারের ফ্রেম আঁকে (শুধু প্রিভিউতে; রেন্ডারে ০)
     */
    fun draw(cv: Canvas, w: Float, h: Float, l: WatermarkLayer, selectColor: Int = 0) {
        val t = l.text.replace('\n', ' ').trim()
        if (t.isEmpty()) return
        val hasBg = l.bgColor != 0
        var fs = max(8f, min(w, h) * l.sizeFrac)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = BrandFont.get(if (l.bold) 700 else 500)
            textAlign = Paint.Align.CENTER
        }
        // লেখা + পট্টি ফ্রেমের ৯৪% প্রস্থে না আঁটলে সেই পর্যন্ত ছোট করা (ঘোরানো থাকলে ছোট করা হয় না)
        var padX = 0f
        var padY = 0f
        var tw = 0f
        fun measure() {
            tp.textSize = fs
            padX = if (hasBg) fs * 0.6f else fs * 0.1f
            padY = if (hasBg) fs * 0.3f else fs * 0.05f
            tw = tp.measureText(t)
        }
        measure()
        val maxW = w * 0.94f
        if (l.rotation == 0f && tw + 2 * padX > maxW) {
            fs = max(8f, fs * maxW / (tw + 2 * padX))
            measure()
        }
        val fm = tp.fontMetrics
        val bw = tw + 2 * padX
        val bh = (fm.descent - fm.ascent) + 2 * padY
        val cx = w * l.x
        val cy = h * l.y
        val r = RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)

        cv.save()
        if (l.rotation != 0f) cv.rotate(l.rotation, cx, cy)
        // স্বচ্ছতা লেখা ও পট্টি একসাথে (আলাদা আলাদা দিলে ওভারল্যাপে গাঢ় দেখায়)
        cv.saveLayerAlpha(0f, 0f, w, h, (l.opacity.coerceIn(0.05f, 1f) * 255).roundToInt())
        if (hasBg) {
            val rad = if (l.round) bh / 2f else fs * 0.1f
            cv.drawRoundRect(r, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.bgColor })
        }
        tp.color = l.color
        if (l.shadow) tp.setShadowLayer(fs * 0.12f, 0f, fs * 0.05f, Color.argb(190, 0, 0, 0))
        cv.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
        cv.restore()   // saveLayerAlpha
        if (selectColor != 0) {
            cv.drawRect(r, Paint().apply {
                color = selectColor; style = Paint.Style.STROKE; strokeWidth = 2f
            })
        }
        cv.restore()
    }
}
