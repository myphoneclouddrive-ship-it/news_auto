package com.digitalcrafting25.newsapp

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ✍️ নতুন পোস্ট (ওয়েবের p-new): ১ খবর → ২ লেখা → ৩ কার্ড → ৪ কনফার্ম → ৫ শেষ
 * সব কল ওয়েবের একই Worker এন্ডপয়েন্টে (/rewrite, /rewrite-confirm, /manual-post, /image, /fetchimg, /publish, /schedule)
 */
class NewPostViewModel(private val app: Application) : AndroidViewModel(app) {
    var step by mutableIntStateOf(1)

    // ধাপ ১
    var src by mutableStateOf("")
    var useOrig by mutableStateOf(false)
    var notes by mutableStateOf("")
    var webNews by mutableStateOf(false)
    var mTitle by mutableStateOf("")                    // নিজে পোস্ট ক্রিয়েট
    var mBody by mutableStateOf(TextFieldValue(""))
    var showManual by mutableStateOf(false)
    var sec by mutableStateOf("src")                    // ধাপ ১-এ একসময়ে একটাই সেকশন খোলা: src / notes / manual / video

    // ধাপ ২
    var title by mutableStateOf("")
    var body by mutableStateOf(TextFieldValue(""))
    var cardTitle by mutableStateOf("")

    // ধাপ ৩
    var category by mutableStateOf("")
    var tag by mutableStateOf("")
    var tplId by mutableStateOf("dark")
    var useAd by mutableStateOf(true)
    var imgMode by mutableStateOf("upload")          // upload | link | ai
    var imgUrl by mutableStateOf("")
    var aiPrompt by mutableStateOf("")
    private var promptEdited = false
    var collVertical by mutableStateOf(false)
    var imgMsg by mutableStateOf("")
    var refUrl by mutableStateOf("")
    var refBmp by mutableStateOf<Bitmap?>(null)
    var fx by mutableFloatStateOf(0.5f)
    var fy by mutableFloatStateOf(0.5f)
    var hasPhoto by mutableStateOf(false)
    var photoVer by mutableIntStateOf(0)
    var preview by mutableStateOf<Bitmap?>(null)
    var thumbs by mutableStateOf<Map<String, Bitmap>>(emptyMap())
    var nextAdLabel by mutableStateOf("")
    var nextAdId by mutableStateOf("")
    var adTick by mutableIntStateOf(0)

    // অবস্থা
    var loaded by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var busyMsg by mutableStateOf("")
    var elapsed by mutableIntStateOf(0)
    // ওয়েবের #sp প্রসেসিং প্যানেল: বার (%), লাইভ ৩ লাইনের লগ
    var rewriting by mutableStateOf(false)
    var procPct by mutableStateOf(0f)
    var procLines by mutableStateOf<List<String>>(emptyList())
    private val procRaw = StringBuilder()
    private var procExpect = 4000
    private var procCap = 8f
    private var procLast = 0L
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var balance by mutableStateOf<Double?>(null)

    // ধাপ ৫
    var pubLink by mutableStateOf("")
    var pubNotes by mutableStateOf<List<String>>(emptyList())

    private var cfg = CardCfg()
    private var logoBmp: Bitmap? = null
    private var iconBmp: Bitmap? = null
    private var photo: Bitmap? = null
    private var ads: List<Ad> = emptyList()
    private var adLast = ""
    private var curAd: Ad? = null
    private var adBmp: Bitmap? = null
    private var email = ""
    private var job: Job? = null
    private var tickJob: Job? = null
    private var tick2 = false
    private var settling = false
    private var lastInput = ""
    private var lastNotes = ""
    private val prefs = app.getSharedPreferences("newpost", Context.MODE_PRIVATE)
    private val undo = ArrayDeque<String>()
    private var lastSnap = 0L

