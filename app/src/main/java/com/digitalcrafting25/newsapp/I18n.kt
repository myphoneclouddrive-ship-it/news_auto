package com.digitalcrafting25.newsapp

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * অ্যাপের ভাষা (বাংলা / English)।
 *
 * - সোর্স কোডের সব UI লেখা বাংলাতেই আছে (আসল ভাষা)। English বাছলে দেখানোর সময় `Text(...)` (LocalizedText.kt) এই
 *   ডিকশনারি (assets/i18n/en.json) থেকে ইংরেজি বসায়; ডিকশনারিতে না থাকলে লেখা যেমন ছিল তেমনই দেখায়।
 * - প্রথমবার (কোনো ভাষা বাছা নেই) → LanguageChooserScreen আসে। বাছার পর সেটাই থাকে; সেটিংস → 🌐 ভাষা থেকে বদলানো যায়।
 * - কার্ড/ভিডিওর ভেতরের লেখা (ক্যানভাসে আঁকা কনটেন্ট), AI-কে পাঠানো নির্দেশ, ডেটা — এগুলো ভাষা বদলায় না।
 */
object Lang {
    const val BN = "bn"
    const val EN = "en"
    private const val PREFS = "app_lang"
    private const val KEY = "lang"

    /** null = এখনো বাছা হয়নি (প্রথমবার চালু) */
    var code: String? by mutableStateOf(null)
        private set

    val isEn: Boolean get() = code == EN
    val chosen: Boolean get() = code != null

    @Volatile private var appCtx: Context? = null

    fun init(ctx: Context) {
        val c = ctx.applicationContext
        appCtx = c
        val saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        code = if (saved == BN || saved == EN) saved else null
        if (code == EN) Dict.ensureLoaded(c)
    }

    fun set(ctx: Context, newCode: String) {
        if (newCode != BN && newCode != EN) return
        val c = ctx.applicationContext
        appCtx = c
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, newCode).apply()
        if (newCode == EN) Dict.ensureLoaded(c)
        code = newCode
        // নোটিফিকেশন চ্যানেলের নামও নতুন ভাষায় (একই id-তে createNotificationChannel নাম আপডেট করে)
        runCatching { Notif.init(c) }
    }

    /** যেকোনো জায়গা থেকে (কম্পোজেবল বা নন-কম্পোজেবল) বাংলা লেখাকে বর্তমান ভাষায় রূপান্তর */
    fun t(s: String): String {
        if (code != EN) return s
        val c = appCtx
        if (c != null) Dict.ensureLoaded(c)
        return Dict.translate(s)
    }
}

/**
 * বাংলা → English ডিকশনারি; assets/i18n/en.json ("e" = পুরো লেখা/টেমপ্লেট, "f" = জোড়া লাগানো টুকরো)।
 * মেলানোর ক্রম: ১) হুবহু মিল ২) `{0}` টেমপ্লেট ৩) লাইন ধরে ধরে ৪) টুকরো-বদল (`"..." + x + "..."` দিয়ে জোড়া লাগানো লেখার জন্য)।
 * ইউজারের নিজের বাংলা লেখা (শিরোনাম, নাম ইত্যাদি) যাতে ভুলে আংশিক বদলে না যায়, সেজন্য টেমপ্লেট/টুকরো-বদলে
 * (ক) শব্দের সীমানা মানা হয়, (খ) ফল তখনই নেওয়া হয় যখন মূল বাংলা অক্ষরের অন্তত ৩৫% ইংরেজি হয়েছে; নইলে লেখা যেমন ছিল তেমনই থাকে।
 */
internal object Dict {
    private class Tpl(val prefix: String, val suffix: String, val re: Regex, val en: String, val n: Int, val weight: Int)

    /** জোড়া-লাগানো টুকরো: plain != null হলে সাধারণ লেখা, নইলে re (প্লেসহোল্ডারসহ) */
    private class Frag(
        val plain: String?, val re: Regex?, val en: String, val n: Int, val lit: Int,
        val startPh: Boolean, val endPh: Boolean, val firstCh: Char, val lastCh: Char,
    )

    private val PH = Regex("\\{(\\d+)\\}")
    private const val MIN_COVER_PCT = 35

