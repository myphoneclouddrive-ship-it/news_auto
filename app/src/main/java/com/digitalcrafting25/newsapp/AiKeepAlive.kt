package com.digitalcrafting25.newsapp

import android.app.Notification
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
 * AI দিয়ে লেখার সময় চালু থাকে: ফোরগ্রাউন্ড সার্ভিস + CPU ও ওয়াইফাই লক। ফলে স্ক্রিন অফ হলে বা অ্যাপ ব্যাকগ্রাউন্ডে গেলেও
 * ইন্টারনেট কাটে না, লেখা চলতেই থাকে; শেষে নোটিফিকেশনে জানায়। কাজ শেষ/বাতিল হলে নিজে বন্ধ হয়।
 */
class AiKeepAliveService : Service() {

    companion object {
        private const val NOTIF_ID = 1003
        /** চলছে কিনা — Net এটা দেখে এই সময়ে "ইন্টারনেট নেই" স্ক্রিন বসায় না */
        @Volatile var active = false
            private set

        fun start(ctx: Context) {
            active = true
            try {
                ctx.startForegroundService(Intent(ctx, AiKeepAliveService::class.java))
            } catch (_: Exception) { /* ব্যাকগ্রাউন্ড থেকে শুরু হতে না দিলে কাজ ঠিকই চলবে, শুধু লক থাকবে না */ }
        }

        fun stop(ctx: Context) {
            active = false
            try { ctx.stopService(Intent(ctx, AiKeepAliveService::class.java)) } catch (_: Exception) {}
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n: Notification = NotificationCompat.Builder(this, Notif.CH_AI)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AI খবর লিখছে…")
            .setContentText("অ্যাপ ব্যাকগ্রাউন্ডে গেলেও লেখা চলবে।")
            .setOngoing(true)
            .setContentIntent(Notif.openApp(this))
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "newsapp:ai")
                .apply { acquire(15 * 60 * 1000L) }   // নিরাপত্তা: ১৫ মিনিটে নিজে ছাড়ে
        }
        if (wifi == null) {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "newsapp:ai")
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
