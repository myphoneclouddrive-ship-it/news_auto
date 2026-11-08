package com.digitalcrafting25.newsapp

import android.net.Uri

/**
 * ভিডিওর ওপরের লেয়ার (HTML এডিটরের "🧩 লেয়ার" মেনুর মতো)।
 * সময় সব টাইমলাইনের পরম মিলিসেকেন্ডে (০ = পুরো ভিডিওর শুরু)।
 * অবস্থান/মাপ ফ্রেমের ভগ্নাংশে (০..১), যাতে ১৬:৯ / ৯:১৬ / ১:১ সব মাপেই একই দেখায়।
 * লিস্টের পরের লেয়ার ওপরে আঁকা হয় (z-order = লিস্টের ক্রম)।
 */
enum class LayerKind(val label: String, val isAudio: Boolean = false) {
    TEXT("🔤 লেখা"),
    IMAGE("🖼️ ছবি"),
    LOGO("🏷️ লোগো"),
    TAG("📍 ট্যাগ"),
    TICKER("📜 স্ক্রলিং লেখা"),
    NAME_BAR("👤 নাম/পদবি"),
    AD_BOTTOM("🖼️ নিচের বিজ্ঞাপন"),
    AD_TOP("🏞️ ওপরের বিজ্ঞাপন"),
    OVERLAY("🎨 ওভারলে"),
    ADJUST("☀️ এডজাস্ট"),
    AUDIO("🎵 অডিও", true),
    VOICE("🎙️ ভয়েস", true),
    BEEP("🔔 বিপ", true),
}

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private val WHITE = 0xFFFFFFFF.toInt()
private val BLACK = 0xFF000000.toInt()
private val RED = 0xFFD32F2F.toInt()
private val DARK = 0xFF1B1E26.toInt()

sealed interface Layer {
    val id: Long
    val kind: LayerKind
    val startMs: Long
    val endMs: Long
    fun withTime(startMs: Long, endMs: Long): Layer
    fun withId(id: Long): Layer
}

val Layer.isAudio: Boolean get() = kind.isAudio
val Layer.lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
/**
 * নাম/পদবির বারের প্রথম দেখানোর সময় (টাইমলাইনের ms)। HTML nameDraw-এর মতো:
 * "আবার দেখান" চালু (repeatSec > 0) থাকলে লেয়ারের নিজের শুরু ধরা হয় না, ইন্ট্রোসহ ১০ সেকেন্ডে প্রথম আসে
 * (ভিডিও ছোট হলে min(১০ সে., মোট − দৈর্ঘ্য)); বন্ধ থাকলে লেয়ারের নিজের startMs।
 * totalMs < 0 হলে (মোট দৈর্ঘ্য জানা নেই) ১০ সেকেন্ড ধরা হয়।
 */
fun NameBarLayer.firstShowMs(totalMs: Long = -1L): Long {
    if (repeatSec <= 0) return startMs
    val du = (endMs - startMs).coerceAtLeast(0L)
    return if (totalMs < 0L) 10_000L else minOf(10_000L, maxOf(0L, totalMs - du))
}

/**
 * লেয়ার এই মুহূর্তে চালু কিনা। নাম/পদবির বারে repeatSec > 0 হলে: প্রথমবার firstShowMs-এ (ইন্ট্রোসহ ১০ সেকেন্ডে; দৈর্ঘ্য = endMs - startMs),
 * তারপর প্রতি max(repeatSec, দৈর্ঘ্য+১) সেকেন্ডে আবার; stopMs (আউট্রো শুরুর ১৫ সেকেন্ড আগে) থেকে আর আসে না — HTML nameDraw-এর নিয়ম।
 * totalMs = টাইমলাইনের মোট দৈর্ঘ্য (প্রথম দেখানোর সময়ের হিসাবে লাগে)।
 */
fun Layer.isActiveAt(ms: Long, stopMs: Long = Long.MAX_VALUE, cards: List<LongRange> = emptyList(), totalMs: Long = -1L): Boolean {
    if (cards.isNotEmpty() && hiddenInCard() && cards.any { ms in it }) return false
    // লোগো লেয়ার নয়, HTML-এর মতো সেটিং: ইন্ট্রো/আউট্রো ছাড়া পুরো ভিডিওতে সবসময় থাকে (শুরু/শেষ সময় ধরা হয় না)
    if (isSingletonLayer()) return true
    if (this is NameBarLayer && repeatSec > 0) {
        val du = endMs - startMs
        val st = firstShowMs(totalMs)
        if (du <= 0L || ms < st || ms >= maxOf(st, stopMs)) return false
        val period = maxOf(repeatSec * 1000L, du + 1000L)
        return (ms - st) % period < du
    }
    return ms >= startMs && ms < endMs
}

