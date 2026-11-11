package com.digitalcrafting25.newsapp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "🚀 পাবলিশ করুন (Facebook+YouTube+WordPress)" — একটা বাটনে (HTML `vidPublish`-এর মতো):
 * ফেইসবুক → ইউটিউব → ওয়ার্ডপ্রেস, একটার পর একটা। যা হয়ে গেছে তা আবার যায় না (ডুপ্লিকেট ঠেকাতে),
 * ব্যর্থ হলে আবার চাপলে শুধু বাকিগুলো চেষ্টা হয়। সেটিংসে সংযোগ না থাকা প্ল্যাটফর্ম বাদ যায়।
 * আপলোড ViewModel-এর স্কোপে চলে, তাই পেইজ/ট্যাব বদলালেও থামে না।
 */
class VideoPublishState(private val app: Application, private val scope: CoroutineScope) {
    /** pct: -১ = অনির্দিষ্ট (চলমান) বার। state: 0 = চলছে/অপেক্ষায়, 1 = হয়ে গেছে, 2 = ব্যর্থ/বাদ, 3 = হয়েছে কিন্তু সতর্কতা */
    class Row(val state: Int, val pct: Int, val text: String, val link: String = "")

    class Need(val fb: Boolean, val yt: Boolean, val wp: Boolean, val skipped: List<String>, val allDone: Boolean = false)

    val rows = mutableStateMapOf<String, Row>()
    var busy by mutableStateOf(false)
        private set
    var msg by mutableStateOf("")
        private set
    var msgKind by mutableIntStateOf(0)   // 0 সাধারণ, 1 সফল, 2 সতর্কতা, 3 ত্রুটি
        private set
    var pub by mutableStateOf(PubState())
        private set

    fun say(text: String, kind: Int) { msg = text; msgKind = kind }

    /** সেভ করা অবস্থা ফেরানো + হয়ে যাওয়া প্ল্যাটফর্মগুলোর সারি দেখানো */
    fun restore(p: PubState) {
        if (busy) return
        pub = p; msg = ""; rows.clear(); paint()
    }

    fun forgetDone() { pub = PubState(); rows.clear(); LastRender.updatePub(app, pub) }

    private fun paint() {
        if (pub.fbDone) rows["fb"] = Row(1, 100, "✅ Facebook-এ পাবলিশ হয়েছে", pub.fbLink)
        if (pub.ytDone) rows["yt"] = Row(if (pub.ytWarn.isBlank()) 1 else 3, 100,
            "✅ YouTube-এ পাবলিশ হয়েছে" + if (pub.ytWarn.isBlank()) "" else "\n⚠️ ${pub.ytWarn}", pub.ytLink)
        if (pub.wpDone) rows["wp"] = Row(if (pub.wpWarn.isBlank()) 1 else 3, 100,
            "✅ WordPress-এ পাবলিশ হয়েছে" + if (pub.wpWarn.isBlank()) "" else "\n⚠️ ${pub.wpWarn}", pub.wpLink)
    }

    /** কোন কোন জায়গায় পাবলিশ হবে: রেন্ডার করা ভিডিও + টার্গেট (চিপ) অনুযায়ী (ওয়ার্ডপ্রেসে ইউটিউব ভিডিওই যায়) */
    fun names(outs: Map<String, RenderOutput>, tg: PubTargets): List<String> {
        val l = ArrayList<String>()
        val fb = outs["fb"] != null && tg.fb
        val yt = outs["yt"] != null && tg.yt
        if (fb) l += "Facebook"
        if (yt) { l += "YouTube"; if (tg.wp) l += "WordPress" }
        return l
    }

    fun label(outs: Map<String, RenderOutput>, tg: PubTargets): String {
        val n = names(outs, tg)
        return "🚀 পাবলিশ করুন" + if (n.isEmpty()) "" else " (" + n.joinToString("+") + ")"
    }

    /** সেটিংসে ফেইসবুক/ওয়ার্ডপ্রেসের সংযোগ আছে কিনা (জানা না গেলে null = চেষ্টা করা হবে) */
    private suspend fun ready(): Pair<Boolean?, Boolean?> = try {
        val f = Worker.post(app, "/creds-get", JSONObject(), 20000).optJSONObject("fields") ?: JSONObject()
        fun ok(k: String) = f.optJSONObject(k)?.optBoolean("set") == true
        (ok("FB_PAGE_ID") && ok("FB_TOKEN")) to (ok("WP_URL") && ok("WP_USER") && ok("WP_APP_PASS"))
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null to null }