    @Volatile private var loaded = false
    private var exact: Map<String, String> = emptyMap()
    private var tpls: List<Tpl> = emptyList()
    private var frags: List<Frag> = emptyList()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try { load(ctx) } catch (e: Exception) { /* ডিকশনারি না পেলে বাংলাই দেখাবে */ }
            loaded = true
        }
    }

    private fun load(ctx: Context) {
        val txt = ctx.assets.open("i18n/en.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(txt)
        val e = root.getJSONObject("e")
        val f = root.getJSONObject("f")
        val ex = HashMap<String, String>(e.length() * 2)
        val tp = ArrayList<Tpl>()
        for (k in e.keys()) {
            val v = e.getString(k)
            if (PH.containsMatchIn(k)) {
                val b = buildRegex(k, anchored = true)
                val firstPh = PH.find(k)!!.range.first
                val lastPh = PH.findAll(k).last().range.last
                tp.add(Tpl(k.substring(0, firstPh), k.substring(lastPh + 1), b.re, v, b.n, b.lit))
            } else ex[k] = v
        }
        tp.sortByDescending { it.weight }
        val fr = ArrayList<Frag>()
        for (k in f.keys()) {
            val v = f.getString(k)
            if (k.isEmpty()) continue
            if (PH.containsMatchIn(k)) {
                val b = buildRegex(k, anchored = false)
                val startPh = k.startsWith("{")
                val endPh = k.endsWith("}")
                fr.add(Frag(null, b.re, v, b.n, b.lit, startPh, endPh, k.first(), k.last()))
            } else fr.add(Frag(k, null, v, 0, k.length, false, false, k.first(), k.last()))
        }
        fr.sortByDescending { it.lit }
        exact = ex; tpls = tp; frags = fr
    }

    private class Built(val re: Regex, val n: Int, val lit: Int)

    private fun buildRegex(key: String, anchored: Boolean): Built {
        val sb = StringBuilder()
        var last = 0; var n = 0; var lit = 0
        for (m in PH.findAll(key)) {
            val part = key.substring(last, m.range.first)
            sb.append(Regex.escape(part)); lit += part.length
            sb.append(if (anchored) "([\\s\\S]*?)" else "([^\\s•·|—]{1,60})")
            last = m.range.last + 1; n++
        }
        val tail = key.substring(last)
        sb.append(Regex.escape(tail)); lit += tail.length
        return Built(Regex(sb.toString()), n, lit)
    }

    private fun isBnLetter(c: Char) = c in '\u0980'..'\u09FF' && c !in '০'..'৯' && c != '৳'
    private fun isWord(c: Char) = isBnLetter(c) || (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')

    private fun hasBn(s: String): Boolean {
        for (ch in s) if (ch in '\u0980'..'\u09FF') return true
        return false
    }

    private fun letters(s: String): Int {
        var n = 0
        for (ch in s) if (isBnLetter(ch)) n++
        return n
    }

    /** অনুবাদের পর মূল বাংলা অক্ষরের অন্তত MIN_COVER_PCT% বদলেছে কিনা (ইউজারের নিজের লেখা বাঁচাতে) */
    private fun accept(orig: String, res: String): Boolean {
        val o = letters(orig)
        if (o == 0) return true
        return (o - letters(res)) * 100 >= o * MIN_COVER_PCT
    }

    private fun digits(s: String): String {
        var any = false
        for (ch in s) if (ch in '০'..'৯') { any = true; break }
        if (!any) return s
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch in '০'..'৯') ('0' + (ch - '০')) else ch)
        return sb.toString()
    }

    fun translate(s: String): String {
        if (s.isEmpty() || !hasBn(s)) return s
        cache[s]?.let { return it }
        val r = compute(s, 0)
        if (cache.size > 4000) cache.clear()
        cache[s] = r
        return r
    }

    private fun compute(s: String, depth: Int): String {
        exact[s]?.let { return digits(it) }
        if (depth >= 3) return s
        matchTpl(s, depth)?.let { return it }
        if (s.indexOf('\n') >= 0) {
            var changed = false
            val out = s.split('\n').map { ln ->
                if (!hasBn(ln)) ln else {
                    val t = compute(ln, depth + 1)
                    if (t != ln) changed = true
                    t
                }
            }
            if (changed) return out.joinToString("\n")
        }
        return fragPass(s, depth) ?: s
    }

    private fun matchTpl(s: String, depth: Int): String? {
        for (t in tpls) {
            if (s.length < t.prefix.length + t.suffix.length) continue
            if (!s.startsWith(t.prefix) || !s.endsWith(t.suffix)) continue
            val m = t.re.matchEntire(s) ?: continue
            var out = t.en
            for (k in 0 until t.n) {
                var g = m.groupValues[k + 1]
                if (hasBn(g)) g = compute(g, depth + 1)
                out = out.replace("{" + k + "}", g)
            }
            val res = digits(out)
            if (accept(s, res)) return res
        }
        return null
    }

    /** "টেক্সট" + x + "টেক্সট" দিয়ে জোড়া লাগানো লেখার জন্য: চেনা টুকরোগুলো আলাদা করে বদলানো */
    private fun fragPass(s: String, depth: Int): String? {
        var r = s
        for (f in frags) {
            if (!hasBn(r)) break
            r = if (f.plain != null) replacePlain(r, f) else replaceTpl(r, f, depth)
        }
        if (r == s) return null
        val res = digits(r)
        return if (accept(s, res)) res else null
    }

    private fun replacePlain(r: String, f: Frag): String {
        val k = f.plain!!
        var i = r.indexOf(k)
        if (i < 0) return r
        val out = StringBuilder()
        var last = 0
        while (i >= 0) {
            val end = i + k.length
            val okL = !isWord(f.firstCh) || i == 0 || !isWord(r[i - 1])
            val okR = !isWord(f.lastCh) || end >= r.length || !isWord(r[end])
            if (okL && okR) {
                out.append(r, last, i).append(f.en)
                last = end
                i = r.indexOf(k, end)
            } else i = r.indexOf(k, i + 1)
        }
        out.append(r, last, r.length)
        return out.toString()
    }

    private fun replaceTpl(r: String, f: Frag, depth: Int): String {
        val re = f.re!!
        val out = StringBuilder()
        var last = 0
        for (m in re.findAll(r)) {
            val st = m.range.first
            val en = m.range.last + 1
            val okL = if (f.startPh) (st == 0 || !isWord(r[st - 1])) else (!isWord(f.firstCh) || st == 0 || !isWord(r[st - 1]))
            val okR = if (f.endPh) (en >= r.length || !isWord(r[en])) else (!isWord(f.lastCh) || en >= r.length || !isWord(r[en]))
            if (!okL || !okR || st < last) continue
            var o = f.en
            for (k in 0 until f.n) {
                var g = m.groupValues[k + 1]
                if (hasBn(g)) g = compute(g, depth + 1)
                o = o.replace("{" + k + "}", g)
            }
            out.append(r, last, st).append(o)
            last = en
        }
        if (last == 0) return r
        out.append(r, last, r.length)
        return out.toString()
    }
}

