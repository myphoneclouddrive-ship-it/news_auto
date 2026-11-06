package com.digitalcrafting25.newsapp

import android.app.DownloadManager
import android.content.Context
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

    /** GitHub Releases থেকে সর্বশেষ রিলিজ আনে। রিলিজ না থাকলে null। ব্লকিং — IO থ্রেডে ডাকুন। */
    fun fetchLatest(): UpdateInfo? {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) throw IOException("UPDATE_REPO সেট নেই (GitHub Actions দিয়ে বিল্ড করুন)")
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15000
        c.readTimeout = 20000
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw IOException("GitHub থেকে সাড়া এসেছে ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.getString("tag_name")
            val code = tag.trim().removePrefix("v").toIntOrNull() ?: return null
            val assets = j.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); break }
            }
            val body = j.optString("body", "")
            val sha = Regex("sha256:\\s*([0-9a-fA-F]{64})").find(body)?.groupValues?.get(1)
            val notes = body.lines().filterNot { it.trim().startsWith("sha256:") }.joinToString("\n").trim()
            return UpdateInfo(code, j.optString("name", tag), url ?: return null, sha, notes)
        } finally {
            c.disconnect()
        }
    }

    fun isNewer(info: UpdateInfo) = info.versionCode > BuildConfig.VERSION_CODE
}
