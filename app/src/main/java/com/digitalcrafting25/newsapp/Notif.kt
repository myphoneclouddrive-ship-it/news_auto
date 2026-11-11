package com.digitalcrafting25.newsapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

object Notif {
    const val CH_RENDER = "render"
    const val CH_UPDATE = "update"
    const val CH_AI = "ai"

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RENDER, Lang.t("ভিডিও রেন্ডার"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_AI, Lang.t("AI লেখা"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_UPDATE, Lang.t("অ্যাপ আপডেট"), NotificationManager.IMPORTANCE_HIGH))
    }

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** চলমান রেন্ডারের প্রগ্রেস-নোটিফিকেশন (০% থেকেই বার + শতাংশ দেখায়); সার্ভিস চালু হলে একই id-তে সেটাই আপডেট হয় */
    fun progress(ctx: Context, id: Int, title: String, text: String, pct: Int) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_RENDER)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(Lang.t(title))
            .setContentText(Lang.t(text))
            .setProgress(100, pct.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }

    fun post(ctx: Context, id: Int, channel: String, title: String, text: String, pi: PendingIntent? = openApp(ctx)) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(Lang.t(title))
            .setContentText(Lang.t(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(Lang.t(text)))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
