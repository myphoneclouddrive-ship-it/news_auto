package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * রেডি ইন্ট্রো / আউট্রো: একটা সাজানো ছবি-কার্ড (PNG) বানিয়ে ছবি-ক্লিপ হিসেবে টাইমলাইনের শুরু/শেষে বসানো হয়।
 * তাই প্রিভিউ, রেন্ডার, সময় বদলানো, ফ্লিপ/জুম সব আগের ছবি-ক্লিপের মতোই চলে।
 * কার্ডের লেখা/স্টাইল `CardSpec`-এ ক্লিপের সাথে থাকে, তাই পরে এডিট করা যায়।
 */
enum class CardStyle(val label: String) {
    RED("🔴 লাল খবর"),
    BLUE("🔵 নীল"),
    GOLD("🟡 ডার্ক গোল্ড"),
    LIGHT("⚪ সাদা"),
}

data class CardSpec(
    val outro: Boolean,
    val style: CardStyle = CardStyle.RED,
    val title: String = "",
    val subtitle: String = "",
    val logo: Uri? = null,   // থাকলে কার্ডের মাঝখানে লেখার ওপরে বসে
)

object CardDefaults {
    const val SEC = 3f
    fun intro(caption: String, logo: Uri?) = CardSpec(
        outro = false,
        title = caption.trim().ifEmpty { "আপনার চ্যানেলের নাম" },
        subtitle = "সর্বশেষ খবর",
        logo = logo,
    )

    fun outro(logo: Uri?) = CardSpec(
        outro = true,
        title = "ধন্যবাদ",
        subtitle = "লাইক · শেয়ার · সাবস্ক্রাইব করুন",
        logo = logo,
    )
}

object CardMaker {

    private class Palette(val bg1: Int, val bg2: Int, val accent: Int, val text: Int, val sub: Int)

    private fun palette(s: CardStyle) = when (s) {
        CardStyle.RED -> Palette(0xFF1A0505.toInt(), 0xFF5A0F0F.toInt(), 0xFFE53935.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFCDD2.toInt())
        CardStyle.BLUE -> Palette(0xFF0B1B3A.toInt(), 0xFF1E4FA3.toInt(), 0xFF4FC3F7.toInt(), 0xFFFFFFFF.toInt(), 0xFFBBDEFB.toInt())
        CardStyle.GOLD -> Palette(0xFF0D0D0D.toInt(), 0xFF1C1710.toInt(), 0xFFFFB300.toInt(), 0xFFFFFFFF.toInt(), 0xFFE0C98A.toInt())
        CardStyle.LIGHT -> Palette(0xFFFFFFFF.toInt(), 0xFFEFEFEF.toInt(), 0xFFD32F2F.toInt(), 0xFF111111.toInt(), 0xFF555555.toInt())
    }

    /**
     * কার্ড বানিয়ে `filesDir/cards/`-এ সেভ করে ফাইল-Uri দেয়। একই লেখা/স্টাইল/মাপ হলে আগের ফাইলই ফেরত যায়
     * (তাই রেন্ডারের সময় আবার ডাকলেও খরচ নেই)।
     */
    fun make(ctx: Context, spec: CardSpec, w: Int, h: Int): Uri {
        val dir = File(ctx.filesDir, "cards").apply { mkdirs() }
        val key = Integer.toHexString(
            listOf(spec.outro, spec.style, spec.title, spec.subtitle, spec.logo?.toString()).hashCode(),
        )
        val f = File(dir, "card_${key}_${w}x$h.png")
        if (!f.exists() || f.length() == 0L) {
            val bmp = draw(ctx, spec, w, h)
            try {
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bmp.recycle()
            }
        }
        return Uri.fromFile(f)
    }

    private fun draw(ctx: Context, spec: CardSpec, w: Int, h: Int): Bitmap {
        val p = palette(spec.style)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val unit = minOf(w, h).toFloat()

        // পটভূমি + হালকা গোল নকশা
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), p.bg1, p.bg2, Shader.TileMode.CLAMP) }
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val deco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
        deco.alpha = 38
        cv.drawCircle(w * 0.92f, h * 0.08f, unit * 0.5f, deco)
        deco.alpha = 26
        cv.drawCircle(w * 0.04f, h * 0.96f, unit * 0.36f, deco)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
        cv.drawRect(0f, 0f, w.toFloat(), unit * 0.018f, edge)
        cv.drawRect(0f, h - unit * 0.018f, w.toFloat(), h.toFloat(), edge)

        val textW = (w * 0.8f).toInt().coerceAtLeast(1)
        val title = spec.title.trim()
        val sub = spec.subtitle.trim()

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.text
            textSize = unit * 0.09f
            typeface = Typeface.DEFAULT_BOLD
        }
        val titleLayout = if (title.isEmpty()) null else
            StaticLayout.Builder.obtain(title, 0, title.length, tp, textW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.sub
            textSize = unit * 0.045f
        }
        val subLayout = if (sub.isEmpty()) null else
            StaticLayout.Builder.obtain(sub, 0, sub.length, sp, textW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

        val logo = spec.logo?.let { loadScaled(ctx, it, (unit * 0.28f).roundToInt()) }
        val gap = unit * 0.035f
        val barH = unit * 0.012f
        var blockH = 0f
        if (logo != null) blockH += logo.height + gap
        if (titleLayout != null) blockH += titleLayout.height + gap
        if (titleLayout != null && subLayout != null) blockH += barH + gap
        if (subLayout != null) blockH += subLayout.height
        var y = (h - blockH) / 2f

        if (logo != null) {
            cv.drawBitmap(logo, (w - logo.width) / 2f, y, Paint(Paint.FILTER_BITMAP_FLAG))
            y += logo.height + gap
            logo.recycle()
        }
        if (titleLayout != null) {
            cv.save()
            cv.translate((w - textW) / 2f, y)
            titleLayout.draw(cv)
            cv.restore()
            y += titleLayout.height + gap
        }
        if (titleLayout != null && subLayout != null) {
            val bw = unit * 0.16f
            cv.drawRoundRect(RectF((w - bw) / 2f, y, (w + bw) / 2f, y + barH), barH, barH, edge)
            y += barH + gap
        }
        if (subLayout != null) {
            cv.save()
            cv.translate((w - textW) / 2f, y)
            subLayout.draw(cv)
            cv.restore()
        }
        return bmp
    }

    /** লোগো ছবি পড়ে বড় দিক maxSide-এর মধ্যে আনে; না পড়া গেলে null (কার্ড লোগো ছাড়াই হয়) */
    private fun loadScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = max(info.size.width, info.size.height)
            if (s > maxSide) {
                val f = maxSide / s.toFloat()
                dec.setTargetSize(max(1, (info.size.width * f).roundToInt()), max(1, (info.size.height * f).roundToInt()))
            }
        }
    }.getOrNull()
}

/**
 * ছবির থাম্বনেইল: গ্যালারির content-Uri হলে `loadThumbnail`, না হলে (যেমন কার্ডের ফাইল-Uri) `ImageDecoder`।
 * বড় দিক px-এর মধ্যে থাকে, অনুপাত বজায় রাখে।
 */
fun loadImageThumb(ctx: Context, uri: Uri, px: Int): Bitmap? {
    if (uri.scheme != "file") {
        runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()?.let { return it }
    }
    return runCatching {
        val src = if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path ?: return null))
        else ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = max(info.size.width, info.size.height)
            if (s > px) {
                val f = px / s.toFloat()
                dec.setTargetSize(max(1, (info.size.width * f).roundToInt()), max(1, (info.size.height * f).roundToInt()))
            }
        }
    }.getOrNull()
}
