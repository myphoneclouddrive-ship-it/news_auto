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
)

sealed interface RenderStatus {
    data object Idle : RenderStatus
    data class Running(val progress: Int) : RenderStatus
    data class Done(val uri: Uri) : RenderStatus
    data class Failed(val message: String) : RenderStatus
}

/** এডিটর UI আর RenderService-এর মধ্যে যোগাযোগ (একই প্রসেস) */
object RenderBus {
    @Volatile var spec: RenderSpec? = null
    val status = MutableStateFlow<RenderStatus>(RenderStatus.Idle)
}

object AppState {
    @Volatile var foreground = false
}
