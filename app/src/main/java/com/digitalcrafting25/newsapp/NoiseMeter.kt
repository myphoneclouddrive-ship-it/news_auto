package com.digitalcrafting25.newsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * নয়েজ ক্যানসেলের লাইভ মিটার: প্রিভিউ প্লেয়ার প্লে চলার সময় প্রতি ফ্রেমে এখানে লেখে,
 * এডিটর প্যানেল পড়ে — আগে (আসল) বনাম পরে (নয়েজ কমার পর) শব্দের জোর (RMS, ০..১)।
 * দুটো আলাদা মিটার: ক্লিপের নিজের শব্দ, আর (সর্বশেষ চলা) অডিও লেয়ার।
 */
object NoiseMeter {
    // ক্লিপের নিজের শব্দ
    var clipIn by mutableFloatStateOf(0f)
        private set
    var clipOut by mutableFloatStateOf(0f)
        private set
    // অডিও লেয়ার
    var layerId by mutableLongStateOf(0L)
        private set
    var layerIn by mutableFloatStateOf(0f)
        private set
    var layerOut by mutableFloatStateOf(0f)
        private set

    fun setClip(i: Float, o: Float) { clipIn = i; clipOut = o }
    fun setLayer(id: Long, i: Float, o: Float) { layerId = id; layerIn = i; layerOut = o }
    fun clear() { clipIn = 0f; clipOut = 0f; layerId = 0L; layerIn = 0f; layerOut = 0f }

    /** RMS → ০..১ বার (−৬০dB..০dB) */
    fun bar(rms: Float): Float {
        if (rms <= 0.000_01f) return 0f
        return ((20f * Math.log10(rms.toDouble()).toFloat() + 60f) / 60f).coerceIn(0f, 1f)
    }

    /** কত dB কমেছে (ধনাত্মক = কমেছে) */
    fun reductionDb(i: Float, o: Float): Float {
        if (i <= 0.000_01f) return 0f
        val oo = o.coerceAtLeast(0.000_01f)
        return (20f * Math.log10((i / oo).toDouble()).toFloat()).coerceIn(0f, 60f)
    }
}
