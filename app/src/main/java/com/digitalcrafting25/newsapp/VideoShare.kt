package com.digitalcrafting25.newsapp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * রেন্ডার করা ভিডিও অন্য অ্যাপে পাঠানো (শেয়ার ইন্টেন্ট)।
 *
 * কেন সরাসরি আপলোড নয়: ফেসবুক/ওয়ার্ডপ্রেসের টোকেন শুধু Worker-এ থাকে (অ্যাপে ফেরত আসে না) আর ইউটিউবের কোনো সংযোগই নেই,
 * তাই অ্যাপ নিজে থেকে আপলোড করতে পারে না। শেয়ারে ভিডিও আগে থেকে সংযুক্ত হয়ে ওই অ্যাপ খোলে; পেইজ/চ্যানেল বাছা ও "পোস্ট" ইউজার নিজে চাপেন।
 * টাইটেল অনেক অ্যাপ ইন্টেন্ট থেকে নেয় না, তাই ক্লিপবোর্ডেও কপি করা হয়।
 */
object VideoShare {
    const val FACEBOOK = "com.facebook.katana"
    const val YOUTUBE = "com.google.android.youtube"

    /** pkg = নির্দিষ্ট অ্যাপ (না থাকলে বা খুলতে না পারলে সাধারণ শেয়ার-তালিকা)। ফেরত দেয় ইউজারকে দেখানোর বার্তা। */
    fun share(ctx: Context, uri: Uri, title: String, pkg: String? = null, appName: String = ""): String {
        val t = title.trim()
        var copied = false
        if (t.isNotEmpty()) {
            copied = runCatching {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("ভিডিও টাইটেল", t))
            }.isSuccess
        }
        fun intent() = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (t.isNotEmpty()) { putExtra(Intent.EXTRA_TEXT, t); putExtra(Intent.EXTRA_SUBJECT, t); putExtra(Intent.EXTRA_TITLE, t) }
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val tail = if (copied) " টাইটেল কপি করা আছে, দরকারে বসিয়ে দিন।" else ""
        if (pkg != null) {
            try {
                ctx.startActivity(intent().setPackage(pkg))
                return "✅ ভিডিও ${appName.ifBlank { "অ্যাপ" }}-এ সংযুক্ত হয়ে খুলেছে; সেখানে পেইজ/চ্যানেল বেছে পোস্ট করুন।$tail"
            } catch (_: ActivityNotFoundException) {
                // অ্যাপ ইন্সটল নেই → সাধারণ তালিকা
            } catch (_: SecurityException) {
            }
        }
        return try {
            ctx.startActivity(Intent.createChooser(intent(), "ভিডিও পাঠান").apply { if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            (if (pkg != null) "${appName.ifBlank { "ওই অ্যাপ" }} পাওয়া যায়নি, তাই শেয়ার-তালিকা খোলা হয়েছে।" else "✅ শেয়ার-তালিকা খুলেছে।") + tail
        } catch (e: Exception) {
            "❌ শেয়ার করা যায়নি: ${e.message ?: "অজানা সমস্যা"}"
        }
    }
}