    /** বাটন চাপার পর প্রথম ধাপ: কী কী যাবে ঠিক করা। null = কিছু করার নেই (কারণ msg-এ) */
    suspend fun prepare(outs: Map<String, RenderOutput>, title: String, tg: PubTargets): Need? {
        if (busy) return null
        if (title.isBlank()) { say("❌ আগে ভিডিও টাইটেল লিখুন", 3); return null }
        val planFb = outs["fb"] != null && tg.fb
        val planYt = outs["yt"] != null && tg.yt
        val planWp = planYt && tg.wp
        if (!planFb && !planYt) { say("❌ পাবলিশ করার মতো কিছু বাছা নেই (ওপরের চিপ থেকে + চেপে যোগ করুন)", 3); return null }
        var needFb = planFb && !pub.fbDone
        val needYt = planYt && !pub.ytDone
        var needWp = planWp && !pub.wpDone
        if (!needFb && !needYt && !needWp) return Need(false, false, false, emptyList(), allDone = true)
        val skipped = ArrayList<String>()
        say("", 0)
        val (fbOk, wpOk) = ready()
        if (needFb && fbOk == false) { needFb = false; skipped += "fb" }
        if (needWp && (wpOk == false || (!pub.ytDone && !needYt))) { needWp = false; skipped += "wp" }
        if (skipped.isNotEmpty() && !needFb && !needYt && !needWp) {
            say("⚠️ " + skipped.joinToString(", ") { nm(it) } + "-এর সংযোগ সেটিংসে দেওয়া নেই, তাই পাবলিশ করা গেল না। সেটিংসে সংযোগ দিন, অথবা নিচের ডাউনলোড বাটনে ভিডিও নামিয়ে নিন।", 2)
            return null
        }
        // টাকা: যেগুলো যাবে সেগুলোর দাম (Facebook ৫, YouTube ২, WordPress ৩) আগে যাচাই; কাটা হয় প্রতিটা সফল হওয়ার পর
        val items = buildList { if (needFb) add(VideoCharge.PUB_FB); if (needYt) add(VideoCharge.PUB_YT); if (needWp) add(VideoCharge.PUB_WP) }
        val vid = LastRender.ensureVid(app)
        if (vid == null) { say("❌ রেন্ডার পাওয়া যায়নি, আবার রেন্ডার করুন", 3); return null }
        val ck = try {
            VideoCharge.check(app, vid, items)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            say("❌ ব্যালেন্স যাচাই করা যায়নি: " + (e.message ?: "নেট সমস্যা") + "। কিছুই পাবলিশ হয়নি, আবার চেষ্টা করুন", 3); return null
        }
        if (!ck.ok) { say("❌ " + VideoCharge.lowMsg(ck) + "। কিছুই পাবলিশ হয়নি", 3); return null }
        chargeVid = vid
        return Need(needFb, needYt, needWp, skipped)
    }

    private var chargeVid: String? = null
    private var spent = 0.0
    private var balNow: Double? = null
    private var chargeMiss = false

    /** একটা প্ল্যাটফর্মে সফল হওয়ার পর তার টাকা কাটা (একই ভিডিওর জন্য একবারই কাটে) */
    private suspend fun charge(item: String) {
        val vid = chargeVid ?: return
        val d = VideoCharge.commit(app, vid, listOf(item))
        if (d == null) chargeMiss = true else { spent += d.charged; balNow = d.balance }
    }

    private fun money(): String {
        val sb = StringBuilder()
        if (spent > 0) sb.append("\n💰 ৳").append(Bn.num(spent.toInt())).append(" কাটা হয়েছে")
            .append(balNow?.let { " (ব্যালেন্স ৳" + Bn.num(it.toInt()) + ")" } ?: "")
        if (chargeMiss) sb.append("\n⚠️ কিছু টাকা কাটা যায়নি (নেট সমস্যা)")
        return sb.toString()
    }

    private fun nm(k: String) = when (k) { "fb" -> "Facebook"; "yt" -> "YouTube"; else -> "WordPress" }

    private fun save(p: PubState) { pub = p; LastRender.updatePub(app, p) }

