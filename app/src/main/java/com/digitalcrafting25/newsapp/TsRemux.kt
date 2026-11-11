package com.digitalcrafting25.newsapp

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * .ts (MPEG-TS) ফাইলকে MP4-তে "রিমাক্স" করে — ভিডিও/অডিও আবার এনকোড হয় না, শুধু প্যাকেজিং বদলায় (তাই দ্রুত ও মান একই)।
 * কেন: ফোনের নিজস্ব পার্সার (MediaExtractor) অনেক .ts পড়তে পারে, কিন্তু ExoPlayer/Transformer-এর TS পার্সার
 * কিছু ফাইল চেনে না (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)। MP4 হলে প্রিভিউ, ট্রিম, রেন্ডার সবই ঠিকঠাক চলে।
 * MP4-এ ঢোকে না এমন ট্র্যাক (যেমন AC3 অডিও) বাদ যায় — Out.note-এ জানানো হয়। ভিডিও ট্র্যাক বাদ গেলে/ব্যর্থ হলে null।
 */
object TsRemux {
    class Out(val file: File, val durationMs: Long, val note: String?)

    fun toMp4(ctx: Context, src: Uri, dir: File): Out? {
        dir.mkdirs()
        val outFile = File(dir, "${System.currentTimeMillis()}_${Math.abs(src.hashCode())}.mp4")
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var result: Out? = null
        try {
            ex.setDataSource(ctx, src, null)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()          // সোর্স ট্র্যাক → মাক্সারের ট্র্যাক
            val skipped = mutableListOf<String>()
            var hasVideo = false
            var maxIn = 1 shl 20
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                val idx = try { muxer.addTrack(f) } catch (_: Throwable) { -1 }
                if (idx < 0) { skipped.add(mime); continue }
                map[i] = idx
                if (mime.startsWith("video/")) hasVideo = true
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = maxOf(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            if (hasVideo && map.isNotEmpty()) {
                muxer.start(); started = true
                for (i in map.keys) ex.selectTrack(i)
                val buf = ByteBuffer.allocateDirect(maxIn.coerceAtMost(8 shl 20))
                val info = MediaCodec.BufferInfo()
                var base = -1L
                var last = 0L
                while (true) {
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val t = ex.sampleTime
                    val ti = ex.sampleTrackIndex
                    val mi = map[ti]
                    if (t < 0 || mi == null) { ex.advance(); continue }
                    if (base < 0) base = t
                    val pts = (t - base).coerceAtLeast(0L)
                    val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, n, pts, flags)
                    muxer.writeSampleData(mi, buf, info)
                    if (pts > last) last = pts
                    ex.advance()
                }
                val note = if (skipped.isNotEmpty()) "MP4-তে সাপোর্ট নেই বলে বাদ গেছে: " + skipped.joinToString(", ") else null
                result = Out(outFile, last / 1000L, note)
            }
        } catch (_: Throwable) {
            result = null
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { ex.release() }
            if (result == null) runCatching { outFile.delete() }
        }
        return result
    }
}