/**
 * ইন্ট্রো/আউট্রো কার্ডের সময়ের পরিসর (টাইমলাইনের ms)। HTML-এর মতো ওই অংশে পুরো ফ্রেম কার্ডের,
 * তাই স্ক্রলিং লেখা, বিজ্ঞাপন, ট্যাগ ও আলাদা লোগো আসে না (`hiddenInCard`)।
 */
fun cardRangesMs(clips: List<Clip>): List<LongRange> {
    val out = ArrayList<LongRange>()
    var acc = 0L
    for (c in clips) {
        val len = c.lengthMs()
        if (c.card != null && len > 0L) out += acc until acc + len
        acc += len
    }
    return out
}

/** HTML draw()-এর isOut নিয়ম: ইন্ট্রো/আউট্রো অংশে স্ক্রলিং লেখা, ওপর-নিচের বিজ্ঞাপন, ট্যাগ ও লোগো থাকে না */
/** লোগো (টাইমলাইনে আলাদা বার নেই; পুরো ভিডিওতে থাকে) */
fun Layer.isLogoLayer(): Boolean = this is ImageLayer && isLogo

/** HTML-এর মতো একটাই, টাইমলাইনে বার নেই, ইন্ট্রো/আউট্রো ছাড়া পুরো ভিডিওতে: লোগো ও স্ক্রলিং লেখা */
fun Layer.isSingletonLayer(): Boolean = isLogoLayer() || this is TickerLayer

fun Layer.hiddenInCard(): Boolean = this is TickerLayer || this is AdLayer || this is TagLayer || (this is ImageLayer && isLogo)

/** "আবার দেখান"-এর শেষ সীমা: আউট্রো শুরুর ১৫ সেকেন্ড আগে (আউট্রো না থাকলে ভিডিওর শেষের ১৫ সেকেন্ড আগে) */
fun repeatStopMs(clips: List<Clip>): Long {
    var acc = 0L
    for (c in clips) {
        if (c.card?.outro == true) return (acc - 15_000L).coerceAtLeast(0L)
        acc += c.lengthMs()
    }
    return (acc - 15_000L).coerceAtLeast(0L)
}

/** ভিডিওর ওপরে বসানো লেখা */
data class TextLayer(
    override val id: Long,
    override val startMs: Long,
    override val endMs: Long,
    val text: String = "",
    val color: Int = WHITE,
    val bgColor: Int = 0,            // ০ = ব্যাকগ্রাউন্ড নেই
    val sizeFrac: Float = 0.07f,     // ফ্রেমের উচ্চতার ভগ্নাংশ
    val bold: Boolean = true,
    val x: Float = 0.5f,             // কেন্দ্রের অবস্থান, ফ্রেমের ভগ্নাংশ
    val y: Float = 0.5f,
    val rotation: Float = 0f,        // ডিগ্রি (-১৮০..১৮০), কেন্দ্র ঘিরে ঘড়ির কাঁটার দিকে +
) : Layer {
    override val kind get() = LayerKind.TEXT
    override fun withTime(startMs: Long, endMs: Long) = copy(startMs = startMs, endMs = endMs)
    override fun withId(id: Long) = copy(id = id)
}

/** ছবি বা লোগো (isLogo = true হলে LOGO) */
data class ImageLayer(
    override val id: Long,
    override val startMs: Long,
    override val endMs: Long,
    val uri: Uri?,
    val isLogo: Boolean = false,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val widthFrac: Float = 0.25f,    // ফ্রেমের প্রস্থের ভগ্নাংশ, উচ্চতা ছবির অনুপাতে
    val opacity: Float = 1f,         // ০..১
    val rotation: Float = 0f,        // ডিগ্রি (-১৮০..১৮০), কেন্দ্র ঘিরে ঘড়ির কাঁটার দিকে +
    val corner: Corner? = null,      // শুধু লোগো: HTML-এর মতো কোণা (ফাঁক = প্রস্থের ২%, ওপরের/নিচের বিজ্ঞাপন-টিকার এড়িয়ে); null = নিজে টেনে বসানো (x, y)
) : Layer {
    override val kind get() = if (isLogo) LayerKind.LOGO else LayerKind.IMAGE
    override fun withTime(startMs: Long, endMs: Long) = copy(startMs = startMs, endMs = endMs)
    override fun withId(id: Long) = copy(id = id)
}

