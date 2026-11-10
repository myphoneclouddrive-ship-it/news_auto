package com.digitalcrafting25.newsapp

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * আপডেট APK ডাউনলোড শেষ হলে নোটিফিকেশন দেয়। ট্যাপ করলে সরাসরি ইন্সটলার না খুলে Download ফোল্ডার খোলে
 * (কিছু ফোনে সরাসরি ইন্সটলার খুললে "অজানা অ্যাপ ইন্সটল"-এর অনুমতির অপশন আসে না); ওখানে APK-তে ট্যাপ করে ইন্সটল করতে হয়।
 */
class UpdateDoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L || id != Updater.downloadId(ctx)) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val st = Updater.queryStatus(dm, id) ?: return
        if (st.status != DownloadManager.STATUS_SUCCESSFUL) return
        val pi = PendingIntent.getActivity(
            ctx, 7,
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Notif.post(
            ctx, 1006, Notif.CH_UPDATE, "নিউজ পাবলিশার — আপডেট ডাউনলোড হয়েছে",
            "Download ফোল্ডারে ${BuildConfig.APK_NAME.removeSuffix(".apk")} ফাইলটায় ট্যাপ করে ইন্সটল করুন।", pi,
        )
    }
}
