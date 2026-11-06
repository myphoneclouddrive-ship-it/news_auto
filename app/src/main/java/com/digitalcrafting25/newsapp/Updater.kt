package com.digitalcrafting25.newsapp

import android.app.DownloadManager
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String,
)

object Updater {
    private const val PREFS = "updater"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** আপডেট APK রাখার গোপন ফোল্ডার: অ্যাপের নিজস্ব এক্সটার্নাল ডিরেক্টরি (ফাইল ম্যানেজারে দেখা যায় না) */
    fun apkDir(ctx: Context): File? = ctx.getExternalFilesDir("updates")

    /** নতুন ডাউনলোডের আইডি ও ভার্সন মনে রাখে */
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
     * অ্যাপ চালু হলে ডাকুন। যদি ডাউনলোড করা ভার্সন ইন্সটল হয়ে গিয়ে থাকে
     * (বর্তমান ভার্সন >= ডাউনলোড করা ভার্সন), APK মুছে দেয়।
     */
    fun cleanupInstalled(ctx: Context) {
        val dl = prefs(ctx).getInt("dlVersion", -1)
        if (dl != -1 && BuildConfig.VERSION_CODE >= dl) {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            removePrevious(ctx, dm)
        }
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
