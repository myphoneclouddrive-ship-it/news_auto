package com.digitalcrafting25.newsapp

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ধাপ ৫: রেন্ডারের আগে সব শব্দ একটা স্টেরিও 16-bit 44.1kHz WAV ফাইলে মেশানো হয়।
 *
 * কী কী মেশে:
 *  - ভিডিও ক্লিপের আসল শব্দ (ক্লিপের ভলিউম / মিউট মেনে; "আসল সাউন্ড রাখুন" বন্ধ থাকলে বাদ)
 *  - অডিও / ভয়েস লেয়ার (শুরু-শেষ, সোর্সের অফসেট, ভলিউম, মিউট মেনে)
 *  - বিপ লেয়ার (১০০০ Hz সাইন ওয়েভ) — বিপ চলার সময় বাকি সব শব্দ (ক্লিপ + অন্য অডিও) বন্ধ থাকে (HTML-এর মতো)
 *
 * ডিকোড ও মেশানো স্ট্রিমিং করে (বড় ভিডিওতেও মেমোরি কম লাগে)। ব্যাকগ্রাউন্ড থ্রেডে চালাতে হবে।
 */
object AudioMixer {
    const val SR = 44100
    private const val CH = 2
    private const val HEADER = 44L
    private const val CHUNK_FRAMES = SR          // ১ সেকেন্ডের টুকরো
    private const val BEEP_HZ = 1000.0

    class Cancelled : Exception("বাতিল")

    /** মিক্স দরকার কিনা: অডিও লেয়ার আছে, অথবা কোনো ক্লিপের ভলিউম/মিউট বদলানো */
    fun needsMix(spec: RenderSpec): Boolean =
        spec.layers.any { it.isAudio } || spec.clips.any { !it.isImage && (it.muted || it.volume != 1f) }

    private class Source(
        val uri: Uri?,              // null = বিপ (নিজে বানানো)
        val srcStartMs: Long,       // সোর্স ফাইলের কোন জায়গা থেকে
        val lenMs: Long,            // টাইমলাইনে কত সময় বাজবে
        val timelineStartMs: Long,
        val gain: Float,
        val ducked: Boolean,        // বিপের নিচে চুপ হবে কিনা
        val label: String,
        val fadeInMs: Long = 0L,
        val fadeOutMs: Long = 0L,
        val denoise: Boolean = false,
    )

