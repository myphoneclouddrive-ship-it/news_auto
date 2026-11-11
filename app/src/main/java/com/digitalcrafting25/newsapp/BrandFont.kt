package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Typeface

/**
 * HTML-এর মতো Noto Sans Bengali (assets/fonts/NotoSansBengali.ttf, ভ্যারিয়েবল ফন্ট)।
 * ট্যাগ, নাম/পদবি, স্ক্রলিং লেখা ও লেখা লেয়ারে প্রিভিউ ও রেন্ডার একই ফন্ট পায়। ফাইল না মিললে সিস্টেম ফন্ট।
 */
object BrandFont {
    private var app: Context? = null
    private val cache = HashMap<Int, Typeface>()

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** weight: ৪০০ সাধারণ, ৫০০ মাঝারি, ৭০০ বোল্ড */
    fun get(weight: Int): Typeface = synchronized(cache) {
        cache.getOrPut(weight) {
            val c = app
            if (c == null) fallback(weight) else runCatching {
                Typeface.Builder(c.assets, "fonts/NotoSansBengali.ttf")
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            }.getOrNull() ?: fallback(weight)
        }
    }

    private fun fallback(weight: Int): Typeface =
        if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    fun family(bold: Boolean) = androidx.compose.ui.text.font.FontFamily(get(if (bold) 700 else 500))
}
