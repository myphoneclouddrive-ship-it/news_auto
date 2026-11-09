package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * রেডি ইন্ট্রো / আউট্রো — HTML-এর `vcMakeIntro` / `vcMakeOutro`-র অ্যান্ড্রয়েড রূপ।
 * HTML ব্রাউজারে ক্যানভাসে এঁকে MediaRecorder-এ রেকর্ড করে; এখানে একই ছবি Canvas-এ এঁকে ফ্রেম ধরে ধরে H.264 এনকোড করা হয়,
 * সাউন্ড কোডে বানিয়ে AAC এনকোড করা হয়, তারপর একটা MP4 হয়ে সাধারণ ভিডিও-ক্লিপের মতো টাইমলাইনে বসে
 * (তাই প্রিভিউ, কাট, রেন্ডার, সাউন্ড মেশানো সব আগের ভিডিও-ক্লিপের মতোই চলে)।
 *
 * দৈর্ঘ্য: HTML-এর চেয়ে ১ সেকেন্ড বেশি — ইন্ট্রো ৩.৫ সে. (HTML ২.৫), আউট্রো ৫.৮ সে. (HTML ৪.৮)।
 */
object CardVideoMaker {
    const val INTRO_SEC = 3.5f
    const val OUTRO_SEC = 5.8f
    const val FPS = 30
    const val SR = 44100

    /** এই টেমপ্লেট-লেখা-মাপের কার্ড আগেই সেভ করা আছে কিনা (থাকলে নতুন করে বানাতে হয় না) */
    fun isSaved(ctx: Context, spec: CardSpec, w0: Int, h0: Int): Boolean {
        val w = max(2, w0 and 1.inv())
        val h = max(2, h0 and 1.inv())
        val f = File(File(ctx.filesDir, "card_saved"), cacheKey(spec, w, h) + ".mp4")
        return f.exists() && f.length() > 0L
    }

    /** সেভ করা কার্ডের চাবি: আউট্রোর লেখা ফিক্সড, তাই শুধু ইন্ট্রোর লেখা চাবিতে ধরা হয় */
    private fun cacheKey(spec: CardSpec, w: Int, h: Int): String {
        val logoSig = spec.logo?.let { u ->
            if (u.scheme == "file") { val f = File(u.path ?: ""); "f:${u.path}:${f.length()}:${f.lastModified()}" } else u.toString()
        } ?: "-"
        val txt = if (spec.outro) "" else spec.title.trim().ifEmpty { CardDefaults.INTRO_HEAD } + "\u0001" + spec.subtitle.trim()
        val raw = "v1|${spec.outro}|${spec.style.name}|$txt|$w|$h|$logoSig"
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }

    /** সেভ করা কার্ড সর্বোচ্চ ৮০টা রাখে; বেশি হলে সবচেয়ে পুরোনোগুলো যায় */
    private fun pruneSaved(dir: File, keep: Int = 80) {
        val fs = dir.listFiles { f -> f.name.endsWith(".mp4") }?.sortedByDescending { it.lastModified() } ?: return
        fs.drop(keep).forEach { runCatching { it.delete() } }
    }

    /** কার্ডের MP4 বানিয়ে `filesDir/cards/`-এ রাখে; (ফাইল-Uri, দৈর্ঘ্য মি.সে.) ফেরত দেয়। ব্যর্থ হলে Exception ছোড়ে */
    fun make(ctx: Context, spec: CardSpec, w0: Int, h0: Int, onProgress: (Float) -> Unit = {}): Pair<Uri, Long> {
        val w = max(2, w0 and 1.inv())
        val h = max(2, h0 and 1.inv())
        // একই টেমপ্লেট + লেখা + লোগো + মাপে আগে বানানো থাকলে সেভ করা ভিডিওটাই ফেরত (নতুন করে বানাতে হয় না)
        val saved = File(ctx.filesDir, "card_saved").apply { mkdirs() }
        val key = cacheKey(spec, w, h)
        val hit = File(saved, "$key.mp4")
        val durMs = ((if (spec.outro) OUTRO_SEC else INTRO_SEC) * 1000f).roundToInt().toLong()
        if (hit.exists() && hit.length() > 0L) {
            runCatching { hit.setLastModified(System.currentTimeMillis()) }
            onProgress(1f)
            return Uri.fromFile(hit) to durMs
        }
        val dir = File(ctx.filesDir, "cards").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val tag = if (spec.outro) "out" else "in"
        val tmpV = File(dir, "tmp_${tag}_$stamp.v.mp4")
        val tmpA = File(dir, "tmp_${tag}_$stamp.a.m4a")
        val outF = File(dir, "cv_${tag}_$stamp.mp4")
        val logo: Bitmap? = spec.logo?.let { loadLogo(ctx, it, 640) }
        try {
            val scene = CardScene(w, h, spec, logo)
            val frames = (scene.durationSec * FPS).roundToInt()
            encodeVideo(scene, w, h, frames, tmpV) { onProgress(it * 0.85f) }

            var hasAudio = false
            runCatching {
                val pcm = CardAudio.render(spec.outro, scene.durationSec)
                encodeAudio(pcm, tmpA)
                hasAudio = tmpA.exists() && tmpA.length() > 0L
            }
            onProgress(0.92f)
            if (hasAudio) {
                val ok = runCatching { mux(tmpV, tmpA, outF) }.isSuccess && outF.exists() && outF.length() > 0L
                if (!ok) { outF.delete(); tmpV.copyTo(outF, overwrite = true) }   // সাউন্ড জোড়া না গেলে শুধু ভিডিও
            } else {
                tmpV.copyTo(outF, overwrite = true)
            }
            // বানানো কার্ড স্টোরেজে সেভ রাখা হয় (পরেরবার একই টেমপ্লেট-লেখায় কাজে লাগবে)
            val finalF = runCatching {
                val tmp = File(saved, "$key.tmp")
                outF.copyTo(tmp, overwrite = true)
                if (tmp.renameTo(hit)) { outF.delete(); pruneSaved(saved); hit } else { tmp.delete(); outF }
            }.getOrDefault(outF)
            onProgress(1f)
            return Uri.fromFile(finalF) to (scene.durationSec * 1000f).roundToInt().toLong()
        } catch (e: Throwable) {
            runCatching { outF.delete() }
            throw e
        } finally {
            runCatching { tmpV.delete() }
            runCatching { tmpA.delete() }
            logo?.recycle()
        }
    }

