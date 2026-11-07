package com.digitalcrafting25.newsapp

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(private val app: Application) : AndroidViewModel(app) {
    val tl = Timeline()
    val clips: List<Clip> get() = tl.clips

    /** অসমাপ্ত এডিট (HTML-এর "📝 অসমাপ্ত ভিডিও এডিট"): ফোনে সেভ থাকে, অ্যাপ কেটে দিলেও ফিরে আসে */
    class Draft(val id: Long, val clips: List<Clip>, val layers: List<Layer>, val savedAt: Long)
    val drafts = mutableStateListOf<Draft>()
    private var currentDraftId: Long? = null

    init {
        // আগের বারের সেভ করা ড্রাফট ফেরানো; যেসব ফাইল আর খোলা যায় না সেগুলো বাদ দেওয়া হয়
        val saved = DraftStore.load(app)
        var maxId = 0L
        saved.forEach { d ->
            maxId = maxOf(maxId, d.id)
            d.clips.forEach { maxId = maxOf(maxId, it.id) }
            d.layers.forEach { l -> maxId = maxOf(maxId, l.id); if (l is AudioLayer) l.sourceClipId?.let { maxId = maxOf(maxId, it) } }
        }
        tl.ensureIdAbove(maxId)
        drafts.addAll(saved.sortedByDescending { it.savedAt })
        if (saved.isNotEmpty()) viewModelScope.launch {
            val cleaned = withContext(Dispatchers.IO) {
                saved.mapNotNull { d ->
                    val clips = d.clips.filter { DraftStore.readable(app, it.uri) }
                    if (clips.isEmpty()) return@mapNotNull null
                    val layers = d.layers.filter { l ->
                        val u = when (l) { is ImageLayer -> l.uri; is AdLayer -> l.uri; is AudioLayer -> l.uri; else -> null }
                        u == null || DraftStore.readable(app, u)
                    }
                    Draft(d.id, clips, layers, d.savedAt)
                }
            }
            // এর মধ্যে ব্যবহারকারী যেসব ড্রাফট বদলেছে/যোগ করেছে সেগুলো ছোঁয়া হয় না
            val byId = cleaned.associateBy { it.id }
            val orig = saved.associateBy { it.id }
            drafts.removeAll { it === orig[it.id] && it.id !in byId }
            for (i in drafts.indices) {
                val d = drafts[i]
                val c = byId[d.id]
                if (c != null && d === orig[d.id]) drafts[i] = c
            }
            persist(sync = false)
        }
    }

    private fun persist(sync: Boolean) {
        val snap = drafts.toList()
        if (sync) DraftStore.save(app, snap)
        else viewModelScope.launch(Dispatchers.IO) { DraftStore.save(app, snap) }
    }

    /** চলতি এডিট ড্রাফট তালিকায় (নতুন আগে) রাখে; আগে থেকে থাকলে নতুন করে বসায়। ফোনেও সেভ হয় */
    fun saveDraft(sync: Boolean = false) {
        if (tl.clips.isEmpty()) return
        val id = currentDraftId ?: tl.newId().also { currentDraftId = it }
        drafts.removeAll { it.id == id }
        drafts.add(0, Draft(id, tl.clips, tl.layers, System.currentTimeMillis()))
        persist(sync)
    }

    /** "ভিডিও বাছুন": আগেরটা ড্রাফট হিসেবে রেখে একদম নতুন এডিট */
    fun startNew() {
        saveDraft()
        tl.resetAll()
        currentDraftId = null
    }

    /** ড্রাফটে "চালিয়ে যান"; চলতিটা আগে সেভ হয়ে থাকে */
    fun openDraft(id: Long): Boolean {
        val d = drafts.firstOrNull { it.id == id } ?: return false
        if (currentDraftId != id) saveDraft()
        tl.loadSnapshot(d.clips, d.layers)
        currentDraftId = id
        return true
    }

    fun removeDraft(id: Long) {
        drafts.removeAll { it.id == id }
        if (currentDraftId == id) { tl.resetAll(); currentDraftId = null }
        persist(sync = false)
        // এই ড্রাফটের জন্য কপি করা ফাইল আর কোথাও না লাগলে মুছে ফেলা
        val inUse = HashSet<String>()
        drafts.forEach { inUse.addAll(DraftStore.urisOf(it.clips, it.layers)) }
        inUse.addAll(DraftStore.urisOf(tl.clips, tl.layers))
        viewModelScope.launch(Dispatchers.IO) { DraftStore.deleteUnused(app, inUse) }
    }
    var aspect by mutableStateOf(Aspect.LANDSCAPE)
    var caption by mutableStateOf("")
    var logo by mutableStateOf<Uri?>(null)
    var music by mutableStateOf<Uri?>(null)
    var musicName by mutableStateOf("")
    var musicVolume by mutableFloatStateOf(0.3f)
    var keepAudio by mutableStateOf(true)
    // পাবলিশ ডায়ালগ ও "২ ভিডিও" পেইজ (HTML-এর vTitle / vc_pub_tt / vc_pub_tb / pbTc)
    var videoTitle by mutableStateOf("")
    var fbTop by mutableStateOf("")
    var fbBottom by mutableStateOf("")
    var fbColor by mutableIntStateOf(0xFF000000.toInt())
    private fun displayName(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: "ফাইল"

    /** front/back = null: সাধারণ যোগ (শেষে); true = টাইমলাইনের একদম সামনে, false = একদম পিছনে ("➕ সামনে/পিছনে ভিডিও") */
    fun addUris(uris: List<Uri>, front: Boolean? = null) {
        viewModelScope.launch {
            val added = mutableListOf<Clip>()
            for (u in uris) {
                val clip = withContext(Dispatchers.IO) {
                    val isImage = app.contentResolver.getType(u)?.startsWith("image/") == true
                    var dur = 0L
                    if (!isImage) {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(app, u)
                            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } catch (_: Exception) {
                        } finally {
                            runCatching { r.release() }
                        }
                    }
                    Clip(tl.newId(), DraftStore.keep(app, u), isImage, dur, displayName(u))
                }
                added.add(clip)
            }
            if (front == null) tl.addAll(added) else tl.addAtEdge(added, front)
        }
    }

    /** ইন্ট্রো/আউট্রো ভিডিও বানানোর সময় true (একসাথে দুটো বানানো ঠেকাতে) */
    var cardBusy by mutableStateOf(false)
        private set
    /** কার্ড বানানোর অগ্রগতি ০..১০০ */
    var cardProgress by mutableIntStateOf(0)
        private set

    /**
     * রেডি ইন্ট্রো/আউট্রো: অ্যানিমেটেড ভিডিও-কার্ড (সাউন্ডসহ) বানিয়ে বসায়; আগে থেকে থাকলে সেটাই বাছে।
     * সমস্যা থাকলে বার্তা ফেরত দেয়। বানাতে কয়েক সেকেন্ড লাগে, শেষে onResult-এ খবর আসে।
     */
    fun addCard(outro: Boolean, onResult: (String?) -> Unit = {}): String? {
        if (clips.isEmpty()) return "আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন, তারপর ইন্ট্রো/আউট্রো দিন"
        tl.cardClip(outro)?.let {
            tl.selectedId = it.id
            tl.selectedLayerId = null
            return null
        }
        if (cardBusy) return "একটু অপেক্ষা করুন, আগের কার্ড বানানো চলছে"
        val (w, h) = aspect.w to aspect.h
        val spec = (if (outro) CardDefaults.outro(logo) else CardDefaults.intro(caption, logo)).copy(w = w, h = h)
        buildCard(spec, w, h, onResult) { uri, dur ->
            val m = tl.addCard(spec, uri, dur)
            onResult(m ?: if (outro) "🏁 আউট্রো বসেছে" else "🎬 ইন্ট্রো বসেছে — লেখা/স্টাইল নিচে বদলান")
        }
        return null
    }

    fun updateCard(id: Long, spec: CardSpec, onResult: (String?) -> Unit = {}) {
        if (cardBusy) { onResult("একটু অপেক্ষা করুন, আগের কার্ড বানানো চলছে"); return }
        val (w, h) = aspect.w to aspect.h
        val s2 = spec.copy(w = w, h = h)
        buildCard(s2, w, h, onResult) { uri, dur ->
            tl.setCard(id, s2, uri, dur)
            onResult("✅ কার্ড বদলানো হয়েছে")
        }
    }

    private fun buildCard(spec: CardSpec, w: Int, h: Int, onResult: (String?) -> Unit, onDone: (Uri, Long) -> Unit) {
        cardBusy = true
        cardProgress = 0
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { CardVideoMaker.make(app, spec, w, h) { cardProgress = (it * 100f).toInt().coerceIn(0, 100) } }
            }
            cardBusy = false
            r.onSuccess { (uri, dur) -> onDone(uri, dur) }
                .onFailure { onResult("⚠️ ইন্ট্রো/আউট্রো বানানো যায়নি: ${it.message ?: "অজানা সমস্যা"}") }
        }
    }

    fun chooseMusic(u: Uri) {
        music = u
        musicName = displayName(u)
    }

    /** সমস্যা থাকলে বার্তা ফেরত দেয়, ঠিক থাকলে রেন্ডার সার্ভিস চালু করে ও null দেয় */
    /**
     * HTML-এর পাবলিশ ডায়ালগের মতো পাস: ফেইসবুক টিক থাকলে আগে ফেইসবুক ভিডিও, ইউটিউব (বা ওয়ার্ডপ্রেস) টিক থাকলে তারপর ইউটিউব ভিডিও —
     * দুটো টিক দিলে দুটো আলাদা ভিডিও (একটার পর একটা)।
     * - ফেইসবুক: ব্যানার বা লেখা থাকলে ওপরে-নিচে সাদা ব্যান্ড; ব্যানার ওই ব্যান্ডে বসে (ভিডিওর ভেতরে নয়), লেখা শুধু সেই দিকে যেদিকে ব্যানার নেই
     * - ইউটিউব: ১২৮০×৭২০, শুধু নিচের ব্যানার ভিতরে (ওপরের ব্যানার বসে না)
     */
    fun startRender(fb: Boolean = false, yt: Boolean = false): String? {
        if (!fb && !yt) return "কোথায় পাবলিশ হবে সেটা বাছুন"
        if (clips.isEmpty()) return "আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন"
        for ((i, c) in clips.withIndex()) {
            if (c.isImage) {
                if (c.imageSec <= 0f) return "ক্লিপ ${i + 1}: ছবির সময় ০ এর বেশি দিন"
            } else {
                val durSec = c.durationMs / 1000f
                if (c.startSec < 0f || (durSec > 0 && c.startSec >= durSec)) return "ক্লিপ ${i + 1}: শুরুর সময় ভিডিওর দৈর্ঘ্যের মধ্যে দিন"
                if (c.endSec != 0f && c.endSec <= c.startSec) return "ক্লিপ ${i + 1}: শেষ সময় শুরুর চেয়ে বেশি দিন (০ = শেষ পর্যন্ত)"
            }
        }
        for (l in tl.layers) LayerDefaults.validate(l)?.let { return it }
        val hasTopAd = tl.layers.any { it.kind == LayerKind.AD_TOP }
        val hasBottomAd = tl.layers.any { it.kind == LayerKind.AD_BOTTOM }
        val top = if (hasTopAd) "" else fbTop.trim()
        val bottom = if (hasBottomAd) "" else fbBottom.trim()
        // ব্যান্ডের উচ্চতা = ব্যানারের উচ্চতা ১০৮ (HTML-এর pbSize: ৭২০ + ২×sh, sh = ৭২০-এর ১৫%); জোড় সংখ্যা, এনকোডারের জন্য
        // ফেইসবুকে ব্যানারও ভিডিওর বাইরে এই ব্যান্ডে বসে (HTML-এর মতো), তাই ব্যানার বা লেখা — যেকোনো একটা থাকলেই ব্যান্ড চালু
        val band = if (fb && aspect == Aspect.LANDSCAPE && (hasTopAd || hasBottomAd || top.isNotEmpty() || bottom.isNotEmpty())) EDITOR_AD_H else 0
        val asp = aspect
        val snapClips = clips.toList()
        val snapLayers = tl.layers.toList()
        val cap = caption
        val lg = logo
        val mu = music
        val mv = musicVolume
        val ka = keepAudio
        val fc = fbColor
        // পাসের তালিকা: ফেইসবুক আগে, ইউটিউব পরে (HTML-এর মতো)
        class Plan(val mode: String, val band: Int, val layers: List<Layer>, val fbTop: String, val fbBottom: String)
        val plans = ArrayList<Plan>()
        if (fb) plans += Plan("fb", band, snapLayers, top, bottom)
        if (yt) plans += Plan("yt", 0, snapLayers.filterNot { it is AdLayer && it.isTop }, "", "")   // ইউটিউবে ওপরের ব্যানার বসে না
        RenderBus.status.value = RenderStatus.Running(0, 1, plans.size, if (plans.first().mode == "fb") "ফেইসবুক" else "ইউটিউব")
        viewModelScope.launch {
            // কার্ড (ইন্ট্রো/আউট্রো) যে মাপে বানানো সেটা পাসের ক্যানভাসের মাপের সাথে না মিললে ওই মাপে আবার বানানো
            // (দৈর্ঘ্য একই থাকে, তাই টাইমলাইন/লেয়ারের সময় বদলায় না; ব্যর্থ হলে আগের ফাইলই চলে)
            val built = withContext(Dispatchers.IO) {
                val cache = HashMap<Int, List<Clip>>()   // ক্যানভাসের উচ্চতা → ক্লিপ (দুই পাসে একই মাপ হলে একবারই বানানো)
                plans.map { p ->
                    // ব্যান্ড চালু থাকলে কার্ড পুরো ক্যানভাসের মাপে (১২৮০×(৭২০+২×ব্যান্ড)) — ইন্ট্রো/আউট্রোতে ব্যান্ড থাকে না
                    val th = asp.h + 2 * p.band
                    val clipsForPass = cache.getOrPut(th) {
                        snapClips.map { c ->
                            val s = c.card
                            if (s != null && !c.isImage && (s.w != asp.w || s.h != th)) {
                                runCatching {
                                    val s2 = s.copy(w = asp.w, h = th)
                                    val (u, d) = CardVideoMaker.make(app, s2, asp.w, th)
                                    c.copy(uri = u, card = s2, durationMs = d)
                                }.getOrDefault(c)
                            } else c
                        }
                    }
                    RenderPass(
                        p.mode,
                        RenderSpec(
                            clipsForPass, asp, cap, lg, mu, mv, ka, p.layers,
                            fbTop = p.fbTop, fbBottom = p.fbBottom, fbColor = fc, fbBandPx = p.band, fbAdsInBand = p.band > 0,
                        ),
                    )
                }
            }
            RenderBus.passes = built
            ContextCompat.startForegroundService(app, Intent(app, RenderService::class.java))
        }
        return null
    }

    fun cancelRender() {
        app.startService(Intent(app, RenderService::class.java).setAction(RenderService.ACTION_CANCEL))
    }
}

