package com.digitalcrafting25.newsapp

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * সব রেট (কোন কাজে ব্যালেন্স থেকে কত টাকা কাটে) এক জায়গায়। অ্যাডমিন অ্যাপের "💰 রেট" মেনু থেকে বদলানো যায়।
 * মান রাখে Worker (`/rates-get`, অ্যাডমিনের `/rates-set`); অ্যাপ লগইনের পর এনে ফোনে ক্যাশ করে রাখে।
 * আনতে না পারলে ক্যাশ, তাও না থাকলে নিচের ডিফল্ট। দেখানোর সব লেখা ও ব্যালেন্স-যথেষ্ট কিনা যাচাই এখান থেকে পড়ে;
 * আসল টাকা কাটে Worker, তাই Worker-ও একই রেট ব্যবহার করতে হবে (worker_rates_patch.js দেখুন)।
 */
object Rates {
    class Def(val key: String, val group: String, val label: String, val hint: String, val default: Int)

    val DEFS: List<Def> = listOf(
        Def("rw", "📝 নতুন পোস্ট", "✍️ AI দিয়ে পোস্ট লেখা", "AI লেখা সফল হওয়ার পর কাটে", 10),
        Def("mp", "📝 নতুন পোস্ট", "📝 নিজে পোস্ট তৈরি (AI ছাড়া)", "\"নিজে লিখব\" চাপলে কাটে", 5),
        Def("pc", "🖼️ ফোটোকার্ড", "🖼️ ফোটোকার্ড সেভ/শেয়ার (প্রতি কার্ড)", "একটা কার্ডের জন্য একবারই কাটে", 1),
        Def("pt", "🖼️ ফোটোকার্ড", "✨ ফোটোকার্ড টাইটেল জেনারেট", "AI নতুন টাইটেল লিখলে (সফল হলে) কাটে", 1),
        Def("oc", "🖼️ ফোটোকার্ড", "📷 ছবি থেকে লেখা পড়া", "ছবি থেকে লেখা পাওয়া গেলে (প্রতিবার) কাটে", 1),
        Def("mn", "🌅 শুভ সকাল", "🌅 অটো শুভ সকাল পোস্ট (দিনে একবার)", "প্রতিদিন যেদিন পোস্ট যায় সেদিন কাটে", 2),
        Def(VideoCharge.PUB_FB, "🎬 ভিডিও", "📘 ভিডিও পাবলিশ — Facebook", "ভিডিও Facebook-এ গেলে কাটে", 5),
        Def(VideoCharge.PUB_YT, "🎬 ভিডিও", "▶️ ভিডিও পাবলিশ — YouTube", "ভিডিও YouTube-এ গেলে কাটে", 2),
        Def(VideoCharge.PUB_WP, "🎬 ভিডিও", "🌐 ভিডিও পাবলিশ — WordPress", "ভিডিওর পোস্ট WordPress-এ গেলে কাটে", 3),
        Def(VideoCharge.DL_FB, "🎬 ভিডিও", "⬇️ ভিডিও ডাউনলোড — Facebook ভিডিও", "একই ভিডিওর জন্য একবারই কাটে", 3),
        Def(VideoCharge.DL_YT, "🎬 ভিডিও", "⬇️ ভিডিও ডাউনলোড — YouTube ভিডিও", "একই ভিডিওর জন্য একবারই কাটে", 2),
        Def("cb", "🧩 টেমপ্লেট", "🧩 আরেকটা কাস্টম টেমপ্লেট কেনা", "প্রথম কাস্টম টেমপ্লেট ফ্রি; এরপর প্রতিটার দাম", 500),
    )

    const val MAX = 99999
    private val map = mutableStateMapOf<String, Int>()
    private var loaded = false

    /** বর্তমান রেট (টাকা)। Compose-এর ভেতর পড়লে রেট বদলালে নিজে নতুন করে আঁকে */
    fun get(key: String): Int = map[key] ?: DEFS.firstOrNull { it.key == key }?.default ?: 0

