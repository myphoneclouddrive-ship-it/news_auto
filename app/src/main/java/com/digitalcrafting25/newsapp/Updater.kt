package com.digitalcrafting25.newsapp

import android.app.DownloadManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String,
)

/** DownloadManager-এর ডাউনলোড অবস্থা */
data class DlStatus(val status: Int, val progress: Float)

object Updater {
    private const val PREFS = "updater"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** আপডেট APK রাখার পুরোনো গোপন ফোল্ডার (এখন আর ব্যবহার হয় না; আগের ভার্সনের রেখে যাওয়া ফাইল মোছার জন্য) */
    private fun legacyDir(ctx: Context): File? = ctx.getExternalFilesDir("updates")

    /** APK এখন ফোনের সাধারণ Download ফোল্ডারে যায় (ফাইল ম্যানেজারে দেখা যায়), যাতে সেখান থেকে হাতে ইন্সটল করা যায় */
    fun apkFileName(versionCode: Int): String = BuildConfig.APK_NAME.removeSuffix(".apk") + "-v$versionCode.apk"

    /** সিস্টেমের Download তালিকা (ফাইল ম্যানেজার/Downloads অ্যাপ) খোলে */
    fun openDownloads(ctx: Context): Boolean = runCatching {
        ctx.startActivity(android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun downloadId(ctx: Context): Long = prefs(ctx).getLong("dlId", -1L)

    fun rememberDownload(ctx: Context, id: Long, versionCode: Int) {
        prefs(ctx).edit().putLong("dlId", id).putInt("dlVersion", versionCode).apply()
    }

    /** আগের ডাউনলোড (রেকর্ড ও ফাইল) মুছে দেয় */
    fun removePrevious(ctx: Context, dm: DownloadManager) {
        val old = prefs(ctx).getLong("dlId", -1L)
        if (old != -1L) runCatching { dm.remove(old) }
        legacyDir(ctx)?.listFiles()?.forEach { it.delete() }
        prefs(ctx).edit().remove("dlId").remove("dlVersion").apply()
    }

    /**
     * অ্যাপ চালু হলে ডাকুন। ডাউনলোড করা ভার্সন ইন্সটল হয়ে গেলে
     * (বর্তমান ভার্সন >= ডাউনলোড করা ভার্সন) APK মুছে দেয়। ইন্সটলের আগে কিছু মোছে না।
     */
    fun cleanup(ctx: Context) {
        // আগের ভার্সনগুলোর রেখে যাওয়া ফাইল
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("update-") }?.forEach { it.delete() }
        File(ctx.filesDir, "updates").deleteRecursively()
        val dl = prefs(ctx).getInt("dlVersion", -1)
        if (dl != -1 && BuildConfig.VERSION_CODE >= dl) {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            removePrevious(ctx, dm)
        }
    }

    /** চলমান ডাউনলোডের অবস্থা; ডাউনলোডটি না পেলে (বাতিল/মোছা) null */
    fun queryStatus(dm: DownloadManager, id: Long): DlStatus? {
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            return DlStatus(st, p)
        }
    }

