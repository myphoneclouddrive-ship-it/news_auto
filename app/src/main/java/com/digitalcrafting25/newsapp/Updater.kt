package com.digitalcrafting25.newsapp

import org.json.JSONObject
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