/** ভিডিওর কোণায় ছোট ট্যাগ লেখা (যেমন: রংপুর) */
data class TagLayer(
    override val id: Long,
    override val startMs: Long,
    override val endMs: Long,
    val text: String = "",
    val corner: Corner = Corner.TOP_LEFT,
    val color: Int = WHITE,
    val bgColor: Int = BLACK,        // HTML: ব্যাকগ্রাউন্ড ডিফল্ট কালো (৬০% স্বচ্ছ গ্রেডিয়েন্ট)
    val sizeFrac: Float = 0.03f,     // HTML: ফ্রেমের প্রস্থের % (৩–১০)
) : Layer {
    override val kind get() = LayerKind.TAG
    override fun withTime(startMs: Long, endMs: Long) = copy(startMs = startMs, endMs = endMs)
    override fun withId(id: Long) = copy(id = id)
}

/** নিচের বারে ডান থেকে বামে স্ক্রল করা লেখা (টিকার) */
data class TickerLayer(
    override val id: Long,
    override val startMs: Long,
    override val endMs: Long,
    val text: String = "",
    val color: Int = WHITE,
    val bgColor: Int = 0xFFC62828.toInt(),   // HTML ডিফল্ট ব্যাকগ্রাউন্ড #c62828
    val barHeightFrac: Float = 0.0595f, // HTML: h × ০.০৮৫ × মাপ(৭০)/১০০
    val speed: Float = 0.12f,        // HTML: প্রস্থ × ০.০৩ × গতি(৪) = প্রতি সেকেন্ডে প্রস্থের ভগ্নাংশ
    val bottomFrac: Float = 0f,      // নিচের বিজ্ঞাপনের ওপর থেকে অতিরিক্ত কত উঁচুতে (০ = বিজ্ঞাপনের ঠিক ওপরে, বিজ্ঞাপন না থাকলে একদম নিচে)
) : Layer {
    override val kind get() = LayerKind.TICKER
    override fun withTime(startMs: Long, endMs: Long) = copy(startMs = startMs, endMs = endMs)
    override fun withId(id: Long) = copy(id = id)
}

/** নাম ও পদবির বার */
data class NameBarLayer(
    override val id: Long,
    override val startMs: Long,
    override val endMs: Long,
    val name: String = "",
    val title: String = "",
    val accent: Int = 0xFF2563EB.toInt(),   // HTML ডিফল্ট দাগের রং #2563eb
    val bgColor: Int = DARK,             // এখন ব্যবহার হয় না (HTML-এ পট্টি সবসময় কালো ৭৮%); পুরোনো ড্রাফটের জন্য রাখা
    val textColor: Int = WHITE,
    val bottomFrac: Float = 0.12f,
    val repeatSec: Int = 0,          // ০ = একবার (লেয়ারের নিজের শুরুতে); >০ হলে HTML-এর মতো ইন্ট্রোসহ ১০ সেকেন্ডে প্রথম আসে, তারপর প্রতি এত সেকেন্ডে (শুরু থেকে শুরু) আবার ("আবার দেখান")
    val sizeFrac: Float = 0.11f,     // HTML size: বারের উচ্চতা = ফ্রেম উচ্চতার ১১%
    val liftFrac: Float = 0f,        // HTML lift: ওপরে তোলা (ফ্রেম উচ্চতার ভগ্নাংশ)
    val right: Boolean = false,      // HTML side: ডান পাশ থেকে
    val fade: Boolean = true,        // HTML fade: স্লাইড/ফেড ইন-আউট
) : Layer {
    override val kind get() = LayerKind.NAME_BAR
    override fun withTime(startMs: Long, endMs: Long) = copy(startMs = startMs, endMs = endMs)
    override fun withId(id: Long) = copy(id = id)
}

/** ভিডিও এডিটরের ব্যানার: ১২৮০×৭২০ ফ্রেমে ঠিক ১২৮০×১০৮ (HTML VC_AD_W/VC_AD_H); যেকোনো মাপের ছবি এই মাপে বসে */
const val EDITOR_AD_W = 1280
const val EDITOR_AD_H = 108
const val EDITOR_FRAME_H = 720
const val EDITOR_AD_FRAC = EDITOR_AD_H / EDITOR_FRAME_H.toFloat()   // = ০.১৫

