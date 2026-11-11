package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * রেডি ইন্ট্রো / আউট্রো: অ্যানিমেটেড ভিডিও-কার্ড (সাউন্ডসহ), বানায় `CardVideoMaker` (CardVideo.kt)।
 * টাইমলাইনে এটা সাধারণ ভিডিও-ক্লিপ হিসেবে বসে (সাথে `Clip.card`-এ লেখা/স্টাইল থাকে, তাই পরে এডিট করা যায়)।
 * দৈর্ঘ্য HTML-এর চেয়ে ১ সেকেন্ড বেশি: ইন্ট্রো ৩.৫ সে., আউট্রো ৫.৮ সে.।
 */
enum class CardStyle(val label: String) {
    GLOBAL("🌍 গ্লোবাল নিউজ"),
    STUDIO("🎙️ স্টুডিও লাইট"),
    MARKET("📈 মার্কেট ডেটা"),
    BURST("💥 ব্রেকিং বার্স্ট"),
    NEWSPRINT("📰 নিউজপ্রিন্ট"),
    CLEAN("⚪ ক্লিন হোয়াইট"),
}

data class CardSpec(
    val outro: Boolean,
    val style: CardStyle = CardStyle.GLOBAL,
    val title: String = "",       // ইন্ট্রো: "বড় লেখা" (আউট্রোর লেখা ফিক্সড)
    val subtitle: String = "",    // ইন্ট্রো: "নিচের ছোট লেখা"
    val logo: Uri? = null,        // থাকলে ইন্ট্রোর ওপরে / আউট্রোর মাঝে বসে
    val w: Int = 0,               // যে মাপে কার্ডের ভিডিও বানানো (রেন্ডারে মাপ আলাদা হলে আবার বানানো হয়)
    val h: Int = 0,
)

object CardDefaults {
    const val INTRO_HEAD = "ব্রেকিং নিউজ"
    const val INTRO_SUB = "সর্বশেষ সংবাদ • সবার আগে"

    fun intro(caption: String, logo: Uri?) = CardSpec(
        outro = false,
        title = caption.trim().ifEmpty { INTRO_HEAD },
        subtitle = INTRO_SUB,
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

    /** লোগো ছবি পড়ে বড় দিক maxSide-এর মধ্যে আনে; না পড়া গেলে null (কার্ড লোগো ছাড়াই হয়) */
    internal fun loadScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
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
