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
fun Layer.isActiveAt(ms: Long): Boolean = ms >= startMs && ms < endMs

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
    val bgColor: Int = RED,
    val sizeFrac: Float = 0.045f,
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
    val bgColor: Int = RED,
    val barHeightFrac: Float = 0.08f,
    val speed: Float = 0.15f,        // প্রতি সেকেন্ডে ফ্রেমের প্রস্থের কত ভগ্নাংশ সরবে
    val bottomFrac: Float = 0f,      // নিচ থেকে কত ওপরে (০ = একদম নিচে)
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
    val accent: Int = RED,
    val bgColor: Int = DARK,
    val textColor: Int = WHITE,
    val bottomFrac: Float = 0.12f,
) : Layer {
    override val kind get() = LayerKind.NAME_BAR
    override fun withTime(startMs: Long, endMs: Long) = copy(startMs = startMs, endMs = endMs)
    override fun withId(id: Long) = copy(id = id)
}

/** বিজ্ঞাপন ছবি: নিচে (isTop = false) বা ওপরে (isTop = true); যত খুশি যোগ করা যাবে */
data class AdLayer(
    override val id: Long,
    override val startMs: Long,
    override val endMs: Long,
    val uri: Uri?,
    val isTop: Boolean = false,
    val heightFrac: Float = 0.18f,   // ফ্রেমের উচ্চতার ভগ্নাংশ, প্রস্থ ছবির অনুপাতে
) : Layer {
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
            LayerKind.LOGO -> ImageLayer(id, startMs, endMs, uri, isLogo = true, x = 0.88f, y = 0.1f, widthFrac = 0.14f)
            LayerKind.TAG -> TagLayer(id, startMs, endMs, text = text)
            LayerKind.TICKER -> TickerLayer(id, startMs, endMs, text = text)
            LayerKind.NAME_BAR -> NameBarLayer(id, startMs, endMs, name = text)
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
        is NameBarLayer -> if (l.name.isBlank() && l.title.isBlank()) "${l.kind.label}: নাম বা পদবি দিন" else null
        is ImageLayer -> if (l.uri == null) "${l.kind.label}: ছবি বাছা হয়নি" else null
        is AdLayer -> if (l.uri == null) "${l.kind.label}: ছবি বাছা হয়নি" else null
        is AudioLayer -> if (l.kind != LayerKind.BEEP && l.uri == null) "${l.kind.label}: অডিও ফাইল নেই" else null
        is OverlayLayer, is AdjustLayer -> null
    }
}
