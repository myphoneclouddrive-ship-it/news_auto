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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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

    // সিডিউল করা পোস্ট এডিট: null না হলে ধাপ ২ থেকে শুরু; ক্লিয়ারের জায়গায় ডিলিট; ড্রাফট সেভ হয় না
    var editItem by mutableStateOf<SchedItem?>(null)
    var onOldCancelled: (String) -> Unit = {}

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
    var tplVer by mutableIntStateOf(0)           // টেমপ্লেট তালিকা/এডিট বদলালে থাম্বনেইল আবার আঁকার জন্য
    var histTick by mutableIntStateOf(0)   // আগে লেখা ক্যাটাগরি/ট্যাগ চিপ নতুন করে আঁকার জন্য

    // অবস্থা
    var loaded by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var busyMsg by mutableStateOf("")
    var elapsed by mutableIntStateOf(0)
    // ওয়েবের #sp প্রসেসিং প্যানেল: বার (%), লাইভ ৩ লাইনের লগ
    var rewriting by mutableStateOf(false)
    var procPct by mutableStateOf(0f)
    /** প্রসেসিং প্যানেলের লেবেল (ধাপ-N): ১ = Gemini (গেস্ট), ২ = ChatGPT (গেস্ট), ৩ = আগের মতো পেইড API */
    var procMode by mutableIntStateOf(0)
    var procLines by mutableStateOf<List<String>>(emptyList())
    private val procRaw = StringBuilder()
    private var procExpect = 4000
    private var procCap = 8f
    private var procLast = 0L
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var popup by mutableStateOf<String?>(null)   // ওয়েবের alert()-এর মতো পপআপ বার্তা (ওকে চাপলে বন্ধ)
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
    private var procPart = -1                 // বড় লেখা দুই ভাগে হলে: ০ = প্রথম, ১ = দ্বিতীয়, -১ = একভাগ (ওয়েবের evPart)
    private var saveJob: Job? = null
    private var savedPhoto: Bitmap? = null
    private var savedRef: Bitmap? = null
    private val imgLock = Mutex()
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
        guestJob = null; guestTok = null
        editItem = null
        step = 1; src = ""; notes = ""; webNews = false; mTitle = ""; mBody = TextFieldValue(""); showManual = false; sec = "src"
        title = ""; body = TextFieldValue(""); cardTitle = ""; category = ""; tag = ""; tplId = "dark"; useAd = true
        imgMode = "upload"; imgUrl = ""; aiPrompt = ""; promptEdited = false; imgMsg = ""; refUrl = ""; refBmp = null
        photo = null; hasPhoto = false; collItems = emptyList(); cropUndo = null; fx = 0.5f; fy = 0.5f; photoVer++; preview = null; thumbs = emptyMap()
        busy = false; error = null; notice = null; balance = null; pubLink = ""; pubNotes = emptyList()
        loaded = false; ads = emptyList(); adLast = ""; curAd = null; adBmp = null; logoBmp = null; iconBmp = null; cfg = CardCfg(); email = ""; TplRegistry.reset(); tplDirty = false
        undo.clear()
        prefs.edit().remove("draft").apply()
        deleteDraftImages()
    }

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        viewModelScope.launch {
            try {
                val c = Fs.cardCfg(app)
                cfg = c
                withContext(Dispatchers.Default) { logoBmp = AdRender.decode(c.logo); iconBmp = AdRender.decode(c.icon) }
                runCatching { Fs.adsCfg(app) }.getOrNull()?.let { (list, last) -> ads = list; adLast = last }
                runCatching { Fs.tplCfg(app) }.getOrNull()?.let { st ->
                    st.extraSlots = TplRegistry.store.extraSlots
                    TplRegistry.store = st
                    // সেটিংসে/এডিটরে বাছা ফটোকার্ড টেমপ্লেটই নতুন পোস্টের ডিফল্ট (ওয়েবের cfg.template); খসড়া থাকলে সেটাই
                    if (step == 1 && title.isBlank() && TplRegistry.isKnownPhotoTpl(st.template)) tplId = st.template
                    tplVer++
                }
                if (!TplRegistry.isKnownPhotoTpl(tplId)) tplId = "dark"
                rebuildAd()
                if (category.isBlank()) category = c.category
                if (tag.isBlank()) tag = c.photoTag
                loaded = true
                photoVer++; histTick++
            } catch (e: Exception) {
                error = "সাইটের তথ্য আনা যায়নি: " + (e.message ?: "")
            }
            runCatching { Worker.post(app, "/cslot-get", JSONObject(), 15000) }.getOrNull()?.let { balance = it.optDouble("balance", 0.0); TplRegistry.store.extraSlots = it.optInt("extra", 0) }
            if (email.isBlank()) runCatching { Worker.post(app, "/whoami", JSONObject(), 15000) }.getOrNull()?.let { email = it.optString("email") }
            settle()
        }
    }

    // ───────── আগে লেখা ক্যাটাগরি/ট্যাগ মনে রাখা (ওয়েবের hist_cat / hist_tag, সর্বোচ্চ ৩০টি) ─────────
    private fun hist(k: String): List<String> = runCatching {
        val a = JSONArray(prefs.getString("hist_$k", "[]")); List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun pushHist(k: String, v: String) {
        val t = v.trim(); if (t.isEmpty()) return
        val l = (listOf(t) + hist(k).filter { it != t }).take(30)
        prefs.edit().putString("hist_$k", JSONArray(l).toString()).apply()
        histTick++
    }

    /** ক্যাটাগরি চিপ: আগে লেখাগুলো + সেটিংসের ক্যাটাগরি (সর্বোচ্চ ১২) */
    val catChips: List<String> get() { val t = histTick; return (hist("cat") + cfg.category).filter { it.isNotBlank() }.distinct().take(12 + 0 * t) }

    /** ট্যাগ চিপ: আগে লেখাগুলো + সেটিংসের ট্যাগ (সর্বোচ্চ ৬) */
    val tagChips: List<String> get() { val t = histTick; return (hist("tag") + cfg.photoTag).filter { it.isNotBlank() }.distinct().take(6 + 0 * t) }

    /** চিপে চাপলে যোগ, আবার চাপলে বাদ; দুটো থাকলে দ্বিতীয়টা বদলে যায় */
    fun toggleCat(v: String) {
        val c = PostUtil.catParts(category).toMutableList()
        if (v in c) c.remove(v) else if (c.size < 2) c.add(v) else c[1] = v
        category = c.joinToString(", "); saveDraft()
    }

    fun pickTag(v: String) { tag = v; saveDraft() }

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

    // ───────── খসড়া (ওয়েবের saveDraft/restoreDraft-এর মতো: অ্যাপ বন্ধ করলেও লেখা ও ছবি থাকে) ─────────
    private val photoFile get() = File(app.filesDir, "newpost_draft_photo.jpg")
    private val refFile get() = File(app.filesDir, "newpost_draft_ref.jpg")

    /** ওয়েবের queueSave: ৪০০ms থামলে সেভ */
    fun queueSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(400); saveDraft() }
    }

    fun saveDraft() {
        if (editItem != null) return   // সিডিউল এডিটের লেখা সাধারণ খসড়া হয়ে যেন না থাকে (নইলে ডুপ্লিকেট পোস্ট হতে পারে)
        val empty = title.isBlank() && body.text.isBlank() && src.isBlank() && notes.isBlank() && !hasPhoto
        if (empty || step >= 5) { prefs.edit().remove("draft").apply(); deleteDraftImages(); return }
        val d = JSONObject().put("title", title).put("body", body.text).put("cardTitle", cardTitle)
            .put("category", category).put("tag", tag).put("src", src).put("notes", notes)
            .put("step", if (step >= 4) 3 else step).put("tpl", tplId)
            .put("webNews", webNews).put("li", lastInput).put("ln", lastNotes)
            .put("imgMode", imgMode).put("imgUrl", imgUrl).put("aiPrompt", aiPrompt).put("refUrl", refUrl)
        prefs.edit().putString("draft", d.toString()).apply()
        saveDraftImages()
    }

    private fun saveDraftImages() {
        val p = photo; val r = refBmp
        if (p !== savedPhoto) { savedPhoto = p; writeDraftImage(photoFile, p) }
        if (r !== savedRef) { savedRef = r; writeDraftImage(refFile, r) }
    }

    private fun writeDraftImage(f: File, b: Bitmap?) {
        viewModelScope.launch(Dispatchers.IO) {
            imgLock.withLock {
                runCatching { if (b == null) f.delete() else f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
            }
        }
    }

    private fun deleteDraftImages() {
        savedPhoto = null; savedRef = null
        val a = photoFile; val b = refFile
        viewModelScope.launch(Dispatchers.IO) { imgLock.withLock { runCatching { a.delete(); b.delete() } } }
    }

    private fun restoreDraft() {
        val raw = prefs.getString("draft", null) ?: return
        runCatching {
            val d = JSONObject(raw)
            title = d.optString("title"); body = TextFieldValue(d.optString("body")); cardTitle = d.optString("cardTitle")
            category = d.optString("category"); tag = d.optString("tag"); src = d.optString("src"); notes = d.optString("notes")
            step = d.optInt("step", 1).coerceIn(1, 3); tplId = d.optString("tpl", "dark")
            if (step >= 2 && title.isBlank()) step = 1
            webNews = d.optBoolean("webNews"); lastInput = d.optString("li"); lastNotes = d.optString("ln")
            imgMode = d.optString("imgMode").ifBlank { "upload" }; imgUrl = d.optString("imgUrl")
            aiPrompt = d.optString("aiPrompt"); refUrl = d.optString("refUrl")
            if (src.isBlank() && notes.isNotBlank()) { sec = "notes"; showManual = false }
        }
        runCatching {
            photoFile.takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.path)?.let { b -> photo = b; savedPhoto = b; hasPhoto = true; photoVer++ } }
            refFile.takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.path)?.let { b -> refBmp = b; savedRef = b } }
        }
    }

    fun clearAll() { reset(); load(true) }

    // ───────── চলমান কাজের ঘড়ি ─────────
    private fun startBusy(msg: String) {
        busy = true; busyMsg = msg; elapsed = 0; error = null; notice = null
        AiKeepAliveService.start(app)   // স্ক্রিন অফ/ব্যাকগ্রাউন্ডেও চলবে
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

    private fun endBusy() {
        busy = false; rewriting = false; tickJob?.cancel()
        AiKeepAliveService.stop(app)
        Net.recheck()   // কাজ চলার সময় যে নেটওয়ার্ক-ঝলক উপেক্ষা হয়েছিল, এখন আসল অবস্থা দেখা
    }

    private fun fail(e: Throwable) { error = e.message ?: "সমস্যা হয়েছে" }

    // ───────── ধাপ ১ → ২: AI দিয়ে লেখা ─────────
    @JvmName("applyUseOrig")
    fun setUseOrig(v: Boolean) {
        useOrig = v
        prefs.edit().putBoolean("useOrig", v).apply()
        if (v && refUrl.isNotBlank() && !busy) viewModelScope.launch { runCatching { useReference() }.onFailure { imgMsg = "⚠️ " + (it.message ?: "") } }
    }


    // ───────── প্রসেসিং বার ও লাইভ লগ (ওয়েবের procStart/procEv/liveLines) ─────────
    private fun procReset() { procPart = -1; procRaw.setLength(0); procExpect = 4000; procCap = 8f; procLast = 0L; procPct = 0f; procSet(2f); procLines = emptyList() }
    private fun procSet(p: Float) { procCap = maxOf(procCap, p); procPct = maxOf(procPct, minOf(p, 99f)) }
    private fun procEv(ev0: JSONObject) {
        var ev = ev0
        if (procPart >= 0) {   // ওয়েবের evPart: দুই ধাপের প্রোগ্রেস বার আধা-আধা করে চলে
            when (ev.optString("t")) {
                "log" -> { ev = JSONObject(ev.toString()).put("p", (if (procPart == 1) 50.0 else 0.0) + ev.optDouble("p", 0.0) / 2) }
                "meta" -> {
                    if (procPart == 1) return
                    ev = JSONObject(ev.toString()).put("expect", (if (ev.optInt("expect") > 0) ev.optInt("expect") else 4000) * 2)
                }
                "reset" -> { if (procPart == 1) return }
            }
        }
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

    /** ওয়েবের updateRewriteBtn: ব্যালেন্স জানা থাকলে আর ১০ টাকার কম হলে লেখার বাটন বন্ধ */
    val rwLow: Boolean get() = balance?.let { it < PostUtil.RW_COST } == true

    /** নিজে পোস্ট তৈরির বাটনের জন্য: ৫ টাকার কম হলে বন্ধ */
    val mpLow: Boolean get() = balance?.let { it < PostUtil.MP_COST } == true

    fun rewrite() {
        val v = src.trim(); val n = notes.trim()
        if (v.isEmpty() && n.isEmpty()) { popup = "লিংক, খবর অথবা মাঠের তথ্য দিন"; return }
        if (v.isEmpty() && n.length < 60) { popup = "শুধু মাঠের তথ্য দিলে একটু বিস্তারিত লিখুন (কমপক্ষে দুই-তিন লাইন)"; return }
        if (rwLow) { popup = "ব্যালেন্স কম আছে"; return }
        runRewrite(v, n)
    }

    /** "AI দিয়ে নতুন করে লিখুন" চাপলে গেস্ট চ্যাট খোলার আগের পরীক্ষা (ব্যালেন্স কম হলে আটকায়); ঠিক থাকলে true */
    fun guestOk(): Boolean {
        val v = src.trim(); val n = notes.trim()
        if (v.isEmpty() && n.isEmpty()) { popup = "লিংক, খবর অথবা মাঠের তথ্য দিন"; return false }
        if (v.isEmpty() && n.length < 60) { popup = "শুধু মাঠের তথ্য দিলে একটু বিস্তারিত লিখুন (কমপক্ষে দুই-তিন লাইন)"; return false }
        if (rwLow) { popup = "ব্যালেন্স কম আছে"; return false }
        return true
    }

    private var guestTok: String? = null
    private var guestTokAt = 0L

    private suspend fun fetchGuestTok(): String {
        val d = Worker.post(app, "/rewrite-guest", JSONObject(), 20000)
        if (d.has("balance")) balance = d.optDouble("balance")
        val t = d.optString("charge_token")
        if (t.isBlank()) throw ApiError("টাকা কাটার টোকেন পাওয়া যায়নি, আবার চেষ্টা করুন")
        guestTok = t; guestTokAt = System.currentTimeMillis()
        return t
    }

    /** চলমান গেস্ট কাজ; স্ক্রিনে থাকলে NewPostScreen অদৃশ্য রানার চালায় (ব্রাউজার/বাটন/সাইটের নাম কিছুই দেখা যায় না) */
    var guestJob by mutableStateOf<GuestJob?>(null)
    private var guestSeq = 0

    /** "AI দিয়ে নতুন করে লিখুন": আগের মতোই প্রসেসিং প্যানেল দেখায়; সার্ভারে ব্যালেন্স যাচাই + টাকা-কাটার টোকেন নিয়ে তবেই লেখা শুরু */
    fun startGuest() {
        if (!guestOk()) return
        val v = src.trim(); val n = notes.trim()
        lastInput = v; lastNotes = n
        startBusy("⏳ AI খবরটা পড়ে নতুন করে লিখছে। একটু অপেক্ষা করুন।")
        rewriting = true; procReset(); procMode = 1   // গেস্টের প্রথম ধাপ = Gemini
        viewModelScope.launch {
            try { fetchGuestTok(); guestJob = GuestJob(++guestSeq, v, n) } catch (e: Exception) { fail(e); endBusy() }
        }
    }

    /** গেস্ট রানার সাইট বদলালে ধাপ-নম্বর বদলায় (১ = Gemini, ২ = ChatGPT); নতুন ধাপে বার ও লাইভ লগ নতুন করে শুরু */
    fun guestStage(n: Int) {
        if (n != procMode && n > 1) { procPct = 5f; procCap = 8f; procLines = emptyList() }
        procMode = n
    }

    /** প্রম্পট পাঠানো হয়েছে */
    fun guestSent() { procSet(15f) }

    /** উত্তর আসছে: বার ও লাইভ লগ আগের মতো চলে (আসল অক্ষর-সংখ্যার অনুপাতে) */
    fun guestPartial(raw: String) {
        procLast = System.currentTimeMillis()
        procSet(25f + 65f * minOf(1f, raw.length.toFloat() / 2500f))
        procLines = guestLiveLines(raw)
    }

    fun guestDone(t: String, b: String) {
        guestJob = null
        procPct = 100f
        applyGuest(t, b)
    }

    /** গেস্টের সব সাইট ব্যর্থ → আগের মতো API দিয়ে লেখা */
    fun guestFailed() {
        guestJob = null
        endBusy()
        fallbackApi()
    }

    /** গেস্ট Gemini ও ChatGPT দুটোই ব্যর্থ হলে আগের মতো API দিয়ে লেখা (এর নিজের টোকেনে টাকা কাটে; গেস্টের টোকেন ফেলে দেওয়া হয়, তাই দুইবার কাটে না) */
    fun fallbackApi() {
        guestTok = null
        rewrite()
    }

    /** গেস্ট AI-র লেখা বসানো; আগের পেইড লেখার মতোই টাকা কাটে (/rewrite-confirm, সফল বসার পর); এরপর ২ নম্বর ধাপে এডিট */
    fun applyGuest(t: String, b: String) {
        startBusy("⏳ লেখা বসানো হচ্ছে...")
        viewModelScope.launch {
            try {
                var tok = guestTok
                if (tok.isNullOrBlank() || System.currentTimeMillis() - guestTokAt > 25 * 60 * 1000L) tok = fetchGuestTok()   // টোকেন ৩০ মিনিটে মরে
                guestTok = null
                lastInput = src.trim(); lastNotes = notes.trim()
                title = t.trim(); body = TextFieldValue(b.trim()); undo.clear()
                cardTitle = title
                aiPrompt = ""; promptEdited = false
                step = 2; saveDraft()
                settle(tok)
            } catch (e: Exception) { fail(e) } finally { endBusy() }
        }
    }

    /** "🔁 আবার লিখুন"-এর আগের পরীক্ষা (ওয়েবের s2retry); ঠিক থাকলে true, নইলে error বসে */
    fun retryOk(): Boolean {
        if (lastInput.isEmpty() && lastNotes.isEmpty()) { error = "আগের খবর পাওয়া যায়নি। ব্যাক করে ১ নম্বর ধাপে আবার দিন।"; return false }
        if (rwLow) { error = "ব্যালেন্স কম আছে"; return false }
        return true
    }

    fun rewriteAgain() {
        if (!retryOk()) return
        runRewrite(lastInput, lastNotes)
    }

    private fun runRewrite(v: String, n: String) {
        lastInput = v; lastNotes = n
        startBusy("⏳ AI খবরটা পড়ে নতুন করে লিখছে। একটু অপেক্ষা করুন।")
        rewriting = true; procReset(); procMode = 3
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
                    procPart = 0
                    d = Worker.postStream(app, "/rewrite", p1, { procEv(it) }, 360000)
                    if (bad(d)) throw ApiError("AI-র উত্তরে শিরোনাম বা খবর আসেনি" + d.optString("error").let { if (it.isNotBlank()) " ($it)" else "" } + "। আবার চেষ্টা করুন।")
                    text = PostUtil.htmlToEditable(d.getString("body_html"))
                    try {
                        busyMsg = "⏳ দ্বিতীয় অংশ লেখা হচ্ছে..."
                        val hint = "[নির্দেশ: এটা একটা বড় খবরের দ্বিতীয় অংশ। আগের অংশের শিরোনাম: \"" + d.getString("title") +
                            "\"। আগের অংশে যা বলা হয়েছে তা আবার লিখবেন না, নতুন করে ভূমিকা বা সমাপনী দেবেন না। শুধু নিচের অংশের তথ্য আগের অংশের ধারাবাহিকতায় লিখুন।]\n\n"
                        procPart = 1
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
                if (warn.isNotEmpty()) popup = "⚠️ $warn"
                else if (d.optString("web") == "none") popup = "ℹ️ গত ৩ ঘণ্টার মধ্যে অনলাইনে এই ঘটনা নিয়ে কোনো পোস্ট পাওয়া যায়নি, তাই আপনার দেওয়া তথ্যের ওপর নির্ভর করেই খবর লেখা হয়েছে।" +
                    d.optString("web_why").let { if (it.isNotBlank()) "\n\n(কারণ: $it)" else "" }
            } catch (e: Exception) { fail(e) } finally { endBusy() }
        }
    }

    /** নিজে পোস্ট তৈরি (AI ছাড়া): /manual-post ৫ টাকা কাটে, তারপর সরাসরি কার্ডের ধাপে */
    fun manualPost() {
        val t = mTitle.replace(Regex("\\s*\\n+\\s*"), " ").trim()
        if (t.isEmpty()) { error = "শিরোনাম লিখুন"; return }
        if (mBody.text.isBlank()) { error = "পোস্ট লিখুন"; return }
        if (mpLow) { error = "ব্যালেন্স কম আছে (কমপক্ষে ৳${PostUtil.bn(PostUtil.MP_COST)} লাগবে)। ড্যাশবোর্ড থেকে ব্যালেন্স যোগ করুন"; return }
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

    private val boldRx = Regex("\\*\\*(.+?)\\*\\*")
    private val italRx = Regex("(?<!\\*)\\*(?!\\*)([^*\\n]+?)(?<!\\*)\\*(?!\\*)")
    private val lone = Regex("(?<!\\*)\\*(?!\\*)")

    /**
     * ওয়েবের execCommand('bold'/'italic')-এর মতো টগল:
     * - সাধারণ লেখা সিলেক্ট করে চাপলে বোল্ড/ইটালিক হয়
     * - বোল্ড/ইটালিক লেখার ভেতরে (সিলেক্ট করে বা শুধু কার্সার রেখে) চাপলে সাধারণ হয়; আংশিক সিলেক্ট করলে শুধু ওইটুকু সাধারণ হয়
     * - কিছু সিলেক্ট না করে সাধারণ জায়গায় চাপলে কিছুই হয় না (আগে অদ্ভুত "****" বসত)
     */
    private fun wrapIn(v: TextFieldValue, mark: String, set: (TextFieldValue) -> Unit) {
        val t = v.text
        var s = v.selection.min.coerceIn(0, t.length)
        var e = v.selection.max.coerceIn(0, t.length)
        val ml = mark.length
        val rx = if (mark == "**") boldRx else italRx
        val ls = t.lastIndexOf('\n', s - 1).let { if (it < 0) 0 else it + 1 }
        val le = t.indexOf('\n', e).let { if (it < 0) t.length else it }
        val ms = ArrayList<IntRange>()   // এই লাইনগুলোর পুরো বোল্ড/ইটালিক অংশ (চিহ্ন সহ)
        var p = ls
        while (p <= le) {
            var q = t.indexOf('\n', p); if (q < 0 || q > le) q = le
            rx.findAll(t.substring(p, q)).forEach { ms.add((p + it.range.first)..(p + it.range.last)) }
            p = q + 1
        }
        // ক) সিলেকশন/কার্সার একটা বোল্ড অংশের ভেতরে → সাধারণ করা
        val inside = ms.firstOrNull { m ->
            val cs = m.first + ml; val ce = m.last + 1 - ml
            if (s == e) s in cs..(m.last + 1) else s >= m.first && e <= m.last + 1 && maxOf(s, cs) < minOf(e, ce)
        }
        if (inside != null) {
            val cs = inside.first + ml; val ce = inside.last + 1 - ml
            val a = if (s == e) cs else maxOf(s, cs)
            val b = if (s == e) ce else minOf(e, ce)
            val left = t.substring(cs, a); val mid = t.substring(a, b); val right = t.substring(b, ce)
            val rep = (if (left.isNotEmpty()) mark + left + mark else "") + mid + (if (right.isNotEmpty()) mark + right + mark else "")
            val nt = t.substring(0, inside.first) + rep + t.substring(inside.last + 1)
            val ms0 = inside.first + (if (left.isNotEmpty()) left.length + ml * 2 else 0)
            set(TextFieldValue(nt, if (s == e) TextRange(ms0 + (s.coerceIn(cs, ce) - cs)) else TextRange(ms0, ms0 + mid.length)))
            return
        }
        // খ) কিছু সিলেক্ট নেই → কিছু করার নেই
        if (s == e) return
        // গ) সাধারণ লেখা → বোল্ড/ইটালিক। আংশিক ছোঁয়া পুরনো অংশ পুরোটা ধরে নেয়, ভেতরের পুরনো চিহ্ন সরিয়ে একটাই জোড়া বসায়
        ms.forEach { m -> if (m.first < e && m.last + 1 > s) { s = minOf(s, m.first); e = maxOf(e, m.last + 1) } }
        val inner = t.substring(s, e).let { if (mark == "**") it.replace("**", "") else it.replace(lone, "") }
        if (inner.isBlank()) return
        val nt = t.substring(0, s) + mark + inner + mark + t.substring(e)
        set(TextFieldValue(nt, TextRange(s + ml, s + ml + inner.length)))
    }

    /** prefix: "## " = উপশিরোনাম, "• " = তালিকা, "" = সাধারণ লেখা */
    fun fmtLine(prefix: String, manual: Boolean = false) {
        val v = if (manual) mBody else body
        val t = v.text; val pos = v.selection.min.coerceIn(0, t.length)
        val ls = t.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
        val le = t.indexOf('\n', pos).let { if (it < 0) t.length else it }
        var line = t.substring(ls, le)
        val had = line.startsWith(prefix) && prefix.isNotEmpty()
        line = line.removePrefix("## ").removePrefix("##").removePrefix("• ").removePrefix("•").replace(Regex("^(>\\s?|[0-9০-৯]+\\.\\s+)"), "").trim()
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

    // ───────── টেমপ্লেট এডিটর: সেভ / মোছা (ওয়েবের cbSave) ─────────
    var tplSaving by mutableStateOf(false)

    /** এডিট করা লেআউট সেভ: ডিফল্ট হলে cfg.dtpl-এ, কাস্টম হলে cfg.customs-এ; সঙ্গে লোগো মোড (tpls) আর "নতুন পোস্টের ডিফল্ট" */
    fun saveTpl(id: String, layout: JSONObject, ts: JSONObject?, onDone: (String?) -> Unit) {
        if (tplSaving) return
        tplSaving = true
        viewModelScope.launch {
            try {
                val st = TplRegistry.store.copyStore()
                if (TplRegistry.isDef(id)) st.dtpl.put(id, layout) else { layout.put("cat", "photo"); st.customs.put(id, layout) }
                if (ts != null) st.tpls.put(id, ts)
                st.template = id
                Fs.saveTplCfg(app, st)
                TplRegistry.store = st
                tplId = id; tplVer++; photoVer++; saveDraft()
                onDone(null)
            } catch (e: Exception) { onDone(e.message ?: "সেভ হয়নি") } finally { tplSaving = false }
        }
    }

    /** এডিট করা ডিফল্ট টেমপ্লেট এক ক্লিকে আসল ডিজাইনে ফেরানো (ওয়েবের ↺) */
    fun resetDefTpl(id: String, onDone: (String?) -> Unit) {
        if (tplSaving || !TplRegistry.isDef(id)) return
        tplSaving = true
        viewModelScope.launch {
            try {
                val st = TplRegistry.store.copyStore()
                st.dtpl.remove(id); st.tpls.remove(id)
                Fs.saveTplCfg(app, st)
                TplRegistry.store = st
                tplVer++; photoVer++; saveDraft()
                onDone(null)
            } catch (e: Exception) { onDone(e.message ?: "সেভ হয়নি") } finally { tplSaving = false }
        }
    }

    /** কাস্টম টেমপ্লেট মুছে ফেলা */
    fun deleteTpl(id: String, onDone: (String?) -> Unit) {
        if (tplSaving || TplRegistry.isDef(id)) return
        tplSaving = true
        viewModelScope.launch {
            try {
                val st = TplRegistry.store.copyStore()
                st.customs.remove(id); st.tpls.remove(id)
                if (st.template == id) st.template = "dark"
                Fs.saveTplCfg(app, st)
                TplRegistry.store = st
                if (tplId == id) tplId = "dark"
                tplVer++; photoVer++; saveDraft()
                onDone(null)
            } catch (e: Exception) { onDone(e.message ?: "মোছা যায়নি") } finally { tplSaving = false }
        }
    }

    /** টেমপ্লেট এডিটরে "নিজের ছবি যোগ": ছোট করে data: লিংক (ওয়েবের cbImgFile) → (data, চওড়া/উচ্চতা অনুপাত); বেশি বড় হলে null */
    fun tplImageData(uri: Uri): Pair<String, Float>? {
        val b = decodeUri(uri, 560) ?: return null
        val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull().orEmpty()
        val png = Regex("png|webp|gif").containsMatchIn(mime)
        val o = java.io.ByteArrayOutputStream()
        if (png) b.compress(Bitmap.CompressFormat.PNG, 100, o) else b.compress(Bitmap.CompressFormat.JPEG, 85, o)
        val data = (if (png) "data:image/png;base64," else "data:image/jpeg;base64,") + android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP)
        if (data.length > 420000) return null
        return data to (b.width.toFloat() / maxOf(1, b.height))
    }

    /** এডিটরের প্রিভিউয়ের জন্য: এখনকার শিরোনাম/ছবিসহ কার্ডের ডেটা */
    fun editorCardData(): CardData = cardData()

    // ───────── সেটিংসের "🖼️ ফোটোকার্ড টেমপ্লেট" কার্ড (ওয়েবের renderTplGrid / openTpl / cbBuy) ─────────
    /** গ্রিড/মোডালের বদল ধরা আছে কিন্তু সেভ হয়নি (ওয়েবে cfg বদলায়, সেভ হয় সেটিংসের নিচের বাটনে) */
    var tplDirty by mutableStateOf(false)

    /** গ্রিড ও মোডালের নমুনা: নমুনা শিরোনাম, সেটিংসের লোগো/তথ্য, আজকের তারিখ; বিজ্ঞাপন ছাড়া */
    private fun sampleData() = CardData(
        photo = photo ?: placeholder,
        head = "এখানে খবরের শিরোনাম এভাবে সাজানো দেখাবে, আপনার লোগো ও আজকের তারিখসহ",
        cat = PostUtil.catParts(cfg.category).firstOrNull() ?: "",
        date = bnDate(),
        url = cfg.siteUrl,
        tag = cfg.photoTag.trim(),
        cta = cfg.ctaText.trim(),
        siteName = cfg.siteName,
        logo = logoBmp, icon = iconBmp, iconRound = cfg.iconShape != "square",
        fx = 0.5f, fy = 0.5f,
    )

    suspend fun settingsThumbs(scale: Float): Map<String, Bitmap> {
        val d = sampleData(); val list = cardTpls("photo")
        return withContext(Dispatchers.Default) { list.associate { it.first to CardRender.render(it.first, d, scale, null) } }
    }

    /** বড় ভিউ: ts = মোডালে বদলানো লোগো-মোড/সাইজ/রঙ (সেভের আগেই প্রিভিউতে দেখায়) */
    suspend fun settingsCard(id: String, scale: Float, ts: JSONObject?): Bitmap {
        val d = sampleData()
        return withContext(Dispatchers.Default) { CardRender.render(id, d, scale, null, TplOv(null, ts)) }
    }

    /** মোডালের বদল ধরে রাখা (cfg.tpls / cfg.template); সেভ হয় saveTplStaged() দিয়ে */
    fun stageTplSettings(id: String, ts: JSONObject?, pick: Boolean, dropLayout: Boolean = false) {
        val st = TplRegistry.store.copyStore()
        if (dropLayout) st.dtpl.remove(id)
        if (ts == null || ts.length() == 0) st.tpls.remove(id) else st.tpls.put(id, ts)
        if (pick) st.template = id
        TplRegistry.store = st
        tplDirty = true; tplVer++; photoVer++
    }

    fun saveTplStaged(onDone: (String?) -> Unit) {
        if (tplSaving) return
        tplSaving = true
        viewModelScope.launch {
            try {
                Fs.saveTplCfg(app, TplRegistry.store)
                tplDirty = false; tplVer++; photoVer++
                onDone(null)
            } catch (e: Exception) { onDone(e.message ?: "সেভ হয়নি") } finally { tplSaving = false }
        }
    }

    /** ব্যালেন্স ও কেনা বাড়তি স্লট আবার আনা (ওয়েবের loadSlots) */
    suspend fun reloadSlots() {
        runCatching { Worker.post(app, "/cslot-get", JSONObject(), 15000) }.getOrNull()?.let {
            balance = it.optDouble("balance", 0.0)
            TplRegistry.store.extraSlots = it.optInt("extra", 0)
            tplVer++
        }
    }

    /** ব্যালেন্স থেকে ৫০০ টাকায় আরেকটা কাস্টম টেমপ্লেট কেনা: আগে লগইন পাসওয়ার্ড যাচাই, তারপর Worker /cslot-buy (ওয়েবের cbBuyGo) */
    fun buyCustomSlot(password: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                if (password.isBlank()) throw ApiError("আপনার লগইন পাসওয়ার্ড দিন")
                if (email.isBlank()) runCatching { Worker.post(app, "/whoami", JSONObject(), 15000) }.getOrNull()?.let { email = it.optString("email") }
                if (email.isBlank()) throw ApiError("ইমেইল জানা যায়নি, ইন্টারনেট দেখে আবার চেষ্টা করুন")
                try { Session.signIn(app, email, password) } catch (e: ApiError) {
                    throw if (e.message?.contains("ভুল") == true) ApiError("পাসওয়ার্ড ভুল") else e
                }
                Worker.post(app, "/cslot-buy", JSONObject(), 20000)
                reloadSlots()
                onDone(null)
            } catch (e: Exception) { onDone(e.message ?: "কেনা যায়নি") }
        }
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
        dragging = true
        refreshFast()
    }

    /** আঙুল তুললে: পুরো মাপের প্রিভিউ আর থাম্বনেইল একবার আঁকা */
    fun panEnd() { dragging = false; dragPlan = null; job?.cancel(); refresh() }

    // টানার সময় হালকা প্রিভিউ: ডিবাউন্স নেই, আধা মাপে আঁকে, চলার সময় নতুন ইভেন্ট এলে শেষে সর্বশেষ অবস্থা আবার আঁকে
    private var dragging = false
    private var fastBusy = false
    private var fastDirty = false
    private var dragPlan: CardRender.AdPlan? = null   // টানার সময় বিজ্ঞাপনের জায়গার হিসাব একবারই
    private fun refreshFast() {
        if (fastBusy) { fastDirty = true; return }
        fastBusy = true
        viewModelScope.launch {
            try {
                do {
                    fastDirty = false
                    val d = cardData(); val id = tplId; val ad = adNow()
                    val pv = withContext(Dispatchers.Default) {
                        val pl = if (ad != null) (dragPlan ?: CardRender.adPlan(id, d).also { dragPlan = it }) else null
                        CardRender.render(id, d, 0.5f, ad, null, pl)
                    }
                    if (dragging) preview = pv
                } while (fastDirty && dragging)
            } finally { fastBusy = false }
        }
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
                cancelOld()?.let { notes.add("⚠️ পুরনো সিডিউলটা মোছা যায়নি ($it)। সিডিউল ট্যাবে গিয়ে সেটা মুছে দিন, নইলে একই পোস্ট আবার হয়ে যাবে।") }
                pubLink = link; pubNotes = notes
                PostUtil.catParts(category).forEach { pushHist("cat", it) }; pushHist("tag", tag)
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
                val sd = Worker.post(app, "/schedule", p, 60000)
                advanceAd(used)
                val cancelErr = cancelOld()
                saveSchedCopy(sd.optString("id"), atMs)
                val keepCat = category; val keepTag = tag
                wipeAfterPost()
                category = keepCat; tag = keepTag
                step = 1
                notice = if (cancelErr == null) "✅ সিডিউল হয়েছে (সিডিউল ট্যাবে দেখুন)।"
                else "✅ নতুন সিডিউল হয়েছে, কিন্তু পুরনোটা মোছা যায়নি ($cancelErr)। সিডিউল ট্যাবে গিয়ে পুরনোটা মুছে দিন, নইলে দুইবার পোস্ট হবে।"
                onDone()
            } catch (e: Exception) { fail(e) } finally { endBusy() }
        }
    }

    /** এডিট করা পোস্ট নতুন করে সিডিউল/পাবলিশ হলে আগের সিডিউলটা বাতিল (সার্ভারে /schedule-cancel); ব্যর্থ হলে কারণ ফেরত */
    private suspend fun cancelOld(): String? {
        val old = editItem ?: return null
        return try {
            Worker.post(app, "/schedule-cancel", JSONObject().put("id", old.id), 20000)
            SchedCache.delete(app, old.id)
            onOldCancelled(old.id)
            editItem = null
            null
        } catch (e: Exception) { e.message ?: "কারণ অজানা" }
    }

    /** সিডিউল হওয়া খবরের লেখা-ছবির কপি ফোনে রাখা, যাতে পরে "এডিট করুন" চাপলে ধাপ ২ থেকে খোলা যায় */
    private fun saveSchedCopy(id: String, atMs: Long) {
        if (id.isBlank()) return
        val d = JSONObject().put("title", title.trim()).put("body", body.text).put("cardTitle", cardTitle)
            .put("category", category).put("tag", tag).put("tpl", tplId).put("useAd", useAd)
            .put("imgMode", imgMode).put("imgUrl", imgUrl).put("aiPrompt", aiPrompt)
            .put("fx", fx.toDouble()).put("fy", fy.toDouble()).put("at", atMs)
        SchedCache.save(app, id, d, photo)
    }

    /** এডিটের মাঝে ডিলিট চাপলে: ফোনের কপি মুছে সব ফাঁকা (সার্ভারের সিডিউল মোছে ScheduleViewModel.cancel) */
    fun discardEdit() { editItem?.let { SchedCache.delete(app, it.id) }; clearAll() }

    /** নতুন পোস্টের পাতায় কিছু লেখা/ছবি আছে কিনা (এডিট শুরুতে সেটা মুছে যাবে, তাই আগে জানানো) */
    fun hasWork(): Boolean = title.isNotBlank() || body.text.isNotBlank() || src.isNotBlank() || notes.isNotBlank() ||
        mTitle.isNotBlank() || mBody.text.isNotBlank() || hasPhoto

    /**
     * সিডিউল করা খবর-পোস্ট এডিট: ধাপ ১ বাদ, ২ লেখা থেকে শুরু। আগে ফোনের কপি (আসল ছবিসহ);
     * না থাকলে (পুরনো সিডিউল/অন্য ফোন) Worker-এর /schedule-get থেকে লেখা, ক্যাটাগরি ও ফিচার ছবি।
     * onResult(null) = ঠিক আছে, নইলে ব্যর্থতার বার্তা
     */
    fun beginEdit(item: SchedItem, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { SchedCache.load(app, item.id) }
            var d: JSONObject? = local?.data
            var ph: Bitmap? = local?.photo
            var fromServer = false
            if (d == null) {
                try {
                    val r = Worker.post(app, "/schedule-get", JSONObject().put("id", item.id), 60000)
                    val bmp = withContext(Dispatchers.Default) { PostUtil.fromDataUrl(r.optString("featured")) }
                    d = JSONObject().put("title", r.optString("title")).put("body", PostUtil.htmlToEditable(r.optString("body")))
                        .put("cardTitle", r.optString("title")).put("category", r.optString("category"))
                    ph = bmp; fromServer = true
                } catch (e: Exception) { onResult("⚠️ " + (e.message ?: "পোস্টের লেখা আনা যায়নি")); return@launch }
            }
            val dd: JSONObject = d ?: return@launch
            reset()
            editItem = item
            title = dd.optString("title", item.title); body = TextFieldValue(dd.optString("body")); cardTitle = dd.optString("cardTitle").ifBlank { title }
            category = dd.optString("category"); tag = dd.optString("tag"); tplId = dd.optString("tpl", "dark"); useAd = dd.optBoolean("useAd", true)
            imgMode = dd.optString("imgMode").ifBlank { "upload" }; imgUrl = dd.optString("imgUrl"); aiPrompt = dd.optString("aiPrompt")
            fx = dd.optDouble("fx", 0.5).toFloat(); fy = dd.optDouble("fy", 0.5).toFloat()
            ph?.let { photo = it; hasPhoto = true }
            photoVer++
            error = null
            notice = if (fromServer) "ℹ️ সার্ভারের কপি থেকে খোলা হয়েছে: ছবি ফিচার ইমেজের মাপে, ট্যাগ ও টেমপ্লেট ডিফল্ট। ধাপ ৩-এ দেখে নিন।" else null
            step = 2
            load(true)
            onResult(null)
        }
    }

    private fun wipeAfterPost() {
        src = ""; notes = ""; mTitle = ""; mBody = TextFieldValue(""); title = ""; body = TextFieldValue(""); cardTitle = ""
        photo = null; hasPhoto = false; collItems = emptyList(); cropUndo = null; refBmp = null; refUrl = ""; imgMsg = ""; imgUrl = ""; aiPrompt = ""; promptEdited = false
        fx = 0.5f; fy = 0.5f; photoVer++; undo.clear(); lastInput = ""; lastNotes = ""
        prefs.edit().remove("draft").apply()
        deleteDraftImages()
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
