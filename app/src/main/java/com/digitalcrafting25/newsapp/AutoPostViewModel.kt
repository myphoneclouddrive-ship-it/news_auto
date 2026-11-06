package com.digitalcrafting25.newsapp

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ওয়েবের kind → বাংলা (KIND_BN) */
val AUTO_KIND_BN = mapOf(
    "clear" to "পরিষ্কার আবহাওয়া", "rain" to "বৃষ্টি", "thunder" to "বজ্রসহ বৃষ্টি",
    "cloudy" to "মেঘলা", "fog" to "কুয়াশা", "winter" to "শীত", "hot" to "গরম",
)

/** ওয়েবের autoTpl-এর অপশন */
val AUTO_TPLS = listOf(
    "auto" to "প্রতিদিন বদলে বদলে (৫টি)",
    "1" to "১: সোনালি সূর্যোদয়",
    "2" to "২: গোলাপি ভোর",
    "3" to "৩: সবুজ প্রকৃতি",
    "4" to "৪: নীল আকাশ",
    "5" to "৫: ক্রিম মিনিমাল",
)

class AutoPostViewModel(private val app: Application) : AndroidViewModel(app) {

    /** null = এখনো জানা যায়নি; false = মালিক নয় (ওয়েবের মতো প্যানেল লুকানো থাকবে) */
    var allowed by mutableStateOf<Boolean?>(null)
        private set
    /** Worker-এ এই এন্ডপয়েন্টই নেই (৪০৪) */
    var missing by mutableStateOf(false)
        private set
    var loadErr by mutableStateOf<String?>(null)
        private set

    var on by mutableStateOf(false)
    var time by mutableStateOf("06:30")
    var tpl by mutableStateOf("auto")
    var siteUrl by mutableStateOf("")
    var rashi by mutableStateOf(true)

    var status by mutableStateOf<Pair<Boolean, String>?>(null)
        private set
    var msg by mutableStateOf<Pair<Boolean?, String>?>(null)   // first: true সবুজ, false লাল, null ধূসর
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var previewBmp by mutableStateOf<Bitmap?>(null)
        private set
    var previewTitle by mutableStateOf("")
        private set
    /** "আজ আগেই হয়ে গেছে" — আবার পোস্ট করবেন কিনা জিজ্ঞাসা */
    var askAgain by mutableStateOf<String?>(null)
        private set

    private var loaded = false

    private fun cfg() = AutoCfg(on = on, time = time.ifBlank { "06:30" }, tpl = tpl, siteUrl = siteUrl.trim(), rashi = rashi)

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        if (busy != null) return
        busy = "load"
        viewModelScope.launch {
            try {
                val (c, r) = AutoApi.get(app)
                on = c.on; time = c.time; tpl = c.tpl; siteUrl = c.siteUrl; rashi = c.rashi
                var t = if (c.on) "🟢 চালু, প্রতিদিন ${c.time}-এ" else "⚪ বন্ধ"
                var ok = c.on
                if (r != null) {
                    if (r.done && r.ok) { t += " · ✅ আজ পোস্ট হয়েছে (${AUTO_KIND_BN[r.kind] ?: r.kind})"; ok = true }
                    else if (r.done) { t += " · ❌ আজ ব্যর্থ: ${r.err}"; ok = false }
                    else if (r.err.isNotBlank()) { t += " · ⏳ ${Bn.num(r.tries)} বার চেষ্টা হয়েছে, আবার চেষ্টা হবে: ${r.err}"; ok = false }
                }
                status = ok to t
                allowed = true; missing = false; loadErr = null; loaded = true
            } catch (e: ApiError) {
                when (e.status) {
                    403 -> { allowed = false; loaded = true }   // মালিক নয়
                    404 -> { allowed = true; missing = true; loadErr = null }
                    else -> { allowed = true; loadErr = e.message }
                }
            } catch (e: Exception) {
                allowed = true; loadErr = e.message ?: "অজানা সমস্যা"
            } finally {
                busy = null
            }
        }
    }

    private fun fail(e: Exception) {
        msg = false to ("⚠️ " + (e.message ?: "সমস্যা হয়েছে"))
    }

    fun save() {
        if (busy != null) return
        busy = "save"
        viewModelScope.launch {
            try {
                AutoApi.save(app, cfg())
                busy = null
                loaded = false; load(force = true)
                msg = true to "✅ সেভ হয়েছে"
            } catch (e: Exception) { fail(e) } finally { if (busy == "save") busy = null }
        }
    }

    fun preview() {
        if (busy != null) return
        busy = "preview"
        msg = null to "কার্ড বানানো হচ্ছে... (আবহাওয়া ও রাশি আনা হচ্ছে)"
        viewModelScope.launch {
            try {
                val p = AutoApi.preview(app, cfg())
                val bmp = withContext(Dispatchers.Default) {
                    val bytes = Base64.decode(p.card.substringAfter("base64,"), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } ?: throw ApiError("কার্ডের ছবি পড়া যায়নি")
                previewBmp = bmp; previewTitle = p.title
                msg = null to ("আজকের ধরন: " + (AUTO_KIND_BN[p.kind] ?: p.kind) +
                    (if (p.rashiShown) " · রাশি " + (if (p.ai) "AI বেছেছে" else "AI পারেনি, নিয়ম অনুযায়ী বাছা") else "") +
                    (if (p.weather) "" else " · ⚠️ আবহাওয়া আনা যায়নি"))
            } catch (e: Exception) { fail(e) } finally { busy = null }
        }
    }

    /** নিশ্চিত করার পর UI এটা ডাকে */
    fun postNow(again: Boolean = false) {
        if (busy != null) return
        busy = "now"
        askAgain = null
        msg = null to "পোস্ট হচ্ছে..."
        viewModelScope.launch {
            try {
                val (ok, err) = AutoApi.now(app, again)
                msg = ok to (if (ok) "✅ পোস্ট হয়েছে" else "❌ হয়নি: $err")
                busy = null
                loaded = false; load(force = true)
            } catch (e: ApiError) {
                if (!again && (e.message ?: "").contains("আগেই হয়ে গেছে")) {
                    askAgain = e.message
                    msg = null
                } else fail(e)
            } catch (e: Exception) { fail(e) } finally { if (busy == "now") busy = null }
        }
    }

    fun dismissAgain() { askAgain = null }

    fun reset() {
        allowed = null; missing = false; loadErr = null
        on = false; time = "06:30"; tpl = "auto"; siteUrl = ""; rashi = true
        status = null; msg = null; busy = null; previewBmp = null; previewTitle = ""; askAgain = null
        loaded = false
    }
}