/** প্রথমবার চালুর ভাষা-বাছাই স্ক্রিন (কোনো ভাষা সেভ না থাকলে অ্যাপের আগে এটা আসে) */
@Composable
fun LanguageChooserScreen() {
    val ctx = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = C.Bg) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🌐", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text("ভাষা বাছুন", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Text, textAlign = TextAlign.Center)
            Text("Choose your language", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "পরে যেকোনো সময় সেটিংস থেকে বদলাতে পারবেন।\nYou can change this anytime from Settings.",
                fontSize = 13.sp, color = C.Muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            LangCard("বাংলা", "Bangla", selected = false) { Lang.set(ctx, Lang.BN) }
            Spacer(Modifier.height(12.dp))
            LangCard("English", "ইংরেজি", selected = false) { Lang.set(ctx, Lang.EN) }
        }
    }
}

@Composable
private fun LangCard(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFFFFEBEE) else Color.White,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) C.Red else C.Border),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.padding(start = 8.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Text)
                Text(sub, fontSize = 12.sp, color = C.Muted)
            }
        }
    }
}

/** সেটিংস → 🌐 ভাষা / Language */
@Composable
fun LanguageSetting() {
    val ctx = LocalContext.current
    val cur = Lang.code ?: Lang.BN
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("অ্যাপ কোন ভাষায় দেখাবে বাছুন। / Choose the language of the app.", fontSize = 13.sp, color = C.Label)
        LangCard("বাংলা", "Bangla", selected = cur == Lang.BN) { Lang.set(ctx, Lang.BN) }
        LangCard("English", "ইংরেজি", selected = cur == Lang.EN) { Lang.set(ctx, Lang.EN) }
        Text(
            "ℹ️ কার্ড/ভিডিওর ভেতরের লেখা (তারিখ, শুভ সকাল, ইন্ট্রো-আউট্রো ইত্যাদি) ও আপনার নিজের লেখা এই ভাষা-বদলে বদলায় না। / " +
                "Text inside cards/videos and your own content is not affected by this setting.",
            fontSize = 12.sp, color = C.Muted,
        )
    }
}
