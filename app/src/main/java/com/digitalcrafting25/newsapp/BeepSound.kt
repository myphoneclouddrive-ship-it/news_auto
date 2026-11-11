package com.digitalcrafting25.newsapp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 🔔 বিপ — HTML-এর `makeBeep()`-এর হুবহু একই শব্দ:
 *  ১০০০ Hz সাইন, দৈর্ঘ্য ০.৬ সেকেন্ড, জোর ০.৬, শুরুতে ০.০০৬ সে. ফেড-ইন, শেষে ০.০৪ সে. ফেড-আউট।
 * প্রিভিউ (AudioTrack) ও রেন্ডার (AudioMixer) দুই জায়গাই এই একই ফাংশন থেকে স্যাম্পল নেয়।
 * HTML-এর মতো বিপের দৈর্ঘ্য ০.৬ সেকেন্ডের বেশি হয় না (বাফারের দৈর্ঘ্যেই সীমা)।
 */
object BeepSound {
    const val SR = 44100
    const val FREQ = 1000.0
    const val DUR = 0.6
    const val LEN_MS = 600L

    /** সময় t (সেকেন্ড) এ বিপের মান (-0.6..0.6); ০.৬ সে. পেরোলে বা t < 0 হলে ০ */
    fun sample(t: Double): Double {
        if (t < 0.0 || t >= DUR) return 0.0
        val env = min(1.0, t / 0.006) * min(1.0, ((DUR - t) / 0.04).coerceAtLeast(0.0))
        return sin(2.0 * PI * FREQ * t) * 0.6 * env
    }

    val pcm: ShortArray by lazy {
        val n = (SR * DUR).roundToInt()
        ShortArray(n) { i -> (sample(i / SR.toDouble()) * 32767.0).roundToInt().toShort() }
    }

    /** নতুন AudioTrack (static); offsetMs থেকে বাজা শুরু হবে। ফেরত পাওয়ার পর `play()` ডাকতে হবে */
    fun newTrack(volume: Float, offsetMs: Long = 0L): AudioTrack? = runCatching {
        val data = pcm
        val tr = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        tr.write(data, 0, data.size)
        tr.setVolume(volume.coerceIn(0f, 1f))
        val off = (offsetMs * SR / 1000L).toInt()
        if (off in 1 until data.size) tr.setPlaybackHeadPosition(off)
        tr
    }.getOrNull()

    /** বিপ বসানোর মুহূর্তে একবার শোনানো (HTML: ক্লিকেই বিপ শোনা যায়) */
    fun playOnce() {
        val tr = newTrack(1f) ?: return
        runCatching { tr.play() }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { tr.stop() }
            runCatching { tr.release() }
        }, LEN_MS + 250L)
    }
}
