package com.digitalcrafting25.newsapp

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * সেটিংসের "🧹 ক্লিয়ার ভিডিও ক্যাশ এন্ড ডাটা" (ওয়েবের vcClearData)।
 *
 * কোন কোন জিনিস মোছে (অ্যাপে যা যা জমে):
 *  • রেন্ডার করা ভিডিও ও অডিও-মিক্সের অস্থায়ী ফাইল — `cacheDir/render_*.mp4`, `cacheDir/mix_*.wav`
 *  • বানানো ইন্ট্রো/আউট্রো কার্ডের ভিডিও — `filesDir/cards/`
 *  • সেটিংসের বিজ্ঞাপন/লোগো থেকে বানানো ছবি — `filesDir/settings_media/` (দরকারে আবার নিজে বানায়)
 *  • রেকর্ড করা ভয়েস — `filesDir/voice/`
 *  • ড্রাফটের জন্য কপি করা মিডিয়া — `filesDir/draft_media/`
 *
 * যা মোছে না: অসমাপ্ত ভিডিও এডিট (ড্রাফট ও এখন খোলা টাইমলাইন) আর সেগুলো যে ফাইল ব্যবহার করছে সেই ফাইল;
 * গ্যালারিতে সেভ করা ভিডিও; সেটিংস, লগইন, WordPress/Facebook/YouTube সংযোগ।
 */
object VideoCache {

    class Result(val files: Int, val bytes: Long, val kept: Int)

    private val RENDER = Regex("^render_.*\\.mp4$")
    private val MIX = Regex("^mix_.*\\.wav$")

    /** ব্লকিং — IO থ্রেডে ডাকুন। inUse = এখনো লাগছে এমন ফাইলের Uri (ড্রাফট + চলমান টাইমলাইন) */
    fun clear(ctx: Context, inUse: Set<String>): Result {
        var files = 0; var bytes = 0L; var kept = 0

        fun wipe(f: File, protect: Boolean) {
            if (!f.exists()) return
            if (f.isDirectory) { f.listFiles()?.forEach { wipe(it, protect) }; return }
            if (protect && Uri.fromFile(f).toString() in inUse) { kept++; return }
            val n = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) { files++; bytes += n }
        }

        ctx.cacheDir.listFiles()?.filter { it.isFile && (RENDER.matches(it.name) || MIX.matches(it.name)) }?.forEach { wipe(it, false) }
        wipe(File(ctx.filesDir, "cards"), true)
        wipe(File(ctx.filesDir, "settings_media"), true)
        wipe(File(ctx.filesDir, "voice"), true)
        // ড্রাফটের কপি করা মিডিয়া: যেগুলো আর কোথাও লাগছে না সেগুলোই যায়
        val before = File(ctx.filesDir, "draft_media").listFiles()?.size ?: 0
        DraftStore.deleteUnused(ctx, inUse)
        val after = File(ctx.filesDir, "draft_media").listFiles()?.size ?: 0
        files += (before - after).coerceAtLeast(0); kept += after
        return Result(files, bytes, kept)
    }

    fun sizeText(b: Long): String = when {
        b >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", b / 1_048_576.0)
        b >= 1024 -> (b / 1024).toString() + " KB"
        else -> "$b B"
    }
}
