package com.digitalcrafting25.newsapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * অ্যাডমিনের ফোনে বিকাশ/নগদের "টাকা পেয়েছি" ও "Cash In" SMS ধরে Worker-এ পাঠানো (অটো কনফার্ম, ৳৫০০০ পর্যন্ত)। চালু/বন্ধ: অ্যাডমিন অ্যাপ → ব্যালেন্স ট্যাব।
 * এই ফাইলের রিসিভার শুধু অ্যাডমিন অ্যাপের ম্যানিফেস্টে (app/src/admin/AndroidManifest.xml) নিবন্ধিত; ইউজার অ্যাপে SMS-এর কোনো অনুমতি/রিসিভার নেই।
 * দুই পথ: (১) SMS আসামাত্র রিসিভার, (২) প্রতি ১৫ মিনিটে ইনবক্সে ছুটে-যাওয়া SMS খোঁজা (READ_SMS অনুমতি থাকলে) — অ্যাপ/ফোন বন্ধ বা ব্যাটারি সেভার মেরে দিলেও পরে ধরা পড়ে।
 * Worker-এ একই SMS দুবার গেলে ক্ষতি নেই (TrxID-র রেকর্ড একটাই, ব্যালেন্স যোগের চিহ্ন একবারই বসে)।
 */
object BkashPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("bkash", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = p(ctx).getBoolean("auto", false)
    fun setEnabled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("auto", on).apply()
    fun last(ctx: Context): String = p(ctx).getString("last", "") ?: ""
    fun setLast(ctx: Context, t: String) = p(ctx).edit().putString("last", Bn.dateTime(System.currentTimeMillis()) + " — " + t).apply()
    /** ইনবক্স স্ক্যানের শেষ সীমা (SMS-এর তারিখ, ms) */
    fun scanned(ctx: Context): Long = p(ctx).getLong("scanned", 0L)
    fun setScanned(ctx: Context, t: Long) = p(ctx).edit().putLong("scanned", t).apply()

    fun isTarget(address: String?): Boolean {
        val a = (address ?: "").replace(" ", "")
        return a.equals("bKash", true) || a.equals("Nagad", true)
    }
    fun canRead(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** সুইচ/অ্যাপ চালুর সময় ডাকা: চালু + READ_SMS থাকলে ১৫ মিনিটের স্ক্যান বসানো, নইলে সরানো */
    fun syncSchedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (enabled(ctx) && canRead(ctx)) {
            val req = PeriodicWorkRequestBuilder<BkashCatchUpWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            wm.enqueueUniquePeriodicWork("bk-catchup", ExistingPeriodicWorkPolicy.KEEP, req)
            wm.enqueue(OneTimeWorkRequestBuilder<BkashCatchUpWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())   // এখনই একবার
        } else wm.cancelUniqueWork("bk-catchup")
    }
}

enum class BkOutcome { DONE, RETRY, GIVEUP }

