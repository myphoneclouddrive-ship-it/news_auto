package com.digitalcrafting25.newsapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * রেন্ডার করা ভিডিও সরাসরি Facebook পেইজে আপলোড (step61)।
 *
 * Worker-এ তিনটা রুট আছে (Page Access Token Worker-এই থাকে, অ্যাপে আসে না):
 *   /vid-fb-start  {size}                              → {session, video_id, start, end}
 *   /vid-fb-chunk  multipart(file, session, offset)    → {start, end}   (বারবার)
 *   /vid-fb-finish {session, video_id, title, description} → {ok, id, link}
 * তিনটাতেই Authorization: Bearer <Firebase idToken> লাগে (`Session.token`)।
 *
 * Facebook resumable আপলোডের নিয়ম: প্রতি উত্তরে [start, end) = এর পরের কোন অংশ চাই। start == end হলে সব পৌঁছে গেছে → finish।
 * এক টুকরো সর্বোচ্চ ৬ MB (Worker-এর মেমরি সীমার জন্য)।
 *
 * ইউটিউব: Worker-এ ইউটিউব আপলোডের কোনো রুট নেই (`/vid-wp` শুধু আগে থেকে ইউটিউবে থাকা ভিডিওর id নিয়ে WordPress পোস্ট বানায়), তাই ইউটিউবে এখনো সরাসরি আপলোড নেই।
 */
object VideoUpload {
    private const val CHUNK = 6 * 1024 * 1024
    private const val TRIES = 3

    data class FbResult(val id: String, val link: String)

    /** ভিডিওর সাইজ (বাইট); না পেলে -1 */
    fun sizeOf(ctx: Context, uri: Uri): Long {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) { val s = c.getLong(0); if (s > 0) return s }
            }
        }
        runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { a -> if (a.length > 0) return a.length }
        }
        return -1
    }

    /** একটানা পড়ার স্ট্রিম; আবার-চেষ্টায় অফসেট পিছিয়ে গেলে ফাইল নতুন করে খুলে সেখানে যায় */
    private class Reader(val ctx: Context, val uri: Uri) {
        private var ins: InputStream? = null
        private var pos = 0L
        fun read(offset: Long, len: Int): ByteArray {
            if (ins == null || offset != pos) {
                runCatching { ins?.close() }
                ins = ctx.contentResolver.openInputStream(uri) ?: throw ApiError("ভিডিও ফাইল খোলা যায়নি")
                var left = offset
                while (left > 0) { val s = ins!!.skip(left); if (s <= 0) throw ApiError("ভিডিও ফাইল পড়া যায়নি"); left -= s }
                pos = offset
            }
            val buf = ByteArray(len)
            var n = 0
            while (n < len) { val r = ins!!.read(buf, n, len - n); if (r < 0) break; n += r }
            if (n <= 0) throw ApiError("ভিডিও ফাইল পড়া যায়নি")
            pos = offset + n
            return if (n == len) buf else buf.copyOf(n)
        }
        fun close() { runCatching { ins?.close() } }
    }

    /** onProgress: ০..১০০ */
    suspend fun toFacebook(ctx: Context, uri: Uri, title: String, description: String, onProgress: (Int) -> Unit): FbResult {
        val size = sizeOf(ctx, uri)
        if (size <= 0) throw ApiError("ভিডিওর সাইজ জানা যায়নি")
        onProgress(0)
        val st = Worker.post(ctx, "/vid-fb-start", JSONObject().put("size", size), 90000)
        val session = st.optString("session")
        if (session.isBlank()) throw ApiError("আপলোড শুরু করা যায়নি")
        val videoId = st.optString("video_id")
        var start = st.optLong("start", 0)
        var end = st.optLong("end", 0)
        val rd = Reader(ctx, uri)
        try {
            var stalls = 0
            while (start < size) {
                coroutineContext.ensureActive()
                val want = if (end > start) minOf(end - start, CHUNK.toLong()) else CHUNK.toLong()
                val len = minOf(want, size - start).toInt()
                val data = rd.read(start, len)
                val r = chunk(ctx, session, start, data)
                val ns = r.optLong("start", start); val ne = r.optLong("end", 0)
                if (ns <= start) {   // এগোয়নি — অল্প আবার-চেষ্টা, তারপর ব্যর্থ
                    if (++stalls >= TRIES) throw ApiError("Facebook আপলোড আটকে গেছে, আবার চেষ্টা করুন")
                } else stalls = 0
                start = ns; end = ne
                onProgress((minOf(start, size) * 100 / size).toInt().coerceIn(0, 99))
            }
        } finally { rd.close() }
        val f = Worker.post(
            ctx, "/vid-fb-finish",
            JSONObject().put("session", session).put("video_id", videoId).put("title", title).put("description", description),
            3 * 60 * 1000 + 30000,
        )
        onProgress(100)
        return FbResult(f.optString("id"), f.optString("link"))
    }

    private suspend fun chunk(ctx: Context, session: String, offset: Long, data: ByteArray): JSONObject {
        var last: ApiError? = null
        repeat(TRIES) { attempt ->
            try {
                return postChunk(ctx, session, offset, data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // লগইন/সংযোগ-সেটিং/অনুমতির ভুলে আবার চেষ্টা বৃথা; নেটওয়ার্ক ও সার্ভার ত্রুটিতেই চেষ্টা
                if (e.status in 400..499 && e.status != 408 && e.status != 429) throw e
                last = e
                delay(1500L * (attempt + 1))
            }
        }
        throw last ?: ApiError("ভিডিওর অংশ পাঠানো যায়নি")
    }

    /** multipart: file (বাইনারি), session, offset — Worker `req.formData()` পড়ে */
    private suspend fun postChunk(ctx: Context, session: String, offset: Long, data: ByteArray): JSONObject = withContext(Dispatchers.IO) {
        fun go(tok: String): Pair<Int, String> {
            val boundary = "----np" + System.nanoTime()
            val head = ByteArrayOutputStream()
            fun part(name: String, value: String) {
                head.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray())
            }
            part("session", session); part("offset", offset.toString())
            head.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray())
            val tail = "\r\n--$boundary--\r\n".toByteArray()
            val total = head.size() + data.size + tail.size
            val c = URL(Cfg.WORKER + "/vid-fb-chunk").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 6 * 60 * 1000; c.doOutput = true
                c.setFixedLengthStreamingMode(total)
                c.setRequestProperty("Authorization", "Bearer $tok")
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.outputStream.use { o -> head.writeTo(o); o.write(data); o.write(tail) }
                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                return code to (s?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
            } catch (e: SocketTimeoutException) {
                throw ApiError("সময় শেষ হয়ে গেছে, আবার চেষ্টা করুন", 408)
            } catch (e: IOException) {
                throw ApiError("ইন্টারনেট সংযোগ নেই", 0)
            } finally {
                c.disconnect()
            }
        }
        var (code, text) = go(Session.token(ctx))
        if (code == 401) { val r = go(Session.token(ctx, force = true)); code = r.first; text = r.second }
        val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (code !in 200..299) throw ApiError(j.optString("error", "").ifBlank { "সমস্যা হয়েছে" }, code)
        j
    }
}