/**
 * বর্তমান ফ্রেমের অনুপাত (চওড়া ÷ উঁচু): পোস্ট = ১২৮০×৭২০ (১৬:৯), রিলস = ৭২০×১২৮০ (৯:১৬)।
 * ব্যানারের উচ্চতা প্রস্থের সাথে বাঁধা (১২৮০ প্রস্থে ১০৮), তাই রিলসে ব্যানার বিকৃত না হয়ে সরু হয়।
 */
object FrameAspect {
    @Volatile var ratio: Float = 1280f / 720f
    fun set(a: Aspect) { ratio = a.w / a.h.toFloat() }
}

/** বিজ্ঞাপন ছবি: নিচে (isTop = false) বা ওপরে (isTop = true); যত খুশি যোগ করা যাবে */
data class AdLayer(
    override val id: Long,
    override val startMs: Long,
    override val endMs: Long,
    val uri: Uri?,
    val isTop: Boolean = false,
    val adId: String = "",          // সেটিংসের কোন বিজ্ঞাপন (খালি = নিজের ছবি); নতুন বিজ্ঞাপনে আগে না-বসা গুলো বসে
) : Layer {
    /** উচ্চতা স্থির: ফ্রেমের ১৫% (১২৮০×৭২০-তে ঠিক ১০৮px); বদলানো যায় না */
    val heightFrac: Float get() = EDITOR_AD_H / EDITOR_AD_W.toFloat() * FrameAspect.ratio
    override val kind get() = if (isTop) LayerKind.AD_TOP else LayerKind.AD_BOTTOM
    override fun withTime(startMs: Long, endMs: Long) = copy(startMs = startMs, endMs = endMs)
    override fun withId(id: Long) = copy(id = id)
}

/** পুরো ফ্রেমে রঙের আস্তরণ বা ছায়া */
data class OverlayLayer(
    override val id: Long,
    override val startMs: Long,
    override val endMs: Long,
    val color: Int = BLACK,
    val alpha: Float = 0.35f,        // ০..১
) : Layer {
    override val kind get() = LayerKind.OVERLAY
    override fun withTime(startMs: Long, endMs: Long) = copy(startMs = startMs, endMs = endMs)
    override fun withId(id: Long) = copy(id = id)
}

/** ব্রাইটনেস কমানো/বাড়ানো */
data class AdjustLayer(
    override val id: Long,
    override val startMs: Long,
    override val endMs: Long,
    val brightness: Float = 0f,      // -১..+১
    val contrast: Float = 0f,        // -১..+১ (HTML: -১০০..১০০; ০ = স্বাভাবিক)
) : Layer {
    override val kind get() = LayerKind.ADJUST
    override fun withTime(startMs: Long, endMs: Long) = copy(startMs = startMs, endMs = endMs)
    override fun withId(id: Long) = copy(id = id)
}

/**
 * অডিও লেয়ার: ব্যাকগ্রাউন্ড মিউজিক (AUDIO), ভয়েস রেকর্ড (VOICE) বা বিপ (BEEP, uri = null)।
 * sourceClipId != null মানে ক্লিপ থেকে এক্সট্রাক্ট করা অডিও।
 */
data class AudioLayer(
    override val id: Long,
    override val startMs: Long,
    override val endMs: Long,
    override val kind: LayerKind = LayerKind.AUDIO,
    val uri: Uri? = null,
    val name: String = "",
    val volume: Float = 1f,          // ০..২ (২ = ২০০%)
    val sourceOffsetMs: Long = 0L,   // অডিও ফাইলের কোন জায়গা থেকে বাজা শুরু
    val sourceClipId: Long? = null,
    val muted: Boolean = false,      // এই লেয়ারের শব্দ বন্ধ
    val sourceDurationMs: Long = 0L, // সোর্স ফাইলের পুরো দৈর্ঘ্য (০ = অজানা/বিপ)
    val fadeInMs: Long = 0L,         // শুরুতে ধীরে জোরালো (০/১০০০/২০০০)
    val fadeOutMs: Long = 0L,        // শেষে ধীরে মিলিয়ে যাওয়া (০/১০০০/২০০০)
    val denoise: Boolean = false,    // হালকা নয়েজ কমানো (৯০Hz হাই-পাস + নয়েজ গেট), প্রিভিউ ও রেন্ডার দুই জায়গাতেই
    val denoiseAmt: Float = 1f,      // নয়েজ কমানোর পরিমাণ ০..১ (denoise চালু থাকলে)
) : Layer {
    override fun withTime(startMs: Long, endMs: Long) = copy(startMs = startMs, endMs = endMs)
    override fun withId(id: Long) = copy(id = id)
}

