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
import kotlinx.coroutines.CompletableDeferred
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
        videoTitle = ""   // নতুন ভিডিওর টাইটেল নতুন করে আগে থেকে বসবে (আগেরটার টাইটেল থাকবে না)
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
        // এই এডিট থেকে বানানো পুরাতন রেন্ডারও মোছা হয় (HTML-এর dRemove)
        if (!publish.busy && (currentDraftId == id || lastRender?.draftId == id || LastRender.load(app)?.draftId == id)) clearRender()
        drafts.removeAll { it.id == id }
        if (currentDraftId == id) { tl.resetAll(); currentDraftId = null }
        persist(sync = false)
        // এই ড্রাফটের জন্য কপি করা ফাইল আর কোথাও না লাগলে মুছে ফেলা
        val inUse = HashSet<String>()
        drafts.forEach { inUse.addAll(DraftStore.urisOf(it.clips, it.layers)) }
        inUse.addAll(DraftStore.urisOf(tl.clips, tl.layers))
        viewModelScope.launch(Dispatchers.IO) { DraftStore.deleteUnused(app, inUse) }
    }
    private var aspectState by mutableStateOf(Aspect.LANDSCAPE)
    /** ভিডিওর মাপ: পোস্ট (১২৮০×৭২০, ডিফল্ট) বা রিলস (৭২০×১২৮০). বদলালে ফ্রেমের অনুপাতও (FrameAspect) বদলায় */
    var aspect: Aspect
        get() = aspectState
        set(v) { aspectState = v; FrameAspect.set(v) }
    init { FrameAspect.set(aspectState) }

    /** পোস্ট/রিলস বদলানোর সময় কার্ড নতুন মাপে বানানোর বার্তা/শতাংশ; null = কিছু চলছে না (পপ-আপ দেখানোর জন্য) */
    var aspectMsg by mutableStateOf<String?>(null)
        private set
    var aspectPercent by mutableIntStateOf(0)
        private set
    /** কয়টা কার্ডের মধ্যে কোনটা চলছে ("১/২") */
    var aspectStep by mutableStateOf("")
        private set

    /**
     * পোস্ট/রিলস বদলানো: ইন্ট্রো/আউট্রো কার্ড নতুন মাপে আবার বানানো হয় (একটার পর একটা), সাথে প্রগ্রেস পপ-আপ।
     * একই টেমপ্লেট-লেখা-মাপে আগে বানানো কার্ড সেভ থাকলে নতুন করে বানানো হয় না, সেটাই বসে।
     */
    fun changeAspect(a: Aspect) {
        if (a == aspect || aspectMsg != null) return
        aspect = a
        val cards = tl.clips.filter { it.card != null && !it.isImage }.map { it.id to it.card!! }
        if (cards.isEmpty()) return
        val n = cards.size
        aspectPercent = 0
        aspectMsg = "⏳ কার্ড নতুন মাপে প্রস্তুত হচ্ছে…"
        viewModelScope.launch {
            try {
                for ((i, pair) in cards.withIndex()) {
                    val (id, spec) = pair
                    while (cardBusy) delay(200)
                    val s2 = spec.copy(w = a.w, h = a.h)
                    val saved = CardVideoMaker.isSaved(app, s2, a.w, a.h)
                    val sizeName = if (a == Aspect.PORTRAIT) "রিলস" else "পোস্ট"
                    aspectStep = "${i + 1}/$n"
                    aspectMsg = (if (spec.outro) "🏁 আউট্রো" else "🎬 ইন্ট্রো") + " — " + sizeName +
                        (if (saved) " মাপের সেভ করা কার্ড বসানো হচ্ছে…" else " মাপে নতুন বানানো হচ্ছে…")
                    aspectPercent = i * 100 / n
                    cardBusy = true; cardProgress = 0
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            CardVideoMaker.make(app, s2, a.w, a.h) {
                                cardProgress = (it * 100f).toInt().coerceIn(0, 100)
                                aspectPercent = ((i * 100 + cardProgress) / n).coerceIn(0, 100)
                            }
                        }
                    }
                    cardBusy = false
                    if (aspect != a) return@launch
                    r.onSuccess { (uri, dur) -> tl.setCard(id, s2, uri, dur) }
                }
                aspectPercent = 100
            } finally {
                cardBusy = false
                aspectMsg = null
                aspectStep = ""
            }
        }
    }
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
    var fbBg by mutableIntStateOf(0xFFFFFFFF.toInt())   // ব্যান্ডের ব্যাকগ্রাউন্ড

    // ---- রেন্ডারের পরের "২ ভিডিও" পেইজ: শেষ রেন্ডার মনে রাখা, টাইটেল আগে থেকে বসা, একটাই পাবলিশ বাটন ----
    /** এক বাটনে Facebook+YouTube+WordPress পাবলিশ (ViewModel-এর স্কোপে চলে) */
    val publish = VideoPublishState(app, viewModelScope)
    /** null না হলে "২ ভিডিও" পেইজ খোলা; অ্যাপ খুললে শেষ রেন্ডার থাকলে এটা নিজে থেকেই বসে */
    var resultOutputs by mutableStateOf<List<RenderOutput>?>(null)
    /** কোন কোন জায়গায় পাবলিশ হবে (রেন্ডারের টিক; পেইজের চিপ দিয়ে ✕ / + করা যায়, মনে থাকে) */
    var targets by mutableStateOf(PubTargets())
        private set

    /** চিপ: k = fb/yt/wp। YouTube কাটলে WordPress-ও কাটে; WordPress যোগ করলে YouTube নিজে যোগ হয় (HTML-এর মতো) */
    fun setTarget(k: String, add: Boolean, hasYt: Boolean) {
        if (publish.busy) return
        var t = targets
        t = when (k) {
            "yt" -> t.copy(yt = add, wp = if (add) t.wp else false)
            "wp" -> if (add && hasYt) t.copy(wp = true, yt = true) else t.copy(wp = false)
            else -> t.copy(fb = add)
        }
        targets = t
        viewModelScope.launch(Dispatchers.IO) { LastRender.updateTargets(app, t) }
    }
    /** মনে রাখা শেষ রেন্ডার (নতুন পোস্ট পেইজে "আগের রেন্ডার দেখুন" বাটনের জন্য) */
    var lastRender by mutableStateOf<LastRender.Rec?>(null)
        private set
    /** নতুন পোস্টের শিরোনাম (NewPostHost বসায়): ভিডিও টাইটেল আগে থেকে এটাই বসে */
    var titleSeed by mutableStateOf("")
    private var titleJob: kotlinx.coroutines.Job? = null

    /** ভিডিও টাইটেলের ডিফল্ট: নতুন পোস্টের শিরোনাম → ইন্ট্রোর লেখা → ইন্ট্রো কার্ডের বড় লেখা (ডিফল্ট "ব্রেকিং নিউজ" বাদে) */
    fun defaultTitle(): String {
        titleSeed.trim().takeIf { it.isNotEmpty() }?.let { return it }
        caption.trim().takeIf { it.isNotEmpty() }?.let { return it }
        return tl.clips.firstNotNullOfOrNull { c ->
            c.card?.takeIf { !it.outro }?.title?.trim()?.takeIf { it.isNotEmpty() && it != CardDefaults.INTRO_HEAD }
        }.orEmpty()
    }

    /** টাইটেল বদলালে একটু থেমে (০.৪ সেকেন্ড) মনে রাখা রেন্ডারের সাথে সেভ */
    fun persistTitleSoon() {
        titleJob?.cancel()
        titleJob = viewModelScope.launch {
            delay(400)
            val t = videoTitle
            withContext(Dispatchers.IO) { LastRender.updateTitle(app, t) }
        }
    }

    private fun applyRecord(r: LastRender.Rec) {
        lastRender = r
        if (r.title.isNotBlank()) videoTitle = r.title
        targets = r.tg
        publish.restore(r.pub)
        resultOutputs = r.outputs
    }

    /** রেন্ডার শেষে "২ ভিডিও" পেইজ খোলা (সার্ভিস আগেই ডিস্কে রেখেছে; না পেলে সরাসরি আউটপুট দিয়ে) */
    fun openResult(o: List<RenderOutput>) {
        val r = LastRender.load(app)
        if (r != null) applyRecord(r) else { publish.restore(PubState()); resultOutputs = o }
        if (videoTitle.isBlank()) videoTitle = defaultTitle()
    }

    /** অ্যাপ নতুন করে খোলার পর টাইমলাইন ফাঁকা থাকে: এই রেন্ডারের এডিট-ড্রাফট (না পেলে সবচেয়ে নতুনটা) খুলে দেয়। ফাঁকা না থাকলে কিছু করে না */
    fun loadEditForRender() {
        if (tl.clips.isNotEmpty()) return
        val id = lastRender?.draftId ?: 0L
        (drafts.firstOrNull { it.id == id } ?: drafts.firstOrNull())?.let { openDraft(it.id) }
    }

    /** নতুন পোস্ট পেইজ থেকে "আগের রেন্ডার দেখুন" */
    fun reopenLast() { (LastRender.load(app) ?: lastRender)?.let { applyRecord(it) } }

    /** লগআউট: মনে রাখা রেন্ডার মোছা */
    fun clearRender() {
        LastRender.clear(app)
        resultOutputs = null; lastRender = null; videoTitle = ""; targets = PubTargets()
        publish.restore(PubState())
    }

    init {
        // অ্যাপ বন্ধ করে খুললে আগের রেন্ডার (ভিডিও + টাইটেল + কোথায় পাবলিশ হয়ে গেছে) ফিরে আসে
        LastRender.load(app)?.let { applyRecord(it) }
    }

    /** চলমান কাজের Uri (ড্রাফট + এখনকার টাইমলাইন) — ক্যাশ ক্লিয়ারে এগুলো বাঁচিয়ে রাখা হয় */
    fun uriInUse(): Set<String> {
        val inUse = HashSet<String>()
        drafts.forEach { inUse.addAll(DraftStore.urisOf(it.clips, it.layers)) }
        inUse.addAll(DraftStore.urisOf(tl.clips, tl.layers))
        return inUse
    }

    /** ইন্ট্রোর লেখা ও টাইটেল/ব্যানার/লোগো ডিফল্টে (ওয়েবের vcResetVideoData)। অসমাপ্ত এডিট (ক্লিপ) থাকলে তার লোগো/টাইটেল ছোঁয়া হয় না */
    fun resetVideoDefaults() {
        caption = ""; fbTop = ""; fbBottom = ""; fbColor = 0xFF000000.toInt(); fbBg = 0xFFFFFFFF.toInt()
        if (clips.isEmpty()) { videoTitle = ""; logo = null; music = null; musicName = ""; musicVolume = 0.3f; keepAudio = true }
    }
    private fun displayName(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: "ফাইল"

    /** front/back = null: সাধারণ যোগ (শেষে); true = টাইমলাইনের একদম সামনে, false = একদম পিছনে ("➕ সামনে/পিছনে ভিডিও") */
    fun addUris(uris: List<Uri>, front: Boolean? = null, seed: Boolean = false) {
        viewModelScope.launch {
            // ডিফল্ট বসানো শেষ না হওয়া পর্যন্ত এডিটরে "প্রসেসিং" বক্স দেখায় (ক্লিপ যোগ হওয়ার আগেই চালু)
            if (seed) { seedStep = 0; seedMsg = "⏳ ভিডিও প্রস্তুত হচ্ছে…" }
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
            if (seed && added.isNotEmpty()) {
                // নিচের বিজ্ঞাপন/ইন্ট্রো-আউট্রো বসার আগে পপ-আপ: কোন টেমপ্লেট, কী লেখা
                seedMsg = null
                val choice = askSeedChoice()
                seedStep = 0
                seedMsg = "⏳ ইন্ট্রো-আউট্রো, বিজ্ঞাপন, লোগো, ট্যাগ বসানো হচ্ছে…"
                seedDefaults(added.first().id, choice)
            } else if (seed) seedMsg = null
        }
    }

    /** নতুন ভিডিওর পর "ইন্ট্রো-আউট্রো বাছুন" পপ-আপের মান (ইন্ট্রো ও আউট্রোর টেমপ্লেট + ইন্ট্রোর লেখা) */
    data class SeedChoice(val intro: CardSpec, val outro: CardSpec)
    /** null না হলে পপ-আপ দেখায়; ব্যবহারকারী "ঠিক আছে" চাপলে answerSeedChoice ডাকে */
    var seedAsk by mutableStateOf<SeedChoice?>(null)
        private set
    private var seedAnswer: CompletableDeferred<SeedChoice>? = null

    private fun seedPrefs() = app.getSharedPreferences("seed_card_choice", android.content.Context.MODE_PRIVATE)

    /** আগেরবার বাছা টেমপ্লেট/লেখা দিয়ে পপ-আপ খুলে উত্তরের জন্য অপেক্ষা করে */
    private suspend fun askSeedChoice(): SeedChoice {
        val p = seedPrefs()
        fun styleOf(k: String) = runCatching { CardStyle.valueOf(p.getString(k, "") ?: "") }.getOrDefault(CardStyle.GLOBAL)
        val logoU = SettingsMedia.logos(app).firstOrNull()?.let { SettingsMedia.logoUri(app, it) }
        val cardLogo = logo ?: logoU
        val title = caption.trim().ifEmpty { p.getString("intro_title", null) ?: CardDefaults.INTRO_HEAD }
        val intro = CardDefaults.intro(title, cardLogo).copy(
            style = styleOf("intro_style"),
            subtitle = p.getString("intro_sub", null) ?: CardDefaults.INTRO_SUB,
        )
        val outro = CardDefaults.outro(cardLogo).copy(style = styleOf("outro_style"))
        val d = CompletableDeferred<SeedChoice>()
        seedAnswer = d
        seedAsk = SeedChoice(intro, outro)
        return d.await()
    }

    /** পপ-আপে "ঠিক আছে": বাছাই মনে রাখে (পরেরবার এটাই আগে থেকে বাছা থাকবে) ও বসানো চালু করে */
    fun answerSeedChoice(c: SeedChoice) {
        seedPrefs().edit()
            .putString("intro_style", c.intro.style.name)
            .putString("intro_title", c.intro.title)
            .putString("intro_sub", c.intro.subtitle)
            .putString("outro_style", c.outro.style.name)
            .apply()
        seedAsk = null
        seedAnswer?.complete(c)
        seedAnswer = null
    }

    /** ডিফল্ট বসানোর পর কোনো কিছু না বসলে তার বার্তা (এডিটর একবার দেখিয়ে মুছে দেয়) */
    var seedWarn by mutableStateOf<String?>(null)
    /** নতুন ভিডিওর ডিফল্ট বসানোর অবস্থা (এডিটরে দেখানোর বার্তা); null = কিছু চলছে না */
    var seedMsg by mutableStateOf<String?>(null)
        private set
    /** ডিফল্ট বসানোর ধাপ: ০ = ইন্ট্রো, ১ = আউট্রো, ২ = বিজ্ঞাপন/লোগো/ট্যাগ/স্ক্রলিং লেখা, ৩ = শেষ */
    var seedStep by mutableIntStateOf(0)
        private set
    /** প্রসেসিং বারের মোট শতাংশ (০..১০০): ইন্ট্রো ০–৪৫, আউট্রো ৪৫–৯০, বাকি ৯০–১০০ */
    val seedPercent: Int
        get() {
            val c = if (cardBusy) cardProgress.coerceIn(0, 100) else 0
            return when (seedStep) {
                0 -> c * 45 / 100
                1 -> 45 + c * 45 / 100
                2 -> 92
                else -> 100
            }
        }

    /**
     * "নতুন পোস্ট → ভিডিও বাছুন" (HTML-এর vcSeed): ইন্ট্রো + আউট্রো, নিচের একটা বিজ্ঞাপন, লোগো, ট্যাগ ও স্ক্রলিং লেখা নিজে বসে।
     * সবই সাধারণ ক্লিপ/লেয়ার, তাই পরে একটা একটা করে মোছা/এডিট করা যায়। মাঝে ভিডিও বদলে/মুছে গেলে থেমে যায়।
     */
    private suspend fun seedDefaults(firstClipId: Long, choice: SeedChoice) {
        fun alive() = tl.clips.any { it.id == firstClipId }
        try {
            seedMsg = "⏳ ডিফল্ট ইন্ট্রো-আউট্রো, বিজ্ঞাপন, লোগো, ট্যাগ বসানো হচ্ছে…"
            val logoOpt = SettingsMedia.logos(app).firstOrNull()
            val logoU = logoOpt?.let { SettingsMedia.logoUri(app, it) }
            val cardLogo = logo ?: logoU
            // ইন্ট্রো-আউট্রো আগে: তারপর লেয়ারগুলো পুরো দৈর্ঘ্য জুড়ে বসে (ইন্ট্রো-আউট্রোর অংশে লেয়ার দেখায় না)
            if (alive()) { seedStep = 0; seedMsg = "🎬 ইন্ট্রো বসানো হচ্ছে…"; makeCardNow(false, cardLogo, choice.intro) }
            if (alive()) { seedStep = 1; seedMsg = "🏁 আউট্রো বসানো হচ্ছে…"; makeCardNow(true, cardLogo, choice.outro) }
            if (!alive()) return
            seedStep = 2
            seedMsg = "⏳ বিজ্ঞাপন, লোগো, ট্যাগ, নাম/পদবি, স্ক্রলিং লেখা বসানো হচ্ছে…"
            // প্রতিটা জিনিস আলাদা ধাপে: একটা ব্যর্থ হলেও বাকিগুলো বসে, আর কী বসেনি তা এডিটরে জানানো হয়
            val missed = ArrayList<String>()
            suspend fun step(name: String, body: suspend () -> String?) {
                if (!alive()) return
                val err = try { body() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { e.message ?: "অজানা সমস্যা" }
                if (err != null) missed += name
            }
            // লোগো (ওপরে ডানে): সেটিংসে লোগো/আইকন/সাইটের নাম কিছুই না থাকলে বসানো যায় না
            step("লোগো") {
                if (logoU == null) "সেটিংসে লোগো নেই"
                else if (tl.layers.any { it.isLogoLayer() }) null
                else tl.addLayer(LayerKind.LOGO, uri = logoU)
            }
            // নিচের বিজ্ঞাপন: সেটিংসের প্রথমটা; আঁকতে না পারলে বা না থাকলে "বিজ্ঞাপন এরিয়া" ডিফল্ট ব্যানার
            step("নিচের ব্যানার") {
                if (tl.layers.any { it is AdLayer }) return@step null
                val ad = SettingsMedia.ads(app).firstOrNull()
                val adUri = ad?.let { runCatching { SettingsMedia.adUri(app, it) }.getOrNull() }
                if (ad != null && adUri != null) tl.addLayer(LayerKind.AD_BOTTOM, uri = adUri, adId = ad.id)
                else tl.addLayer(LayerKind.AD_BOTTOM, uri = SettingsMedia.placeholderAdUri(app))
            }
            // ট্যাগ (পুরো ভিডিও জুড়ে)
            step("ট্যাগ") {
                if (tl.layers.any { it is TagLayer }) return@step null
                val e = tl.addLayer(LayerKind.TAG, text = SEED_TAG)
                tl.selectedLayerId?.let { id -> tl.updateLayer(id) { it.withTime(0L, tl.totalMs) } }
                e
            }
            // স্ক্রলিং লেখা
            step("স্ক্রলিং লেখা") {
                if (tl.layers.any { it is TickerLayer }) null else tl.addLayer(LayerKind.TICKER, text = SEED_TICKER)
            }
            // নাম/পদবির বার (HTML-এর মতো): মূল ভিডিও শুরুর (ইন্ট্রোর পরের) ১০ সেকেন্ড পর আসে, ৫ সেকেন্ড থাকে।
            // লেখা = সেটিংসের সাইটের নাম + ট্যাগলাইন (পদবির জায়গায়); পরে লেয়ার এডিটে লেখা/সময় বদলানো বা মোছা যায়
            step("নাম/পদবি") {
                if (tl.layers.any { it is NameBarLayer }) return@step null
                val cfg = withContext(Dispatchers.IO) { Fs.cardCfg(app) }
                val siteName = cfg.siteName.trim()
                val tagline = cfg.siteTagline.trim()
                if (siteName.isEmpty() && tagline.isEmpty()) return@step "সেটিংসে সাইটের নাম/ট্যাগলাইন নেই"
                val e = tl.addLayer(LayerKind.NAME_BAR, text = siteName.ifEmpty { tagline })
                tl.selectedLayerId?.let { id ->
                    val introEnd = tl.cardClip(false)?.lengthMs() ?: 0L
                    val mainEnd = tl.totalMs - (tl.cardClip(true)?.lengthMs() ?: 0L)   // আউট্রোর আগ পর্যন্ত
                    var st = introEnd + 10_000L
                    var du = 5_000L
                    if (st > mainEnd - 1_000L) { st = maxOf(introEnd, mainEnd - du) }   // ভিডিও খুব ছোট হলে শেষের দিকে
                    du = minOf(du, maxOf(1_000L, mainEnd - st))
                    tl.updateLayer(id) { l ->
                        (l as? NameBarLayer)?.copy(name = siteName.ifEmpty { tagline }, title = tagline)?.withTime(st, st + du) ?: l
                    }
                }
                e
            }
            if (missed.isNotEmpty()) seedWarn = "⚠️ এগুলো নিজে বসেনি: " + missed.joinToString(", ") + "। লেয়ার মেনু থেকে নিজে যোগ করুন (সেটিংসে তথ্য/নেট ঠিক আছে কিনা দেখুন)।"
            tl.selectedId = null
            tl.selectedLayerId = null
            tl.playheadMs = 0L
        } catch (_: Throwable) {
            // ডিফল্ট বসানো ব্যর্থ হলেও ভিডিও যেমন ছিল তেমনই থাকে; দরকারি জিনিস নিজে যোগ করা যাবে
        } finally {
            seedStep = 3
            seedMsg = null
        }
    }

    /** একটা কার্ড (ইন্ট্রো/আউট্রো) বানিয়ে টাইমলাইনে বসায়; চলমান আরেকটা কার্ড বানানো শেষ হওয়া পর্যন্ত অপেক্ষা করে */
    private suspend fun makeCardNow(outro: Boolean, cardLogo: Uri?, base: CardSpec? = null): Boolean {
        if (tl.cardClip(outro) != null) return true
        while (cardBusy) delay(200)
        val (w, h) = aspect.w to aspect.h
        val spec = (base ?: (if (outro) CardDefaults.outro(cardLogo) else CardDefaults.intro(caption, cardLogo))).copy(w = w, h = h)
        cardBusy = true
        cardProgress = 0
        val r = withContext(Dispatchers.IO) {
            runCatching { CardVideoMaker.make(app, spec, w, h) { cardProgress = (it * 100f).toInt().coerceIn(0, 100) } }
        }
        cardBusy = false
        return r.fold({ (uri, dur) -> tl.addCard(spec, uri, dur) == null }, { false })
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
    fun addCard(outro: Boolean, onResult: (String?) -> Unit = {}, base: CardSpec? = null): String? {
        if (clips.isEmpty()) return "আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন, তারপর ইন্ট্রো/আউট্রো দিন"
        tl.cardClip(outro)?.let {
            tl.selectedId = it.id
            tl.selectedLayerId = null
            return null
        }
        if (cardBusy) return "একটু অপেক্ষা করুন, আগের কার্ড বানানো চলছে"
        val (w, h) = aspect.w to aspect.h
        val spec = (base ?: (if (outro) CardDefaults.outro(logo) else CardDefaults.intro(caption, logo))).copy(w = w, h = h)
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
    fun startRender(fb: Boolean = false, yt: Boolean = false, wp: Boolean = false, merge: Boolean = false): String? {
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
        FrameAspect.set(asp)
        // ভিডিও টাইটেল আগে থেকে বসে (পরে পেইজে বদলানো যায়); রেন্ডার শেষে সার্ভিস এগুলো দিয়ে "শেষ রেন্ডার" মনে রাখে
        if (videoTitle.isBlank()) videoTitle = defaultTitle()
        saveDraft()   // রেন্ডার কোন এডিট ড্রাফট থেকে, সেটা মনে রাখতে (ড্রাফট মুছলে এই রেন্ডারও মোছা হয়)
        RenderBus.summary = run {
            val names = tl.layers.filter { !it.isAudio }.joinToString(", ") { it.kind.label }.ifEmpty { "একটাও না" }
            "রেন্ডারে পাঠানো লেয়ার: $names\nফেইসবুক ব্যান্ড: " + (if (band > 0) "${band}px" else "নেই") +
                (if (top.isNotEmpty()) "\nওপরের লেখা: $top" else "") + (if (bottom.isNotEmpty()) "\nনিচের লেখা: $bottom" else "") +
                "\nইন্ট্রো: " + (if (tl.cardClip(false) != null) "আছে" else "নেই") + ", আউট্রো: " + (if (tl.cardClip(true) != null) "আছে" else "নেই")
        }
        RenderBus.title = videoTitle; RenderBus.wp = wp; RenderBus.merge = merge; RenderBus.draftId = currentDraftId ?: 0L
        val snapClips = clips.toList()
        val snapLayers = tl.layers.toList()
        val cap = caption
        val lg = logo
        val mu = music
        val mv = musicVolume
        val ka = keepAudio
        val fc = fbColor
        val fbg = fbBg
        // পাসের তালিকা: ফেইসবুক আগে, ইউটিউব পরে (HTML-এর মতো)
        class Plan(val mode: String, val band: Int, val layers: List<Layer>, val fbTop: String, val fbBottom: String)
        val plans = ArrayList<Plan>()
        if (fb) plans += Plan("fb", band, snapLayers, top, bottom)
        if (yt) plans += Plan("yt", 0, snapLayers.filterNot { it is AdLayer && it.isTop }, "", "")   // ইউটিউবে ওপরের ব্যানার বসে না
        RenderBus.status.value = RenderStatus.Running(0, 1, plans.size, if (plans.first().mode == "fb") "ফেইসবুক" else "ইউটিউব")
        // রেন্ডার চাপার সঙ্গে সঙ্গেই নোটিফিকেশনে ০% (কার্ড মাপ মেলানোর সময়টাতেও); সার্ভিস চালু হলে এটাই আপডেট হয়
        Notif.progress(app, RenderService.NOTIF_ID, "নিউজ পাবলিশার", "রেন্ডার হচ্ছে — 0%", 0)
        renderJob = viewModelScope.launch {
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
                            fbTop = p.fbTop, fbBottom = p.fbBottom, fbColor = fc, fbBg = fbg, fbBandPx = p.band, fbAdsInBand = p.band > 0,
                        ),
                    )
                }
            }
            RenderBus.passes = built
            try {
                ContextCompat.startForegroundService(app, Intent(app, RenderService::class.java))
            } catch (e: Exception) {
                Notif.cancel(app, RenderService.NOTIF_ID)
                RenderBus.status.value = RenderStatus.Failed(e.message ?: "রেন্ডার শুরু করা যায়নি")
            }
        }
        return null
    }

    private var renderJob: kotlinx.coroutines.Job? = null

    fun cancelRender() {
        // সার্ভিস চালুর আগের প্রস্তুতি (কার্ড মাপ মেলানো) চলতে থাকলে সেটাই থামে, সার্ভিস আর চালু হয় না
        val j = renderJob
        if (j != null && j.isActive) {
            j.cancel()
            renderJob = null
            RenderBus.status.value = RenderStatus.Idle
            Notif.cancel(app, RenderService.NOTIF_ID)
            return
        }
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

    /** Download ফোল্ডার খোলে; না পারলে বার্তা */
    fun openDownloads() {
        if (!Updater.openDownloads(app)) _state.update { it.copy(message = "Download ফোল্ডার খোলা যায়নি — ফাইল ম্যানেজারে Download ফোল্ডারে দেখুন") }
    }

    /**
     * অ্যাপের ভেতর থেকে ডাউনলোড শুরু (সিস্টেমের DownloadManager দিয়ে), প্রগ্রেস বার সহ।
     * APK যায় ফোনের সাধারণ Download ফোল্ডারে। শেষ হলে ফোল্ডার খুলে APK-তে ট্যাপ করে ইন্সটল করতে হয়
     * (অ্যাপ নিজে ইন্সটলার খোলে না, তাই কোনো ইন্সটল-পারমিশন লাগে না)।
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
                    .setDescription("আপডেট — Download ফোল্ডারে সেভ হচ্ছে")
                    .setMimeType("application/vnd.android.package-archive")
                    // শেষের নোটিফিকেশন আমাদের নিজের (UpdateDoneReceiver): ট্যাপ করলে Download ফোল্ডার খোলে
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationUri(
                        Uri.fromFile(
                            java.io.File(
                                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                                Updater.apkFileName(info.versionCode),
                            ),
                        ),
                    )
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
                        message = "✅ ডাউনলোড শেষ। Download ফোল্ডারে সেভ হয়েছে",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, progress = null, message = "❌ ${e.message}") }
            }
        }
    }

    private companion object { var autoChecked = false }
}

/** নতুন ভিডিওতে নিজে বসা ট্যাগ ও স্ক্রলিং লেখা (HTML VC_DEF_TAG / VC_DEF_TICKER) */
private const val SEED_TAG = "সংগৃহীত"
private const val SEED_TICKER = "আপনার ব্যবসার/পণ্যের বিজ্ঞাপন দিতে যোগাযোগ করুন:"
