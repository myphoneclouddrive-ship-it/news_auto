package com.digitalcrafting25.newsapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * ফোনে Gemini অ্যাপ থাকলে প্রম্পট সেখানে পাঠানো (সাধারণ Share Intent)।
 * Gemini-র উত্তর/ছবি অ্যাপে অটো ফেরত আসে না — ইউজার নিজে ডাউনলোড করে আপলোড করে।
 * প্যাকেজ চেক করতে AndroidManifest-এ <queries> লাগে।
 */
object GeminiHandoff {
    const val PKG = "com.google.android.apps.bard"

    fun installed(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    /** সফলভাবে Gemini খুললে true। প্রম্পট ক্লিপবোর্ডেও কপি থাকে, যাতে Gemini টেক্সট না বসালে ইউজার পেস্ট করতে পারে। */
    fun send(ctx: Context, userPrompt: String): Boolean {
        val text = "Generate a realistic, photo-style image (not a cartoon), Bangladesh context: " + userPrompt
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("prompt", text))
        }
        return runCatching {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                setPackage(PKG)
            }
            ctx.startActivity(i)
            true
        }.getOrDefault(false)
    }
}
