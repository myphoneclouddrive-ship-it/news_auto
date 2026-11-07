package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * সেটিংসে সেভ করা বিজ্ঞাপন ও লোগো/আইকন ভিডিও এডিটরে কাজে লাগানোর সেতু (HTML-এর vcAdsBridge / vcLogoBridge)।
 * বিজ্ঞাপন ১০৮০×১২০ PNG হয়ে ড্রাফট-ফোল্ডারের মতো স্থায়ী ফাইলে যায়, তাই লেয়ার/ড্রাফটে Uri টিকে থাকে।
 */
object SettingsMedia {
    class LogoOpt(val key: String, val label: String, val bmp: Bitmap)

    private fun dir(ctx: Context) = File(ctx.filesDir, "settings_media").apply { mkdirs() }

    /** সেভ করা, ব্যবহারযোগ্য বিজ্ঞাপন (নেট বা সেটিংস না পেলে ফাঁকা তালিকা — তখন ছবি বাছাই চলে) */
    suspend fun ads(ctx: Context): List<Ad> = withContext(Dispatchers.IO) {
        runCatching { Fs.adsCfg(ctx).first.filter { it.ok } }.getOrDefault(emptyList())
    }

    /** সেটিংসের লোগো, আইকন ও সাইটের নাম (HTML vcLogoBridge.options); ডিফল্ট = তালিকার প্রথমটা: লোগো, না থাকলে আইকন, না থাকলে সাইটের নাম */
    suspend fun logos(ctx: Context): List<LogoOpt> = withContext(Dispatchers.IO) {
        runCatching {
            val c = Fs.cardCfg(ctx)
            buildList {
                AdRender.decode(c.logo)?.let { add(LogoOpt("logo", "লোগো", it)) }
                AdRender.decode(c.icon)?.let { add(LogoOpt("icon", "আইকন", it)) }
                if (c.siteName.isNotBlank()) add(LogoOpt("text", "সাইটের নাম", siteNameBitmap(c.siteName.trim())))
            }
        }.getOrDefault(emptyList())
    }

    /** বিজ্ঞাপন আঁকা PNG ফাইলের Uri (একই বিজ্ঞাপন আবার চাইলে নতুন করে আঁকে, কারণ সেটিংসে বদলে যেতে পারে) */
    suspend fun adUri(ctx: Context, ad: Ad): Uri = withContext(Dispatchers.IO) {
        save(File(dir(ctx), "ad_${ad.id}.png"), AdRender.render(ad, EDITOR_AD_W, EDITOR_AD_H))
    }

    suspend fun logoUri(ctx: Context, o: LogoOpt): Uri = withContext(Dispatchers.IO) {
        save(File(dir(ctx), "logo_${o.key}.png"), o.bmp)
    }

    /**
     * "সাইটের নাম (লেখা)" লোগো — HTML `render('text')`-এর মতো: স্বচ্ছ ব্যাকগ্রাউন্ড, সাদা বোল্ড লেখা (১২০px),
     * ছায়া (ঝাপসা ১৪, নিচে ৩), বাঁয়ে ৩০px ফাঁক; চওড়া = লেখা + ৬০, উচ্চতা = ১২০ × ১.৫।
     */
    private fun siteNameBitmap(name: String): Bitmap {
        val fs = 120f
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = BrandFont.get(700); textSize = fs; color = android.graphics.Color.WHITE
            setShadowLayer(14f, 0f, 3f, android.graphics.Color.argb(191, 0, 0, 0))
        }
        val tw = Math.ceil(p.measureText(name).toDouble()).toInt()
        val h = Math.round(fs * 1.5f)
        val bmp = Bitmap.createBitmap(tw + 60, h, Bitmap.Config.ARGB_8888)
        val fm = p.fontMetrics
        android.graphics.Canvas(bmp).drawText(name, 30f, h / 2f - (fm.ascent + fm.descent) / 2f, p)
        return bmp
    }

    /** HTML `vcPlaceholderAd`: "বিজ্ঞাপন এরিয়া ১২৮০×১০৮ px" লেখা ডিফল্ট ব্যানার (ধূসর পট, ড্যাশ বর্ডার) */
    private fun placeholderAd(): Bitmap {
        val w = EDITOR_AD_W; val h = EDITOR_AD_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.parseColor("#E2E8F0"))
        val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f
            color = android.graphics.Color.parseColor("#64748B")
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 9f), 0f)
        }
        c.drawRect(6f, 6f, w - 6f, h - 6f, line)
        val t = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = BrandFont.get(700); textSize = Math.round(h * 0.42f).toFloat()
            color = android.graphics.Color.parseColor("#334155"); textAlign = android.graphics.Paint.Align.CENTER
        }
        val fm = t.fontMetrics
        c.drawText("বিজ্ঞাপন এরিয়া ${w}×${h} px", w / 2f, h / 2f + 2f - (fm.ascent + fm.descent) / 2f, t)
        return bmp
    }

    /** ডিফল্ট ব্যানারের PNG Uri (সেটিংসে বিজ্ঞাপন না থাকলেও চলে) */
    suspend fun placeholderAdUri(ctx: Context): Uri = withContext(Dispatchers.IO) {
        save(File(dir(ctx), "ad_placeholder.png"), placeholderAd())
    }

    private fun save(f: File, bmp: Bitmap): Uri {
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }
}