    /** আসল পাবলিশ (ইউটিউব টোকেন আগেই নেওয়া থাকে, কারণ Google পপআপ ক্লিকের সাথে সাথেই খোলে) */
    fun run(need: Need, token: String?, title: String, outs: Map<String, RenderOutput>) {
        if (busy) return
        busy = true; say("", 0)
        spent = 0.0; balNow = null; chargeMiss = false
        UploadKeepAliveService.start(app)   // ব্যাকগ্রাউন্ডে গেলেও আপলোড চলবে (ফোরগ্রাউন্ড সার্ভিস + লক)
        for (k in listOf("fb", "yt", "wp")) rows.remove(k)
        if (need.fb) rows["fb"] = Row(0, 0, "অপেক্ষায়...")
        if (need.yt) rows["yt"] = Row(0, 0, "অপেক্ষায়...")
        if (need.wp) rows["wp"] = Row(0, 0, "অপেক্ষায়...")
        paint()
        for (k in need.skipped) rows[k] = Row(2, 0, "⏭️ ${nm(k)}-এর সংযোগ সেটিংসে দেওয়া নেই, তাই পাবলিশ হয়নি" +
            if (k == "wp" && !pub.ytDone && !need.yt) " (YouTube ছাড়া WordPress হয় না)" else "")
        scope.launch {
            val failed = ArrayList<String>()
            try {
                if (need.fb) {
                    try {
                        rows["fb"] = Row(0, 0, "আপলোড শুরু হচ্ছে...")
                        val r = VideoUpload.toFacebook(app, outs.getValue("fb").uri, title, title) { p ->
                            rows["fb"] = if (p >= 100) Row(0, -1, "আপলোড শেষ, Facebook-এ পোস্ট হচ্ছে...") else Row(0, p, "আপলোড হচ্ছে $p%")
                            UploadKeepAliveService.update(app, if (p >= 100) "Facebook: পোস্ট হচ্ছে…" else "Facebook আপলোড হচ্ছে $p%", if (p >= 100) -1 else p)
                        }
                        val link = r.link.ifBlank { "https://www.facebook.com/" + r.id }
                        save(pub.copy(fbId = r.id.ifBlank { "ok" }, fbLink = link))
                        charge(VideoCharge.PUB_FB)
                        rows["fb"] = Row(1, 100, "✅ Facebook-এ পোস্ট হয়েছে", link)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        failed += "Facebook"; rows["fb"] = Row(2, 0, "❌ " + (e.message ?: "আপলোড হয়নি"))
                    }
                }
                if (need.yt) {
                    try {
                        rows["yt"] = Row(0, 0, "আপলোড শুরু হচ্ছে...")
                        val r = YouTube.upload(app, outs.getValue("yt").uri, title, title, token ?: error("YouTube লগইন হয়নি")) { p ->
                            rows["yt"] = if (p >= 100) Row(0, -1, "আপলোড শেষ, YouTube ভিডিও তৈরি করছে...") else Row(0, p, "আপলোড হচ্ছে $p%")
                            UploadKeepAliveService.update(app, if (p >= 100) "YouTube: ভিডিও তৈরি হচ্ছে…" else "YouTube আপলোড হচ্ছে $p%", if (p >= 100) -1 else p)
                        }
                        val warn = if (r.privacy.isBlank() || r.privacy == "public") ""
                        else "YouTube ভিডিওটি \"${r.privacy}\" রেখেছে (নতুন API প্রজেক্টের অডিট না হলে এমন হয়)। YouTube Studio থেকে Public করুন, নইলে WordPress-এ চলবে না।"
                        save(pub.copy(ytId = r.id, ytWarn = warn))
                        charge(VideoCharge.PUB_YT)
                        rows["yt"] = Row(if (warn.isBlank()) 1 else 3, 100, "✅ YouTube-এ পোস্ট হয়েছে" + if (warn.isBlank()) "" else "\n⚠️ $warn", r.link)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        failed += "YouTube"; rows["yt"] = Row(2, 0, "❌ " + (e.message ?: "আপলোড হয়নি"))
                    }
                }
                if (need.wp) {
                    if (!pub.ytDone) {
                        failed += "WordPress"; rows["wp"] = Row(2, 0, "⏭️ YouTube ভিডিও না থাকায় WordPress-এ দেওয়া হয়নি")
                    } else {
                        try {
                            rows["wp"] = Row(0, -1, "WordPress-এ পোস্ট হচ্ছে...")
                            UploadKeepAliveService.update(app, "WordPress-এ পোস্ট হচ্ছে…", -1)
                            val yid = pub.ytId
                            val body = JSONObject().put("title", title).put("youtubeId", yid).put("youtubeUrl", "https://www.youtube.com/watch?v=$yid")
                                .put("thumb", "https://i.ytimg.com/vi/$yid/maxresdefault.jpg").put("facebook", pub.fbLink)
                            val r = Worker.post(app, "/vid-wp", body, 120000)
                            val link = r.optString("link")
                            val w = r.optString("warn")
                            save(pub.copy(wpDone = true, wpLink = link, wpWarn = w))
                            charge(VideoCharge.PUB_WP)
                            rows["wp"] = Row(if (w.isBlank()) 1 else 3, 100, "✅ WordPress-এ পোস্ট হয়েছে" + if (w.isBlank()) " (YouTube iframe সহ)" else "\n⚠️ $w", link)
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            failed += "WordPress"; rows["wp"] = Row(2, 0, "❌ " + (e.message ?: "WordPress-এ পোস্ট হয়নি"))
                        }
                    }
                }
            } finally { busy = false; UploadKeepAliveService.stop(app) }
            UploadKeepAliveService.finished(app, if (failed.isEmpty()) "✅ পাবলিশ সম্পন্ন" else "⚠️ হয়নি: " + failed.joinToString(", ") + "। অ্যাপ খুলে আবার চেষ্টা করুন।")
            if (failed.isNotEmpty()) say("⚠️ হয়নি: " + failed.joinToString(", ") + "। আবার \"পাবলিশ\" চাপলে শুধু এগুলো চেষ্টা হবে। যেগুলো হয়ে গেছে সেগুলো আবার যাবে না।" + money(), 3)
            else if (need.skipped.isNotEmpty()) say("✅ যেগুলোর সেটিংস আছে সেগুলো পাবলিশ হয়েছে। সেটিংস না থাকায় বাদ: " + need.skipped.joinToString(", ") { nm(it) } + "। চাইলে ডাউনলোড বাটনে ভিডিও নামিয়ে নিন।" + money(), 2)
            else say("✅ সব পাবলিশ সম্পন্ন।" + money(), 1)
        }
    }
}