    fun mix(ctx: Context, spec: RenderSpec, out: File, isCancelled: () -> Boolean) {
        val totalMs = spec.clips.sumOf { it.lengthMs() }
        val totalFrames = totalMs * SR / 1000L
        if (totalFrames <= 0L) throw IllegalStateException("ভিডিওর দৈর্ঘ্য ০")

        // বিপের সময়সীমা (ফ্রেমে): এর ভেতরে অন্য সব শব্দ চুপ
        val audio = spec.layers.filterIsInstance<AudioLayer>()
        val duck: List<LongRange> = audio
            .filter { it.kind == LayerKind.BEEP && !it.muted && it.volume > 0f }
            .map { msToFrame(it.startMs)..msToFrame(minOf(it.endMs, totalMs, it.startMs + (BeepSound.LEN_MS - it.sourceOffsetMs).coerceAtLeast(0L))) }

        val sources = mutableListOf<Source>()
        if (spec.keepOriginalAudio) {
            var acc = 0L
            for (c in spec.clips) {
                val len = c.lengthMs()
                if (!c.isImage && !c.muted && c.volume > 0f && len > 0L) {
                    sources += Source(c.uri, Math.round(c.startSec * 1000.0), len, acc, c.volume, true, "ক্লিপের শব্দ")
                }
                acc += len
            }
        }
        for (l in audio) {
            if (l.muted || l.volume <= 0f) continue
            val start = l.startMs.coerceIn(0L, totalMs)
            var len = (minOf(l.endMs, totalMs) - start)
            if (l.kind == LayerKind.BEEP) len = minOf(len, BeepSound.LEN_MS - l.sourceOffsetMs)   // HTML-এর মতো বিপ ≤ ০.৬ সে.
            if (len <= 0L) continue
            if (l.kind == LayerKind.BEEP) {
                sources += Source(null, l.sourceOffsetMs, len, start, l.volume, false, "বিপ")
            } else if (l.uri != null) {
                sources += Source(l.uri, l.sourceOffsetMs, len, start, l.volume, true, l.kind.label, l.fadeInMs, l.fadeOutMs, l.denoise)
            }
        }

        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            writeHeader(raf, totalFrames)
            zeroFill(raf, totalFrames * CH * 2L, isCancelled)

            for (s in sources) {
                if (isCancelled()) throw Cancelled()
                val startFrame = msToFrame(s.timelineStartMs)
                val maxFrames = min(msToFrame(s.lenMs), totalFrames - startFrame)
                if (maxFrames <= 0L) continue
                val sink = Sink(raf, startFrame, maxFrames, s.gain, if (s.ducked) duck else emptyList(), msToFrame(s.fadeInMs), msToFrame(s.fadeOutMs), s.denoise)
                if (s.uri == null) generateBeep(sink, s.srcStartMs, isCancelled) else decode(ctx, s, sink, isCancelled)
                sink.flush()
            }
        }
    }

    private fun msToFrame(ms: Long): Long = ms * SR / 1000L

    private fun writeHeader(raf: RandomAccessFile, frames: Long) {
        val dataSize = frames * CH * 2L
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36L + dataSize).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1)                       // PCM
        b.putShort(CH.toShort())
        b.putInt(SR)
        b.putInt(SR * CH * 2)               // byte rate
        b.putShort((CH * 2).toShort())      // block align
        b.putShort(16)                      // bits
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataSize.toInt())
        raf.seek(0)
        raf.write(b.array())
    }

    private fun zeroFill(raf: RandomAccessFile, bytes: Long, isCancelled: () -> Boolean) {
        val z = ByteArray(1 shl 16)
        raf.seek(HEADER)
        var left = bytes
        while (left > 0) {
            if (isCancelled()) throw Cancelled()
            val n = min(left, z.size.toLong()).toInt()
            raf.write(z, 0, n)
            left -= n
        }
    }

    /**
     * স্টেরিও ফ্রেম জমিয়ে ফাইলে যোগ (add) করে। সোর্সের প্রতিটি ফ্রেম টাইমলাইনের
     * startFrame থেকে পরপর বসে; maxFrames পার হলে বাকিটা বাদ।
     */
    private class Sink(
        private val raf: RandomAccessFile,
        private val startFrame: Long,
        val maxFrames: Long,
        private val gain: Float,
        private val duck: List<LongRange>,
        private val fadeInFrames: Long = 0L,
        private val fadeOutFrames: Long = 0L,
        private val denoise: Boolean = false,
    ) {
        private val nr = if (denoise) NoiseReducer(SR, CH) else null
        private val buf = ShortArray(CHUNK_FRAMES * CH)
        private var n = 0            // buf-এ জমা শর্ট সংখ্যা
        private var written = 0L     // এ পর্যন্ত ফাইলে যাওয়া ফ্রেম
        val full: Boolean get() = written + n / CH >= maxFrames

        fun put(l: Short, r: Short) {
            if (written + n / CH >= maxFrames) return
            if (nr != null) {
                val o = nr.process(l, r)
                buf[n++] = o[0]
                buf[n++] = o[1]
            } else {
                buf[n++] = l
                buf[n++] = r
            }

            if (n == buf.size) flush()
        }

        fun flush() {
            if (n == 0) return
            val frames = n / CH
            val first = startFrame + written
            val bytes = ByteArray(n * 2)
            raf.seek(HEADER + first * CH * 2L)
            raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until frames) {
                val abs = first + f
                var ducked = false
                for (r in duck) if (abs in r) { ducked = true; break }
                if (ducked) continue
                // ফেড ইন/আউট: এই সোর্সের শুরু-শেষ থেকে দূরত্ব ধরে ০..১ গুণক
                val rel = written + f
                var env = 1f
                if (fadeInFrames > 0L && rel < fadeInFrames) env = rel / fadeInFrames.toFloat()
                if (fadeOutFrames > 0L && maxFrames - rel < fadeOutFrames) env = minOf(env, (maxFrames - rel).coerceAtLeast(0L) / fadeOutFrames.toFloat())
                for (c in 0 until CH) {
                    val pos = (f * CH + c) * 2
                    val v = bb.getShort(pos) + (buf[f * CH + c] * gain * env).roundToInt()
                    bb.putShort(pos, v.coerceIn(-32768, 32767).toShort())
                }
            }
            raf.seek(HEADER + first * CH * 2L)
            raf.write(bytes)
            written += frames
            n = 0
        }
    }

    /** HTML makeBeep()-এর হুবহু শব্দ (BeepSound.sample); offsetMs = বিপের কোন জায়গা থেকে বাজবে */
    private fun generateBeep(sink: Sink, offsetMs: Long, isCancelled: () -> Boolean) {
        val total = sink.maxFrames
        val offSec = offsetMs / 1000.0
        var f = 0L
        while (f < total) {
            if ((f and 0x3FFFL) == 0L && isCancelled()) throw Cancelled()
            val t = offSec + f / SR.toDouble()
            val v = (BeepSound.sample(t) * 32767.0).roundToInt().toShort()
            sink.put(v, v)
            f++
        }
    }

    /** ফাইলের (ভিডিও/অডিও) অডিও ট্র্যাক ডিকোড করে স্টেরিও 44.1kHz-এ sink-এ ঢালে */
    private fun decode(ctx: Context, s: Source, sink: Sink, isCancelled: () -> Boolean) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, s.uri!!, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; break }
            }
            if (track < 0) return   // অডিও ট্র্যাক নেই (যেমন নীরব ভিডিও) — বাদ
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val startUs = s.srcStartMs * 1000L
            val endUs = (s.srcStartMs + s.lenMs) * 1000L
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            var rate = fmt.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, SR)
            var ch = fmt.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmFloat = false
            var rs = Resampler(rate)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && !sink.full) {
                if (isCancelled()) throw Cancelled()

                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(ib, 0)
                        val t = ex.sampleTime
                        if (size < 0 || t > endUs + 500_000L) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, t, 0)
                            ex.advance()
                        }
                    }
                }

                val oi = c.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        val nr = of.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                        ch = of.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, ch)
                        pcmFloat = of.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                        if (nr != rate) { rate = nr; rs = Resampler(rate) }
                    }
                    oi >= 0 -> {
                        if (info.size > 0 && ch > 0) {
                            val ob = c.getOutputBuffer(oi)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val bytesPerSample = if (pcmFloat) 4 else 2
                            val frames = info.size / (bytesPerSample * ch)
                            val bb = ob.slice().order(ByteOrder.nativeOrder())
                            var f = 0
                            while (f < frames && !sink.full) {
                                val tUs = info.presentationTimeUs + f * 1_000_000L / rate
                                if (tUs >= endUs) { outputDone = true; break }
                                if (tUs >= startUs) {
                                    val l = readSample(bb, (f * ch) * bytesPerSample, pcmFloat)
                                    val r = if (ch >= 2) readSample(bb, (f * ch + 1) * bytesPerSample, pcmFloat) else l
                                    rs.push(l, r, sink)
                                }
                                f++
                            }
                        }
                        c.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, def: Int): Int =
        if (containsKey(key)) getInteger(key) else def

    private fun readSample(bb: ByteBuffer, byteIndex: Int, isFloat: Boolean): Short =
        if (isFloat) (bb.getFloat(byteIndex).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
        else bb.getShort(byteIndex)

    /** সরল লিনিয়ার রিস্যাম্পলার (যেকোনো rate → 44100), ফ্রেম এক এক করে ঢোকে */
    private class Resampler(private val inRate: Int) {
        private val step = inRate.toDouble() / SR
        private var idx = 0L          // এ পর্যন্ত কতটা ইনপুট ফ্রেম ঢুকেছে
        private var nextOut = 0.0     // পরের আউটপুট ফ্রেম ইনপুট-ফ্রেম এককে কোথায়
        private var pl = 0f
        private var pr = 0f

        fun push(l: Short, r: Short, sink: Sink) {
            val cl = l.toFloat()
            val cr = r.toFloat()
            if (idx == 0L) {
                pl = cl; pr = cr; idx = 1L
                return
            }
            // prev = ফ্রেম (idx-1), cur = ফ্রেম idx
            while (nextOut <= idx.toDouble()) {
                val frac = (nextOut - (idx - 1)).toFloat().coerceIn(0f, 1f)
                sink.put(
                    (pl + (cl - pl) * frac).roundToInt().coerceIn(-32768, 32767).toShort(),
                    (pr + (cr - pr) * frac).roundToInt().coerceIn(-32768, 32767).toShort(),
                )
                nextOut += step
            }
            pl = cl; pr = cr
            idx++
        }
    }

    // ------------------------------------------------------------------
    // UI-র জন্য ছোট সাহায্যকারী
    // ------------------------------------------------------------------

    /** ফাইলে অন্তত একটা অডিও ট্র্যাক আছে কিনা (IO থ্রেডে ডাকুন) */
    fun hasAudioTrack(ctx: Context, uri: Uri): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ctx, uri, null)
            (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { ex.release() }
        }
    }

    /** (দেখানোর নাম, দৈর্ঘ্য ms)। দৈর্ঘ্য না পেলে ০ (IO থ্রেডে ডাকুন) */
    fun probe(ctx: Context, uri: Uri): Pair<String, Long> {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "অডিও"
        val dur = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { r.release() }
            }
        }.getOrDefault(0L)
        return name to dur
    }
}

