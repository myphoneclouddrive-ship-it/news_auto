package com.digitalcrafting25.newsapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ইউটিউব সংযোগ ও আপলোড (step62) — HTML-এর সেটিংসের "▶️ YouTube" অংশের মতো:
 * কোনো সিক্রেট লাগে না; Google লগইনে `youtube.upload` অনুমতি নিয়ে ভিডিও সরাসরি ইউটিউব চ্যানেলে যায় (Worker ঘুরে নয়)।
 *
 * HTML: ব্রাউজারে Google Identity Services + Web Client ID। অ্যাপে সেই Web Client ID চলে না;
 * এখানে Google Play services-এর Authorization API: Google Cloud প্রজেক্টে "Android" ধরনের OAuth client লাগে
 * (প্যাকেজ নাম + অ্যাপের সাইনিং SHA-1 মিললেই চলে, কোডে কিছু বসাতে হয় না)।
 * বাকি সব (YouTube Data API চালু, OAuth consent screen, Test users, অডিট না হলে ভিডিও প্রাইভেট থাকা, দিনে ~৬ ভিডিও কোটা) HTML-এর মতোই।
 */
object YouTube {
    const val SCOPE = "https://www.googleapis.com/auth/youtube.upload"
    private const val PREFS = "yt_conn"

    /** "সংযোগ ঠিক আছে" — শেষ সফল লগইন টেস্ট/আপলোডের পর true; মুছলে false */
    fun isConnected(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ok", false)
    fun setConnected(ctx: Context, v: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ok", v).apply() }

    /** Google Cloud-এ Android OAuth client বানাতে লাগে: অ্যাপের সাইনিং সার্টিফিকেটের SHA-1 (কোলন-সহ) */
    @Suppress("DEPRECATION")
    fun sha1(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val sig = pi.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching ""
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("")

    /** Google পপআপ/কনসেন্ট খোলার লঞ্চার সহ লগইন; `rememberYtLogin()` দিয়ে নিন */
    class Login(private val ctx: Context) {
        internal var launch: ((IntentSenderRequest) -> Unit)? = null
        private var pending: CompletableDeferred<String>? = null

        suspend fun token(): String {
            val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
            val res = try {
                suspendCancellableCoroutine { c ->
                    Identity.getAuthorizationClient(ctx).authorize(req)
                        .addOnSuccessListener { c.resume(it) }
                        .addOnFailureListener { c.resumeWithException(it) }
                }
            } catch (e: ApiException) {
                throw ApiError(explain(e))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ApiError("YouTube লগইন হয়নি: " + (e.message ?: "অজানা সমস্যা"))
            }
            if (res.hasResolution()) {   // প্রথমবার/অনুমতি লাগলে Google-এর অ্যাকাউন্ট বাছাইয়ের পপআপ
                val pi = res.pendingIntent ?: throw ApiError("YouTube লগইন পপআপ খোলা যায়নি")
                val l = launch ?: throw ApiError("YouTube লগইন পপআপ খোলা যায়নি")
                val d = CompletableDeferred<String>(); pending = d
                l(IntentSenderRequest.Builder(pi.intentSender).build())
                return d.await()
            }
            return res.accessToken ?: throw ApiError("YouTube অনুমতি মেলেনি")
        }

        internal fun onResult(resultCode: Int, data: Intent?) {
            val d = pending ?: return
            pending = null
            if (resultCode != Activity.RESULT_OK || data == null) { d.completeExceptionally(ApiError("YouTube লগইন বাতিল হয়েছে")); return }
            try {
                val r = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)
                val t = r.accessToken
                if (t.isNullOrBlank()) d.completeExceptionally(ApiError("YouTube অনুমতি মেলেনি")) else d.complete(t)
            } catch (e: ApiException) {
                d.completeExceptionally(ApiError(explain(e)))
            } catch (e: Exception) {
                d.completeExceptionally(ApiError("YouTube লগইন হয়নি: " + (e.message ?: "")))
            }
        }

        private fun explain(e: ApiException): String = when (e.statusCode) {
            10 -> "Google Cloud-এ এই অ্যাপের জন্য Android OAuth client মেলেনি (প্যাকেজ নাম বা SHA-1 ভুল)। সেটিংসের গাইড দেখুন।"
            7, 8 -> "ইন্টারনেট সংযোগ দেখুন, তারপর আবার চেষ্টা করুন"
            16 -> "YouTube লগইন বাতিল হয়েছে"
            else -> "YouTube লগইন হয়নি (কোড ${e.statusCode})"
        }
    }

    // ───────── আপলোড (YouTube resumable, ৮ MB টুকরো) ─────────
    private const val CHUNK = 8 * 1024 * 1024   // ২৫৬ KB-র গুণিতক
    private const val TRIES = 3

    data class Result(val id: String, val privacy: String) {
        val link: String get() = "https://www.youtube.com/watch?v=$id"
    }

    private fun clean(t: String, n: Int) = t.replace(Regex("[<>]"), "").take(n)

    /** HTML `ytUpload`-এর মতোই মেটাডেটা: public, categoryId 25, বাচ্চাদের জন্য নয় */
    suspend fun upload(ctx: Context, uri: Uri, title: String, desc: String, token: String, onProgress: (Int) -> Unit): Result {
        val size = VideoUpload.sizeOf(ctx, uri)
        if (size <= 0) throw ApiError("ভিডিওর সাইজ জানা যায়নি")
        onProgress(0)
        val meta = JSONObject()
            .put("snippet", JSONObject().put("title", clean(title, 100)).put("description", clean(desc, 4900)).put("categoryId", "25"))
            .put("status", JSONObject().put("privacyStatus", "public").put("selfDeclaredMadeForKids", false))
        val loc = withContext(Dispatchers.IO) { initSession(token, meta, size) }
        var sent = 0L
        var tries = 0
        val ins0 = ctx.contentResolver.openInputStream(uri) ?: throw ApiError("ভিডিও ফাইল খোলা যায়নি")
        var ins = ins0; var pos = 0L
        try {
            while (true) {
                coroutineContext.ensureActive()
                if (pos != sent) {   // আবার-চেষ্টায় অফসেট বদলালে ফাইল নতুন করে খুলে সেখানে যাই
                    runCatching { ins.close() }
                    ins = ctx.contentResolver.openInputStream(uri) ?: throw ApiError("ভিডিও ফাইল খোলা যায়নি")
                    var left = sent
                    while (left > 0) { val s = ins.skip(left); if (s <= 0) throw ApiError("ভিডিও ফাইল পড়া যায়নি"); left -= s }
                    pos = sent
                }
                val len = minOf(CHUNK.toLong(), size - sent).toInt()
                val buf = ByteArray(len)
                var n = 0
                while (n < len) { val r = ins.read(buf, n, len - n); if (r < 0) break; n += r }
                if (n < len) throw ApiError("ভিডিও ফাইল পড়া যায়নি")
                pos = sent + n
                val out = try {
                    withContext(Dispatchers.IO) { putChunk(loc, buf, sent, size) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    if (e.status in 400..499 && e.status != 408 && e.status != 429) throw e
                    if (++tries >= TRIES) throw e
                    delay(2000L * tries)
                    sent = withContext(Dispatchers.IO) { queryOffset(loc, size) }   // কতটুকু পৌঁছেছে জেনে সেখান থেকে
                    if (sent >= size) throw ApiError("আপলোড শেষ হয়েছে কিনা নিশ্চিত নয়; YouTube Studio-তে দেখুন, না থাকলে আবার চেষ্টা করুন")
                    continue
                }
                tries = 0
                if (out.first) {   // শেষ টুকরো গেছে, ভিডিও তৈরি
                    val j = out.second
                    val id = j.optString("id")
                    if (id.isBlank()) throw ApiError("YouTube ভিডিওর আইডি পাওয়া যায়নি")
                    onProgress(100)
                    return Result(id, j.optJSONObject("status")?.optString("privacyStatus").orEmpty())
                }
                sent += len
                onProgress((sent * 100 / size).toInt().coerceIn(0, 99))
            }
        } finally { runCatching { ins.close() } }
    }

    private fun err(code: Int, text: String): ApiError {
        val e = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
        val why = e?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        val m = e?.optString("message").orEmpty().ifBlank { why }
        return ApiError(
            when {
                why == "quotaExceeded" || m.contains("quota", true) -> "আজকের YouTube আপলোড কোটা শেষ, কাল আবার চেষ্টা করুন"
                code == 401 -> "YouTube লগইনের মেয়াদ শেষ, আবার চেষ্টা করুন"
                code == 403 -> "YouTube অনুমতি দেয়নি বা চ্যানেল নেই: $m"
                else -> "YouTube আপলোড হয়নি ($code) $m"
            },
            code,
        )
    }

    private fun initSession(token: String, meta: JSONObject, size: Long): String {
        val c = URL("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 60000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.setRequestProperty("X-Upload-Content-Length", size.toString())
            c.setRequestProperty("X-Upload-Content-Type", "video/mp4")
            c.outputStream.use { it.write(meta.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw err(code, c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
            return c.getHeaderField("Location") ?: throw ApiError("YouTube আপলোডের লিংক পাওয়া যায়নি")
        } catch (e: SocketTimeoutException) {
            throw ApiError("সময় শেষ হয়ে গেছে, আবার চেষ্টা করুন", 408)
        } catch (e: IOException) {
            throw ApiError("ইন্টারনেট সংযোগ নেই", 0)
        } finally { c.disconnect() }
    }

    /** ফেরত: (true, JSON) = শেষ, ভিডিও তৈরি; (false, _) = আরও টুকরো লাগবে */
    private fun putChunk(loc: String, data: ByteArray, offset: Long, total: Long): Pair<Boolean, JSONObject> {
        val c = URL(loc).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"; c.connectTimeout = 20000; c.readTimeout = 5 * 60 * 1000; c.doOutput = true
            c.instanceFollowRedirects = false   // 308 = "আরও দিন", রিডাইরেক্ট নয়
            c.setFixedLengthStreamingMode(data.size)
            c.setRequestProperty("Content-Type", "video/mp4")
            c.setRequestProperty("Content-Range", "bytes $offset-${offset + data.size - 1}/$total")
            c.outputStream.use { it.write(data) }
            val code = c.responseCode
            if (code == 308) return false to JSONObject()
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) return true to (runCatching { JSONObject(text) }.getOrDefault(JSONObject()))
            throw err(code, text)
        } catch (e: SocketTimeoutException) {
            throw ApiError("সময় শেষ হয়ে গেছে", 408)
        } catch (e: IOException) {
            throw ApiError("নেটওয়ার্ক সমস্যা, YouTube আপলোড থেমে গেছে", 0)
        } finally { c.disconnect() }
    }

    /** আপলোড-সেশনে এ পর্যন্ত কত বাইট পৌঁছেছে */
    private fun queryOffset(loc: String, total: Long): Long {
        val c = URL(loc).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"; c.connectTimeout = 20000; c.readTimeout = 30000; c.doOutput = true
            c.instanceFollowRedirects = false
            c.setFixedLengthStreamingMode(0)
            c.setRequestProperty("Content-Range", "bytes */$total")
            c.outputStream.close()
            val code = c.responseCode
            if (code == 308) {
                val r = c.getHeaderField("Range") ?: return 0L   // "bytes=0-12345"
                return (r.substringAfter("-", "").toLongOrNull() ?: -1L) + 1
            }
            throw ApiError("YouTube আপলোড আর চালানো যাচ্ছে না, নতুন করে চেষ্টা করুন", code)
        } catch (e: IOException) {
            throw ApiError("ইন্টারনেট সংযোগ নেই", 0)
        } finally { c.disconnect() }
    }
}

/** কম্পোজেবলে লগইন-অবজেক্ট: Google পপআপের ফলাফল এখানেই ফেরে */
@Composable
fun rememberYtLogin(): YouTube.Login {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val login = remember { YouTube.Login(ctx.applicationContext) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        login.onResult(r.resultCode, r.data)
    }
    login.launch = { req -> launcher.launch(req) }
    return login
}
