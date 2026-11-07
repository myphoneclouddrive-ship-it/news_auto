package com.digitalcrafting25.newsapp

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

enum class Aspect(val label: String, val w: Int, val h: Int, val bitrate: Int) {
    LANDSCAPE("১৬:৯ (ইউটিউব)", 1280, 720, 6_000_000),
    PORTRAIT("৯:১৬ (রিল/শর্টস)", 720, 1280, 6_000_000),
    SQUARE("১:১ (ফেসবুক)", 1080, 1080, 8_000_000),
}

data class Clip(
    val id: Long,
    val uri: Uri,
    val isImage: Boolean,
    val durationMs: Long,
    val name: String,
    val startSec: Float = 0f,   // ভিডিও ট্রিম শুরু
    val endSec: Float = 0f,     // ০ = শেষ পর্যন্ত
    val imageSec: Float = 3f,   // ছবি কত সেকেন্ড দেখাবে
    val volume: Float = 1f,     // ক্লিপের আসল শব্দের ভলিউম ০..২ (২ = ২০০%)
    val muted: Boolean = false, // এই ক্লিপের আসল শব্দ বন্ধ
    // ধাপ ৬: ক্লিপের ছবি-রূপান্তর (প্রিভিউ ও রেন্ডার দুই জায়গায় একই নিয়মে)
    val flipH: Boolean = false, // ডান-বাম উল্টানো
    val flipV: Boolean = false, // ওপর-নিচ উল্টানো
    val zoom: Float = 1f,       // ১..৪ (১ = জুম নেই); ফ্রেমের কেন্দ্র থেকে বড় করে
    val panX: Float = 0f,       // -১..১; জুম করা ছবির ভেতরে দেখার জানালা বাঁ(-১) ↔ ডান(+১)
    val panY: Float = 0f,       // -১..১; ওপর(-১) ↔ নিচ(+১)
    val trans: String = "",   // এই ক্লিপ ও পরের ক্লিপের জোড়ার ট্রানজিশন (ClipTr.LIST-এর কী; খালি = নেই)
    val card: CardSpec? = null, // ধাপ ৭: রেডি ইন্ট্রো/আউট্রো কার্ড হলে তার লেখা/স্টাইল (ছবি-ক্লিপ হিসেবেই চলে)
)

/** ফ্লিপ বা জুম আছে কিনা (না থাকলে রেন্ডারে কোনো বাড়তি ইফেক্ট বসে না) */
fun Clip.hasTransform(): Boolean = flipH || flipV || zoom > 1.001f

data class RenderSpec(
    val clips: List<Clip>,
    val aspect: Aspect,
    val caption: String,
    val logo: Uri?,
    val music: Uri?,
    val musicVolume: Float,
    val keepOriginalAudio: Boolean,
    val layers: List<Layer> = emptyList(),   // সব লেয়ার: ভিজ্যুয়াল (ধাপ ৪) + অডিও (ধাপ ৫)
    // ফেইসবুক ব্যানার এরিয়া: fbBandPx > 0 হলে ক্যানভাস ১২৮০×(৭২০+২×fbBandPx); ভিডিও মাঝে, ওপরে-নিচে সাদা ব্যান্ডে লেখা
    val fbTop: String = "",
    val fbBottom: String = "",
    val fbColor: Int = 0xFF000000.toInt(),
    val fbBandPx: Int = 0,
    // true হলে বিজ্ঞাপন (ব্যানার) ভিডিওর ভেতরে নয়, ওপরে-নিচের সাদা ব্যান্ডে বসে (HTML-এর ফেইসবুক পাস)
    val fbAdsInBand: Boolean = false,
)

/** একটা রেন্ডার পাস (HTML pbPass): mode "fb" = ফেইসবুক (ব্যান্ডসহ হতে পারে), "yt" = ইউটিউব ১৬:৯ */
data class RenderPass(val mode: String, val spec: RenderSpec) {
    val label: String get() = if (mode == "fb") "ফেইসবুক" else "ইউটিউব"
}

/** রেন্ডার হয়ে গ্যালারিতে সেভ হওয়া একটা ভিডিও */
data class RenderOutput(val mode: String, val uri: Uri, val w: Int, val h: Int) {
    val label: String get() = if (mode == "fb") "ফেইসবুক" else "ইউটিউব"
}

sealed interface RenderStatus {
    data object Idle : RenderStatus
    /** progress = সব পাস মিলিয়ে ০..১০০; pass/passCount = কত নম্বর ভিডিও (HTML: "ফেইসবুক ভিডিও রেন্ডার হচ্ছে (১/২)") */
    data class Running(val progress: Int, val pass: Int = 1, val passCount: Int = 1, val label: String = "") : RenderStatus
    /** outputs: ফেইসবুক আগে, তারপর ইউটিউব (HTML-এর মতো) */
    data class Done(val outputs: List<RenderOutput>) : RenderStatus {
        /** মূল ভিডিও: ফেইসবুক থাকলে সেটা, না হলে প্রথমটা (HTML vcAfterRender-এর `main`) */
        val main: RenderOutput get() = outputs.firstOrNull { it.mode == "fb" } ?: outputs.first()
        val uri: Uri get() = main.uri
    }
    data class Failed(val message: String) : RenderStatus
}

/** এডিটর UI আর RenderService-এর মধ্যে যোগাযোগ (একই প্রসেস) */
object RenderBus {
    /** পরপর যে ভিডিওগুলো রেন্ডার হবে (ফেইসবুক + ইউটিউব দুটো টিক দিলে দুটো) */
    @Volatile var passes: List<RenderPass> = emptyList()
    val status = MutableStateFlow<RenderStatus>(RenderStatus.Idle)
}

object AppState {
    @Volatile var foreground = false
}