    private val placeholder: Bitmap by lazy {
        Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).also {
            val pt = Paint().apply { shader = LinearGradient(0f, 0f, 800f, 600f, Color.parseColor("#5b7fa6"), Color.parseColor("#c9a46c"), Shader.TileMode.CLAMP) }
            Canvas(it).drawRect(0f, 0f, 800f, 600f, pt)
        }
    }

    init {
        useOrig = prefs.getBoolean("useOrig", false)
        restoreDraft()
    }

    fun reset() {
        job?.cancel(); tickJob?.cancel()
        step = 1; src = ""; notes = ""; webNews = false; mTitle = ""; mBody = TextFieldValue(""); showManual = false; sec = "src"
        title = ""; body = TextFieldValue(""); cardTitle = ""; category = ""; tag = ""; tplId = "dark"; useAd = true
        imgMode = "upload"; imgUrl = ""; aiPrompt = ""; promptEdited = false; imgMsg = ""; refUrl = ""; refBmp = null
        photo = null; hasPhoto = false; collItems = emptyList(); cropUndo = null; fx = 0.5f; fy = 0.5f; photoVer++; preview = null; thumbs = emptyMap()
        busy = false; error = null; notice = null; balance = null; pubLink = ""; pubNotes = emptyList()
        loaded = false; ads = emptyList(); adLast = ""; curAd = null; adBmp = null; logoBmp = null; iconBmp = null; cfg = CardCfg(); email = ""
        undo.clear()
        prefs.edit().remove("draft").apply()
    }

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        viewModelScope.launch {
            try {
                val c = Fs.cardCfg(app)
                cfg = c
                withContext(Dispatchers.Default) { logoBmp = AdRender.decode(c.logo); iconBmp = AdRender.decode(c.icon) }
                runCatching { Fs.adsCfg(app) }.getOrNull()?.let { (list, last) -> ads = list; adLast = last }
                rebuildAd()
                if (category.isBlank()) category = c.category
                if (tag.isBlank()) tag = c.photoTag
                loaded = true
                photoVer++
            } catch (e: Exception) {
                error = "সাইটের তথ্য আনা যায়নি: " + (e.message ?: "")
            }
            runCatching { Worker.post(app, "/cslot-get", JSONObject(), 15000) }.getOrNull()?.let { balance = it.optDouble("balance", 0.0) }
            if (email.isBlank()) runCatching { Worker.post(app, "/whoami", JSONObject(), 15000) }.getOrNull()?.let { email = it.optString("email") }
            settle()
        }
    }

    private fun rebuildAd() {
        curAd = nextAd(ads, adLast)
        adBmp = curAd?.let { AdRender.render(it) }
        nextAdId = curAd?.id ?: ""
        nextAdLabel = curAd?.let { listOf(it.t1, it.t2, it.t3).firstOrNull { x -> x.isNotBlank() }?.replace("|", " ") ?: "ছবির ব্যানার" } ?: ""
    }

    /** এই কার্ডে যে বিজ্ঞাপন গেল সেটা মনে রাখা; পরের কার্ডে পরেরটা (ওয়েবের adAdvance) */
    private suspend fun advanceAd(used: Boolean) {
        val cur = curAd
        if (used && cur != null) {
            adLast = cur.id
            runCatching { Fs.saveAds(app, ads, adLast) }
            rebuildAd()
            adTick++
        }
    }

    // ───────── টাকা কাটা নিশ্চিতকরণ (লেখা স্ক্রিনে পৌঁছালে তবেই ১০ টাকা কাটে) ─────────
    private fun toks(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("rwToks", "[]")); List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun saveToks(l: List<String>) { prefs.edit().putString("rwToks", JSONArray(l.takeLast(5)).toString()).apply() }

    fun settle(tok: String? = null) {
        if (!tok.isNullOrBlank()) saveToks(toks() + tok)
        if (settling) return
        settling = true
        viewModelScope.launch {
            try {
                for (t in toks()) {
                    try {
                        val c = Worker.post(app, "/rewrite-confirm", JSONObject().put("token", t), 20000)
                        saveToks(toks().filter { it != t })
                        if (c.has("balance")) balance = c.optDouble("balance")
                    } catch (e: Exception) { break }   // নেট/সার্ভার সমস্যা: টোকেন থাকল, পরে আবার
                }
            } finally { settling = false }
        }
    }

    // ───────── খসড়া (অ্যাপ বন্ধ করলেও লেখা থাকে; ছবি থাকে না) ─────────
    fun saveDraft() {
        val d = JSONObject().put("title", title).put("body", body.text).put("cardTitle", cardTitle)
            .put("category", category).put("tag", tag).put("src", src).put("notes", notes)
            .put("step", if (step >= 4) 3 else step).put("tpl", tplId)
        prefs.edit().putString("draft", d.toString()).apply()
    }

    private fun restoreDraft() {
        val raw = prefs.getString("draft", null) ?: return
        runCatching {
            val d = JSONObject(raw)
            title = d.optString("title"); body = TextFieldValue(d.optString("body")); cardTitle = d.optString("cardTitle")
            category = d.optString("category"); tag = d.optString("tag"); src = d.optString("src"); notes = d.optString("notes")
            step = d.optInt("step", 1).coerceIn(1, 3); tplId = d.optString("tpl", "dark")
            if (step >= 2 && title.isBlank()) step = 1
        }
    }

    fun clearAll() { reset(); load(true) }

    // ───────── চলমান কাজের ঘড়ি ─────────
    private fun startBusy(msg: String) {
        busy = true; busyMsg = msg; elapsed = 0; error = null; notice = null
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                delay(500)
                if (rewriting) {   // আউটপুট আসতে থাকলে আন্দাজে নয়, আসল অনুপাতে চলে; নইলে ধীরে এগোয়
                    val ceil = minOf(procCap + 12f, 94f)
                    val streaming = System.currentTimeMillis() - procLast < 2500
                    if (!streaming && procPct < ceil) procPct += maxOf(0.08f, (ceil - procPct) * 0.03f)
                }
                tick2 = !tick2; if (!tick2) elapsed++
            }
        }
    }

    private fun endBusy() { busy = false; rewriting = false; tickJob?.cancel() }

    private fun fail(e: Throwable) { error = e.message ?: "সমস্যা হয়েছে" }

    // ───────── ধাপ ১ → ২: AI দিয়ে লেখা ─────────
    @JvmName("applyUseOrig")
    fun setUseOrig(v: Boolean) {
        useOrig = v
        prefs.edit().putBoolean("useOrig", v).apply()
        if (v && refUrl.isNotBlank() && !busy) viewModelScope.launch { runCatching { useReference() }.onFailure { imgMsg = "⚠️ " + (it.message ?: "") } }
    }


    // ───────── প্রসেসিং বার ও লাইভ লগ (ওয়েবের procStart/procEv/liveLines) ─────────
    private fun procReset() { procRaw.setLength(0); procExpect = 4000; procCap = 8f; procLast = 0L; procPct = 0f; procSet(2f); procLines = emptyList() }
    private fun procSet(p: Float) { procCap = maxOf(procCap, p); procPct = maxOf(procPct, minOf(p, 99f)) }
    private fun procEv(ev: JSONObject) {
        when (ev.optString("t")) {
            "log" -> if (ev.has("p")) procSet(ev.optDouble("p").toFloat())
            "meta" -> if (ev.optInt("expect") > 0) procExpect = ev.optInt("expect")
            "reset" -> procRaw.setLength(0)
            "chunk" -> {
                procRaw.append(ev.optString("d")); procLast = System.currentTimeMillis()
                procSet(25f + 65f * minOf(1f, procRaw.length.toFloat() / procExpect))
            }
        }
        procLines = liveLines(procRaw.toString())
    }

    private val liveLab = mapOf(
        "facts" to "\n📋 তথ্য:\n• ", "people" to "\n👤 ব্যক্তি: ", "title" to "\n📰 শিরোনাম: ",
        "body_html" to "\n✍️ লেখা:\n", "card_title" to "\n🖼️ কার্ড: ", "image_prompt" to "\n🎨 ছবির বর্ণনা: ",
    )

    /** AI-র চলমান JSON আউটপুট থেকে পড়ার মতো শেষ ৩ লাইন (অসম্পূর্ণ হলেও) */
    private fun liveLines(raw: String): List<String> {
        var s = raw.replace("\\\"", "\u0001")
        s = s.replace(Regex("\\{\\s*\"id\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*\"title\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]*)\"\\s*\\}"), "\$1 \$2")
        s = s.replace(Regex("\\{\\s*\"id\"[^}]*$"), "")
        s = s.replace(Regex("\\{\\{\\s*p\\d+(?:\\.\\w+)?\\s*\\}\\}"), "\u0002")
        s = s.replace(Regex("\"(facts|people|title|body_html|card_title|image_prompt)\"\\s*:\\s*[\\[\\s\"]*")) { liveLab[it.groupValues[1]] ?: "" }
        s = s.replace(Regex("\"\\s*,\\s*\""), "\n• ")
        s = s.replace(Regex("</(p|li|h3|ul|ol|blockquote)>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]*>?"), " ")
        s = s.replace("\\n", " ")
        s = s.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }
        val blank = Regex("^[•\\s]*$")
        return s.split("\n").map { l ->
            l.replace(Regex("[{}\\[\\]]"), " ").replace(Regex("^[\\s\",]+|[\\s\",]+$"), "").replace(Regex("\\s+"), " ")
                .replace("\u0002", "〔নাম〕").replace("\u0001", "\"").trim()
        }.filter { it.isNotEmpty() && !blank.matches(it) }.takeLast(3).map { if (it.length > 150) "…" + it.takeLast(150) else it }
    }

    private fun bad(d: JSONObject?) = d == null || d.optString("title").isBlank() || d.optString("body_html").isBlank()

    fun rewrite() {
        val v = src.trim(); val n = notes.trim()
        if (v.isEmpty() && n.isEmpty()) { error = "খবরের লিংক/লেখা দিন, অথবা মাঠ পর্যায়ের তথ্য লিখুন"; return }
        runRewrite(v, n)
    }

    fun rewriteAgain() {
        if (lastInput.isEmpty() && lastNotes.isEmpty()) { error = "আগের খবর পাওয়া যায়নি। ব্যাক করে ১ নম্বর ধাপে আবার দিন।"; return }
        runRewrite(lastInput, lastNotes)
    }

    private fun runRewrite(v: String, n: String) {
        lastInput = v; lastNotes = n
        startBusy("⏳ AI খবরটা পড়ে নতুন করে লিখছে। একটু অপেক্ষা করুন।")
        rewriting = true; procReset()
        viewModelScope.launch {
            try {
                val isUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(v)
                val webOn = n.isNotEmpty() && webNews
                val d: JSONObject
                var text: String
                var warn = ""
                if (!isUrl && v.length > PostUtil.SPLIT_AT) {
                    val (t1, t2) = PostUtil.splitText(v)
                    val p1 = JSONObject().put("text", t1).put("split", true)
                    if (n.isNotEmpty()) { p1.put("notes", n); if (webOn) p1.put("web", true) }
                    d = Worker.postStream(app, "/rewrite", p1, { procEv(it) }, 360000)
                    if (bad(d)) throw ApiError("AI-র উত্তরে শিরোনাম বা খবর আসেনি" + d.optString("error").let { if (it.isNotBlank()) " ($it)" else "" } + "। আবার চেষ্টা করুন।")
                    text = PostUtil.htmlToEditable(d.getString("body_html"))
                    try {
                        busyMsg = "⏳ দ্বিতীয় অংশ লেখা হচ্ছে..."
                        val hint = "[নির্দেশ: এটা একটা বড় খবরের দ্বিতীয় অংশ। আগের অংশের শিরোনাম: \"" + d.getString("title") +
                            "\"। আগের অংশে যা বলা হয়েছে তা আবার লিখবেন না, নতুন করে ভূমিকা বা সমাপনী দেবেন না। শুধু নিচের অংশের তথ্য আগের অংশের ধারাবাহিকতায় লিখুন।]\n\n"
                        procEv(JSONObject().put("t", "reset"))
                        val d2 = Worker.postStream(app, "/rewrite", JSONObject().put("text", hint + t2).put("cont", d.optString("cont_token")), { procEv(it) }, 360000)
                        if (bad(d2)) throw ApiError("AI-র উত্তরে শিরোনাম বা খবর আসেনি")
                        text = PostUtil.mergeBodies(text, PostUtil.htmlToEditable(d2.getString("body_html")))
                        if (d2.has("balance")) d.put("balance", d2.getDouble("balance"))
                    } catch (e: Exception) {
                        warn = "প্রথম অংশ লেখা হয়েছে, কিন্তু দ্বিতীয় অংশ আসেনি (${e.message})। বডিতে বাকি অংশ নিজে যোগ করুন অথবা আবার চেষ্টা করুন।"
                    }
                } else {
                    val p = JSONObject()
                    if (v.isNotEmpty()) { if (isUrl) p.put("url", v) else p.put("text", v) }
                    if (n.isNotEmpty()) { p.put("notes", n); if (webOn) p.put("web", true) }
                    d = Worker.postStream(app, "/rewrite", p, { procEv(it) }, 360000)
                    if (bad(d)) throw ApiError("AI-র উত্তরে শিরোনাম বা খবর আসেনি" + d.optString("error").let { if (it.isNotBlank()) " ($it)" else "" } + "। Worker-এ সর্বশেষ worker.js ডিপ্লয় আছে কিনা দেখুন।")
                    text = PostUtil.htmlToEditable(d.getString("body_html"))
                }
                if (isUrl && d.optString("source_text").isNotBlank()) src = d.getString("source_text")
                title = d.getString("title")
                body = TextFieldValue(text); undo.clear()
                cardTitle = d.optString("card_title").ifBlank { title }
                aiPrompt = ""; promptEdited = false
                refBmp = null; refUrl = ""
                val srcImg = d.optString("source_image")
                if (isUrl && srcImg.isNotBlank()) { refUrl = srcImg; imgMode = "ai"; if (useOrig) runCatching { useReference() }.onFailure { imgMsg = "⚠️ খবরের ছবি আনা যায়নি (${it.message})" } else runCatching { loadRef() } }
                if (d.has("balance")) balance = d.optDouble("balance")
                step = 2; saveDraft()
                settle(d.optString("charge_token"))
                if (warn.isNotEmpty()) notice = "⚠️ $warn"
                else if (d.optString("web") == "none") notice = "ℹ️ গত ৩ ঘণ্টার মধ্যে অনলাইনে এই ঘটনা নিয়ে কোনো পোস্ট পাওয়া যায়নি, তাই আপনার দেওয়া তথ্যের ওপর নির্ভর করেই খবর লেখা হয়েছে।" +
                    d.optString("web_why").let { if (it.isNotBlank()) "\n(কারণ: $it)" else "" }
            } catch (e: Exception) { fail(e) } finally { endBusy() }
        }
    }

    /** নিজে পোস্ট তৈরি (AI ছাড়া): /manual-post ৫ টাকা কাটে, তারপর সরাসরি কার্ডের ধাপে */
    fun manualPost() {
        val t = mTitle.trim()
        if (t.isEmpty()) { error = "শিরোনাম লিখুন"; return }
        if (mBody.text.isBlank()) { error = "পোস্ট লিখুন"; return }
        startBusy("⏳ অপেক্ষা করুন...")
        viewModelScope.launch {
            try {
                val d = Worker.post(app, "/manual-post", JSONObject(), 20000)
                if (d.has("balance")) balance = d.optDouble("balance")
                title = t; cardTitle = t; body = mBody; undo.clear()
                lastInput = ""; lastNotes = ""
                step = 3; saveDraft()
            } catch (e: Exception) { fail(e) } finally { endBusy() }
        }
    }

    // ───────── বডি এডিটর: B / I / উপশিরোনাম / সাধারণ / তালিকা / আনডু ─────────
    fun editBody(v: TextFieldValue) {
        if (v.text != body.text) {
            val now = System.currentTimeMillis()
            if (now - lastSnap > 900 || undo.isEmpty()) { undo.addLast(body.text); if (undo.size > 60) undo.removeFirst() }
            lastSnap = now
        }
        body = v
    }

    private fun snap() { undo.addLast(body.text); if (undo.size > 60) undo.removeFirst(); lastSnap = System.currentTimeMillis() }

    fun fmtWrap(mark: String, manual: Boolean = false) = wrapIn(if (manual) mBody else body, mark) { v -> if (manual) mBody = v else { snap(); body = v } }

    private fun wrapIn(v: TextFieldValue, mark: String, set: (TextFieldValue) -> Unit) {
        val s = v.selection.min; val e = v.selection.max
        val t = v.text
        val nt = if (s == e) t.substring(0, s) + mark + mark + t.substring(e)
        else t.substring(0, s) + mark + t.substring(s, e) + mark + t.substring(e)
        set(TextFieldValue(nt, if (s == e) TextRange(s + mark.length) else TextRange(s, e + mark.length * 2)))
    }

    /** prefix: "## " = উপশিরোনাম, "• " = তালিকা, "" = সাধারণ লেখা */
    fun fmtLine(prefix: String, manual: Boolean = false) {
        val v = if (manual) mBody else body
        val t = v.text; val pos = v.selection.min.coerceIn(0, t.length)
        val ls = t.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
        val le = t.indexOf('\n', pos).let { if (it < 0) t.length else it }
        var line = t.substring(ls, le)
        val had = line.startsWith(prefix) && prefix.isNotEmpty()
        line = line.removePrefix("## ").removePrefix("##").removePrefix("• ").removePrefix("•").trim()
        if (prefix.isEmpty()) line = line.replace("**", "")
        val nl = if (had || prefix.isEmpty()) line else prefix + line
        val nt = t.substring(0, ls) + nl + t.substring(le)
        if (!manual) snap()
        val nv = TextFieldValue(nt, TextRange(ls + nl.length))
        if (manual) mBody = nv else body = nv
    }

    fun doUndo() {
        if (undo.isEmpty()) return
        val t = undo.removeLast()
        body = TextFieldValue(t, TextRange(t.length))
    }

    // ───────── ধাপ ২ → ৩ → ৪ ─────────
    fun toStep3() {
        if (title.isBlank() || body.text.isBlank()) { error = "শিরোনাম ও বডি লিখুন"; return }
        if (cardTitle.isBlank()) cardTitle = title.trim()
        error = null; step = 3; saveDraft()
    }

    fun toStep4() {
        if (title.isBlank() || body.text.isBlank()) { error = "শিরোনাম ও বডি লিখুন (ধাপ ২)"; return }
        if (!hasPhoto) { error = "আগে ছবি যোগ করুন"; return }
        error = null; step = 4; saveDraft()
    }

    fun back() { error = null; notice = null; if (step > 1) step--; saveDraft() }

    // ───────── ছবি ─────────
    private fun setPhoto(b: Bitmap) {
        photo = b; hasPhoto = true; fx = 0.5f; fy = 0.5f; photoVer++
    }

    fun clearPhoto() { collItems = emptyList(); photo = null; hasPhoto = false; fx = 0.5f; fy = 0.5f; photoVer++; imgMsg = "" }

    fun flipPhoto() { photo?.let { collItems = emptyList(); setPhoto(PostUtil.flipH(it)) } }

    /** কোলাজের প্রতিটি ছবি নিজের জুম/পজিশনসহ (কোলাজ না হলে ফাঁকা) */
    var collItems by mutableStateOf<List<PostUtil.CollageItem>>(emptyList())
        private set
    val canEditCollage: Boolean get() = collItems.size >= 2

    /** গ্যালারি: ১টি হলে সরাসরি, ২–৬টি হলে কোলাজ */
    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        imgMsg = "⏳ ছবি আনছি..."
        viewModelScope.launch {
            try {
                val bmps = withContext(Dispatchers.IO) { uris.take(6).mapNotNull { decodeUri(it, 2000) } }
                if (bmps.isEmpty()) { imgMsg = "⚠️ ছবি খোলা যায়নি"; return@launch }
                if (bmps.size == 1) { collItems = emptyList(); setPhoto(PostUtil.shrink(bmps[0])); imgMsg = "✅ ছবি যোগ হয়েছে — প্রিভিউতে আঙুল টেনে পজিশন ঠিক করুন, বা ✂️ ক্রপ করুন" }
                else {
                    val items = bmps.map { PostUtil.CollageItem(it) }
                    collItems = items
                    setPhoto(withContext(Dispatchers.Default) { PostUtil.collageItems(items, collVertical) })
                    imgMsg = "✅ ${PostUtil.bn(bmps.size)}টি ছবির কোলাজ হয়েছে — 🧩 কোলাজ এডিট দিয়ে প্রতিটি ছবি আলাদা সরান/জুম করুন"
                }
                imgMode = "upload"
            } catch (e: Exception) { imgMsg = "⚠️ " + (e.message ?: "ছবি যোগ করা যায়নি") }
        }
    }

    /** ২টি ছবির সাজানো বদল: পাশাপাশি ↔ উপর-নিচ */
    @JvmName("applyCollVertical")
    fun setCollVertical(v: Boolean) {
        collVertical = v
        val items = collItems
        if (items.size == 2) viewModelScope.launch { setPhoto(withContext(Dispatchers.Default) { PostUtil.collageItems(items, v) }) }
    }

    // ✂️ ক্রপ: ছবির ০–১ অনুপাতে নেওয়া অংশ কেটে নেয়; আগের ছবি রাখা থাকে, ↩️ দিয়ে ফেরানো যায়
    private var cropUndo: Bitmap? = null
    private var cropUndoItems: List<PostUtil.CollageItem> = emptyList()
    private var cropUndoVer = -1
    val canUndoCrop: Boolean get() = cropUndo != null && cropUndoVer == photoVer
    fun photoForCrop(): Bitmap? = photo
    fun cropRatio(): Float = CardRender.photoBox(tplId).let { it.first / it.second }

    fun applyCrop(l: Float, t: Float, r: Float, b: Float) {
        val im = photo ?: return
        val x = (l * im.width).toInt().coerceIn(0, im.width - 1)
        val y = (t * im.height).toInt().coerceIn(0, im.height - 1)
        val w = ((r - l) * im.width).toInt().coerceIn(1, im.width - x)
        val h = ((b - t) * im.height).toInt().coerceIn(1, im.height - y)
        if (w < 16 || h < 16) { notice = "❌ ক্রপের অংশ খুব ছোট"; return }
        if (w == im.width && h == im.height) return
        val out = Bitmap.createBitmap(im, x, y, w, h)
        val oldItems = collItems
        cropUndo = im; cropUndoItems = oldItems
        collItems = emptyList()          // ক্রপের পর কোলাজ আর আলাদা করে সরানো যাবে না (↩️ দিলে ফিরবে)
        notice = null
        setPhoto(out)
        cropUndoVer = photoVer
    }

    fun undoCrop() {
        val old = cropUndo ?: return
        if (cropUndoVer != photoVer) return
        val items = cropUndoItems
        cropUndo = null; cropUndoItems = emptyList()
        setPhoto(old)
        collItems = items
    }

    // 🧩 কোলাজ এডিট: প্রতিটি ছবির জুম/পজিশন আলাদা রেখে আবার কোলাজ বানানো
    fun applyCollage(items: List<PostUtil.CollageItem>, vertical: Boolean) {
        if (items.size < 2) return
        collItems = items
        collVertical = vertical
        viewModelScope.launch {
            setPhoto(withContext(Dispatchers.Default) { PostUtil.collageItems(items, vertical) })
            imgMsg = "✅ কোলাজ হালনাগাদ হয়েছে"
        }
    }

    fun fetchLink() {
        val u = imgUrl.trim()
        if (!Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(u)) { error = "ছবির লিংক http:// বা https:// দিয়ে শুরু হতে হবে"; return }
        startBusy("🔗 ছবি আনছি...")
        viewModelScope.launch {
            try {
                val d = Worker.post(app, "/fetchimg", JSONObject().put("url", u), 30000)
                val b = PostUtil.fromDataUrl(d.optString("image")) ?: throw ApiError("ছবি খোলা যায়নি")
                setPhoto(PostUtil.shrink(b)); collItems = emptyList()
                imgMsg = "✅ লিংক থেকে ছবি যোগ হয়েছে"
            } catch (e: Exception) { fail(e) } finally { endBusy() }
        }
    }

    fun onPromptEdit(s: String) { aiPrompt = s; promptEdited = true }

    /** 🤖 AI কার্টুন: শিরোনাম (বা সম্পাদিত প্রম্পট) থেকে; ✓ AI Generated চিহ্ন বসে */
    fun makeCartoon() {
        val t = title.trim(); val own = if (promptEdited) aiPrompt.trim() else ""
        if (t.isEmpty() && own.isEmpty()) { error = "আগে শিরোনাম লিখুন, শিরোনাম থেকেই কার্টুন হবে"; return }
        startBusy("🎨 কার্টুন বানাচ্ছি... (১০-৩০ সেকেন্ড)")
        viewModelScope.launch {
            try {
                val p = JSONObject().put("hint", t)
                if (own.isNotEmpty()) p.put("prompt", own)
                val d = Worker.post(app, "/image", p, 90000)
                val img = d.optString("image")
                if (img.length < 500) throw ApiError("Worker ছবি পাঠায়নি। worker_dashboard.js নতুন করে ডিপ্লয় আছে কিনা দেখুন")
                val b = PostUtil.fromDataUrl(img) ?: throw ApiError("ছবি এসেছে কিন্তু খোলা যায়নি। প্রম্পট একটু বদলে আবার চেষ্টা করুন")
                if (d.optString("prompt").isNotBlank()) { aiPrompt = d.getString("prompt"); promptEdited = false }
                setPhoto(PostUtil.shrink(b, 1600, ai = true)); collItems = emptyList()
                imgMsg = "✅ AI কার্টুন তৈরি হয়েছে (✓ AI Generated চিহ্নসহ)"
            } catch (e: Exception) { fail(e) } finally { endBusy() }
        }
    }

    /** লিংকের খবরের ছবি রেফারেন্স হিসেবে আনা (পাবলিশ হয় না) */
    private suspend fun loadRef() {
        if (refBmp != null || refUrl.isBlank()) return
        val d = Worker.post(app, "/fetchimg", JSONObject().put("url", refUrl), 30000)
        refBmp = PostUtil.fromDataUrl(d.optString("image"))
    }

    /** লিংকের খবরের ছবিই সরাসরি ব্যবহার (কার্টুন নয়) */
    suspend fun useReference() {
        loadRef()
        val b = refBmp ?: throw ApiError("খবরের ছবি নেই")
        setPhoto(PostUtil.shrink(b)); collItems = emptyList()
        imgMsg = "✅ লিংকের খবরের ছবিই সরাসরি যোগ হয়েছে (কার্টুন বানানো হয়নি)"
    }

    fun useRefNow() { viewModelScope.launch { runCatching { useReference() }.onFailure { imgMsg = "⚠️ " + (it.message ?: "") } } }

    fun dropRef() { refBmp = null; refUrl = "" }

    fun pan(dx: Float, dy: Float) {
        val im = photo ?: return
        val (bw, bh) = CardRender.photoBox(tplId)
        val k = maxOf(bw / im.width, bh / im.height)
        val ox = im.width * k - bw; val oy = im.height * k - bh
        if (ox > 1f) fx = (fx - dx / ox).coerceIn(0f, 1f)
        if (oy > 1f) fy = (fy - dy / oy).coerceIn(0f, 1f)
    }

    private fun bnDate(): String = runCatching { SimpleDateFormat("d MMMM yyyy", Locale("bn", "BD")).format(Date()) }.getOrDefault("")

    private fun cardData() = CardData(
        photo = photo ?: placeholder,
        head = cardTitle.trim().ifBlank { title.trim() }.ifBlank { "এখানে খবরের শিরোনাম দেখাবে" },
        cat = PostUtil.catParts(category).firstOrNull() ?: "",
        date = bnDate(),
        url = cfg.siteUrl,
        tag = tag.trim(),
        cta = cfg.ctaText.trim(),     // নতুন পোস্টের কার্ডে সেটিংসের লেখাই (ওয়েবের মতো)
        siteName = cfg.siteName,
        logo = logoBmp, icon = iconBmp, iconRound = cfg.iconShape != "square",
        fx = fx, fy = fy,
    )

    private fun adNow(): Bitmap? = if (useAd) adBmp else null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            delay(60)
            val d = cardData(); val id = tplId; val ad = adNow(); val list = cardTpls("photo")
            val (pv, th) = withContext(Dispatchers.Default) {
                CardRender.render(id, d, 1f, ad) to list.associate { it.first to CardRender.render(it.first, d, 0.25f, ad) }
            }
            preview = pv; thumbs = th
        }
    }

    private fun problem(): String? = when {
        title.isBlank() || body.text.isBlank() -> "শিরোনাম ও বডি লিখুন"
        !hasPhoto -> "আগে ছবি যোগ করুন"
        else -> null
    }

    private suspend fun payload(): JSONObject = withContext(Dispatchers.Default) {
        val card = CardRender.render(tplId, cardData(), 1f, adNow())
        JSONObject()
            .put("title", title.trim())
            .put("body", PostUtil.editableToHtml(body.text))
            .put("category", PostUtil.catParts(category).joinToString(", "))
            .put("featured", PostUtil.featured(photo!!, cfg))
            .put("card", PostUtil.toDataUrl(card, 90))
    }

    /** ✅ কনফার্ম ও পাবলিশ (WordPress + Facebook ফোটোকার্ড + কমেন্টে লিংক) */
    fun publish() {
        problem()?.let { error = it; return }
        startBusy("🚀 পাবলিশ হচ্ছে... (১ মিনিট পর্যন্ত লাগতে পারে)")
        viewModelScope.launch {
            try {
                val used = useAd && curAd != null
                val d = Worker.post(app, "/publish", payload(), 180000)
                advanceAd(used)
                val link = d.optString("link")
                val notes = ArrayList<String>()
                if (d.optString("facebook_error").isNotBlank())
                    notes.add("⚠️ WordPress-এ পোস্ট হয়েছে, কিন্তু Facebook-এ ফোটোকার্ড যায়নি (${d.optString("facebook_error")})। আবার পাবলিশ করবেন না (ডুপ্লিকেট হবে), ফেসবুকে হাতে দিন।")
                else if (d.optBoolean("comment_ok")) notes.add("✅ Facebook-এ ফোটোকার্ড পোস্ট হয়েছে আর তার কমেন্টে WordPress লিংক দেওয়া হয়েছে। https://www.facebook.com/" + d.optString("facebook_post"))
                else notes.add("⚠️ ফোটোকার্ড Facebook-এ পোস্ট হয়েছে, কিন্তু কমেন্টে লিংক দেওয়া যায়নি (${d.optString("comment_error").ifBlank { "কারণ অজানা" }})। লিংকটা হাতে কমেন্ট করুন।")
                try { Fs.addPost(app, title.trim(), link, d.optString("facebook_post"), email) }
                catch (e: Exception) { notes.add("পোস্ট হয়েছে, কিন্তু ড্যাশবোর্ডের হিসাবে সেভ হয়নি: ${e.message}") }
                pubLink = link; pubNotes = notes
                val keepCat = category; val keepTag = tag
                wipeAfterPost()
                category = keepCat; tag = keepTag
                step = 5
            } catch (e: Exception) { fail(e) } finally { endBusy() }
        }
    }

    /** ⏰ সিডিউল পোস্ট: সময় হলে Worker নিজেই পোস্ট করে (ফোন বন্ধ থাকলেও) */
    fun schedule(atMs: Long, onDone: () -> Unit) {
        problem()?.let { error = it; return }
        if (atMs < System.currentTimeMillis() + 60000) { error = "অন্তত ১ মিনিট পরের সময় দিন"; return }
        startBusy("⏰ সিডিউল করা হচ্ছে...")
        viewModelScope.launch {
            try {
                val used = useAd && curAd != null
                val p = payload().put("at", atMs)
                Worker.post(app, "/schedule", p, 60000)
                advanceAd(used)
                val keepCat = category; val keepTag = tag
                wipeAfterPost()
                category = keepCat; tag = keepTag
                step = 1
                notice = "✅ সিডিউল হয়েছে (সিডিউল ট্যাবে দেখুন)।"
                onDone()
            } catch (e: Exception) { fail(e) } finally { endBusy() }
        }
    }

    private fun wipeAfterPost() {
        src = ""; notes = ""; mTitle = ""; mBody = TextFieldValue(""); title = ""; body = TextFieldValue(""); cardTitle = ""
        photo = null; hasPhoto = false; collItems = emptyList(); cropUndo = null; refBmp = null; refUrl = ""; imgMsg = ""; imgUrl = ""; aiPrompt = ""; promptEdited = false
        fx = 0.5f; fy = 0.5f; photoVer++; undo.clear(); lastInput = ""; lastNotes = ""
        prefs.edit().remove("draft").apply()
    }

    private fun decodeUri(uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val r = app.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / s > maxSide * 2 || o.outHeight / s > maxSide * 2) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        var b = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val k = maxSide / maxOf(b.width, b.height).toFloat()
        if (k < 1f) b = Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        val deg = r.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (deg != 0f) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        b
    }.getOrNull()
}
