package com.digitalcrafting25.newsapp

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ফোরগ্রাউন্ড সার্ভিস: অ্যাপ ব্যাকগ্রাউন্ডে গেলেও রেন্ডার চলে, নোটিফিকেশনে % দেখায় */
@UnstableApi
class RenderService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.digitalcrafting25.newsapp.CANCEL_RENDER"
        private const val NOTIF_ID = 1001
        private const val DONE_ID = 1002
    }

    private val handler = Handler(Looper.getMainLooper())
    private val holder = ProgressHolder()
    private var transformer: Transformer? = null
    private var wake: PowerManager.WakeLock? = null
    private var outFile: File? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancel()
            return START_NOT_STICKY
        }
        val spec = RenderBus.spec
        if (spec == null || running) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(0, "শুরু হচ্ছে…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        begin(spec)
        return START_NOT_STICKY
    }

    private fun begin(spec: RenderSpec) {
        running = true
        RenderBus.status.value = RenderStatus.Running(0)
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "newsapp:render")
            .apply { acquire(3 * 60 * 60 * 1000L) }

        val out = File(cacheDir, "render_${System.currentTimeMillis()}.mp4")
        outFile = out
        try {
            val composition = RenderComposer.build(this, spec)
            val encoder = DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(spec.aspect.bitrate).build())
                .build()
            val t = Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        onExported()
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        fail(exportException.message ?: "রেন্ডার ব্যর্থ হয়েছে")
                    }
                })
                .build()
            transformer = t
            t.start(composition, out.absolutePath)
            handler.post(poll)
        } catch (e: Exception) {
            fail(e.message ?: "রেন্ডার শুরু করা যায়নি")
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (!running) return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                val p = holder.progress.coerceIn(0, 100)
                RenderBus.status.value = RenderStatus.Running(p)
                if (Notif.canPost(this@RenderService)) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(p, "ভিডিও রেন্ডার হচ্ছে — $p%"))
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun onExported() {
        handler.removeCallbacks(poll)
        val file = outFile ?: return fail("আউটপুট ফাইল পাওয়া যায়নি")
        Thread {
            try {
                val name = "news_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                val uri = saveToGallery(file, name)
                file.delete()
                handler.post {
                    RenderBus.status.value = RenderStatus.Done(uri)
                    finishUp()
                    Notif.post(this, DONE_ID, Notif.CH_RENDER, "✅ ভিডিও তৈরি হয়েছে", "গ্যালারি → Movies/NewsPublisher")
                }
            } catch (e: Exception) {
                handler.post { fail("সেভ করা যায়নি: ${e.message}") }
            }
        }.start()
    }

    private fun saveToGallery(file: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NewsPublisher")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore এ ফাইল বানানো যায়নি")
        contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun cancel() {
        handler.removeCallbacks(poll)
        runCatching { transformer?.cancel() }
        outFile?.delete()
        RenderBus.status.value = RenderStatus.Idle
        finishUp()
    }

    private fun fail(msg: String) {
        handler.removeCallbacks(poll)
        outFile?.delete()
        RenderBus.status.value = RenderStatus.Failed(msg)
        finishUp()
        Notif.post(this, DONE_ID, Notif.CH_RENDER, "❌ রেন্ডার হয়নি", msg)
    }

    private fun finishUp() {
        running = false
        transformer = null
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(progress: Int, text: String): Notification {
        val cancelPi = PendingIntent.getService(
            this, 1,
            Intent(this, RenderService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notif.CH_RENDER)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("নিউজ পাবলিশার")
            .setContentText(text)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notif.openApp(this))
            .addAction(0, "বাতিল", cancelPi)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }
}
