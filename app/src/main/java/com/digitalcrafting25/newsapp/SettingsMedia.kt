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

    /** সেটিংসের লোগো ও আইকন; ডিফল্ট = লোগো, না থাকলে আইকন (তালিকার প্রথমটা) */
    suspend fun logos(ctx: Context): List<LogoOpt> = withContext(Dispatchers.IO) {
        runCatching {
            val c = Fs.cardCfg(ctx)
            buildList {
                AdRender.decode(c.logo)?.let { add(LogoOpt("logo", "লোগো", it)) }
                AdRender.decode(c.icon)?.let { add(LogoOpt("icon", "আইকন", it)) }
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

    private fun save(f: File, bmp: Bitmap): Uri {
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }
}
