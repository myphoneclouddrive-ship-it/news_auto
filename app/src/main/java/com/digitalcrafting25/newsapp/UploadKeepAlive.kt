package com.digitalcrafting25.newsapp

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * ভিডিও পাবলিশ (Facebook / YouTube / WordPress আপলোড) চলার সময় চালু থাকে: ফোরগ্রাউন্ড সার্ভিস + CPU ও ওয়াইফাই লক।
 * ফলে অ্যাপ ব্যাকগ্রাউন্ডে গেলে বা স্ক্রিন অফ হলেও বড় ভিডিওর আপলোড মাঝপথে থামে না; নোটিফিকেশনে % দেখায়।
 * পাবলিশ শেষ হলে (সফল/ব্যর্থ/বাতিল) VideoPublishState নিজে বন্ধ করে।
 */
class UploadKeepAliveService : Service() {

    companion object {
        private const val NOTIF_ID = 1004
        private const val DONE_ID = 1005
        /** চলছে কিনা — Net এটা দেখে এই সময়ে "ইন্টারনেট নেই" স্ক্রিন বসায় না */
        @Volatile var active = false
            private set
        @Volatile private var label = "আপলোড শুরু হচ্ছে…"
        @Volatile private var pct = -1
        private var lastShown = Long.MIN_VALUE

        private fun build(ctx: Context): Notification {
            val b = NotificationCompat.Builder(ctx, Notif.CH_RENDER)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(Lang.t("ভিডিও পাবলিশ হচ্ছে…"))
                .setContentText(Lang.t(label))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(Notif.openApp(ctx))
            if (pct < 0) b.setProgress(0, 0, true) else b.setProgress(100, pct.coerceIn(0, 100), false)
            return b.build()
        }

        fun start(ctx: Context, text: String = "আপলোড শুরু হচ্ছে…") {
            active = true; label = text; pct = -1; lastShown = Long.MIN_VALUE
            try {
                ctx.startForegroundService(Intent(ctx, UploadKeepAliveService::class.java))
            } catch (_: Exception) { /* শুরু করতে না দিলে আপলোড ঠিকই চলবে, শুধু ব্যাকগ্রাউন্ড সুরক্ষা থাকবে না */ }
        }

        /** নোটিফিকেশনের লেখা ও শতাংশ বদলায় (p < 0 = অনির্দিষ্ট বার); বারবার না ঝলকাতে সর্বোচ্চ সেকেন্ডে ১ বার */
        fun update(ctx: Context, text: String, p: Int) {
            val changed = text != label || (p < 0) != (pct < 0)
            label = text; pct = p
            val now = System.currentTimeMillis()
            if (!active || !Notif.canPost(ctx)) return
            if (!changed && now - lastShown < 1000) return
            lastShown = now
            try { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(ctx)) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            active = false
            try { ctx.stopService(Intent(ctx, UploadKeepAliveService::class.java)) } catch (_: Exception) {}
        }

        /** শেষ হলে নোটিফিকেশনে ফলাফল (অ্যাপ সামনে না থাকলে চোখে পড়ার জন্য) */
        fun finished(ctx: Context, text: String) {
            if (!AppState.foreground) Notif.post(ctx, DONE_ID, Notif.CH_RENDER, "ভিডিও পাবলিশ", text)
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "newsapp:upload")
                .apply { acquire(3 * 60 * 60 * 1000L) }   // নিরাপত্তা: ৩ ঘণ্টায় নিজে ছাড়ে
        }
        if (wifi == null) {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "newsapp:upload")
                .apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        wake = null; wifi = null
        super.onDestroy()
    }
}
