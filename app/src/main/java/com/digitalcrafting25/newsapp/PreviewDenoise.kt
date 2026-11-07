@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.digitalcrafting25.newsapp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * (ধাপ ৩-এর ৬) প্রিভিউয়ে "নয়েজ কমান" শোনানোর AudioProcessor।
 * রেন্ডারের NoiseReducer-ই ব্যবহার করে (৯০Hz হাই-পাস + নয়েজ গেট), তাই শব্দ রেন্ডারের সাথে মেলে।
 *
 *  - [enabled] বন্ধ থাকলে শব্দ অবিকল পাস হয়ে যায়
 *  - চালু করলে ও সিক/ফ্লাশের পর ফিল্টার-অবস্থা নতুন করে শুরু হয়
 *  - ১৬-বিট PCM ও ১-২ চ্যানেল ছাড়া অন্য ফরম্যাট ছুঁয়ে দেখে না (পাস-থ্রু), যাতে প্লেব্যাক না ভাঙে
 */
class PreviewDenoise : BaseAudioProcessor() {
    @Volatile var enabled: Boolean = false
    private var wasEnabled = false
    private var supported = false
    private var reducer: NoiseReducer? = null
    private var frame = ShortArray(2)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount in 1..2 && inputAudioFormat.sampleRate > 0
        reducer = if (supported) NoiseReducer(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
        wasEnabled = false
        return inputAudioFormat   // ফরম্যাট বদলায় না
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val r = reducer
        val on = enabled
        if (!on || !supported || r == null) {
            wasEnabled = false
            out.put(inputBuffer)
        } else {
            if (!wasEnabled) { r.reset(); wasEnabled = true }
            val ch = inputAudioFormat.channelCount
            if (frame.size < ch) frame = ShortArray(ch)
            val src = inputBuffer.order(ByteOrder.nativeOrder())
            val frames = size / (2 * ch)
            for (f in 0 until frames) {
                for (c in 0 until ch) frame[c] = src.getShort()
                r.processFrame(frame, 0)
                for (c in 0 until ch) out.putShort(frame[c])
            }
            // ফ্রেমে ভাগ না হওয়া বাড়তি বাইট (সাধারণত থাকে না) অবিকল পাঠাই
            while (src.hasRemaining()) out.put(src.get())
        }
        out.flip()
    }

    override fun onFlush() {   // সিক বা নতুন গানে গেলে
        reducer?.reset()
        wasEnabled = enabled && supported
    }

    override fun onReset() {
        reducer = null
        supported = false
        wasEnabled = false
    }
}
