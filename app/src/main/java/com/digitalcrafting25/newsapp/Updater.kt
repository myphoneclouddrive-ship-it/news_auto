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

    /** আপডেট APK রাখার গোপন ফোল্ডার: অ্যাপের নিজস্ব এক্সটার্নাল ডিরেক্টরি (ফাইল ম্যানেজারে দেখা যায় না) */
    fun apkDir(ctx: Context): File? = ctx.getExternalFilesDir("updates")

    fun apkFile(ctx: Context, versionCode: Int): File? = apkDir(ctx)?.let { File(it, "update-$versionCode.apk") }

    fun rememberDownload(ctx: Context, id: Long, versionCode: Int) {
        prefs(ctx).edit().putLong("dlId", id).putInt("dlVersion", versionCode).apply()
    }

    /** আগের ডাউনলোড (রেকর্ড ও ফাইল) মুছে দেয় */
    fun removePrevious(ctx: Context, dm: DownloadManager) {
        val old = prefs(ctx).getLong("dlId", -1L)
        if (old != -1L) runCatching { dm.remove(old) }
        apkDir(ctx)?.listFiles()?.forEach { it.delete() }
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
        val f = apkFile(ctx, info.versionCode) ?: return false
        if (!f.exists()) return false
        return info.sha256 == null || sha256(f).equals(info.sha256, true)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
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
            // একাধিক ভার্সন বাদ পড়ে থাকলে সবগুলোর "নতুন কী" একসাথে; না পারলে শুধু সর্বশেষটার নোট
            val notes = runCatching { notesSince(repo, BuildConfig.VERSION_CODE) }.getOrNull().orEmpty()
                .ifBlank { cleanNotes(body) }
            return UpdateInfo(code, j.optString("name", tag), url ?: return null, sha, notes)
        } finally {
            c.disconnect()
        }
    }

    /** রিলিজ নোটের লেখা থেকে `sha256:` লাইন বাদ */
    private fun cleanNotes(body: String): String {
        val t = body.lines().filterNot { val l = it.trim(); l.startsWith("sha256:") || l.startsWith("sha256-admin:") }.joinToString("\n").trim()
        // build.yml-এর ফলব্যাক ("সংস্করণ 2.0.53") আসল নোট নয় — দেখাবো না
        return if (Regex("^সংস্করণ\\s+\\S+$").matches(t)) "" else t
    }

    /**
     * ইন্সটল করা ভার্সনের (installed) পরের সব রিলিজের নোট, নতুন থেকে পুরোনো। একটাই রিলিজ হলে শুধু তার নোট;
     * একাধিক হলে প্রতিটার আগে ভার্সনের নাম। একই লেখা বারবার থাকলে একবারই দেখায়। ব্লকিং; ব্যর্থ হলে ব্যতিক্রম ছোঁড়ে।
     */
    private fun notesSince(repo: String, installed: Int): String {
        val c = URL("https://api.github.com/repos/$repo/releases?per_page=20").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 15000
        c.readTimeout = 20000
        try {
            if (c.responseCode !in 200..299) throw IOException("releases ${c.responseCode}")
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            val items = ArrayList<Triple<Int, String, String>>()   // কোড, নাম, নোট
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
                val tag = r.optString("tag_name")
                val code = tag.trim().removePrefix("v").toIntOrNull() ?: continue
                if (code <= installed) continue
                val body = cleanNotes(r.optString("body", ""))
                if (body.isNotBlank()) items += Triple(code, r.optString("name", tag), body)
            }
            items.sortByDescending { it.first }
            // একই লাইন একাধিক রিলিজের নোটে থাকলে (নোট জমা হয়ে গেলে) নতুনটাতেই একবার দেখায়
            val seenLines = HashSet<String>()
            val uniq = items.mapNotNull { (code, name, body) ->
                val kept = body.lines().filter { l -> val k = l.trim(); k.isEmpty() || seenLines.add(k) }.joinToString("\n").trim()
                if (kept.lines().none { it.trim().isNotEmpty() && !it.trim().startsWith("#") }) null else Triple(code, name, kept)
            }
            return when {
                uniq.isEmpty() -> ""
                uniq.size == 1 -> uniq[0].third
                else -> uniq.joinToString("\n\n") { "# ${it.second}\n${it.third}" }
            }
        } finally {
            c.disconnect()
        }
    }

    /** ওয়েব-ফলব্যাকে API ছাড়া নোট: ওই ট্যাগের কমিটের `RELEASE_NOTES.md` (raw.githubusercontent, লিমিট নেই) */
    private fun notesFromRaw(repo: String, tag: String): String {
        val c = URL("https://raw.githubusercontent.com/$repo/$tag/RELEASE_NOTES.md").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 15000
        c.readTimeout = 20000
        return try {
            if (c.responseCode in 200..299) cleanNotes(c.inputStream.bufferedReader().use { it.readText() }) else ""
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
        val notes = runCatching { notesFromRaw(repo, tag) }.getOrDefault("")
        return UpdateInfo(code, name, url, sha, notes)
    }

    fun isNewer(info: UpdateInfo) = info.versionCode > BuildConfig.VERSION_CODE
}