object BkashSend {
    /** একটা SMS Worker-এ পাঠানো। quiet = ছুটে-যাওয়া SMS পড়ার সময় (আগের "শেষ SMS" ফল ও নোটিফিকেশন ঘাঁটে না, নতুন হলেই লেখে) */
    suspend fun push(ctx: Context, sender: String, text: String, attempt: Int): BkOutcome {
        return try {
            val r = Worker.post(ctx, "/bkash-sms", JSONObject().put("sender", sender).put("text", text), 30000)
            val dup = r.optBoolean("dup")
            val who = if (r.optString("provider") == "nagad") "নগদ" else "বিকাশ"
            when {
                r.optBoolean("ignored") -> if (!dup) { /* টাকা-পাওয়ার SMS নয়: কিছু লিখি না, নইলে ক্যাশ-ইন ইত্যাদিতে "শেষ SMS" ভরে যায় */ }
                r.optBoolean("auto") -> {
                    val msg = who + " ৳" + Bn.num(r.optDouble("amount")) + " • TrxID " + r.optString("trx")
                    BkashPrefs.setLast(ctx, "✅ অটো যোগ হয়েছে: $msg")
                    Notif.post(ctx, 7000 + (r.optString("trx").hashCode() and 0xFFF), Notif.CH_UPDATE, "💳 ব্যালেন্স অটো যোগ হয়েছে", msg + "\nপাঠানো নম্বর: " + r.optString("from"))
                }
                !dup -> BkashPrefs.setLast(ctx, "📩 " + who + " ৳" + Bn.num(r.optDouble("amount")) + " • TrxID " + r.optString("trx") + " — অটো হয়নি: " + r.optString("why"))
            }
            BkOutcome.DONE
        } catch (e: ApiError) {
            // ৪xx (অ্যাডমিন নয়/ভুল SMS) আবার পাঠিয়ে লাভ নেই; লগইন/সার্ভার/নেটের সমস্যা হলে আবার চেষ্টা
            if (e.status in 400..499 && e.status != 401 && e.status != 429) {
                BkashPrefs.setLast(ctx, "⚠️ " + (e.message ?: "পাঠানো যায়নি")); BkOutcome.GIVEUP
            } else if (attempt >= 6) {
                BkashPrefs.setLast(ctx, "⚠️ বারবার চেষ্টা করেও Worker-এ পাঠানো যায়নি: " + (e.message ?: "")); BkOutcome.GIVEUP
            } else BkOutcome.RETRY
        } catch (e: Exception) {
            if (attempt >= 6) { BkashPrefs.setLast(ctx, "⚠️ নেট/লগইন সমস্যায় পাঠানো যায়নি"); BkOutcome.GIVEUP } else BkOutcome.RETRY
        }
    }
}

class BkashSmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!BkashPrefs.enabled(ctx)) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // লম্বা SMS কয়েক টুকরায় আসে: একই পাঠকের টুকরা জোড়া লাগানো
        msgs.groupBy { it.originatingAddress ?: "" }.forEach { (from, parts) ->
            if (!BkashPrefs.isTarget(from)) return@forEach   // শুধু "bKash" / "Nagad" নামের SMS
            val text = parts.joinToString("") { it.messageBody ?: "" }
            val req = OneTimeWorkRequestBuilder<BkashSmsWorker>()
                .setInputData(workDataOf("sender" to from, "text" to text))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)   // নেট না থাকলে/ব্যর্থ হলে আবার চেষ্টা
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}

class BkashSmsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val sender = inputData.getString("sender") ?: return Result.failure()
        val text = inputData.getString("text") ?: return Result.failure()
        return when (BkashSend.push(applicationContext, sender, text, runAttemptCount)) {
            BkOutcome.DONE -> Result.success()
            BkOutcome.RETRY -> Result.retry()
            BkOutcome.GIVEUP -> Result.failure()
        }
    }
}

/** ইনবক্সে গত ৩ দিনের (বা শেষ স্ক্যানের পরের) বিকাশ/নগদ SMS পড়ে Worker-এ পাঠায়: রিসিভার যেটা ধরতে পারেনি সেটা এখানে ধরা পড়ে */
class BkashCatchUpWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!BkashPrefs.enabled(ctx) || !BkashPrefs.canRead(ctx) || Session.uid == null) return Result.success()
        val since = maxOf(BkashPrefs.scanned(ctx), System.currentTimeMillis() - 3L * 24 * 3600 * 1000)
        data class Row(val addr: String, val body: String, val date: Long)
        val rows = ArrayList<Row>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                Telephony.Sms.DATE + " > ?", arrayOf(since.toString()), Telephony.Sms.DATE + " ASC",
            )?.use { c ->
                while (c.moveToNext() && rows.size < 100) {
                    val a = c.getString(0) ?: ""
                    if (BkashPrefs.isTarget(a)) rows.add(Row(a, c.getString(1) ?: "", c.getLong(2)))
                }
            }
        } catch (e: Exception) { return Result.success() }   // অনুমতি তুলে নেওয়া ইত্যাদি
        var upTo = since
        for (r in rows) {
            when (BkashSend.push(ctx, r.addr, r.body, runAttemptCount)) {
                BkOutcome.RETRY -> { BkashPrefs.setScanned(ctx, upTo); return Result.retry() }   // নেট নেই: যেটুকু হয়েছে সেটুকুর পর থেকে আবার
                else -> upTo = r.date
            }
        }
        if (rows.isNotEmpty()) BkashPrefs.setScanned(ctx, upTo)
        return Result.success()
    }
}