    /** ডাউনলোড করা ফাইলের SHA-256 ঠিক আছে কিনা (SHA না থাকলে true)। ব্লকিং — IO থ্রেডে ডাকুন। */
    fun verify(ctx: Context, info: UpdateInfo): Boolean {
        val id = downloadId(ctx)
        if (id == -1L) return false
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // Download ফোল্ডারের ফাইল সরাসরি পাথে না পড়ে DownloadManager-এর মাধ্যমে খোলা হয় (নতুন Android-এও কাজ করে)
        val pfd = runCatching { dm.openDownloadedFile(id) }.getOrNull() ?: return false
        return pfd.use {
            info.sha256 == null || sha256(android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(it.fileDescriptor))).equals(info.sha256, true)
        }
    }

    private fun sha256(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        input.use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = i.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private const val UA = "NewsPublisher-Updater"

    /**
     * সর্বশেষ রিলিজ আনে। রিলিজ না থাকলে null। ব্লকিং — IO থ্রেডে ডাকুন।
     * আগে GitHub API; লিমিট (403/429 — মোবাইল নেটে এক IP বহু জন ব্যবহার করে, তাই ঘন ঘন হয়) বা অন্য ত্রুটিতে
     * লিমিট-ছাড়া ওয়েব পেজের রিডাইরেক্ট দিয়ে (`/releases/latest` → `/releases/tag/vNNN`)।
     */
    fun fetchLatest(): UpdateInfo? {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) throw IOException("UPDATE_REPO সেট নেই (GitHub Actions দিয়ে বিল্ড করুন)")
        return try {
            fetchViaApi(repo)
        } catch (e: ApiLimited) {
            fetchViaWeb(repo) ?: throw IOException("GitHub API লিমিট (${e.code}); ওয়েব পেজ দিয়েও পাওয়া গেল না — একটু পরে আবার চেষ্টা করুন")
        } catch (e: IOException) {
            // নেটওয়ার্ক/অন্য সমস্যা: ওয়েব পথ চেষ্টা; সেটাও না হলে আসল ত্রুটি
            runCatching { fetchViaWeb(repo) }.getOrNull() ?: throw e
        }
    }

    private class ApiLimited(val code: Int) : IOException("GitHub থেকে সাড়া এসেছে $code")

    private fun fetchViaApi(repo: String): UpdateInfo? {
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.connectTimeout = 15000
        c.readTimeout = 20000
        try {
            val rc = c.responseCode
            if (rc == 404) return null
            if (rc == 403 || rc == 429) throw ApiLimited(rc)
            if (rc !in 200..299) throw IOException("GitHub থেকে সাড়া এসেছে $rc")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.getString("tag_name")
            val code = tag.trim().removePrefix("v").toIntOrNull() ?: return null
            val assets = j.getJSONArray("assets")
            var url: String? = null
            var assetSha: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == BuildConfig.APK_NAME) {
                    url = a.getString("browser_download_url")
                    // GitHub প্রতিটা ফাইলের নিজের sha256 দেয় ("sha256:<hex>") — এটাই সবচেয়ে নির্ভরযোগ্য
                    assetSha = Regex("^sha256:([0-9a-fA-F]{64})$").find(a.optString("digest", ""))?.groupValues?.get(1)
                    break
                }   // রিলিজে ইউজার ও অ্যাডমিন দুটো APK; নিজেরটা
            }
            val body = j.optString("body", "")
            val shaLabel = if (BuildConfig.IS_ADMIN) "sha256-admin" else "sha256"   // রিলিজ-নোটে দুই APK-র আলাদা লাইন
            val sha = assetSha ?: Regex("(?m)^\\s*" + shaLabel + ":\\s*([0-9a-fA-F]{64})\\s*$").find(body)?.groupValues?.get(1)
            return UpdateInfo(code, j.optString("name", tag), url ?: return null, sha, "")
        } finally {
            c.disconnect()
        }
    }

    /** API ছাড়া পথ: রিডাইরেক্টের Location থেকে ট্যাগ, expanded_assets পেজ থেকে APK লিংক ও (থাকলে) SHA-256 */
    private fun fetchViaWeb(repo: String): UpdateInfo? {
        val c = URL("https://github.com/$repo/releases/latest").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 15000
        c.readTimeout = 20000
        val tag: String = try {
            val rc = c.responseCode
            if (rc == 404) return null
            if (rc !in 300..399) throw IOException("GitHub ওয়েব থেকে সাড়া এসেছে $rc")
            val loc = c.getHeaderField("Location") ?: return null
            loc.substringAfter("/releases/tag/", "").substringBefore('?').substringBefore('#').trim('/')
        } finally {
            c.disconnect()
        }
        if (tag.isEmpty()) return null   // রিলিজ নেই (/releases-এ রিডাইরেক্ট)
        val code = tag.trim().removePrefix("v").toIntOrNull() ?: return null

        var url = "https://github.com/$repo/releases/download/$tag/${BuildConfig.APK_NAME}"   // build.yml-এর ফাইলের নাম
        var sha: String? = null
        runCatching {
            val h = URL("https://github.com/$repo/releases/expanded_assets/$tag").openConnection() as HttpURLConnection
            h.setRequestProperty("User-Agent", UA)
            h.connectTimeout = 15000
            h.readTimeout = 20000
            try {
                if (h.responseCode in 200..299) {
                    val html = h.inputStream.bufferedReader().use { it.readText() }
                    val nm = Regex.escape(BuildConfig.APK_NAME)
                    Regex("href=\"(/[^\"]+/releases/download/[^\"]+/" + nm + ")\"").find(html)?.groupValues?.get(1)
                        ?.let { url = "https://github.com$it" }
                    // নতুন GitHub অ্যাসেটের পাশে sha256 ডাইজেস্ট দেখায় (এই APK-র লিংকের ঠিক পরেরটা); না থাকলে যাচাই ছাড়াই (SHA null)
                    sha = Regex("href=\"/[^\"]+/releases/download/[^\"]+/" + nm + "\"[\\s\\S]{0,900}?sha256:([0-9a-fA-F]{64})").find(html)?.groupValues?.get(1)
                }
            } finally {
                h.disconnect()
            }
        }
        // build.yml: ভার্সন কোড = ১০০ + রান নম্বর, নাম = ২.০.রান নম্বর
        val name = if (code > 100) "2.0.${code - 100}" else tag
        return UpdateInfo(code, name, url, sha, "")
    }

    fun isNewer(info: UpdateInfo) = info.versionCode > BuildConfig.VERSION_CODE
}
