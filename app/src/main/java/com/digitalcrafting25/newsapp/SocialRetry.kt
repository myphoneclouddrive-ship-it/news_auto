package com.digitalcrafting25.newsapp

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Instagram বা Threads-এর একটা ব্যর্থতা। media থাকলে পোস্ট আগেই হয়ে গেছে, শুধু কমেন্ট/রিপ্লাই বাকি */
data class SocialFail(val which: String, val error: String, val media: String) {
    val name: String get() = if (which == "ig") "Instagram" else "Threads"
}

object Social {
    /** পোস্টের ফলাফল থেকে ব্যর্থ Instagram/Threads-এর তালিকা (সফল বা বন্ধ থাকলে কিছু নেই) */
    fun fails(d: JSONObject): List<SocialFail> {
        val out = ArrayList<SocialFail>()
        if (!d.isNull("instagram_ok") && d.has("instagram_ok") && !d.optBoolean("instagram_ok", true))
            out.add(SocialFail("ig", d.optString("instagram_error").ifBlank { "কারণ অজানা" }, d.optString("instagram_media")))
        if (!d.isNull("threads_ok") && d.has("threads_ok") && !d.optBoolean("threads_ok", true))
            out.add(SocialFail("threads", d.optString("threads_error").ifBlank { "কারণ অজানা" }, d.optString("threads_media")))
        return out
    }

    /** সফল Instagram/Threads-এর নোট (আর এক্সট্রা কত টাকা কেটেছে) */
    fun okNotes(d: JSONObject): List<String> {
        val out = ArrayList<String>()
        if (d.optBoolean("instagram_ok", false)) out.add("✅ Instagram-এও পোস্ট হয়েছে।")
        if (d.optBoolean("threads_ok", false)) out.add("✅ Threads-এও পোস্ট হয়েছে।")
        chargeNote(d.optInt("social_charged", 0))?.let { out.add(it) }
        return out
    }

    fun chargeNote(n: Int): String? = if (n > 0) "💳 Instagram/Threads-এর জন্য এক্সট্রা ৳${Bn.num(n)} কাটা হয়েছে।" else null

    /** ইউজারের 🔁 রিট্রাই: শুধু ওই একটা (Instagram বা Threads) আবার চেষ্টা। টাকা কাটে না। */
    suspend fun retry(ctx: Context, f: SocialFail, fbPost: String, caption: String, link: String): JSONObject =
        Worker.post(
            ctx, "/social-retry",
            JSONObject().put("which", f.which).put("fbPost", fbPost).put("caption", caption).put("link", link).put("media", f.media),
            120000,
        )

    /** রিট্রাইয়ের উত্তর বসিয়ে নতুন তালিকা; সফল হলে ওই এন্ট্রি বাদ, না হলে নতুন এরর */
    fun apply(list: List<SocialFail>, f: SocialFail, d: JSONObject): List<SocialFail> =
        if (d.optBoolean("ok", false)) list.filter { it.which != f.which }
        else list.map {
            if (it.which == f.which) it.copy(error = d.optString("error").ifBlank { "কারণ অজানা" }, media = d.optString("media")) else it
        }
}

/** "⚠️ Instagram-এ যায়নি (কারণ)" লেখার নিচে 🔁 রিট্রাই বাটন */
@Composable
internal fun SocialFailRow(f: SocialFail, busy: Boolean, anyBusy: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            "⚠️ ${f.name}-এ যায়নি (${f.error})" + if (f.media.isNotBlank()) "। পোস্ট হয়েছে, শুধু কমেন্টটা যায়নি।" else "।",
            fontSize = 13.sp, color = Color(0xFFB26A00),
        )
        AppBtn(
            if (busy) "চেষ্টা চলছে..." else "🔁 রিট্রাই",
            Modifier.padding(top = 4.dp), color = C.Gray, small = true, enabled = !anyBusy, onClick = onRetry,
        )
    }
}

/**
 * সেটিংসে Instagram "সেটাপ করা হয়েছে" সুইচ চালু (আর Facebook সংযোগ আছে) অথবা Threads টোকেন সেভ থাকলে `avail` সত্য হয়।
 * তখন পোস্ট করার আগে টিক বক্স দেখায়। সেটিংস বদলালে বা পোস্টের পেইজ খুললে Worker থেকে নতুন করে পড়ে।
 */
object SocialPrefs {
    var ig by mutableStateOf(false)
        private set
    var th by mutableStateOf(false)
        private set
    val avail: Boolean get() = ig || th

    fun label(): String = if (ig && th) "Instagram ও Threads" else if (ig) "Instagram" else "Threads"

    /** `/creds-get`-এর fields থেকে বসায় */
    fun fromCreds(f: JSONObject) {
        fun set(k: String) = f.optJSONObject(k)?.optBoolean("set", false) == true
        ig = set("FB_TOKEN") && set("FB_PAGE_ID") && f.optJSONObject("IG_ENABLED")?.optString("value") == "1"
        th = set("THREADS_TOKEN")
    }

    suspend fun refresh(ctx: Context) {
        try {
            val d = Worker.post(ctx, "/creds-get", JSONObject(), 20000)
            d.optJSONObject("fields")?.let { fromCreds(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { /* অফলাইন হলে আগেরটাই থাকে */ }
    }
}

/** পোস্ট বাটনের উপরের টিক বক্স। Instagram/Threads সেটাপ না থাকলে কিছুই দেখায় না। */
@Composable
internal fun SocialCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    if (!SocialPrefs.avail) return
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text("${SocialPrefs.label()}-এও পোস্ট করুন", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("এক্সট্রা ${Rates.taka("ps")}। যেকোনো একটায় পোস্ট হলে কাটে, ব্যর্থ হলে কাটে না।", fontSize = 12.sp, color = C.Muted)
        }
    }
}
