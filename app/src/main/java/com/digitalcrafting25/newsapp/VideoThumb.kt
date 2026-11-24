package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * ফেইসবুক/ইউটিউবের থাম্বনেইল: রেন্ডার করা ভিডিওর ইন্ট্রো বা আউট্রো থেকে নয়, মূল ভিডিওর ভেতর থেকে একটা ফ্রেম।
 * মূল অংশ = [introMs, মোট − outroMs]। ফ্রেম নেওয়া হয় মূল অংশের ~৩০% জায়গা থেকে (কমপক্ষে ১ সে., বেশির পক্ষে ৬ সে. ভেতরে)।
 * ফেরত: JPEG বাইট (≤ ১.৮ MB, ইউটিউবের সীমা ২ MB); না পারলে null (তখন থাম্বনেইল পাঠানো হয় না, পাবলিশ চলতে থাকে)।
 */
object VideoThumb {
    private const val MAX_W = 1280
    private const val MAX_BYTES = 1_800_000

    /** মূল ভিডিওর কোন সময়ে (মিলিসেকেন্ড) ফ্রেম নেব */
    fun pickMs(totalMs: Long, introMs: Long, outroMs: Long): Long {
        if (totalMs <= 0) return 0L
        var s = introMs.coerceIn(0L, totalMs)
        var e = (totalMs - outroMs).coerceIn(s, totalMs)
        if (e - s < 1_000L) { s = 0L; e = totalMs }   // মূল অংশ প্রায় নেই: পুরো ভিডিওর মাঝখান
        val span = e - s
        val off = if (span < 2_000L) span / 2 else (span * 3 / 10).coerceIn(1_000L, 6_000L)
        return (s + off).coerceIn(s, maxOf(s, e - 300L))
    }

    suspend fun make(ctx: Context, out: RenderOutput): ByteArray? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, out.uri)
            val total = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val t = pickMs(total, out.introMs, out.outroMs)
            val raw = r.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) ?: r.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@withContext null
            val bmp = if (raw.width > MAX_W) Bitmap.createScaledBitmap(raw, MAX_W, (raw.height * MAX_W.toFloat() / raw.width).toInt().coerceAtLeast(1), true) else raw
            var q = 88
            var bytes: ByteArray
            do {
                val bo = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, bo)
                bytes = bo.toByteArray()
                q -= 10
            } while (bytes.size > MAX_BYTES && q >= 50)
            bytes
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
