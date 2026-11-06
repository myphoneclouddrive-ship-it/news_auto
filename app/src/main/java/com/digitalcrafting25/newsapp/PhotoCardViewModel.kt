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

    fun reset() {
        job?.cancel()
        loaded = false; loadError = null; busy = false; notice = null
        title = ""; category = ""; tag = ""; cta = ""; tplId = "dark"; mode = "photo"; name = ""; role = ""; rashi = ""; fx = 0.5f; fy = 0.5f; useAd = true
        mnTime = "06:30"; mnOn = false; mnAd = "auto"; mnSite = ""; mnBalance = null; mnBusy = false; mnMsg = null; mnChoices = emptyList()
        mnBgs = emptyMap(); weather = null; mnOwner = false; mnLoaded = false; mnVer++
        ads = emptyList(); adLast = ""; adBmp = null; nextAdId = ""; nextAdLabel = ""; savedSig = ""
        photo = null; hasPhoto = false; preview = null; thumbs = emptyMap(); savedUri = null
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
                rebuildAd()
                if (category.isBlank()) category = c.category
                if (tag.isBlank()) tag = c.photoTag
                if (cta.isBlank()) cta = c.ctaText
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
                mnBalance = it.optDouble("balance", 0.0); mnOwner = it.optBoolean("owner", false)
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
                val low = if (mnOn && b != null && b < 2) " ⚠️ ব্যালেন্স ২ টাকার কম। পোস্ট হবে, কিন্তু ব্যালেন্স মাইনাস হবে; ৭ দিনের মধ্যে ব্যালেন্স যোগ করুন।" else ""
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
            if (bmp == null) { notice = "❌ ছবিটা খোলা যায়নি"; return@launch }
            photo = bmp; hasPhoto = true; notice = null; fx = 0.5f; fy = 0.5f
            photoVer++
        }
    }

    /** ফটোকার্ড ↔ বক্তব্য কার্ড; ছবির অবস্থান আর টেমপ্লেট ঐ ধরনের প্রথমটায় ফেরে */
    fun switchMode(m: String) {
        if (m == mode) return
        mode = m; tplId = cardTpls(m).first().first; fx = 0.5f; fy = 0.5f
    }

    fun clearPhoto() { photo = null; hasPhoto = false; fx = 0.5f; fy = 0.5f; photoVer++ }

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
        if (ok.isNotEmpty()) l.add("auto" to "🔁 অটো")
        l.add("none" to "🏷️ ব্যানার ছাড়া")
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

    /** প্রিভিউ ও থাম্বনেইল নতুন করে আঁকে (টাইপ করার সময় বারবার না আঁকতে সামান্য অপেক্ষা) */
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            delay(60)
            val d = data(); val id = tplId; val ad = adNow(); val list = cardTpls(mode)
            val (pv, th) = withContext(Dispatchers.Default) {
                CardRender.render(id, d, 1f, ad) to list.associate { it.first to CardRender.render(it.first, d, 0.25f, ad) }
            }
            preview = pv; thumbs = th
        }
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
