package com.digitalcrafting25.newsapp

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val FC_FILE_HINT = "১টি ছবি দিলে সরাসরি যোগ হবে। একাধিক (সর্বোচ্চ ৬টি) একসাথে বাছলে সব মিলিয়ে একটা কোলাজ ছবি হবে, তারপর টেনে পজিশন ঠিক করা যাবে।"
const val FC_CTA_DEFAULT = "এইমাত্র পাওয়া"   // ওয়েবের FC_CTA_DEFAULT: ফোটোকার্ড মেনুর নিচের লেখার ডিফল্ট

/** ফোটোকার্ড ট্যাব (ধাপ ১): ছবি + শিরোনাম + ৫টি ডিফল্ট টেমপ্লেট, গ্যালারিতে সেভ ও শেয়ার */
class PhotoCardViewModel(private val app: Application) : AndroidViewModel(app) {
    var loaded by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)

    // এই কার্ডের জন্য লেখা (সাইটের ডিফল্ট থেকে ভরে, কার্ডে আলাদা বদলানো যায়)
    var title by mutableStateOf("")
    var category by mutableStateOf("")
    var tag by mutableStateOf("")
    var cta by mutableStateOf("")
    var tplId by mutableStateOf("dark")
    /** "photo" = ফটোকার্ড, "quote" = বক্তব্য কার্ড, "morning" = 🌅 শুভ সকাল */
    var mode by mutableStateOf("photo")
        private set
    var rashi by mutableStateOf("")   // শুভ সকাল কার্ডের রাশি
    var name by mutableStateOf("")   // বক্তার নাম
    var role by mutableStateOf("")   // বক্তার পদবি
    /** ছবি টেনে সরানোর অবস্থান (০–১) */
    var fx by mutableFloatStateOf(0.5f)
        private set
    var fy by mutableFloatStateOf(0.5f)
        private set
    /** কার্ডে বিজ্ঞাপন বসবে কিনা (বিজ্ঞাপন তৈরি থাকলে ডিফল্ট চালু) */
    var useAd by mutableStateOf(true)
    /** এখন যে বিজ্ঞাপন কার্ডে বসবে তার আইডি (বিজ্ঞাপন না থাকলে ফাঁকা); স্ক্রিন এটা দেখে প্রিভিউ নতুন করে আঁকে */
    var nextAdId by mutableStateOf("")
        private set
    var nextAdLabel by mutableStateOf("")
        private set

    /** ছবি বদলালে বাড়ে, স্ক্রিন তখন প্রিভিউ নতুন করে আঁকে */
    var photoVer by mutableIntStateOf(0)
        private set
    var hasPhoto by mutableStateOf(false)
        private set
    var preview by mutableStateOf<Bitmap?>(null)
        private set
    var thumbs by mutableStateOf<Map<String, Bitmap>>(emptyMap())
        private set

    // ---- 🌅 শুভ সকাল: ইলাস্ট্রেশন, আবহাওয়া ও অটো-পোস্ট সেটিং ----
    /** ইলাস্ট্রেশন/আবহাওয়া এলে বাড়ে, স্ক্রিন তখন প্রিভিউ নতুন করে আঁকে */
    var mnVer by mutableIntStateOf(0)
        private set
    var mnTime by mutableStateOf("06:30")
        private set
    var mnOn by mutableStateOf(false)
    var mnAd by mutableStateOf("auto")
    var mnSite by mutableStateOf("")
    var mnBalance by mutableStateOf<Double?>(null)
        private set
    var mnBusy by mutableStateOf(false)
        private set
    var mnMsg by mutableStateOf<String?>(null)
        private set
    /** ব্যানার পছন্দের তালিকা: (value, লেবেল) */
    var mnChoices by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    private var mnBgs: Map<Int, Bitmap> = emptyMap()
    private var weather: MnWeather? = null
    private var mnOwner = false
    private var mnLoaded = false

    private var cfg = CardCfg()
    private var logoBmp: Bitmap? = null
    private var iconBmp: Bitmap? = null
    private var photo: Bitmap? = null
    private var job: Job? = null
    private var savedUri: Uri? = null
    private var savedSig = ""
    private var ads: List<Ad> = emptyList()
    private var adLast = ""
    private var adBmp: Bitmap? = null
    private val placeholder: Bitmap by lazy {   // ছবি না থাকলে নমুনা রঙ (ওয়েবের samplePhoto)
        Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).also {
            val pt = Paint().apply { shader = LinearGradient(0f, 0f, 800f, 600f, Color.parseColor("#5b7fa6"), Color.parseColor("#c9a46c"), Shader.TileMode.CLAMP) }
            Canvas(it).drawRect(0f, 0f, 800f, 600f, pt)
        }
    }

    /** ওয়েবের fcCta/fcCat: বদলালে সেভ থাকে (পরের বার এটাই), নইলে ডিফল্ট; ট্যাগ শুধু এই সেশনে */
    fun onCta(v: String) { cta = v; ctaTouched = true; sp.edit().putString("fcCta", v).apply() }
    fun onCategory(v: String) { category = v; catTouched = true; sp.edit().putString("fcCat", v).apply() }
    fun onTag(v: String) { tag = v; tagTouched = true; tagAi = false }

    fun reset() {
        job?.cancel()
        tplReady = false; sp.edit().clear().apply(); ctaTouched = false; catTouched = false; tagTouched = false; tagAi = false
        collItems = emptyList(); fileMsg = FC_FILE_HINT; fcFbUrl = null; ocrAsk = null
        loaded = false; loadError = null; busy = false; notice = null
        title = ""; category = ""; tag = ""; cta = ""; tplId = "dark"; mode = "photo"; name = ""; role = ""; rashi = ""; fx = 0.5f; fy = 0.5f; useAd = true
        mnTime = "06:30"; mnOn = false; mnAd = "auto"; mnSite = ""; mnBalance = null; mnBusy = false; mnMsg = null; mnChoices = emptyList()
        mnBgs = emptyMap(); weather = null; mnOwner = false; mnLoaded = false; mnVer++
        ads = emptyList(); adLast = ""; adBmp = null; nextAdId = ""; nextAdLabel = ""; savedSig = ""
        photo = null; hasPhoto = false; preview = null; thumbs = emptyMap(); savedUri = null
        link = ""; balance = null; fcBusy = false; fcOut = null; aiMsg = ""; aiPrompt = ""; aiBusy = false; ocrMsg = ""; ocrText = ""; ocrBusy = false
        used = false; paid = false; sid = PostUtil.newSid(); picked = emptyList(); collVertical = false
        cfg = CardCfg(); logoBmp = null; iconBmp = null
        photoVer++
    }

    fun load(force: Boolean = false) {
        if (busy || (loaded && !force)) return
        busy = true
        viewModelScope.launch {
            try {
                val c = Fs.cardCfg(app)
                cfg = c
                withContext(Dispatchers.Default) {
                    logoBmp = AdRender.decode(c.logo)
                    iconBmp = AdRender.decode(c.icon)
                }
                // বিজ্ঞাপন না আনা গেলেও কার্ড বানানো চলবে
                runCatching { Fs.adsCfg(app) }.getOrNull()?.let { (list, last) -> ads = list; adLast = last }
                // কাস্টম/এডিট করা টেমপ্লেট (ওয়েবের cfg.customs/dtpl): ফোটোকার্ড মেনুতেও দেখায়
                runCatching { Fs.tplCfg(app) }.getOrNull()?.let { st -> st.extraSlots = TplRegistry.store.extraSlots; TplRegistry.store = st; tplReady = true }
                if (mode == "photo" && !TplRegistry.isKnownPhotoTpl(tplId)) tplId = "dark"
                rebuildAd()
                if (!ctaTouched) cta = if (sp.contains("fcCta")) sp.getString("fcCta", "").orEmpty() else FC_CTA_DEFAULT
                if (!catTouched) category = if (sp.contains("fcCat")) sp.getString("fcCat", "").orEmpty() else c.category
                if (!tagTouched) tag = c.photoTag
                if (mnSite.isBlank()) mnSite = c.siteName
                refreshMnChoices()
                loaded = true; loadError = null
                photoVer++
                loadMorning()
            } catch (e: Exception) {
                loadError = e.message ?: "সাইটের তথ্য আনা যায়নি"
            } finally {
                busy = false
            }
        }
    }

    /** শুভ সকাল: ইলাস্ট্রেশন + আজকের আবহাওয়া + সেভ করা অটো-পোস্ট সেটিং আনে (প্রতিটা আলাদা, একটা না এলে বাকিগুলো আটকায় না) */
    private fun loadMorning() {
        viewModelScope.launch {
            if (mnBgs.isEmpty()) {
                val b = withContext(Dispatchers.IO) { MnCal.loadBgs(app) }
                if (b.isNotEmpty()) { mnBgs = b; mnVer++ }
            }
        }
        viewModelScope.launch {
            val today = MnCal.iso(MnCal.today())
            if (weather?.day != today) {
                val w = withContext(Dispatchers.IO) { MnCal.fetchWeather() }
                if (w != null) { weather = w; mnVer++ }
            }
        }
        viewModelScope.launch {
            runCatching { Worker.post(app, "/cslot-get", JSONObject(), 15000) }.getOrNull()?.let {
                mnBalance = it.optDouble("balance", 0.0); mnOwner = it.optBoolean("owner", false) && BuildConfig.IS_ADMIN; TplRegistry.store.extraSlots = it.optInt("extra", 0); photoVer++
            }
            if (!mnLoaded) {
                runCatching { Fs.morningCfg(app) }.getOrNull()?.let { m ->
                    mnOn = m.on ?: mnOwner   // নিজে কখনো না বদলালে মান সেভ হয় না; অ্যাডমিনের আগের অটো-পোস্ট যেমন চলছিল তেমনই
                    mnTime = clampTime(m.time)
                    mnAd = m.ad
                    refreshMnChoices()
                    mnLoaded = true
                }
            }
        }
    }

    /** শুভ সকালের সময় শুধু সকাল ৬:৩০ থেকে ৮:৩০-এর মধ্যে (HH:mm) */
    private fun clampTime(v: String): String {
        val m = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$").find(v) ?: return "06:30"
        val t = (m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()).coerceIn(390, 510)
        return "%02d:%02d".format(t / 60, t % 60)
    }

    val mnTimeMin: Int get() = clampTime(mnTime).let { it.substring(0, 2).toInt() * 60 + it.substring(3).toInt() }

    fun setMnTime(h: Int, m: Int) {
        val raw = "%02d:%02d".format(h, m)
        val fixed = clampTime(raw)
        mnTime = fixed
        mnMsg = if (raw != fixed) "⚠️ সময় সকাল ৬:৩০ থেকে ৮:৩০-এর মধ্যে হতে হবে, তাই " + MnCal.clock(mnTimeMin) + " সেট হয়েছে।" else null
    }

    private fun pngB64(a: Ad): String {
        val bmp = AdRender.render(a)
        val o = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    /** ওয়েবের mnSave: সেটিং সেভ + সাইটের নাম, ব্যানার ও চালু/বন্ধ Worker-এ (/morning-banner) পাঠানো */
    fun saveMorning() {
        if (mnBusy) return
        mnBusy = true; mnMsg = null
        viewModelScope.launch {
            try {
                val time = clampTime(mnTime)
                val wasFixed = time != mnTime
                mnTime = time
                val site = mnSite.trim()
                Fs.saveMorningCfg(app, mnOn, time, mnAd, site)
                cfg = cfg.copy(siteName = site)
                val ok = ads.filter { it.ok }
                val body = JSONObject()
                    .put("site", JSONObject().put("name", site).put("url", cfg.siteUrl.trim()))
                    .put("on", mnOn).put("time", time)
                withContext(Dispatchers.Default) {
                    val one = if (mnAd != "auto" && mnAd != "none") ok.firstOrNull { it.id == mnAd } else null
                    if (mnAd == "auto" && ok.isNotEmpty()) {   // সব বিজ্ঞাপন পাঠানো হয়; Worker প্রতিদিন একটির পর একটি বসায়
                        val l = ok.map { pngB64(it) }
                        body.put("pngs", org.json.JSONArray(l)).put("png", l[0]).put("adId", "")
                    } else if (one != null) {
                        body.put("png", pngB64(one)).put("adId", one.id)
                    } else {
                        body.put("png", "")   // ব্যানার ছাড়া
                    }
                }
                Worker.post(app, "/morning-banner", body, 60000)
                var warn = ""
                if (mnOn) {
                    runCatching { Worker.post(app, "/creds-get", JSONObject(), 20000) }.getOrNull()?.let { d ->
                        val f = d.optJSONObject("fields")
                        val has = f?.optJSONObject("FB_PAGE_ID")?.optBoolean("set") == true && f.optJSONObject("FB_TOKEN")?.optBoolean("set") == true
                        if (!has) warn = " ⚠️ আপনার Facebook সংযোগ (সেটিংস) এখনো দেওয়া নেই, তাই পোস্ট হবে না।"
                    }
                }
                val b = mnBalance
                val low = if (mnOn && b != null && b < Rates.get("mn")) " ⚠️ ব্যালেন্স ${Bn.num(Rates.get("mn"))} টাকার কম। পোস্ট হবে, কিন্তু ব্যালেন্স মাইনাস হবে; ৭ দিনের মধ্যে ব্যালেন্স যোগ করুন।" else ""
                val fix = if (wasFixed) " ⚠️ সময় ৬:৩০–৮:৩০-এর মধ্যে ছিল না, তাই " + MnCal.clock(mnTimeMin) + " সেট হয়েছে।" else ""
                mnMsg = "✅ সেভ হয়েছে" + (if (mnOn) "। প্রতিদিন " + MnCal.clock(mnTimeMin) + "-এর পর আপনার পেইজে পোস্ট হবে।" else "। প্রতিদিনের অটো-পোস্ট বন্ধ আছে।") + warn + low + fix
                mnVer++
            } catch (e: Exception) {
                mnMsg = "❌ " + (e.message ?: "সেভ করা যায়নি")
            } finally {
                mnBusy = false
            }
        }
    }

    /** গ্যালারির ছবি লোড (বড় হলে ছোট করে, ফোনের ঘোরানো ঠিক করে) */
    fun setPhoto(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodePhoto(uri) }
            if (bmp == null) { fileMsg = "⚠️ ছবিটি পড়া যায়নি, অন্য ছবি দিন"; return@launch }
            collItems = emptyList(); fileMsg = FC_FILE_HINT
            photo = PostUtil.shrink(bmp, 1600); hasPhoto = true; notice = null; fx = 0.5f; fy = 0.5f
            if (tagAi) { tagAi = false; tagTouched = false; tag = cfg.photoTag }
                if (geminiPending) { geminiPending = false; tag = PostUtil.AI_TAG; tagTouched = true; tagAi = true }
            photoVer++
        }
    }

    /** ফটোকার্ড ↔ বক্তব্য কার্ড; ছবির অবস্থান আর টেমপ্লেট ঐ ধরনের প্রথমটায় ফেরে */
    fun switchMode(m: String) {
        if (m == mode) return
        mode = m; tplId = cardTpls(m).first().first; fx = 0.5f; fy = 0.5f
    }

    fun clearPhoto() { collItems = emptyList(); fileMsg = FC_FILE_HINT; photo = null; hasPhoto = false; fx = 0.5f; fy = 0.5f; photoVer++ }

    /** প্রিভিউতে আঙুল টানলে ছবি সরে (dx, dy = কার্ডের ১০৮০×১৩৫০ মাপের হিসাবে পিক্সেল) */
    fun pan(dx: Float, dy: Float) {
        val im = photo ?: return
        val (bw, bh) = CardRender.photoBox(tplId)
        val k = maxOf(bw / im.width, bh / im.height)
        val ox = im.width * k - bw; val oy = im.height * k - bh
        if (ox > 1f) fx = (fx - dx / ox).coerceIn(0f, 1f)
        if (oy > 1f) fy = (fy - dy / oy).coerceIn(0f, 1f)
    }

    private fun refreshMnChoices() {
        val ok = ads.filter { it.ok }
        val l = ArrayList<Pair<String, String>>()
        if (ok.isNotEmpty()) l.add("auto" to "🔁 অটো (বিজ্ঞাপন একটির পর একটি)")
        l.add("none" to if (mnOwner) "🏷️ লোগো (আগের মতো, ব্যানার নয়)" else "🏷️ ব্যানার ছাড়া (শুধু সাইটের নাম)")
        ads.forEachIndexed { i, a -> if (a.ok) l.add(a.id to "📢 বিজ্ঞাপন " + MnCal.n(i + 1)) }
        mnChoices = l
        // পছন্দ করা বিজ্ঞাপন আর নেই / ঠিক নেই হলে ওয়েবের mbMode()-এর নিয়মে
        mnAd = when {
            mnAd == "none" -> "none"
            mnAd != "auto" && ok.any { it.id == mnAd } -> mnAd
            ok.isNotEmpty() -> "auto"
            else -> "none"
        }
    }

    private fun rebuildAd() {
        val a = nextAd(ads, adLast)
        adBmp = a?.let { AdRender.render(it) }
        nextAdId = a?.id ?: ""
        nextAdLabel = a?.let { listOf(it.t1, it.t2, it.t3).firstOrNull { x -> x.isNotBlank() }?.replace("|", " ") ?: "ছবির ব্যানার" } ?: ""
    }

    private fun sig() = listOf(title, category, tag, cta, mode, name, role, rashi, mnSite, mnVer, tplId, photoVer, fx, fy, useAd, nextAdId).joinToString("\u0001")

    private fun decodePhoto(uri: Uri): Bitmap? = runCatching {
        val r = app.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        val maxSide = 2400
        while (o.outWidth / s > maxSide * 2 || o.outHeight / s > maxSide * 2) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        var b = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val k = maxSide / max(b.width, b.height).toFloat()
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

    private fun max(a: Int, b: Int) = if (a > b) a else b

    private fun bnDate(): String =
        runCatching { SimpleDateFormat("d MMMM yyyy", Locale("bn", "BD")).format(Date()) }.getOrDefault("")

    private fun data() = CardData(
        photo = photo ?: placeholder,
        head = title.trim().ifBlank { if (mode == "quote") "এখানে বক্তব্য দেখাবে" else "এখানে খবরের শিরোনাম দেখাবে" },
        cat = category.trim(),
        date = bnDate(),
        url = cfg.siteUrl,
        tag = tag.trim(),
        cta = cta.trim(),
        siteName = if (mode == "morning") mnSite.trim().ifBlank { cfg.siteName } else cfg.siteName,
        logo = logoBmp,
        icon = iconBmp,
        iconRound = cfg.iconShape != "square",
        fx = fx, fy = fy,
        name = if (mode == "quote") name.trim() else "",
        role = if (mode == "quote") role.trim() else "",
        rashi = if (mode == "morning") rashi else "",
        mnBgs = mnBgs,
        wx = weather,
    )

    private fun adNow(): Bitmap? = if (useAd) adBmp else null

    /** ওয়েবের fcWM: প্রিভিউ/থাম্বনেইলের ওপর বড় ওয়াটারমার্ক (সেভ/পোস্টের কার্ডে থাকে না) */
    private fun wm(src: Bitmap): Bitmap {
        val b = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val w = b.width.toFloat(); val h = b.height.toFloat(); val fs = Math.round(w * 0.115f).toFloat()
        val c = Canvas(b)
        c.save(); c.translate(w / 2f, h / 2f); c.rotate(-30f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fs; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER; strokeJoin = Paint.Join.ROUND }
        val y = -(p.ascent() + p.descent()) / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(2f, fs * 0.07f); p.color = Color.argb(97, 0, 0, 0)
        c.drawText("auto-post.site", 0f, y, p)
        p.style = Paint.Style.FILL; p.color = Color.argb(140, 255, 255, 255)
        c.drawText("auto-post.site", 0f, y, p)
        c.restore()
        return b
    }

    /** প্রিভিউ ও থাম্বনেইল নতুন করে আঁকে (টাইপ করার সময় বারবার না আঁকতে সামান্য অপেক্ষা) */
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            delay(60)
            val d = data(); val id = tplId; val ad = adNow(); val list = cardTpls(mode)
            val (pv, th) = withContext(Dispatchers.Default) {
                wm(CardRender.render(id, d, 1f, ad)) to list.associate { it.first to wm(CardRender.render(it.first, d, 0.25f, ad)) }
            }
            preview = pv; thumbs = th
        }
    }

    // ───────── ওয়েবের বাকি ফোটোকার্ড ফিচার: Facebook-এ পোস্ট, সিডিউল, ১ টাকার হিসাব, AI ছবি, লেখা জেনারেট, ছবি থেকে লেখা, কোলাজ, ফ্লিপ ─────────
    var link by mutableStateOf("")            // কমেন্টে "বিস্তারিত পড়ুন: লিংক" যায় (ঐচ্ছিক)
    var balance by mutableStateOf<Double?>(null)
    var fcBusy by mutableStateOf(false)
    var fcOut by mutableStateOf<String?>(null)
    var used by mutableStateOf(false)         // পোস্ট/সিডিউল হয়ে গেছে; "ক্লিয়ার" না চাপা পর্যন্ত আবার নয় (ওয়েবের fcUsed)
    var aiOpen by mutableStateOf(false)
    var aiPrompt by mutableStateOf("")
    var aiBusy by mutableStateOf(false)
    var aiMsg by mutableStateOf("")
    var ocrMsg by mutableStateOf("")
    var ocrText by mutableStateOf("")
    var ocrBusy by mutableStateOf(false)
    var collVertical by mutableStateOf(false)
    var fcFbUrl by mutableStateOf<String?>(null)   // পোস্টের পর "ফেসবুক পোস্ট দেখুন" লিংক
    var fileMsg by mutableStateOf(FC_FILE_HINT)
    /** কোলাজের প্রতিটি ছবি নিজের জুম/পজিশনসহ (কোলাজ না হলে ফাঁকা) */
    var collItems by mutableStateOf<List<PostUtil.CollageItem>>(emptyList())
        private set
    val canEditCollage: Boolean get() = collItems.size >= 2
    /** ছবি থেকে লেখা: ঘরে আগে লেখা থাকলে বদলাবে কিনা জিজ্ঞেস (বক্তব্য, নাম, পদবি) */
    var ocrAsk by mutableStateOf<Triple<String, String, String>?>(null)
    val mnIsOwner: Boolean get() = mnOwner
    /** টেমপ্লেটের তালিকা সার্ভার থেকে এসেছে (না এলে এডিট/➕ বন্ধ, নইলে সেভে কাস্টমগুলো মুছে যেত) */
    var tplReady by mutableStateOf(false)
        private set
    fun tplChanged(select: String?) {
        if (select != null && mode == "photo" && TplRegistry.isKnownPhotoTpl(select)) tplId = select
        if (mode == "photo" && !TplRegistry.isKnownPhotoTpl(tplId)) tplId = "dark"
        photoVer++
    }
    private val sp by lazy { app.getSharedPreferences("photocard", Context.MODE_PRIVATE) }
    private var ctaTouched = false
    private var catTouched = false
    private var tagTouched = false
    private var tagAi = false
    private var geminiPending = false
    /** Gemini থেকে বানানো ছবি আপলোড হতে যাচ্ছে — ছবি বসলে "AI জেনারেটেড" ট্যাগ বসবে */
    fun expectGeminiImage() { geminiPending = true }
    private var sid = PostUtil.newSid()       // একটা কার্ডের জন্য মোট ১ টাকা: একই sid-এ আর কাটে না
    var paid by mutableStateOf(false)         // এই কার্ডের ১ টাকা কাটা হয়ে গেছে; ফর্ম লক (ওয়েবের fcPaid)
        private set
    private var email = ""
    private var picked: List<Bitmap> = emptyList()

    fun refreshBalance() {
        viewModelScope.launch {
            runCatching { Worker.post(app, "/cslot-get", JSONObject(), 15000) }.getOrNull()?.let { balance = it.optDouble("balance", 0.0) }
        }
    }

    private fun postTitle() = if (mode == "morning") "শুভ সকাল" else title.trim()

    /** ওয়েবের fcCheck: কিছু বাদ পড়লে কারণ ফেরত দেয় */
    fun problem(skipLink: Boolean = false): String? {
        if (mode != "morning" && title.isBlank()) return if (mode == "quote") "বক্তব্য লিখুন" else "ফোটোকার্ড টাইটেল লিখুন"
        if (mode != "morning" && !hasPhoto) return "ছবি আপলোড করুন"
        if (!skipLink && mode == "photo" && link.isNotBlank() && !Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(link.trim()))
            return "লিংকটি http:// বা https:// দিয়ে শুরু হতে হবে"
        val b = balance
        if (!paid && b != null && b < Rates.get("pc")) return "ব্যালেন্স কম আছে (কমপক্ষে ${Rates.taka("pc")} লাগবে)। ড্যাশবোর্ড থেকে ব্যালেন্স যোগ করুন"
        return null
    }

    private suspend fun cardDataUrl(): String = withContext(Dispatchers.Default) {
        PostUtil.toDataUrl(CardRender.render(tplId, data(), 1f, adNow()), 92)
    }

    private suspend fun afterUse(usedAd: Boolean) {
        val cur = nextAd(ads, adLast)
        if (usedAd && cur != null) {
            adLast = cur.id
            rebuildAd()
            runCatching { Fs.saveAds(app, ads, adLast) }.onFailure { notice = "⚠️ বিজ্ঞাপনের ক্রম সেভ হয়নি: " + (it.message ?: "") }
            adTick++
        }
    }
    var adTick by mutableIntStateOf(0)        // পোস্টের পর বিজ্ঞাপনের "পরেরটা" বদলালে স্ক্রিন বিজ্ঞাপন ট্যাব রিফ্রেশ করে

    private suspend fun ensureEmail() {
        if (email.isBlank()) email = runCatching { Worker.post(app, "/whoami", JSONObject(), 15000).optString("email") }.getOrDefault("")
    }

    private fun task(block: suspend () -> Unit) {
        if (fcBusy) return
        fcBusy = true; fcOut = null; fcFbUrl = null
        viewModelScope.launch {
            try { block() } catch (e: Exception) { fcOut = "❌ " + (e.message ?: "সমস্যা হয়েছে") } finally { fcBusy = false }
        }
    }

    /** 📘 এখনই Facebook পেইজে ফোটোকার্ড পোস্ট (লিংক দিলে কমেন্টে যায়) */
    fun postNow() = task {
        problem()?.let { throw ApiError(it) }
        val usedAd = adNow() != null
        val body = JSONObject().put("kind", "card").put("title", postTitle()).put("link", link.trim()).put("card", cardDataUrl()).put("sid", sid)
        val d = Worker.post(app, "/photocard", body, 90000)
        paid = true; used = true
        if (d.has("balance")) balance = d.optDouble("balance")
        afterUse(usedAd)
        val fb = d.optString("facebook_post")
        val head = if (link.isNotBlank()) {
            if (d.optBoolean("comment_ok")) "✅ ফোটোকার্ড পোস্ট হয়েছে আর কমেন্টে লিংক দেওয়া হয়েছে। "
            else "⚠️ ফোটোকার্ড পোস্ট হয়েছে, কিন্তু কমেন্টে লিংক যায়নি (" + d.optString("comment_error").ifBlank { "কারণ অজানা" } + ")। হাতে কমেন্ট করুন। "
        } else "✅ ফোটোকার্ড পোস্ট হয়েছে। "
        fcOut = head
        fcFbUrl = if (fb.isNotBlank()) "https://www.facebook.com/$fb" else null
        ensureEmail()
        runCatching { Fs.addPost(app, postTitle(), link.trim(), fb, email) }.onFailure { fcOut += "\nপোস্ট হয়েছে, কিন্তু ড্যাশবোর্ডের হিসাবে সেভ হয়নি: " + (it.message ?: "") }
    }

    /** ⏰ সিডিউল পোস্ট: Worker নিজেই সময়মতো পোস্ট করে (অ্যাপ বন্ধ থাকলেও) */
    fun schedule(atMs: Long, onDone: () -> Unit) = task {
        problem()?.let { throw ApiError(it) }
        if (atMs < System.currentTimeMillis() + 60000) throw ApiError("অন্তত ১ মিনিট পরের সময় দিন")
        val usedAd = adNow() != null
        val body = JSONObject().put("kind", "card").put("title", postTitle()).put("link", link.trim()).put("card", cardDataUrl()).put("at", atMs).put("sid", sid)
        val d = Worker.post(app, "/schedule", body, 60000)
        paid = true; used = true
        if (d.has("balance")) balance = d.optDouble("balance")
        afterUse(usedAd)
        fcOut = "✅ সিডিউল হয়েছে (সিডিউল ট্যাবে দেখুন)।"
        onDone()
    }

    /** সেভ/শেয়ারের আগে: এই কার্ডের ১ টাকা কাটা (একবারই, একই sid-এ)। ব্যর্থ হলে false */
    suspend fun chargeSave(): Boolean {
        problem(skipLink = true)?.let { fcOut = "❌ $it"; return false }
        if (paid) return true
        return try {
            val d = Worker.post(app, "/card-save", JSONObject().put("sid", sid), 30000)
            paid = true
            if (d.has("balance")) balance = d.optDouble("balance")
            true
        } catch (e: Exception) { fcOut = "❌ " + (e.message ?: "সমস্যা হয়েছে"); false }
    }

    /** 🧹 ক্লিয়ার (নতুন করে শুরু): সব খালি, নতুন সেশন */
    fun clearAll() {
        title = ""; name = ""; role = ""; rashi = ""; link = ""; photo = null; hasPhoto = false; picked = emptyList()
        collItems = emptyList(); fileMsg = FC_FILE_HINT; fcFbUrl = null; ocrAsk = null; cropUndo = null
        fx = 0.5f; fy = 0.5f; photoVer++; savedUri = null; savedSig = ""
        sid = PostUtil.newSid(); paid = false; used = false; fcOut = null; notice = null
        aiMsg = ""; aiPrompt = ""; ocrMsg = ""; ocrText = ""
        tagTouched = false; tagAi = false; tag = cfg.photoTag   // ক্যাটাগরি ও নিচের লেখা আগের মতোই থাকে (ওয়েবের fcReset)
    }

    /** ✨ AI দিয়ে ছবি তৈরী (বাংলাদেশের প্রেক্ষাপট, বাস্তব ধাঁচ); ছবির ট্যাগে "AI জেনারেটেড" বসে */
    fun aiImage() {
        val p = aiPrompt.trim()
        if (p.isEmpty()) { aiMsg = "⚠️ আগে প্রম্পট লিখুন (কেমন ছবি চান)"; return }
        aiBusy = true; aiMsg = "🎨 ছবি বানাচ্ছি... (১০-৩০ সেকেন্ড লাগতে পারে)"
        viewModelScope.launch {
            try {
                val d = Worker.post(app, "/image", JSONObject().put("prompt", p).put("style", "real"), 90000)
                val img = d.optString("image")
                if (img.length < 500) throw ApiError("Worker ছবি পাঠায়নি। worker_dashboard.js নতুন করে ডিপ্লয় করেছেন কি না দেখুন, তারপর আবার চেষ্টা করুন")
                val b = PostUtil.fromDataUrl(img) ?: throw ApiError("ছবি এসেছে কিন্তু খোলা যায়নি। প্রম্পট একটু বদলে আবার চেষ্টা করুন")
                collItems = emptyList(); fileMsg = FC_FILE_HINT
                photo = PostUtil.shrink(b, 1600); hasPhoto = true; fx = 0.5f; fy = 0.5f; picked = emptyList()
                tag = PostUtil.AI_TAG; tagTouched = true; tagAi = true; photoVer++
                if (d.optString("prompt").isNotBlank()) aiPrompt = d.getString("prompt")
                aiMsg = "✅ ছবি তৈরি হয়েছে, ছবির ট্যাগে \"AI জেনারেটেড\" বসেছে। পছন্দ না হলে আবার \"ছবি বানাও\" চাপুন।"
            } catch (e: Exception) { aiMsg = "⚠️ " + (e.message ?: "ছবি বানানো যায়নি") } finally { aiBusy = false }
        }
    }

    /** ✨ জেনারেট: লেখা টাইটেলের ভিত্তিতে AI নতুন টাইটেল লেখে (ওয়েবে ১ টাকা, সফল হলে) */
    fun genTitle() {
        val src = title.trim()
        if (mode != "photo") return
        if (src.isEmpty()) { fcOut = "❌ আগে ফোটোকার্ড টাইটেলে কিছু লিখুন, তারপর জেনারেট চাপুন"; return }
        balance?.let { if (it < Rates.get("pt")) { fcOut = "❌ ব্যালেন্স কম আছে (কমপক্ষে ${Rates.taka("pt")} লাগবে)। ড্যাশবোর্ড থেকে ব্যালেন্স যোগ করুন"; return } }
        task {
            val d = Worker.post(app, "/title-gen", JSONObject().put("title", src).put("kind", "title"), 60000)
            d.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
            if (d.has("balance")) balance = d.optDouble("balance")
        }
    }

    /** 🖼️ ছবি থেকে লেখা (বক্তব্য কার্ড): ছবির ভেতরের বাংলা/ইংরেজি লেখা পড়ে বক্তব্য, নাম, পদবির ঘরে বসায় */
    fun ocr(uri: Uri) {
        ocrBusy = true; ocrText = ""; ocrMsg = "⏳ ছবি থেকে লেখা পড়া হচ্ছে..."
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { decodePhoto(uri) } ?: throw ApiError("ছবিটা খোলা যায়নি")
                val img = withContext(Dispatchers.Default) { PostUtil.toDataUrl(PostUtil.shrink(bmp, 2000), 85) }
                val d = Worker.post(app, "/ocr-text", JSONObject().put("image", img), 70000)
                ocrText = d.optString("text")
                if (ocrText.isBlank()) ocrMsg = "⚠️ ছবিতে পড়ার মতো কোনো লেখা পাওয়া যায়নি"
                else {
                    val q = d.optString("quote"); val n = d.optString("name"); val r = d.optString("role")
                    if (paid || (q.isBlank() && n.isBlank() && r.isBlank())) ocrMsg = "✅ লেখা পাওয়া গেছে। "
                    else {
                        val clash = (q.isNotBlank() && title.isNotBlank() && title.trim() != q) || (n.isNotBlank() && name.isNotBlank() && name.trim() != n) || (r.isNotBlank() && role.isNotBlank() && role.trim() != r)
                        if (clash) { ocrMsg = "✅ লেখা পাওয়া গেছে। "; ocrAsk = Triple(q, n, r) } else applyOcr(q, n, r)
                    }
                }
                if (d.has("balance")) balance = d.optDouble("balance")
            } catch (e: Exception) { ocrMsg = "⚠️ " + (e.message ?: "লেখা পড়া যায়নি") + " — আবার চেষ্টা করুন" } finally { ocrBusy = false }
        }
    }

    private fun applyOcr(q: String, n: String, r: String) {
        val filled = ArrayList<String>()
        if (q.isNotBlank()) { title = q; filled.add("বক্তব্য") }
        if (n.isNotBlank()) { name = n; filled.add("নাম") }
        if (r.isNotBlank()) { role = r; filled.add("পদবি") }
        ocrMsg = "✅ লেখা পাওয়া গেছে। " + if (filled.isNotEmpty()) filled.joinToString(", ") + " ওপরের ঘরে বসে গেছে।" else ""
    }

    fun resolveOcr(yes: Boolean) {
        val a = ocrAsk ?: return
        ocrAsk = null
        if (yes) applyOcr(a.first, a.second, a.third)
    }

    /** গ্যালারি: ১টি হলে সরাসরি, ২–৬টি হলে কোলাজ */
    fun setPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) { setPhoto(uris[0]); return }
        fileMsg = "⏳ ছবি প্রসেস হচ্ছে..."
        viewModelScope.launch {
            try {
                val bmps = withContext(Dispatchers.IO) { uris.take(6).mapNotNull { decodePhoto(it) } }
                if (bmps.isEmpty()) { fileMsg = "⚠️ ছবি যোগ করা যায়নি — আবার চেষ্টা করুন"; return@launch }
                picked = bmps
                val items = bmps.map { PostUtil.CollageItem(it) }
                collItems = items
                photo = withContext(Dispatchers.Default) { PostUtil.collageItems(items, collVertical) }
                hasPhoto = true; notice = null; fx = 0.5f; fy = 0.5f
                if (tagAi) { tagAi = false; tagTouched = false; tag = cfg.photoTag }
                if (geminiPending) { geminiPending = false; tag = PostUtil.AI_TAG; tagTouched = true; tagAi = true }
                photoVer++
                fileMsg = "✅ " + PostUtil.bn(bmps.size) + "টি ছবির কোলাজ হয়েছে — ছবি টেনে পজিশন ঠিক করুন"
            } catch (e: Exception) { fileMsg = "⚠️ " + (e.message ?: "ছবি যোগ করা যায়নি") + " — আবার চেষ্টা করুন" }
        }
    }

    @JvmName("applyCollVertical")
    fun setCollVertical(v: Boolean) {
        collVertical = v
        val items = collItems
        if (items.size == 2) viewModelScope.launch {
            photo = withContext(Dispatchers.Default) { PostUtil.collageItems(items, v) }; fx = 0.5f; fy = 0.5f; photoVer++
            fileMsg = "✅ " + (if (v) "উপর-নিচ" else "পাশাপাশি") + " সাজানো হয়েছে"
        }
    }

    /** 🧩 কোলাজ এডিট: প্রতিটি ছবির জুম/পজিশন আলাদা রেখে আবার কোলাজ বানানো */
    fun applyCollage(items: List<PostUtil.CollageItem>, vertical: Boolean) {
        if (items.size < 2) return
        collItems = items; collVertical = vertical
        viewModelScope.launch {
            photo = withContext(Dispatchers.Default) { PostUtil.collageItems(items, vertical) }; fx = 0.5f; fy = 0.5f; photoVer++
            fileMsg = "✅ কোলাজ হালনাগাদ হয়েছে"
        }
    }

    /** ↔️ ছবি ফ্লিপ (ডান-বাম উল্টান) */
    fun flipPhoto() { photo?.let { collItems = emptyList(); photo = PostUtil.flipH(it); photoVer++ } }

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
        cropUndo = im; cropUndoItems = collItems
        collItems = emptyList()          // ক্রপের পর কোলাজ আর আলাদা করে সরানো যাবে না (↩️ দিলে ফিরবে)
        photo = out; fx = 0.5f; fy = 0.5f; notice = null
        photoVer++
        cropUndoVer = photoVer
    }

    fun undoCrop() {
        val old = cropUndo ?: return
        if (cropUndoVer != photoVer) return
        photo = old; cropUndo = null; collItems = cropUndoItems; cropUndoItems = emptyList(); fx = 0.5f; fy = 0.5f
        photoVer++
    }

    /** Pictures/NewsPublisher-এ JPEG সেভ; আগেই সেভ হয়ে থাকলে (কিছু না বদলালে) সেটাই ফেরত দেয় */
    suspend fun save(): Uri? = withContext(Dispatchers.IO) {
        savedUri?.let { if (savedSig == sig()) return@withContext it }
        val usedAd = adNow()
        val bmp = CardRender.render(tplId, data(), 1f, usedAd)
        val name = "card_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NewsPublisher")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = app.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return@withContext null
        val ok = runCatching { r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } == true }.getOrDefault(false)
        if (!ok) { runCatching { r.delete(uri, null, null) }; return@withContext null }
        v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0)
        r.update(uri, v, null, null)
        // এই কার্ডে যে বিজ্ঞাপন গেল সেটা মনে রাখা; পরের কার্ডে পরেরটা (ওয়েবের adAdvance)
        val cur = nextAd(ads, adLast)
        if (usedAd != null && cur != null) {
            adLast = cur.id
            rebuildAd()
            runCatching { Fs.saveAds(app, ads, adLast) }.onFailure { notice = "⚠️ বিজ্ঞাপনের ক্রম সেভ হয়নি: " + (it.message ?: "") }
        }
        savedUri = uri; savedSig = sig()
        uri
    }
}