    /** অ্যাপ চালুর সময় ফোনে ক্যাশ করা রেট ফিরিয়ে আনে */
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val s = ctx.getSharedPreferences("rates_cache", Context.MODE_PRIVATE).getString("json", null) ?: return
        runCatching { applyJson(JSONObject(s)) }
    }

    private fun applyJson(j: JSONObject) {
        for (d in DEFS) if (j.has(d.key)) {
            val v = j.optInt(d.key, -1)
            if (v in 0..MAX) map[d.key] = v
        }
    }

    /** Worker-এর পাঠানো `{rates:{…}}` বসিয়ে ক্যাশ করে */
    fun apply(ctx: Context, rates: JSONObject) {
        applyJson(rates)
        val out = JSONObject()
        for (d in DEFS) out.put(d.key, get(d.key))
        ctx.getSharedPreferences("rates_cache", Context.MODE_PRIVATE).edit().putString("json", out.toString()).apply()
    }

    /** Worker থেকে রেট আনে। strict = false হলে ব্যর্থ হলে চুপচাপ আগেরটাই থাকে; true হলে ব্যতিক্রম ছোঁড়ে (অ্যাডমিন পর্দায় দেখানোর জন্য) */
    suspend fun refresh(ctx: Context, strict: Boolean = false) {
        try {
            val r = Worker.post(ctx, "/rates-get", JSONObject(), 20000)
            val j = r.optJSONObject("rates") ?: throw ApiError("Worker রেট পাঠায়নি")
            apply(ctx, j)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (strict) throw e
        }
    }

    fun taka(key: String): String = "৳" + Bn.num(get(key))
}

/** 💰 অ্যাডমিনের রেট পর্দার স্টেট: প্রতিটা রেটের ঘরে লেখা মান + সেভ */
class RatesViewModel(private val app: Application) : AndroidViewModel(app) {
    val edit = mutableStateMapOf<String, String>()
    var loading by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)

    private fun resetEdits() { for (d in Rates.DEFS) edit[d.key] = Rates.get(d.key).toString() }

    fun reset() { loaded = false; msg = null; error = null; edit.clear() }

    fun load() {
        if (loading) return
        viewModelScope.launch {
            loading = true; error = null; msg = null
            try {
                Rates.refresh(app, strict = true)
                resetEdits(); loaded = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                resetEdits()
                error = (e.message ?: "রেট আনা যায়নি") + " (Worker-এ /rates-get ডিপ্লয় করা আছে কিনা দেখুন)"
            } finally { loading = false }
        }
    }

    fun valueOf(key: String): Int? = edit[key]?.trim()?.toIntOrNull()?.takeIf { it in 0..Rates.MAX }

    fun bump(key: String, d: Int) {
        val v = ((valueOf(key) ?: Rates.get(key)) + d).coerceIn(0, Rates.MAX)
        edit[key] = v.toString(); msg = null
    }

    fun setText(key: String, t: String) { edit[key] = t.filter(Char::isDigit).take(5); msg = null }

    fun toDefault(key: String) {
        edit[key] = (Rates.DEFS.firstOrNull { it.key == key }?.default ?: 0).toString(); msg = null
    }

    val dirty: Boolean get() = Rates.DEFS.any { valueOf(it.key) != Rates.get(it.key) }
    val invalid: Boolean get() = Rates.DEFS.any { valueOf(it.key) == null }

    fun save() {
        if (saving) return
        if (invalid) { error = "কোনো ঘর ফাঁকা বা ভুল। ০ থেকে ${Rates.MAX} এর মধ্যে সংখ্যা দিন"; return }
        viewModelScope.launch {
            saving = true; error = null; msg = null
            try {
                val body = JSONObject()
                for (d in Rates.DEFS) body.put(d.key, valueOf(d.key)!!)
                val r = Worker.post(app, "/rates-set", JSONObject().put("rates", body), 30000)
                Rates.apply(app, r.optJSONObject("rates") ?: body)
                resetEdits()
                msg = "✅ রেট সেভ হয়েছে। ইউজারদের অ্যাপে পরের বার খোলা/লগইনে নতুন রেট দেখাবে"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "সেভ করা যায়নি"
            } finally { saving = false }
        }
    }
}
