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

    fun addUris(uris: List<Uri>) {
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
            tl.addAll(added)
        }
    }

    /** রেডি ইন্ট্রো/আউট্রো: ডিফল্ট লেখায় কার্ড বানিয়ে বসায়; আগে থেকে থাকলে সেটাই বাছে। সমস্যা থাকলে বার্তা ফেরত দেয় */
    fun addCard(outro: Boolean): String? {
        if (clips.isEmpty()) return "আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন, তারপর ইন্ট্রো/আউট্রো দিন"
        tl.cardClip(outro)?.let {
            tl.selectedId = it.id
            tl.selectedLayerId = null
            return null
        }
        val spec = if (outro) CardDefaults.outro(logo) else CardDefaults.intro(caption, logo)
        val (w, h) = aspect.w to aspect.h
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { CardMaker.make(app, spec, w, h) }.getOrNull() }
            if (uri != null) tl.addCard(spec, uri)
        }
        return null
    }

    fun updateCard(id: Long, spec: CardSpec) {
        val (w, h) = aspect.w to aspect.h
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { CardMaker.make(app, spec, w, h) }.getOrNull() }
            if (uri != null) tl.setCard(id, spec, uri)
        }
    }

    fun chooseMusic(u: Uri) {
        music = u
        musicName = displayName(u)
    }

    /** সমস্যা থাকলে বার্তা ফেরত দেয়, ঠিক থাকলে রেন্ডার সার্ভিস চালু করে ও null দেয় */
    fun startRender(): String? {
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
        // কার্ড (ইন্ট্রো/আউট্রো) এখনকার ভিডিওর মাপে আবার বানানো (মাপ না বদলালে আগের ফাইলই মেলে)
        val renderClips = clips.map { c ->
            val s = c.card
            if (s != null && c.isImage) {
                runCatching { c.copy(uri = CardMaker.make(app, s, aspect.w, aspect.h)) }.getOrDefault(c)
            } else c
        }
        RenderBus.spec = RenderSpec(renderClips, aspect, caption, logo, music, musicVolume, keepAudio, tl.layers.toList())
        RenderBus.status.value = RenderStatus.Running(0)
        ContextCompat.startForegroundService(app, Intent(app, RenderService::class.java))
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