data class UpdateUiState(
    val busy: Boolean = false,
    val message: String = "",
    val available: UpdateInfo? = null,
    val showPopup: Boolean = false,
    val progress: Float? = null,      // ডাউনলোড চলাকালীন 0..1
    val downloaded: Boolean = false,  // ডাউনলোড শেষ, ইন্সটলের জন্য তৈরি
)

class UpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** ম্যানুয়াল আপডেট চেক */
    fun check() {
        if (_state.value.busy) return
        _state.value = UpdateUiState(busy = true, message = "চেক করা হচ্ছে…")
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                _state.value = when {
                    info == null -> UpdateUiState(message = "কোনো রিলিজ পাওয়া যায়নি")
                    Updater.isNewer(info) -> UpdateUiState(message = "🎉 নতুন সংস্করণ পাওয়া গেছে: ${info.versionName}", available = info)
                    else -> UpdateUiState(message = "✅ আপনার অ্যাপ আপ-টু-ডেট")
                }
            } catch (e: Exception) {
                _state.value = UpdateUiState(message = "❌ চেক করা যায়নি: ${e.message}")
            }
        }
    }

    /**
     * অটো চেক: অ্যাপ খুললে (সর্বোচ্চ ৬ ঘণ্টায় একবার) চুপচাপ চেক করে।
     * নতুন ভার্সন থাকলে হোম পেজে পপ-আপ দেখায়। নেট/GitHub সমস্যায় কিছুই দেখায় না।
     */
    fun autoCheck() {
        if (autoChecked) return
        autoChecked = true
        val prefs = app.getSharedPreferences("updater", android.content.Context.MODE_PRIVATE)
        val last = prefs.getLong("lastCheck", 0L)
        if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000L) return
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                if (info != null && Updater.isNewer(info)) {
                    _state.update { it.copy(available = info, showPopup = true) }
                }
            } catch (_: Exception) { /* চুপচাপ বাদ — পরের বার আবার চেক হবে */ }
        }
    }

    fun dismissPopup() = _state.update { it.copy(showPopup = false) }

    /**
     * অ্যাপের ভেতর থেকে ডাউনলোড শুরু (সিস্টেমের DownloadManager দিয়ে), প্রগ্রেস বার সহ।
     * APK যায় অ্যাপের গোপন ফোল্ডারে। শেষ হলে নোটিফিকেশনে ট্যাপ করে ইন্সটল করতে হবে।
     * অ্যাপে কোনো ইন্সটল-পারমিশন লাগে না।
     */
    fun startDownload() {
        val info = _state.value.available ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, progress = 0f, downloaded = false, message = "ডাউনলোড হচ্ছে…") }
        viewModelScope.launch {
            try {
                val dm = app.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
                Updater.removePrevious(app, dm)
                val req = DownloadManager.Request(Uri.parse(info.apkUrl))
                    .setTitle("নিউজ পাবলিশার ${info.versionName}")
                    .setDescription("আপডেট — ডাউনলোড শেষে ট্যাপ করে ইন্সটল করুন")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(app, "updates", "update-${info.versionCode}.apk")
                val id = dm.enqueue(req)
                Updater.rememberDownload(app, id, info.versionCode)

                // প্রগ্রেস দেখাতে নিয়মিত অবস্থা জিজ্ঞেস করা
                while (true) {
                    val st = withContext(Dispatchers.IO) { Updater.queryStatus(dm, id) }
                    if (st == null) throw java.io.IOException("ডাউনলোড বাতিল হয়েছে")
                    when (st.status) {
                        DownloadManager.STATUS_SUCCESSFUL -> break
                        DownloadManager.STATUS_FAILED -> throw java.io.IOException("ডাউনলোড ব্যর্থ হয়েছে, আবার চেষ্টা করুন")
                        else -> _state.update { it.copy(progress = st.progress) }
                    }
                    delay(400)
                }

                val ok = withContext(Dispatchers.IO) { Updater.verify(app, info) }
                if (!ok) {
                    Updater.removePrevious(app, dm)
                    throw java.io.IOException("ডাউনলোড করা ফাইল ঠিক নেই (SHA-256 মেলেনি)")
                }
                _state.update {
                    it.copy(
                        busy = false, progress = null, downloaded = true,
                        message = "✅ ডাউনলোড শেষ। নোটিফিকেশনে ট্যাপ করে ইন্সটল করুন",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, progress = null, message = "❌ ${e.message}") }
            }
        }
    }

    private companion object { var autoChecked = false }
}