/** নতুন লেয়ারের ডিফল্ট সময় ও মান */
object LayerDefaults {
    const val MIN_LAYER_MS = 300L
    private const val SHORT_MS = 3000L
    private const val AD_MS = 5000L

    /** নতুন লেয়ারের (শুরু, শেষ)। লোগো/টিকার/ওভারলে/এডজাস্ট পুরো ভিডিও জুড়ে, বিজ্ঞাপন ৫ সেকেন্ড, বাকি ৩ সেকেন্ড */
    fun range(kind: LayerKind, playheadMs: Long, totalMs: Long): Pair<Long, Long> {
        val full = when (kind) {
            LayerKind.LOGO, LayerKind.TICKER, LayerKind.OVERLAY, LayerKind.ADJUST -> true
            else -> false
        }
        if (full) return 0L to totalMs
        val len = if (kind == LayerKind.AD_BOTTOM || kind == LayerKind.AD_TOP) AD_MS else SHORT_MS
        val start = playheadMs.coerceIn(0L, (totalMs - MIN_LAYER_MS).coerceAtLeast(0L))
        val end = minOf(totalMs, start + len).coerceAtLeast(start + MIN_LAYER_MS)
        return start to end
    }

    fun create(kind: LayerKind, id: Long, startMs: Long, endMs: Long, uri: Uri? = null, text: String = ""): Layer =
        when (kind) {
            LayerKind.TEXT -> TextLayer(id, startMs, endMs, text = text)
            LayerKind.IMAGE -> ImageLayer(id, startMs, endMs, uri)
            LayerKind.LOGO -> ImageLayer(id, startMs, endMs, uri, isLogo = true, x = 0.88f, y = 0.1f, widthFrac = 0.09f, corner = Corner.TOP_RIGHT)   // ওপরে ডানে, প্রস্থের ৯% (HTML-এ ছিল ৬%, একটু বড় করা হয়েছে)
            LayerKind.TAG -> TagLayer(id, startMs, endMs, text = text)
            LayerKind.TICKER -> TickerLayer(id, startMs, endMs, text = text)
            LayerKind.NAME_BAR -> NameBarLayer(id, startMs, endMs, name = text, title = "আপনার পদবি")
            LayerKind.AD_BOTTOM -> AdLayer(id, startMs, endMs, uri, isTop = false)
            LayerKind.AD_TOP -> AdLayer(id, startMs, endMs, uri, isTop = true)
            LayerKind.OVERLAY -> OverlayLayer(id, startMs, endMs)
            LayerKind.ADJUST -> AdjustLayer(id, startMs, endMs)
            LayerKind.AUDIO, LayerKind.VOICE, LayerKind.BEEP ->
                AudioLayer(id, startMs, endMs, kind = kind, uri = uri, name = text)
        }

    /** রেন্ডারের আগে যাচাই; সমস্যা থাকলে বাংলা বার্তা, ঠিক থাকলে null */
    fun validate(l: Layer): String? = when (l) {
        is TextLayer -> if (l.text.isBlank()) "${l.kind.label}: লেখা খালি" else null
        is TagLayer -> if (l.text.isBlank()) "${l.kind.label}: লেখা খালি" else null
        is TickerLayer -> if (l.text.isBlank()) "${l.kind.label}: লেখা খালি" else null
        is NameBarLayer -> if (l.name.isBlank()) "${l.kind.label}: নাম দিন" else null
        is ImageLayer -> if (l.uri == null) "${l.kind.label}: ছবি বাছা হয়নি" else null
        is AdLayer -> if (l.uri == null) "${l.kind.label}: ছবি বাছা হয়নি" else null
        is AudioLayer -> if (l.kind != LayerKind.BEEP && l.uri == null) "${l.kind.label}: অডিও ফাইল নেই" else null
        is OverlayLayer, is AdjustLayer -> null
    }
}
