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
)

data class RenderSpec(
    val clips: List<Clip>,
    val aspect: Aspect,
    val caption: String,
    val logo: Uri?,
    val music: Uri?,
    val musicVolume: Float,
    val keepOriginalAudio: Boolean,
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
