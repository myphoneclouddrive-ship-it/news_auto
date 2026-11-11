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
        const val NOTIF_ID = 1001
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
        val list = RenderBus.passes
        if (list.isEmpty() || running) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(0, "রেন্ডার হচ্ছে — 0%"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        begin(list)
        return START_NOT_STICKY
    }

    @Volatile private var renderToken = 0
    private var mixFile: File? = null
    // একাধিক পাস (ফেইসবুক + ইউটিউব): একটার পর একটা রেন্ডার, শেষে সবগুলো একসাথে Done
    private var passes: List<RenderPass> = emptyList()
    private var passIdx = 0
    private val outputs = ArrayList<RenderOutput>()
    private var passStamp = ""

    private fun begin(list: List<RenderPass>) {
        passes = list
        passIdx = 0
        outputs.clear()
        passStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        running = true
        RenderBus.status.value = RenderStatus.Running(0, 1, list.size, list.first().label)
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "newsapp:render")
            .apply { acquire(3 * 60 * 60 * 1000L) }
        beginPass()
    }

    /** এখনকার পাসের রেন্ডার শুরু (অডিও মেশানো লাগলে আগে সেটা) */
    private fun beginPass() {
        val pass = passes[passIdx]
        val spec = pass.spec
        val all0 = publishProgress(0)
        // ০% থেকেই নোটিফিকেশনে বার + শতাংশ (আগে প্রথম অগ্রগতি আসা পর্যন্ত শতাংশ দেখাত না)
        if (Notif.canPost(this)) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(all0, passText("রেন্ডার হচ্ছে — $all0%")))
        }
        val out = File(cacheDir, "render_${System.currentTimeMillis()}_${pass.mode}.mp4")
        outFile = out

        if (!AudioMixer.needsMix(spec)) {
            startTransform(spec, null)
            return
        }

        // ধাপ ৫: আগে ব্যাকগ্রাউন্ড থ্রেডে সব শব্দ মেশানো, তারপর Transformer
        if (Notif.canPost(this)) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(all0, passText("অডিও মেশানো হচ্ছে — $all0%")))
        }
        val token = ++renderToken
        val wav = File(cacheDir, "mix_${System.currentTimeMillis()}.wav")
        mixFile = wav
        Thread {
            try {
                AudioMixer.mix(this, spec, wav) { token != renderToken }
                handler.post {
                    if (token != renderToken || !running) wav.delete()
                    else startTransform(spec, Uri.fromFile(wav))
                }
            } catch (_: AudioMixer.Cancelled) {
                wav.delete()
            } catch (e: Throwable) {
                wav.delete()
                handler.post { if (token == renderToken && running) fail("অডিও মেশানো যায়নি: ${e.message}") }
            }
        }.start()
    }

    private fun startTransform(spec: RenderSpec, mixedAudio: Uri?) {
        if (outFile == null) return fail("আউটপুট ফাইল পাওয়া যায়নি")
        val out = outFile!!
        try {
            val composition = RenderComposer.build(this, spec, mixedAudio)
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

    /** পাসের নাম/নম্বর সহ নোটিফিকেশন-লেখা (একটাই পাস হলে আগের মতো সাদামাটা) */
    private fun passText(base: String): String =
        if (passes.size > 1) "${passes[passIdx].label} ভিডিও (${passIdx + 1}/${passes.size}) — $base" else base

    /** এই পাসের % (০..১০০) থেকে সব পাস মিলিয়ে % বের করে পাঠায়; রিটার্ন = মোট % */
    private fun publishProgress(passPercent: Int): Int {
        val n = passes.size.coerceAtLeast(1)
        val total = ((passIdx * 100 + passPercent.coerceIn(0, 100)) / n).coerceIn(0, 100)
        RenderBus.status.value = RenderStatus.Running(total, passIdx + 1, n, passes.getOrNull(passIdx)?.label.orEmpty())
        return total
    }

    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (!running) return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                val p = holder.progress.coerceIn(0, 100)
                val all = publishProgress(p)
                if (Notif.canPost(this@RenderService)) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(all, passText("রেন্ডার হচ্ছে — $all%")))
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun onExported() {
        handler.removeCallbacks(poll)
        val file = outFile ?: return fail("আউটপুট ফাইল পাওয়া যায়নি")
        val pass = passes[passIdx]
        val multi = passes.size > 1
        RenderBus.summary += "\n${pass.label} রেন্ডারে ওভারলের ফ্রেম আঁকা: ${LayerFrameOverlay.calls.getAndSet(0)}"
        // পরের পাসের স্পেক হুবহু একই হলে (যেমন ব্যান্ড/ওপরের ব্যানার নেই) আবার রেন্ডার না করে এই ফাইলটাই কাজে লাগে
        val reuse = passIdx + 1 < passes.size && passes[passIdx + 1].spec == pass.spec
        Thread {
            try {
                // দুই ভিডিও হলে ফাইলের নামে প্ল্যাটফর্ম থাকে (HTML: facebook.mp4 / youtube.mp4)
                val name = "news_" + passStamp + (if (multi) (if (pass.mode == "fb") "_facebook" else "_youtube") else "") + ".mp4"
                // গ্যালারিতে নিজে সেভ হয় না: অ্যাপের নিজস্ব ফোল্ডারে থাকে; "💾 সেভ করুন" চাপলে তবেই গ্যালারিতে যায়
                val dir = File(filesDir, "renders").apply { mkdirs() }
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                val kept = LastRender.keptPaths(this)   // "শেষ রেন্ডার" অ্যাপ বন্ধ করে খুললেও ফিরবে, তাই ওগুলো মোছা হয় না
                dir.listFiles()?.forEach { if (it.lastModified() < cutoff && it.absolutePath !in kept) runCatching { it.delete() } }   // একদিনের পুরোনো রেন্ডার মোছা
                val dest = File(dir, name)
                file.copyTo(dest, overwrite = true)
                val uri = Uri.fromFile(dest)
                if (!reuse) file.delete()
                handler.post {
                    if (!running) { file.delete(); return@post }
                    val sp = pass.spec
                    outputs += RenderOutput(pass.mode, uri, sp.aspect.w, sp.aspect.h + 2 * sp.fbBandPx.coerceAtLeast(0), name)
                    passIdx++
                    runCatching { mixFile?.delete() }
                    mixFile = null
                    if (passIdx < passes.size) {
                        if (reuse) {
                            publishProgress(100)
                            outFile = file
                            onExported()
                        } else {
                            beginPass()
                        }
                    } else {
                        // আগে ডিস্কে মনে রাখা (অ্যাপ ব্যাকগ্রাউন্ডে/বন্ধ থাকলেও), তারপর UI-কে জানানো
                        LastRender.onRendered(applicationContext, outputs.toList(), RenderBus.title, RenderBus.wp, RenderBus.merge, RenderBus.draftId)
                        RenderBus.status.value = RenderStatus.Done(outputs.toList())
                        finishUp()
                        Notif.post(
                            this, DONE_ID, Notif.CH_RENDER,
                            if (multi) "✅ ${outputs.size}টি ভিডিও তৈরি হয়েছে" else "✅ ভিডিও তৈরি হয়েছে",
                            "অ্যাপ খুলে \"🚀 পাবলিশ করুন\" চাপুন (বা \"💾 সেভ করুন\" দিয়ে গ্যালারিতে নিন)",
                        )
                    }
                }
            } catch (e: Exception) {
                handler.post { fail("ভিডিও ফাইল রাখা যায়নি: ${e.message}") }
            }
        }.start()
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
        renderToken++                       // চলমান অডিও-মিক্স থাকলে সেটা বাতিল
        runCatching { mixFile?.delete() }
        mixFile = null
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
            .setContentText(Lang.t(text))
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
