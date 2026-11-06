package com.digitalcrafting25.newsapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
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

object Updater {
    const val ACTION_INSTALL = "com.digitalcrafting25.newsapp.INSTALL_RESULT"

    /** আপডেট APK রাখার গোপন ফোল্ডার: অ্যাপের ইন্টারনাল স্টোরেজ (অন্য অ্যাপ/ফাইল ম্যানেজার দেখতে পায় না) */
    private fun dir(ctx: Context) = File(ctx.filesDir, "updates").apply { mkdirs() }

    fun apkFile(ctx: Context, versionCode: Int) = File(dir(ctx), "update-$versionCode.apk")

    /** ডাউনলোড করা ফাইল আছে কিনা (ও ঠিক আছে কিনা) */
    fun isDownloaded(ctx: Context, info: UpdateInfo): Boolean {
        val f = apkFile(ctx, info.versionCode)
        return f.exists() && (info.sha256 == null || sha256(f).equals(info.sha256, true))
    }

    /**
     * অ্যাপ চালু হলে ডাকুন: যে আপডেট ইন্সটল হয়ে গেছে (ফাইলের ভার্সন <= বর্তমান ভার্সন)
     * বা অসম্পূর্ণ ডাউনলোড (.part) থাকলে মুছে দেয়। ইন্সটলের আগে কিছু মোছে না।
     */
    fun cleanup(ctx: Context) {
        // আগের ভার্সনগুলোর রেখে যাওয়া ফাইল
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("update-") }?.forEach { it.delete() }
        dir(ctx).listFiles()?.forEach { f ->
            val code = Regex("update-(\\d+)\\.apk$").find(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (code == null || code <= BuildConfig.VERSION_CODE) f.delete()
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

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = i.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** APK নামায় (আগে নামানো ও মিললে আবার নামায় না)। প্রগ্রেস 0..1। ব্লকিং — IO থ্রেডে ডাকুন। */
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dest = apkFile(ctx, info.versionCode)
        if (isDownloaded(ctx, info)) return dest
        dir(ctx).listFiles()?.forEach { it.delete() }

        val part = File(dir(ctx), "update-${info.versionCode}.apk.part")
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        try {
            if (c.responseCode !in 200..299) throw IOException("ডাউনলোড হয়নি (${c.responseCode})")
            val total = c.contentLengthLong
            c.inputStream.use { i ->
                part.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = i.read(buf)
                        if (n <= 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress((done.toFloat() / total).coerceIn(0f, 1f)) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
        if (info.sha256 != null && !sha256(part).equals(info.sha256, true)) {
            part.delete()
            throw IOException("ডাউনলোড করা ফাইল ঠিক নেই (SHA-256 মেলেনি)")
        }
        if (!part.renameTo(dest)) throw IOException("ফাইল সেভ করা যায়নি")
        return dest
    }

    /** PackageInstaller দিয়ে ইন্সটল। ফোন সবসময় ইউজারের কনফার্মেশন চাইবে ("Update" চাপতে হবে)। */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("app.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    s.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_INSTALL).setPackage(ctx.packageName)
            val pending = PendingIntent.getBroadcast(
                ctx, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pending.intentSender)
        }
    }
}

/** ইন্সটলারের ফলাফল ধরে: কনফার্মেশন স্ক্রিন খোলে, ব্যর্থ হলে নোটিফিকেশন দেয় */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (AppState.foreground) {
                        ctx.startActivity(confirm)
                    } else {
                        val pi = PendingIntent.getActivity(
                            ctx, 77, confirm,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                        Notif.post(ctx, 2001, Notif.CH_UPDATE, "আপডেট ইন্সটল", "ইন্সটল কনফার্ম করতে এখানে ট্যাপ করুন", pi)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { /* অ্যাপ নতুন ভার্সনে বদলে গেছে */ }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "অজানা সমস্যা"
                Notif.post(ctx, 2002, Notif.CH_UPDATE, "আপডেট ইন্সটল হয়নি", msg)
            }
        }
    }
}
