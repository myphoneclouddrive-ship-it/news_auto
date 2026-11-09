package com.digitalcrafting25.newsapp

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** পাবলিশের অবস্থা: কোথায় কোথায় হয়ে গেছে (আবার চাপলে শুধু বাকিগুলো যায়, হয়ে যাওয়াগুলো ডুপ্লিকেট হয় না) */
data class PubState(
    val fbId: String = "", val fbLink: String = "",
    val ytId: String = "", val ytWarn: String = "",
    val wpDone: Boolean = false, val wpLink: String = "", val wpWarn: String = "",
) {
    val fbDone: Boolean get() = fbId.isNotBlank() || fbLink.isNotBlank()
    val ytDone: Boolean get() = ytId.isNotBlank()
    val ytLink: String get() = if (ytId.isBlank()) "" else "https://www.youtube.com/watch?v=$ytId"
}

/** কোন কোন প্ল্যাটফর্মে পাবলিশ হবে (HTML `vidTg`): রেন্ডারের টিক, পরে চিপ দিয়ে ✕ / + করা যায়। ওয়ার্ডপ্রেসে ইউটিউব ভিডিওই যায় */
data class PubTargets(val fb: Boolean = false, val yt: Boolean = false, val wp: Boolean = false)

/**
 * শেষ রেন্ডার মনে রাখা (HTML-এর IndexedDB `vidRender` / restoreVid): নতুন রেন্ডার না হওয়া পর্যন্ত অ্যাপ বন্ধ করে খুললেও
 * রেন্ডার করা ভিডিও, টাইটেল ও পাবলিশের অবস্থা ফিরে আসে। ভিডিও ফাইল আগে থেকেই `filesDir/renders`-এ থাকে; এখানে শুধু তালিকা + টাইটেল।
 */
object LastRender {
    class Rec(val title: String, val outputs: List<RenderOutput>, val tg: PubTargets, val pub: PubState, val draftId: Long = 0L)

    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "last_render.json")

    fun load(ctx: Context): Rec? = synchronized(lock) {
        runCatching {
            val f = file(ctx)
            if (!f.exists()) return@runCatching null
            val j = JSONObject(f.readText())
            val arr = j.optJSONArray("outputs") ?: return@runCatching null
            val outs = ArrayList<RenderOutput>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = File(o.optString("path"))
                if (!p.exists() || p.length() == 0L) continue   // ফাইল মুছে গেলে বাদ
                outs += RenderOutput(o.optString("mode"), Uri.fromFile(p), o.optInt("w"), o.optInt("h"), o.optString("name"))
            }
            if (outs.isEmpty()) return@runCatching null
            outs.sortBy { if (it.mode == "fb") 0 else 1 }
            val pj = j.optJSONObject("pub") ?: JSONObject()
            val has = outs.map { it.mode }.toSet()
            val tg = if (j.has("tgFb")) PubTargets(j.optBoolean("tgFb"), j.optBoolean("tgYt"), j.optBoolean("tgWp"))
            else PubTargets("fb" in has, "yt" in has, j.optBoolean("wp") && "yt" in has)
            Rec(
                j.optString("title"), outs, tg,
                PubState(
                    pj.optString("fbId"), pj.optString("fbLink"), pj.optString("ytId"), pj.optString("ytWarn"),
                    pj.optBoolean("wpDone"), pj.optString("wpLink"), pj.optString("wpWarn"),
                ),
                j.optLong("draftId"),
            )
        }.getOrNull()
    }

    private fun write(ctx: Context, r: Rec) = synchronized(lock) {
        runCatching {
            val j = JSONObject().put("title", r.title).put("tgFb", r.tg.fb).put("tgYt", r.tg.yt).put("tgWp", r.tg.wp).put("draftId", r.draftId)
            j.put("outputs", JSONArray().also { a ->
                r.outputs.forEach { o ->
                    a.put(JSONObject().put("mode", o.mode).put("path", o.uri.path.orEmpty()).put("w", o.w).put("h", o.h).put("name", o.fileName))
                }
            })
            val p = r.pub
            j.put("pub", JSONObject().put("fbId", p.fbId).put("fbLink", p.fbLink).put("ytId", p.ytId).put("ytWarn", p.ytWarn)
                .put("wpDone", p.wpDone).put("wpLink", p.wpLink).put("wpWarn", p.wpWarn))
            val tmp = File(ctx.filesDir, "last_render.json.tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(file(ctx))) { file(ctx).writeText(j.toString()); tmp.delete() }
        }
    }

    /**
     * রেন্ডার শেষ (RenderService থেকে, অ্যাপ ব্যাকগ্রাউন্ডে থাকলেও): নতুন রেন্ডার আগেরটার জায়গা নেয়।
     * merge = true হলে (পেইজ থেকে অন্য ভার্সন রেন্ডার) আগের রেন্ডারের সাথে জোড়া লাগে, টাইটেল/টার্গেট/পাবলিশের অবস্থা থাকে।
     * draftId = যে এডিট ড্রাফট থেকে রেন্ডার (ড্রাফট মুছলে এই রেন্ডারও মোছা হয়, HTML-এর মতো)
     */
    fun onRendered(ctx: Context, fresh: List<RenderOutput>, title: String, wp: Boolean, merge: Boolean, draftId: Long) {
        val old = if (merge) load(ctx) else null
        val outs = ((old?.outputs ?: emptyList()).filter { o -> fresh.none { it.mode == o.mode } } + fresh)
            .sortedBy { if (it.mode == "fb") 0 else 1 }
        val has = outs.map { it.mode }.toSet()
        val newModes = fresh.map { it.mode }.toSet()
        val tg = if (old == null) PubTargets("fb" in has, "yt" in has, wp && "yt" in has)
        else PubTargets(old.tg.fb || "fb" in newModes, old.tg.yt || "yt" in newModes, old.tg.wp && "yt" in has)
        write(ctx, Rec(old?.title?.ifBlank { null } ?: title, outs, tg, old?.pub ?: PubState(), old?.draftId ?: draftId))
    }

    fun updateTitle(ctx: Context, title: String) { load(ctx)?.let { write(ctx, Rec(title, it.outputs, it.tg, it.pub, it.draftId)) } }
    fun updatePub(ctx: Context, pub: PubState) { load(ctx)?.let { write(ctx, Rec(it.title, it.outputs, it.tg, pub, it.draftId)) } }
    fun updateTargets(ctx: Context, tg: PubTargets) { load(ctx)?.let { write(ctx, Rec(it.title, it.outputs, tg, it.pub, it.draftId)) } }

    /** মনে রাখা রেন্ডার ও তার ভিডিও ফাইল মোছা (ক্যাশ ক্লিয়ার / লগআউট / ড্রাফট মোছা) */
    fun clear(ctx: Context) {
        val paths = keptPaths(ctx)
        synchronized(lock) { runCatching { file(ctx).delete() } }
        paths.forEach { runCatching { File(it).delete() } }
    }

    /** ২৪ ঘণ্টার পুরোনো রেন্ডার মোছার সময় এই ফাইলগুলো বাঁচিয়ে রাখা হয় */
    fun keptPaths(ctx: Context): Set<String> = load(ctx)?.outputs?.mapNotNull { it.uri.path }?.toSet().orEmpty()
}