/** ভয়েস রেকর্ডার: বাটন চেপে ধরলে start(), ছাড়লে stop() */
class VoiceRecorder(private val ctx: Context) {
    private var rec: android.media.MediaRecorder? = null
    private var file: File? = null
    private var t0 = 0L

    val isRecording: Boolean get() = rec != null

    /** সমস্যা থাকলে বাংলা বার্তা, ঠিক থাকলে null */
    @Suppress("DEPRECATION")
    fun start(): String? {
        if (rec != null) return null
        val dir = File(ctx.filesDir, "voice").apply { mkdirs() }
        val f = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(ctx) else android.media.MediaRecorder()
        try {
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            f.delete()
            return "মাইক্রোফোন চালু করা যায়নি (অন্য অ্যাপ মাইক ব্যবহার করছে কিনা দেখুন)"
        }
        rec = r
        file = f
        t0 = SystemClock.elapsedRealtime()
        return null
    }

    /** (ফাইল, দৈর্ঘ্য ms); খুব ছোট বা ব্যর্থ হলে null */
    fun stop(): Pair<File, Long>? {
        val r = rec ?: return null
        val f = file
        rec = null
        file = null
        val dur = SystemClock.elapsedRealtime() - t0
        val ok = try { r.stop(); true } catch (_: RuntimeException) { false }
        runCatching { r.release() }
        if (!ok || f == null || dur < 400L) {
            f?.delete()
            return null
        }
        return f to dur
    }

    fun cancel() {
        val r = rec ?: return
        rec = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }
}