    /**
     * বাছাইয়ের সময় দেখানোর স্থির প্রিভিউ: আসল কার্ড-দৃশ্যের একটা ফ্রেম (লেখা/লোগোসহ সব এসে গেছে এমন মুহূর্তে)।
     * ভিডিও বানানো হয় না, তাই খুব দ্রুত। ব্যর্থ হলে Exception ছোড়ে।
     */
    fun preview(ctx: Context, spec: CardSpec, w: Int, h: Int): Bitmap {
        val logo: Bitmap? = spec.logo?.let { loadLogo(ctx, it, 320) }
        try {
            val bmp = Bitmap.createBitmap(max(2, w), max(2, h), Bitmap.Config.ARGB_8888)
            CardScene(bmp.width, bmp.height, spec, logo).draw(Canvas(bmp), if (spec.outro) 3.9f else 2.8f)
            return bmp
        } finally {
            logo?.recycle()
        }
    }

    private fun loadLogo(ctx: Context, uri: Uri, maxSide: Int): Bitmap? =
        if (uri.scheme == "file") {
            runCatching {
                val src = android.graphics.ImageDecoder.createSource(File(uri.path ?: return null))
                android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = max(info.size.width, info.size.height)
                    if (s > maxSide) {
                        val f = maxSide / s.toFloat()
                        dec.setTargetSize(max(1, (info.size.width * f).roundToInt()), max(1, (info.size.height * f).roundToInt()))
                    }
                }
            }.getOrNull()
        } else CardMaker.loadScaled(ctx, uri, maxSide)

    // ------------------------------------------------------------------ ভিডিও এনকোড ----

    private fun encodeVideo(scene: CardScene, w: Int, h: Int, frames: Int, out: File, onProgress: (Float) -> Unit) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val px = IntArray(w * h)
        val yB = ByteArray(w * h)
        val uB = ByteArray(w * h / 4)
        val vB = ByteArray(w * h / 4)

        var fed = 0
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        if (fed >= frames) {
                            codec.queueInputBuffer(ii, 0, 0, fed * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            scene.draw(canvas, fed / FPS.toFloat())
                            bmp.getPixels(px, 0, w, 0, 0, w, h)
                            toYuv(px, w, h, yB, uB, vB)
                            val img: Image? = codec.getInputImage(ii)
                            if (img != null) {
                                fillImage(img, yB, uB, vB, w, h)
                            } else {
                                // ফ্লেক্সিবল ইমেজ না পেলে NV12 ধরে নিয়ে সরাসরি বাফারে লেখা
                                val ib = codec.getInputBuffer(ii)!!
                                ib.clear()
                                ib.put(yB, 0, w * h)
                                val cw = w / 2
                                val chroma = ByteArray(w * h / 2)
                                for (k in 0 until cw * (h / 2)) { chroma[2 * k] = uB[k]; chroma[2 * k + 1] = vB[k] }
                                ib.put(chroma)
                            }
                            codec.queueInputBuffer(ii, 0, w * h * 3 / 2, fed * 1_000_000L / FPS, 0)
                            fed++
                            onProgress(fed / frames.toFloat())
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && started) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            bmp.recycle()
        }
    }

    /** ARGB পিক্সেল → Y / U / V (BT.601, সীমিত পরিসর); U/V ২×২ ব্লকের গড় থেকে */
    private fun toYuv(px: IntArray, w: Int, h: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        var yi = 0
        for (j in 0 until h) {
            val row = j * w
            for (i in 0 until w) {
                val p = px[row + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                y[yi++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
        }
        val cw = w / 2
        for (j in 0 until h / 2) {
            for (i in 0 until cw) {
                var rs = 0
                var gs = 0
                var bs = 0
                for (dj in 0..1) {
                    for (di in 0..1) {
                        val p = px[(2 * j + dj) * w + 2 * i + di]
                        rs += (p shr 16) and 0xFF
                        gs += (p shr 8) and 0xFF
                        bs += p and 0xFF
                    }
                }
                val r = rs shr 2
                val g = gs shr 2
                val b = bs shr 2
                u[j * cw + i] = ((((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte()
                v[j * cw + i] = ((((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte()
            }
        }
    }

    private fun fillImage(img: Image, y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int) {
        val pl = img.planes
        copyPlane(pl[0].buffer, pl[0].rowStride, pl[0].pixelStride, y, w, h)
        copyPlane(pl[1].buffer, pl[1].rowStride, pl[1].pixelStride, u, w / 2, h / 2)
        copyPlane(pl[2].buffer, pl[2].rowStride, pl[2].pixelStride, v, w / 2, h / 2)
    }

    private fun copyPlane(buf: ByteBuffer, rowStride: Int, pixStride: Int, src: ByteArray, pw: Int, ph: Int) {
        if (pixStride == 1 && rowStride == pw) {
            buf.position(0)
            buf.put(src, 0, pw * ph)
            return
        }
        for (r in 0 until ph) {
            val base = r * rowStride
            if (pixStride == 1) {
                buf.position(base)
                buf.put(src, r * pw, pw)
            } else {
                for (c in 0 until pw) buf.put(base + c * pixStride, src[r * pw + c])
            }
        }
    }

    // ------------------------------------------------------------------ অডিও এনকোড ----

    private fun encodeAudio(pcm: ShortArray, out: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        var pos = 0                 // কত স্যাম্পল (মনো) দেওয়া হলো
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear()
                        val ptsUs = pos * 1_000_000L / SR
                        if (pos >= pcm.size) {
                            codec.queueInputBuffer(ii, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = min(1024, min(pcm.size - pos, ib.capacity() / 4))
                            val bytes = ByteArray(n * 4)
                            for (k in 0 until n) {
                                val s = pcm[pos + k].toInt()
                                val lo = (s and 0xFF).toByte()
                                val hi = ((s shr 8) and 0xFF).toByte()
                                bytes[4 * k] = lo; bytes[4 * k + 1] = hi      // বাম চ্যানেল (little-endian)
                                bytes[4 * k + 2] = lo; bytes[4 * k + 3] = hi  // ডান চ্যানেল
                            }
                            ib.put(bytes)
                            codec.queueInputBuffer(ii, 0, bytes.size, ptsUs, 0)
                            pos += n
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && started) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    // ------------------------------------------------------------------ ভিডিও + অডিও জোড়া ----

    private fun mux(video: File, audio: File, out: File) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val exV = MediaExtractor()
        val exA = MediaExtractor()
        try {
            exV.setDataSource(video.path)
            exA.setDataSource(audio.path)
            val tv = pickTrack(exV, "video/")
            val ta = pickTrack(exA, "audio/")
            require(tv >= 0 && ta >= 0) { "ট্র্যাক পাওয়া যায়নি" }
            exV.selectTrack(tv)
            exA.selectTrack(ta)
            val outV = muxer.addTrack(exV.getTrackFormat(tv))
            val outA = muxer.addTrack(exA.getTrackFormat(ta))
            muxer.start()
            copySamples(exV, muxer, outV)
            copySamples(exA, muxer, outA)
            muxer.stop()
        } finally {
            runCatching { exV.release() }
            runCatching { exA.release() }
            runCatching { muxer.release() }
        }
    }

    private fun pickTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith(prefix)) return i
        }
        return -1
    }

    private fun copySamples(ex: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buf = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            info.set(
                0, size, ex.sampleTime,
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
            muxer.writeSampleData(track, buf, info)
            ex.advance()
        }
    }
}

// ======================================================================================
// সাউন্ড (HTML-এর WebAudio অসিলেটর/নয়েজ সিনথ কোডে)
// ======================================================================================

internal class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun lowpass(f: Float, q: Double = 0.7071) = set(0, f, q)
    fun highpass(f: Float, q: Double = 1.0) = set(1, f, q)
    fun bandpass(f: Float, q: Double = 1.0) = set(2, f, q)

    private fun set(type: Int, f0: Float, q: Double) {
        val sr = CardVideoMaker.SR.toDouble()
        val f = f0.toDouble().coerceIn(20.0, sr * 0.45)
        val w0 = 2.0 * PI * f / sr
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        val nb0: Double
        val nb1: Double
        val nb2: Double
        when (type) {
            0 -> { nb0 = (1.0 - cw) / 2.0; nb1 = 1.0 - cw; nb2 = (1.0 - cw) / 2.0 }
            1 -> { nb0 = (1.0 + cw) / 2.0; nb1 = -(1.0 + cw); nb2 = (1.0 + cw) / 2.0 }
            else -> { nb0 = alpha; nb1 = 0.0; nb2 = -alpha }
        }
        b0 = nb0 / a0
        b1 = nb1 / a0
        b2 = nb2 / a0
        a1 = (-2.0 * cw) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + z1
        z1 = b1 * xd - a1 * y + z2
        z2 = b2 * xd - a2 * y
        return y.toFloat()
    }
}

internal class Synth(durSec: Float) {
    private val sr = CardVideoMaker.SR
    val n = (durSec * sr).toInt()
    val buf = FloatArray(n)

    /** type: 0 সাইন, 1 ত্রিভুজ, 2 করাত, 3 বর্গ; f2 > 0 হলে ফ্রিকোয়েন্সি d×০.৭ সময়ে f থেকে f2-তে গড়ায় */
    fun tone(f: Float, t: Float, d: Float, type: Int, vol: Float, f2: Float = 0f, lp: Float = 0f) {
        val s0 = (t * sr).toInt()
        val len = ((d + 0.05f) * sr).toInt()
        val att = 0.012f
        val lpf = if (lp > 0f) Biquad().also { it.lowpass(lp) } else null
        var phase = 0.0
        for (i in 0 until len) {
            val idx = s0 + i
            if (idx < 0) continue
            if (idx >= n) break
            val tt = i.toFloat() / sr
            val freq = when {
                f2 <= 0f -> f
                tt < d * 0.7f -> f * (f2 / f).pow(tt / (d * 0.7f))
                else -> f2
            }
            phase += freq.toDouble() / sr
            val ph = (phase - floor(phase)).toFloat()
            var s = when (type) {
                0 -> sin(2.0 * PI * ph).toFloat()
                1 -> 4f * abs(ph - 0.5f) - 1f
                2 -> 2f * ph - 1f
                else -> if (ph < 0.5f) 1f else -1f
            }
            if (lpf != null) s = lpf.process(s)
            val env = when {
                tt < att -> 0.0001f * (vol / 0.0001f).pow(tt / att)
                tt < d -> vol * (0.0001f / vol).pow((tt - att) / (d - att))
                else -> 0f
            }
            buf[idx] += s * env
        }
    }

    /** ছাঁকা সাদা-নয়েজ: ব্যান্ডপাস (বা হাইপাস) ফিল্টারের কেন্দ্র f0 থেকে f1-এ গড়ায় */
    fun noise(t: Float, d: Float, f0: Float, f1: Float, vol: Float, highpass: Boolean = false) {
        val s0 = (t * sr).toInt()
        val len = (d * sr).toInt()
        val bq = Biquad()
        val rnd = java.util.Random(1234L + (t * 1000f).toLong())
        for (i in 0 until len) {
            val idx = s0 + i
            if (idx < 0) continue
            if (idx >= n) break
            val tt = i.toFloat() / sr
            val fr = f0 * (f1 / f0).pow(tt / d)
            if (highpass) bq.highpass(fr, 1.1) else bq.bandpass(fr, 1.2)
            val x = rnd.nextFloat() * 2f - 1f
            val y = bq.process(x)
            val rise = d * 0.4f
            val env = if (tt < rise) 0.0001f * (vol / 0.0001f).pow(tt / rise)
            else vol * (0.0001f / vol).pow((tt - rise) / (d - rise))
            buf[idx] += y * env
        }
    }

    /** শেষে ফেড-আউট + সীমা ছাড়ালে নামিয়ে আনা → ১৬-বিট মনো PCM */
    fun finish(fadeSec: Float): ShortArray {
        val fadeStart = n - (fadeSec * sr).toInt()
        var peak = 0f
        for (i in 0 until n) {
            if (i > fadeStart) buf[i] *= 1f - (i - fadeStart).toFloat() / (n - fadeStart).coerceAtLeast(1)
            peak = max(peak, abs(buf[i]))
        }
        val k = if (peak > 0.95f) 0.95f / peak else 1f
        return ShortArray(n) { (buf[it] * k * 32767f).coerceIn(-32767f, 32767f).toInt().toShort() }
    }
}

internal object CardAudio {
    fun render(outro: Boolean, durSec: Float): ShortArray {
        val s = Synth(durSec)
        if (outro) {
            // লোগো ঢোকা
            s.noise(0.05f, 0.8f, 250f, 3500f, 0.5f)
            s.tone(180f, 0.55f, 0.5f, 0, 0.5f, 60f)
            // তিন বাটনের পপ
            for (k in 0..2) s.tone(520f + k * 110f, 1.1f + 0.4f * k, 0.22f, 1, 0.35f, 900f + k * 150f)
            // লাইক-কমেন্ট-শেয়ার সুর (দ্বিতীয়বার একটু নরমে)
            val mel = floatArrayOf(523.25f, 659.25f, 783.99f)
            for (k in 0..2) s.tone(mel[k], 2.6f + k * 0.35f, 0.45f, 0, 0.32f)
            for (k in 0..2) s.tone(mel[k], 3.6f + k * 0.35f, 0.45f, 0, 0.20f)
            // শেষের চাইম
            val fin = CardScene.OUTRO_FINALE
            for (f in floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f)) {
                s.tone(f, fin, 1.2f, 0, 0.22f)
                s.tone(f * 2f, fin, 0.6f, 1, 0.06f)
            }
            return s.finish(0.5f)
        }
        // ইন্ট্রো: ধাক্কা + হুশ + নিউজ স্টিং
        s.tone(110f, 0f, 0.55f, 0, 0.9f, 38f)
        s.noise(0f, 0.35f, 300f, 5000f, 0.55f)
        s.noise(0.05f, 0.07f, 3000f, 3000f, 0.35f, true)
        s.noise(0.15f, 0.07f, 3000f, 3000f, 0.3f, true)
        s.tone(392f, 0.25f, 0.30f, 2, 0.28f, 0f, 1800f)
        s.tone(587.33f, 0.25f, 0.30f, 2, 0.2f, 0f, 1800f)
        val notes = arrayOf(floatArrayOf(523.25f, 0.55f), floatArrayOf(659.25f, 0.68f), floatArrayOf(783.99f, 0.81f))
        for (q in notes) s.tone(q[0], q[1], 0.22f, 3, 0.16f, 0f, 2600f)
        for (f in floatArrayOf(392f, 493.88f, 587.33f, 783.99f)) {
            s.tone(f, 1.0f, 2.4f, 2, 0.2f, 0f, 2200f)     // HTML-এ ১.৪ সে.; বাড়তি ১ সে. টানা
            s.tone(f / 2f, 1.0f, 2.4f, 0, 0.18f)
        }
        s.noise(1.35f, 0.5f, 2000f, 8000f, 0.25f)          // আলোর ঝলকের শব্দ
        s.noise(2.4f, 0.5f, 2000f, 8000f, 0.2f)            // দ্বিতীয় ঝলক (বাড়তি সময়ে)
        return s.finish(0.25f)
    }
}

// ======================================================================================
// দৃশ্য: ৬টা অ্যানিমেটেড ব্যাকগ্রাউন্ড + ইন্ট্রো/আউট্রোর ফ্রেম (HTML-এর vcBgDraw ও ফ্রেম-ফাংশনের Canvas রূপ)
// ======================================================================================

internal class CardScene(
    private val w: Int,
    private val h: Int,
    private val spec: CardSpec,
    private val logo: Bitmap?,
) {
    companion object {
        /** আউট্রোর শেষ অংশ (ঝিলিক + চাইম) শুরুর সময়: HTML-এ ৩.৫, এখানে ১ সে. পিছিয়ে ৪.৫ */
        const val OUTRO_FINALE = 4.5f
    }

    val durationSec: Float = if (spec.outro) CardVideoMaker.OUTRO_SEC else CardVideoMaker.INTRO_SEC
    private val wf = w.toFloat()
    private val hf = h.toFloat()
    private val base = min(wf, hf * 0.9f)
    private lateinit var cv: Canvas
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val face: Typeface = Typeface.DEFAULT_BOLD
    private val twoPi = (PI * 2.0).toFloat()
    private val piF = PI.toFloat()

    // আউট্রোর ঝিলিক (HTML-এর মতো সিড-ধরা র‍্যান্ডম, তাই প্রতিবার একই)
    private val spA = FloatArray(34)
    private val spV = FloatArray(34)
    private val spS = FloatArray(34)
    private val spH = IntArray(34)

    init {
        var seed = 7L
        fun rnd(): Float { seed = (seed * 16807L) % 2147483647L; return (seed - 1L).toFloat() / 2147483646f }
        for (i in 0 until 34) {
            spA[i] = rnd() * twoPi
            spV[i] = 0.25f + rnd() * 0.55f
            spS[i] = 0.008f + rnd() * 0.014f
            spH[i] = floor(rnd() * 360f).toInt()
        }
    }

    fun draw(c: Canvas, t: Float) {
        cv = c
        if (spec.outro) drawOutro(t) else drawIntro(t)
    }

    // ---------------------------------------------------------------- ছোট সহায়ক ----

    private fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

    private fun eob(x0: Float): Float {
        val x = clamp01(x0)
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
    }

    private fun argb(r: Int, g: Int, b: Int, a: Float): Int = Color.argb((clamp01(a) * 255f).roundToInt(), r, g, b)
    private fun rgb(v: Int, a: Float = 1f): Int = argb((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF, a)
    private fun withA(c: Int, a: Float): Int = argb(Color.red(c), Color.green(c), Color.blue(c), a)

    private fun hs(i: Int, s: Int): Float {
        val x = sin(i * 127.1 + s * 311.7) * 43758.5453
        return (x - floor(x)).toFloat()
    }

    private fun fill(color: Int) {
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = color
    }

    private fun stroke(color: Int, width: Float) {
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.color = color; paint.strokeWidth = width
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float) = cv.drawRect(l, t, r, b, paint)
    private fun circle(x: Float, y: Float, r: Float) = cv.drawCircle(x, y, r, paint)

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        cv.drawPath(path, paint)
    }

    private fun fillAll(color: Int) { fill(color); rect(0f, 0f, wf, hf) }

    private fun vGrad(c0: Int, c1: Int) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(0f, 0f, 0f, hf, c0, c1, Shader.TileMode.CLAMP)
        rect(0f, 0f, wf, hf)
    }

    private fun radialAll(cx: Float, cy: Float, r: Float, colors: IntArray, pos: FloatArray?) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(cx, cy, r, colors, pos, Shader.TileMode.CLAMP)
        rect(0f, 0f, wf, hf)
    }

    private fun layerStart(alpha: Float) {
        layerPaint.alpha = (clamp01(alpha) * 255f).roundToInt()
        cv.saveLayer(null, layerPaint)
    }

    private fun layerEnd() = cv.restore()

    private fun textSetup(size: Float, color: Int, align: Paint.Align) {
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL
        paint.typeface = face; paint.textSize = size; paint.color = color; paint.textAlign = align
    }

    private fun measure(s: String, size: Float): Float {
        textSetup(size, Color.BLACK, Paint.Align.LEFT)
        return paint.measureText(s)
    }

    /** cy = লেখার মাঝের রেখা (JS-এর textBaseline='middle') */
    private fun text(s: String, x: Float, cy: Float, size: Float, color: Int, align: Paint.Align) {
        textSetup(size, color, align)
        val fm = paint.fontMetrics
        cv.drawText(s, x, cy - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun hsl(hDeg: Int, s: Float, l: Float, a: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = hDeg / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val rgb: Triple<Float, Float, Float> = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return argb(((rgb.first + m) * 255f).roundToInt(), ((rgb.second + m) * 255f).roundToInt(), ((rgb.third + m) * 255f).roundToInt(), a)
    }

    // ---------------------------------------------------------------- ৬টা ব্যাকগ্রাউন্ড ----

    private fun bg(t: Float) {
        when (spec.style) {
            CardStyle.GLOBAL -> bgGlobal(t)
            CardStyle.STUDIO -> bgStudio(t)
            CardStyle.MARKET -> bgMarket(t)
            CardStyle.BURST -> bgBurst(t)
            CardStyle.NEWSPRINT -> bgNewsprint(t)
            CardStyle.CLEAN -> bgClean(t)
        }
    }

    /** ১. গ্লোবাল নিউজ: ঘুরন্ত গ্লোব, ডট-নেটওয়ার্ক ও স্পন্দিত লোকেশন */
    private fun bgGlobal(t: Float) {
        vGrad(rgb(0x06222B), rgb(0x0E4A52))
        val cx = wf / 2f
        val cy = hf * 0.5f
        val r = base * 0.58f
        val g0 = argb(45, 212, 191, 0.28f)
        radialAll(cx, cy, r * 1.5f, intArrayOf(g0, g0, argb(45, 212, 191, 0f)), floatArrayOf(0f, 0.4f, 1f))
        stroke(argb(94, 234, 212, 0.22f), max(1f, base * 0.0025f))
        for (i in 0 until 8) {
            val a = t * 0.35f + i * piF / 8f
            val rx = abs(r * cos(a)) + 0.5f
            cv.drawOval(cx - rx, cy - r, cx + rx, cy + r, paint)
        }
        for (d in intArrayOf(-60, -30, 0, 30, 60)) {
            val la = d * piF / 180f
            val rx = r * cos(la)
            val cy2 = cy - r * sin(la)
            cv.drawOval(cx - rx, cy2 - rx * 0.16f, cx + rx, cy2 + rx * 0.16f, paint)
        }
        stroke(argb(94, 234, 212, 0.5f), max(1.5f, base * 0.004f))
        circle(cx, cy, r)
        val n = 80
        val px = FloatArray(n)
        val py = FloatArray(n)
        val pz = FloatArray(n)
        for (i in 0 until n) {
            val lon = hs(i, 1) * twoPi + t * 0.35f
            val lat = (hs(i, 2) - 0.5f) * piF * 0.9f
            val x = cos(lat) * sin(lon)
            val y = sin(lat)
            val z = cos(lat) * cos(lon)
            px[i] = cx + x * r
            py[i] = cy - y * r
            pz[i] = z
        }
        stroke(argb(153, 246, 228, 0.28f), max(1f, base * 0.002f))
        var i = 0
        while (i < n) {
            if (pz[i] > 0.1f && pz[i + 1] > 0.1f) {
                path.reset()
                path.moveTo(px[i], py[i])
                path.quadTo((px[i] + px[i + 1]) / 2f, min(py[i], py[i + 1]) - r * 0.18f, px[i + 1], py[i + 1])
                cv.drawPath(path, paint)
            }
            i += 2
        }
        for (k in 0 until n) {
            if (pz[k] <= 0f) continue
            fill(argb(204, 251, 241, 0.35f + 0.6f * pz[k]))
            circle(px[k], py[k], base * 0.004f * (0.6f + pz[k]))
            if (k < 8) {
                val d = (t * 0.7f + k * 0.31f) % 1f
                stroke(argb(251, 191, 36, (1f - d) * 0.8f), max(1f, base * 0.003f))
                circle(px[k], py[k], d * base * 0.04f)
            }
        }
    }

    /** ২. স্টুডিও লাইট: এলইডি ওয়ালের ডট-ঢেউ + ঘুরে বেড়ানো স্পটলাইট */
    private fun bgStudio(t: Float) {
        vGrad(rgb(0x1B0F3A), rgb(0x3B1A6B))
        val sp = base * 0.04f
        val rad = sp * 0.2f
        var y = sp / 2f
        while (y < hf) {
            var x = sp / 2f
            while (x < wf) {
                val s = 0.5f + 0.5f * sin((x * 0.9f + y * 0.5f) / base * 7f - t * 3f)
                fill(argb(196, 181, 253, 0.07f + 0.28f * s * s * s))
                circle(x, y, rad)
                x += sp
            }
            y += sp
        }
        val cols = arrayOf(intArrayOf(103, 232, 249), intArrayOf(244, 114, 182), intArrayOf(253, 224, 71), intArrayOf(167, 139, 250))
        for (i in 0 until 4) {
            val c = cols[i]
            val ax = wf * (0.12f + 0.76f * i / 3f)
            val ang = sin(t * 0.9f + i * 1.7f) * 0.4f
            val len = hf * 1.25f
            val sw = wf * 0.07f
            val ex = ax + sin(ang) * len
            val ey = cos(ang) * len
            paint.reset(); paint.isAntiAlias = true
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            paint.shader = LinearGradient(ax, -hf * 0.05f, ex, ey, argb(c[0], c[1], c[2], 0.45f), argb(c[0], c[1], c[2], 0f), Shader.TileMode.CLAMP)
            path.reset()
            path.moveTo(ax - base * 0.01f, -hf * 0.05f)
            path.lineTo(ax + base * 0.01f, -hf * 0.05f)
            path.lineTo(ex + sw, ey)
            path.lineTo(ex - sw, ey)
            path.close()
            cv.drawPath(path, paint)
        }
    }

    /** ৩. মার্কেট ডেটা: চলমান বার-চার্ট, লাইন গ্রাফ ও গ্রিড */
    private fun bgMarket(t: Float) {
        vGrad(rgb(0x052E22), rgb(0x065F46))
        stroke(argb(110, 231, 183, 0.12f), max(1f, base * 0.002f))
        for (i in 1 until 8) cv.drawLine(0f, hf * i / 8f, wf, hf * i / 8f, paint)
        val sp = base * 0.12f
        val ox = (t * base * 0.08f) % sp
        var gx = -sp + ox
        while (gx < wf) { cv.drawLine(gx, 0f, gx, hf, paint); gx += sp }
        val n = 28
        val bw = wf / n
        for (i in 0 until n) {
            val v = 0.30f + 0.22f * sin(i * 0.5f + t * 0.9f) + 0.12f * sin(i * 1.3f - t * 0.6f)
            val bh = hf * 0.55f * v
            val x = i * bw
            fill(argb(52, 211, 153, 0.2f))
            rect(x + bw * 0.12f, hf - bh, x + bw * 0.88f, hf)
            fill(argb(250, 204, 21, 0.55f))
            rect(x + bw * 0.12f, hf - bh, x + bw * 0.88f, hf - bh + max(2f, base * 0.004f))
        }
        val m = 60
        val lx = FloatArray(m + 1)
        val ly = FloatArray(m + 1)
        for (i in 0..m) {
            lx[i] = wf * i / m
            ly[i] = hf * (0.30f + 0.09f * sin(i * 0.22f + t * 1.1f) + 0.05f * sin(i * 0.61f - t * 0.8f) - 0.07f * i / m)
        }
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(0f, hf * 0.2f, 0f, hf * 0.6f, argb(250, 204, 21, 0.18f), argb(250, 204, 21, 0f), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(0f, hf * 0.6f)
        for (i in 0..m) path.lineTo(lx[i], ly[i])
        path.lineTo(wf, hf * 0.6f)
        path.close()
        cv.drawPath(path, paint)
        stroke(argb(253, 224, 71, 0.85f), max(2f, base * 0.005f))
        paint.setShadowLayer(base * 0.015f, 0f, 0f, rgb(0xFACC15))
        path.reset()
        path.moveTo(lx[0], ly[0])
        for (i in 1..m) path.lineTo(lx[i], ly[i])
        cv.drawPath(path, paint)
    }

    /** ৪. ব্রেকিং বার্স্ট: ঘুরন্ত আলোর রশ্মি + রাডারের মতো ছড়ানো রিং */
    private fun bgBurst(t: Float) {
        val cx = wf / 2f
        val cy = hf * 0.5f
        radialAll(cx, cy, hypot(wf, hf) * 0.6f, intArrayOf(rgb(0xFFD166), rgb(0xF97316), rgb(0x9A3412)), floatArrayOf(0f, 0.45f, 1f))
        val len = hypot(wf, hf)
        val a0 = t * 0.25f
        val cnt = 28
        fill(argb(255, 255, 255, 0.11f))
        var i = 0
        while (i < cnt) {
            val a = a0 + i * twoPi / cnt
            val b = a + twoPi / cnt
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx + cos(a) * len, cy + sin(a) * len)
            path.lineTo(cx + cos(b) * len, cy + sin(b) * len)
            path.close()
            cv.drawPath(path, paint)
            i += 2
        }
        for (j in 0 until 3) {
            val d = (t * 0.45f + j / 3f) % 1f
            stroke(argb(255, 255, 255, (1f - d) * 0.55f), max(2f, base * 0.006f))
            circle(cx, cy, d * base * 1.1f)
        }
    }

    /** ৫. নিউজপ্রিন্ট: পত্রিকার কাগজ, চলমান কলাম-লেখা ও হাফটোন ডট */
    private fun bgNewsprint(t: Float) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(0f, 0f, wf, hf, rgb(0xEEE4CC), rgb(0xD8CBAC), Shader.TileMode.CLAMP)
        rect(0f, 0f, wf, hf)
        val cols = 4
        val mx = wf * 0.05f
        val gp = wf * 0.025f
        val cw = (wf - 2f * mx - (cols - 1) * gp) / cols
        val pitch = base * 0.024f
        val sc = t * base * 0.05f
        val off = sc % pitch
        val k0 = floor(sc / pitch).toInt()
        for (c in 0 until cols) {
            val x = mx + c * (cw + gp)
            if (c > 0) {
                fill(argb(60, 45, 25, 0.18f))
                rect(x - gp / 2f, 0f, x - gp / 2f + max(1f, base * 0.0015f), hf)
            }
            val rows = (hf / pitch).toInt() + 1
            for (r in -1 until rows) {
                val id = k0 + r + c * 131
                val v = hs(id, 1)
                val y = r * pitch - off + pitch
                val ww = if (v > 0.9f) cw * 0.4f else cw * (0.72f + 0.28f * hs(id, 2))
                fill(argb(60, 45, 25, 0.14f + 0.06f * hs(id, 3)))
                rect(x, y, x + ww, y + base * 0.0085f)
            }
        }
        val dsp = base * 0.022f
        fill(argb(110, 70, 30, 0.30f))
        val origins = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(wf, hf))
        for (o in origins) {
            var y = 0f
            while (y < hf) {
                var x = 0f
                val shift = if ((y / dsp).roundToInt() % 2 != 0) dsp / 2f else 0f
                while (x < wf) {
                    val d = hypot(x - o[0], y - o[1])
                    val r = dsp * 0.5f * max(0f, 1f - d / (base * 0.75f))
                    if (r > 0.4f) circle(x + shift, y, r)
                    x += dsp
                }
                y += dsp
            }
        }
    }

    /** ৬. ক্লিন হোয়াইট: ঝকঝকে সাদা স্টুডিও, হালকা ডট-ঢেউ, ঝলক ও চলমান অ্যাকসেন্ট রেখা */
    private fun bgClean(t: Float) {
        vGrad(rgb(0xFFFFFF), rgb(0xE8EDF5))
        val sp = base * 0.045f
        var y = sp / 2f
        while (y < hf) {
            var x = sp / 2f
            while (x < wf) {
                val a = 0.10f + 0.16f * (0.5f + 0.5f * sin((x * 0.8f + y * 0.6f) / base * 6f - t * 2.2f))
                fill(argb(100, 116, 139, a))
                circle(x, y, sp * 0.07f)
                x += sp
            }
            y += sp
        }
        val origins = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(wf, hf))
        for (o in origins) {
            for (j in 1..4) {
                val d = (t * 0.12f + j * 0.22f) % 1f
                stroke(argb(148, 163, 184, 0.28f * 0.9f * (1f - d)), max(1.5f, base * 0.004f))
                circle(o[0], o[1], base * (0.2f + d * 0.9f))
            }
        }
        val sx = -wf * 0.3f + ((t * 0.35f) % 1.6f) * wf
        fill(argb(255, 255, 255, 0.55f))
        quad(sx, 0f, sx + wf * 0.10f, 0f, sx + wf * 0.10f - hf * 0.35f, hf, sx - hf * 0.35f, hf)
        val bh = max(4f, base * 0.012f)
        val mv = ((t * 0.4f) % 1.3f - 0.15f) * wf
        fill(rgb(0xDC2626))
        rect(0f, 0f, wf * 0.22f, bh)
        rect(wf - wf * 0.22f, hf - bh, wf, hf)
        fill(argb(220, 38, 38, 0.35f))
        rect(mv, hf - bh * 0.5f, mv + wf * 0.18f, hf)
        fill(argb(37, 99, 235, 0.35f))
        rect(wf - mv - wf * 0.18f, bh, wf - mv, bh * 1.5f)
    }

    // ---------------------------------------------------------------- লোগো ----

    private fun drawLogoFit(maxW: Float, maxH: Float) {
        val lg = logo ?: return
        val k = min(maxW / lg.width, maxH / lg.height)
        val dw = lg.width * k
        val dh = lg.height * k
        cv.drawBitmap(lg, null, RectF(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f), bmpPaint)
    }

    // ---------------------------------------------------------------- ইন্ট্রো ----

    private class InTheme(
        val ring: Int, val ban0: Int, val ban1: Int, val bglow: Int, val st: Int, val head: Int,
        val sub0: Int, val sub1: Int, val sub2: Int, val glow: Int,
    )

    private fun inTheme(s: CardStyle): InTheme = when (s) {
        CardStyle.GLOBAL -> InTheme(rgb(0x5EEAD4), rgb(0xF43F5E), rgb(0x9F1239), argb(244, 63, 94, 0.6f), rgb(0xFFFFFF), rgb(0xFFFFFF), rgb(0x04181D), rgb(0x5EEAD4), rgb(0xCCFBF1), argb(45, 212, 191, 0.40f))
        CardStyle.STUDIO -> InTheme(rgb(0x67E8F9), rgb(0x06B6D4), rgb(0x0E7490), argb(6, 182, 212, 0.55f), rgb(0xFFFFFF), rgb(0xFFFFFF), rgb(0x1B0F3A), rgb(0x67E8F9), rgb(0xEDE9FE), argb(196, 181, 253, 0.40f))
        CardStyle.MARKET -> InTheme(rgb(0xFACC15), rgb(0xFACC15), rgb(0xCA8A04), argb(250, 204, 21, 0.5f), rgb(0xFFFFFF), rgb(0x052E22), rgb(0x022C22), rgb(0xFACC15), rgb(0xD1FAE5), argb(52, 211, 153, 0.35f))
        CardStyle.BURST -> InTheme(rgb(0xFFFFFF), rgb(0x1F2937), rgb(0x030712), argb(0, 0, 0, 0.5f), rgb(0xFACC15), rgb(0xFFFFFF), rgb(0xFFFFFF), rgb(0xDC2626), rgb(0x7F1D1D), argb(255, 255, 255, 0.5f))
        CardStyle.NEWSPRINT -> InTheme(rgb(0xB91C1C), rgb(0xB91C1C), rgb(0x7F1D1D), argb(127, 29, 29, 0.4f), rgb(0xFECACA), rgb(0xFFFFFF), rgb(0x2B1D0E), rgb(0xD6B25E), rgb(0xF5ECD7), argb(255, 255, 255, 0.5f))
        CardStyle.CLEAN -> InTheme(rgb(0xDC2626), rgb(0xDC2626), rgb(0x991B1B), argb(220, 38, 38, 0.4f), rgb(0xFFFFFF), rgb(0xFFFFFF), rgb(0x0F172A), rgb(0xDC2626), rgb(0xFFFFFF), argb(148, 163, 184, 0.30f))
    }

    private fun bannerShine(sh: Float, cy: Float, bh: Float) {
        if (sh <= 0f || sh >= 1f) return
        cv.save()
        cv.clipRect(0f, cy - bh / 2f, wf, cy + bh / 2f)
        val x = -wf * 0.2f + sh * wf * 1.4f
        fill(argb(255, 255, 255, 0.35f))
        quad(x, cy - bh / 2f, x + bh * 0.9f, cy - bh / 2f, x + bh * 0.5f, cy + bh / 2f, x - bh * 0.4f, cy + bh / 2f)
        cv.restore()
    }

    private fun drawIntro(t: Float) {
        val dur = durationSec
        val it = inTheme(spec.style)
        val head = spec.title.trim().ifEmpty { CardDefaults.INTRO_HEAD }
        val sub = spec.subtitle.trim()
        val hasLogo = logo != null
        val cx = wf / 2f
        val cy = if (hasLogo) hf * 0.55f else hf * 0.48f
        val bh = base * 0.2f
        val gap = base * 0.02f
        val ly = hf * 0.24f

        bg(t)

        // আলোর ঝলক (তির্যক)
        if (t < 0.55f) {
            val x = -wf * 0.3f + t / 0.55f * wf * 1.5f
            fill(argb(255, 255, 255, 0.16f))
            quad(x, 0f, x + wf * 0.12f, 0f, x + wf * 0.12f - hf * 0.25f, hf, x - hf * 0.25f, hf)
        }
        // রিং
        val rd = t - 0.25f
        if (rd > 0f && rd < 0.9f) {
            stroke(withA(it.ring, (1f - rd / 0.9f) * 0.55f), base * 0.012f)
            circle(cx, cy, rd * base * 1.1f)
        }
        // লোগো (মাঝের ওপরে)
        if (hasLogo) {
            val lp = clamp01((t - 0.1f) / 0.5f)
            val sc = eob(lp)
            val fl = sin(t * 3f) * base * 0.006f
            layerStart(clamp01(lp * 2.2f))
            cv.save()
            cv.translate(cx, ly + fl)
            cv.scale(sc, sc)
            paint.reset(); paint.isAntiAlias = true
            paint.shader = RadialGradient(0f, 0f, base * 0.4f, it.glow, withA(it.glow, 0f), Shader.TileMode.CLAMP)
            cv.drawRect(-base * 0.4f, -base * 0.4f, base * 0.4f, base * 0.4f, paint)
            drawLogoFit(base * 0.5f, hf * 0.2f)
            cv.restore()
            layerEnd()
        }
        // ব্যানার (বাঁ থেকে ঢোকে)
        val p1 = eob((t - 0.2f) / 0.4f)
        val x1 = (1f - p1) * -wf
        val top = cy - bh / 2f
        val bot = cy + bh / 2f
        cv.save()
        cv.translate(x1, 0f)
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(0f, top, 0f, bot, it.ban0, it.ban1, Shader.TileMode.CLAMP)
        paint.setShadowLayer(base * 0.05f, 0f, 0f, it.bglow)
        rect(0f, top, wf, bot)
        fill(it.st)
        rect(0f, top, wf, top + max(2f, bh * 0.04f))
        fill(Color.WHITE)
        rect(wf * 0.05f, top, wf * 0.05f + bh, bot)
        text("📰", wf * 0.05f + bh / 2f, cy + bh * 0.03f, bh * 0.5f, rgb(0x111111), Paint.Align.CENTER)
        val avail = wf * 0.9f - bh * 1.6f
        val hw = measure(head, bh * 0.56f)
        val hfs = bh * 0.56f * min(1f, avail / max(1f, hw))
        val tp = clamp01((t - 0.45f) / 0.3f)
        val tx0 = wf * 0.05f + bh + bh * 0.3f
        cv.save()
        cv.clipRect(tx0, top, tx0 + (wf * 0.95f - tx0) * tp, bot)
        text(head, tx0, cy + bh * 0.04f, hfs, it.head, Paint.Align.LEFT)
        cv.restore()
        cv.restore()

        // ব্যানারের ওপর আলোর ঝলক: HTML-এর একটা + বাড়তি সময়ে আরেকটা
        bannerShine(clamp01((t - 1.2f) / 0.7f), cy, bh)
        bannerShine(clamp01((t - 2.4f) / 0.7f), cy, bh)

        // নিচের সরু ব্যানার (ডান থেকে ঢোকে)
        if (sub.isNotEmpty()) {
            val sbh = bh * 0.45f
            val p2 = eob((t - 0.55f) / 0.4f)
            val x2 = (1f - p2) * wf
            val sy = cy + bh / 2f + gap
            val sw = measure(sub, sbh * 0.5f)
            val sfs = sbh * 0.5f * min(1f, (wf * 0.92f) / max(1f, sw))
            cv.save()
            cv.translate(x2, 0f)
            fill(it.sub0)
            rect(0f, sy, wf, sy + sbh)
            fill(it.sub1)
            val line = max(2f, sbh * 0.07f)
            rect(0f, sy + sbh - line, wf, sy + sbh)
            text(sub, cx, sy + sbh * 0.48f, sfs, it.sub2, Paint.Align.CENTER)
            cv.restore()
        }
        // ফ্ল্যাশ
        val fl2 = max(0f, 0.6f * (1f - abs(t - 0.22f) / 0.1f))
        if (fl2 > 0f) fillAll(argb(255, 255, 255, fl2))
        // শেষে ফেড
        val fo = clamp01((t - (dur - 0.25f)) / 0.25f)
        if (fo > 0f) fillAll(argb(0, 0, 0, fo))
    }

    // ---------------------------------------------------------------- আউট্রো ----

    private class OutTheme(val gr: Int, val gg: Int, val gb: Int, val ga: Float, val r1: Int, val r2: Int, val tx: Int)

    private fun outTheme(s: CardStyle): OutTheme = when (s) {
        CardStyle.GLOBAL -> OutTheme(45, 212, 191, 0.22f, rgb(0x5EEAD4), rgb(0xFBBF24), rgb(0xE6FFFB))
        CardStyle.STUDIO -> OutTheme(196, 181, 253, 0.20f, rgb(0x67E8F9), rgb(0xF9A8D4), rgb(0xF5F3FF))
        CardStyle.MARKET -> OutTheme(52, 211, 153, 0.20f, rgb(0x34D399), rgb(0xFACC15), rgb(0xECFDF5))
        CardStyle.BURST -> OutTheme(255, 255, 255, 0.30f, rgb(0xFFFFFF), rgb(0x7C2D12), rgb(0xFFFFFF))
        CardStyle.NEWSPRINT -> OutTheme(255, 255, 255, 0.40f, rgb(0xB91C1C), rgb(0x78350F), rgb(0x3F2A14))
        CardStyle.CLEAN -> OutTheme(148, 163, 184, 0.22f, rgb(0xDC2626), rgb(0x2563EB), rgb(0x0F172A))
    }

    private fun pulseAt(t: Float, s: Float): Float {
        val d = t - s
        return if (d > 0f && d < 0.35f) 1f + 0.16f * sin(d / 0.35f * piF) else 1f
    }

    private fun drawOutro(t: Float) {
        val dur = durationSec
        val ot = outTheme(spec.style)
        val fin = CardScene.OUTRO_FINALE
        val cx = wf / 2f
        val cyL = hf * 0.32f
        val pw = base * 0.29f
        val ph = pw * 0.4f
        val gapB = base * 0.025f
        val x0 = (wf - (3f * pw + 2f * gapB)) / 2f
        val py = hf * 0.585f

        bg(t)
        // লোগোর পেছনের আলো
        val ga = ot.ga + 0.08f * sin(t * 3f)
        radialAll(cx, cyL, base * 0.75f, intArrayOf(argb(ot.gr, ot.gg, ot.gb, ga), argb(ot.gr, ot.gg, ot.gb, 0f)), null)
        // রিং
        fun ring(t0: Float, col: Int) {
            val d = t - t0
            if (d < 0f || d > 1.2f) return
            stroke(withA(col, (1f - d / 1.2f) * 0.6f), base * 0.012f)
            circle(cx, cyL, d * base * 0.9f)
        }
        ring(0.5f, ot.r1)
        ring(fin, ot.r2)

        // লোগো
        val lp = clamp01((t - 0.1f) / 0.85f)
        val sc = eob(lp)
        val fl = sin(t * 2.2f) * base * 0.008f
        layerStart(clamp01(lp * 2.2f))
        cv.save()
        cv.translate(cx, cyL + fl)
        cv.scale(sc, sc)
        if (logo != null) {
            drawLogoFit(base * 0.6f, hf * 0.28f)
        } else {
            val r = min(base * 0.6f, hf * 0.28f) / 2f
            fill(rgb(0x4F46E5))
            circle(0f, 0f, r)
            fill(Color.WHITE)
            path.reset()
            path.moveTo(-r * 0.28f, -r * 0.4f); path.lineTo(r * 0.5f, 0f); path.lineTo(-r * 0.28f, r * 0.4f); path.close()
            cv.drawPath(path, paint)
        }
        cv.restore()
        layerEnd()

        // তিনটা বাটন: লাইক / কমেন্ট / শেয়ার
        val labs = arrayOf("👍 লাইক", "💬 কমেন্ট", "📤 শেয়ার")
        val cols = intArrayOf(rgb(0x2563EB), rgb(0x16A34A), rgb(0xEA580C))
        for (k in 0 until 3) {
            val p = clamp01((t - (1.1f + 0.4f * k)) / 0.45f)
            if (p <= 0f) continue
            val pulse = max(pulseAt(t, 2.6f + 0.35f * k), pulseAt(t, 3.6f + 0.35f * k))
            val s2 = eob(p) * pulse
            layerStart(clamp01(p * 2.5f))
            cv.save()
            cv.translate(x0 + k * (pw + gapB) + pw / 2f, py + ph / 2f)
            cv.scale(s2, s2)
            fill(cols[k])
            paint.setShadowLayer((if (pulse > 1f) 0.06f else 0.02f) * base, 0f, 0f, cols[k])
            cv.drawRoundRect(RectF(-pw / 2f, -ph / 2f, pw / 2f, ph / 2f), ph / 2f, ph / 2f, paint)
            text(labs[k], 0f, ph * 0.03f, ph * 0.38f, Color.WHITE, Paint.Align.CENTER)
            cv.restore()
            layerEnd()
        }
        // লেখা
        val tp = clamp01((t - 2.2f) / 0.5f)
        if (tp > 0f) {
            text("ধন্যবাদ! আমাদের সাথে থাকুন", cx, py + ph + base * 0.09f + (1f - tp) * base * 0.02f, base * 0.05f, withA(ot.tx, tp), Paint.Align.CENTER)
        }
        // ঝিলিক (শেষ অংশে)
        val sd = t - fin
        if (sd > 0f && sd < 1.1f) {
            for (i in 0 until 34) {
                val r = sd * spV[i] * base
                val x = cx + cos(spA[i]) * r
                val y = py + ph / 2f + sin(spA[i]) * r * 0.8f
                fill(hsl(spH[i], 0.9f, 0.65f, 1f - sd / 1.1f))
                circle(x, y, max(0.5f, spS[i] * base * (1f - sd / 1.6f)))
            }
        }
        // শেষে ফেড
        val fo = clamp01((t - (dur - 0.5f)) / 0.5f)
        if (fo > 0f) fillAll(argb(0, 0, 0, fo))
    }
}
