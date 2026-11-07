package com.digitalcrafting25.newsapp

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * হালকা নয়েজ কমানো (HTML denoise()-এর স্ট্রিমিং রূপ): ৯০Hz হাই-পাস ফিল্টার + ২০ মি.সে. ফ্রেমে নয়েজ গেট।
 * HTML-এ পুরো ফাইলের ১০% পার্সেন্টাইল দিয়ে "ফ্লোর" ধরা হয়; এখানে স্ট্রিমে চলমান সর্বনিম্ন এনার্জি ধরে ফ্লোর ঠিক হয়।
 * কথার নিচের নয়েজ পুরো যায় না, শুধু চুপ জায়গার হিস/গুঞ্জন কমে।
 *
 * (ধাপ ৩-এর ৬) আগে এটা AudioMixer-এর ভেতরে private ছিল, শুধু রেন্ডারে চলত।
 * এখন আলাদা ফাইলে — রেন্ডার (AudioMixer, 44.1kHz স্টেরিও) আর প্রিভিউ (PreviewDenoise, সোর্সের আসল
 * স্যাম্পল রেট/চ্যানেল) একই হিসাব ব্যবহার করে, তাই প্রিভিউয়ে যা শোনা যায় রেন্ডারেও তা-ই হয়।
 */
internal class NoiseReducer(private val sr: Int, private val ch: Int) {
    // বাইকুয়াড হাই-পাস, fc = 90Hz, Q = 0.707
    private val b0: Double; private val b1: Double; private val b2: Double
    private val a1: Double; private val a2: Double
    private val x1 = DoubleArray(ch); private val x2 = DoubleArray(ch)
    private val y1 = DoubleArray(ch); private val y2 = DoubleArray(ch)
    private val frameLen = max(64, (sr * 0.02).roundToInt())
    private var cnt = 0
    private var energy = 0.0
    private var floor = -1.0
    private var gain = 1.0
    private var target = 1.0
    private val out = ShortArray(ch)
    private val y = DoubleArray(ch)

    init {
        val w0 = 2.0 * Math.PI * 90.0 / sr
        val alpha = Math.sin(w0) / (2.0 * 0.707)
        val cw = Math.cos(w0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cw) / 2.0) / a0
        b1 = (-(1.0 + cw)) / a0
        b2 = b0
        a1 = (-2.0 * cw) / a0
        a2 = (1.0 - alpha) / a0
    }

    /** সিক (seek) বা চালু/বন্ধের পর পুরনো অবস্থা মুছে নতুন করে শুরু */
    fun reset() {
        x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
        cnt = 0; energy = 0.0; floor = -1.0; gain = 1.0; target = 1.0
    }

    private fun hp(c: Int, x: Double): Double {
        val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
        x2[c] = x1[c]; x1[c] = x
        y2[c] = y1[c]; y1[c] = y
        return y
    }

    /** একটা ইন্টারলিভড ফ্রেম (ch সংখ্যক স্যাম্পল) buf[off..] থেকে নিয়ে জায়গাতেই বদলে দেয় */
    fun processFrame(buf: ShortArray, off: Int) {
        var e2 = 0.0
        for (c in 0 until ch) {
            y[c] = hp(c, buf[off + c] / 32768.0)
            e2 += y[c] * y[c]
        }
        energy += e2 / ch
        if (++cnt >= frameLen) {
            val e = Math.sqrt(energy / cnt)
            floor = if (floor < 0.0) e else if (e < floor) e else floor + (e - floor) * 0.002
            val thr = max(floor * 2.5, 0.004)
            val lo = thr * 0.6
            target = when {
                e >= thr -> 1.0
                e <= lo -> 0.08
                else -> 0.08 + 0.92 * (e - lo) / (thr - lo)
            }
            cnt = 0; energy = 0.0
        }
        // খোলার সময় দ্রুত (০.০২৫), বন্ধের সময় ধীরে (০.০০০৮) প্রতি স্যাম্পলে
        gain += (target - gain) * (if (target > gain) 0.025 else 0.0008)
        for (c in 0 until ch) {
            buf[off + c] = (y[c] * gain * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private val tmp = ShortArray(2)

    /** স্টেরিও (ch = 2) ফ্রেমের সুবিধাজনক রূপ — রেন্ডারের AudioMixer.Sink এটা ডাকে */
    fun process(l: Short, r: Short): ShortArray {
        tmp[0] = l; tmp[1] = r
        processFrame(tmp, 0)
        out[0] = tmp[0]; out[1] = tmp[1]
        return out
    }
}
