package com.digitalcrafting25.newsapp

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** ক্লিপের শেষ সময় (সেকেন্ড, সোর্সের ভেতরে)। endSec=০ মানে ভিডিওর শেষ পর্যন্ত */
fun Clip.effEndSec(): Float = when {
    isImage -> imageSec
    endSec > startSec -> endSec
    else -> durationMs / 1000f
}

/** টাইমলাইনে এই ক্লিপ কত মিলিসেকেন্ড জায়গা নেয় */
fun Clip.lengthMs(): Long =
    if (isImage) (imageSec * 1000f).toLong().coerceAtLeast(0L)
    else ((effEndSec() - startSec) * 1000f).toLong().coerceAtLeast(0L)

fun fmtTime(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    val tenth = (t % 1000) / 100
    return "%d:%02d.%d".format(t / 60000, (t / 1000) % 60, tenth)
}

/**
 * মাল্টি-ক্লিপ টাইমলাইন। ক্লিপের তালিকা অপরিবর্তনীয় (immutable) স্ন্যাপশট হিসেবে রাখা হয়,
 * তাই প্রতিটি সম্পাদনার আগের অবস্থা স্ট্যাকে জমিয়ে আনডু/রিডু করা যায়।
 */
class Timeline {
    var clips by mutableStateOf<List<Clip>>(emptyList())
        private set
    /** ভিডিওর ওপরের লেয়ার (ভিজ্যুয়াল + অডিও); লিস্টের ক্রমই z-order */
    var layers by mutableStateOf<List<Layer>>(emptyList())
        private set
    var selectedId by mutableStateOf<Long?>(null)
    var selectedLayerId by mutableStateOf<Long?>(null)
    var playheadMs by mutableLongStateOf(0L)
    /** টাইমলাইনের জুম (শুধু দেখার মাপ; আনডু/রেন্ডারে প্রভাব নেই) */
    var timelineZoom by mutableFloatStateOf(1f)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** আনডু/রিডুর জন্য ক্লিপ + লেয়ারের একসাথে স্ন্যাপশট */
    private data class Snap(val clips: List<Clip>, val layers: List<Layer>)

    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()
    private var nextId = 1L
    private var lastKey: String? = null
    private var lastKeyAt = 0L

    companion object {
        const val MAX_HISTORY = 60
        const val MIN_PIECE_MS = 300L
        const val MIN_LAYER_MS = LayerDefaults.MIN_LAYER_MS
        private const val MERGE_WINDOW_MS = 1000L
        const val ZOOM_MIN = 1f
        const val ZOOM_MAX = 4f
        const val TL_ZOOM_MIN = 0.1f
        const val TL_ZOOM_MAX = 6f
    }

    fun newId(): Long = nextId++

    /** ফোনে সেভ করা ড্রাফট ফেরানোর পর নতুন আইডি যেন পুরোনোর সাথে না মেলে */
    fun ensureIdAbove(v: Long) { if (nextId <= v) nextId = v + 1 }

    /** সব খালি করে নতুন এডিট (আইডির গণনা চলতেই থাকে, তাই ড্রাফটের আইডির সাথে মিলে যাবে না) */
    fun resetAll() {
        undoStack.clear(); redoStack.clear(); lastKey = null
        clips = emptyList(); layers = emptyList()
        selectedId = null; selectedLayerId = null
        playheadMs = 0L; timelineZoom = 1f
        refresh()
    }

    /** সেভ করা ড্রাফট ফেরানো (আনডু ইতিহাস খালি, HTML-এর মতো) */
    fun loadSnapshot(c: List<Clip>, l: List<Layer>) {
        undoStack.clear(); redoStack.clear(); lastKey = null
        clips = c; layers = l
        selectedId = null; selectedLayerId = null
        playheadMs = 0L; timelineZoom = 1f
        refresh()
    }

    val totalMs: Long get() = clips.sumOf { it.lengthMs() }

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        val sel = selectedId
        if (sel != null && clips.none { it.id == sel }) selectedId = null
        val lsel = selectedLayerId
        if (lsel != null && layers.none { it.id == lsel }) selectedLayerId = null
        playheadMs = playheadMs.coerceIn(0L, totalMs)
    }

    /**
     * নতুন অবস্থা বসায় ও আগের অবস্থা আনডু স্ট্যাকে রাখে।
     * mergeKey একই থাকলে ১ সেকেন্ডের ভেতরের পরপর বদল একটাই আনডু ধাপ হয় (স্লাইডার টানার সময়)।
     */
    private fun commitAll(nextClips: List<Clip>, nextLayers: List<Layer>, mergeKey: String? = null) {
        val now = System.currentTimeMillis()
        val merge = mergeKey != null && mergeKey == lastKey && now - lastKeyAt < MERGE_WINDOW_MS && undoStack.isNotEmpty()
        if (!merge) {
            undoStack.addLast(Snap(clips, layers))
            while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        }
        redoStack.clear()
        lastKey = mergeKey
        lastKeyAt = now
        clips = nextClips
        layers = nextLayers
        refresh()
    }

    private fun commit(next: List<Clip>) = commitAll(next, layers)

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        // কোনো আনডুই সব ক্লিপ খালি করে এডিটর থেকে বের করবে না
        if (prev.clips.isEmpty()) { refresh(); return }
        redoStack.addLast(Snap(clips, layers))
        clips = prev.clips
        layers = prev.layers
        lastKey = null
        refresh()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snap(clips, layers))
        clips = next.clips
        layers = next.layers
        lastKey = null
        refresh()
    }

    fun clipById(id: Long?): Clip? = if (id == null) null else clips.firstOrNull { it.id == id }

    /** কোন ক্লিপে প্লেহেড আছে এবং সোর্সের ভেতরে কত মিলিসেকেন্ডে */
    fun locate(posMs: Long): Pair<Clip, Long>? {
        var acc = 0L
        for (c in clips) {
            val len = c.lengthMs()
            if (posMs < acc + len) {
                val off = (posMs - acc).coerceAtLeast(0L)
                return c to (if (c.isImage) off else (c.startSec * 1000f).toLong() + off)
            }
            acc += len
        }
        val last = clips.lastOrNull() ?: return null
        return last to (if (last.isImage) 0L else ((last.effEndSec() * 1000f).toLong() - 1L).coerceAtLeast(0L))
    }

    /** ক্লিপটি টাইমলাইনে কোন মিলিসেকেন্ড থেকে শুরু */
    fun startOf(id: Long): Long {
        var acc = 0L
        for (c in clips) {
            if (c.id == id) return acc
            acc += c.lengthMs()
        }
        return 0L
    }

    fun addAll(list: List<Clip>) {
        if (list.isEmpty()) return
        if (clips.isEmpty()) {
            // প্রথম ভিডিও বসানো আনডুর ধাপ নয় (HTML-এর মতো ইতিহাস খালি): আনডু করে এডিটর খালি হয়ে ব্যাক হবে না
            undoStack.clear(); redoStack.clear(); lastKey = null
            clips = list
            selectedId = list.last().id
            refresh()
            return
        }
        commit(clips + list)
        selectedId = list.last().id
    }

    fun remove(id: Long) {
        val c = clips.firstOrNull { it.id == id } ?: return
        val next = clips.filterNot { it.id == id }
        // ইন্ট্রো কার্ড মুছলে বাকি সব লেয়ার/অডিও তার দৈর্ঘ্যের সমান এগিয়ে আসে
        if (c.card != null && !c.card.outro) commitAll(next, shiftLayers(layers, -c.lengthMs()))
        else commit(next)
    }

    fun move(id: Long, delta: Int) {
        val i = clips.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in clips.indices) return
        val m = clips.toMutableList()
        val c = m.removeAt(i)
        m.add(j, c)
        commit(m)
    }

    fun duplicate(id: Long) {
        val i = clips.indexOfFirst { it.id == id }
        if (i < 0 || clips[i].card != null) return
        val copy = clips[i].copy(id = newId())
        val m = clips.toMutableList()
        m.add(i + 1, copy)
        commit(m)
        selectedId = copy.id
    }

    /** ভিডিও ক্লিপের ট্রিম (সেকেন্ড) */
    fun trim(id: Long, startSec: Float, endSec: Float) {
        val i = clips.indexOfFirst { it.id == id }
        if (i < 0) return
        val c = clips[i]
        if (c.isImage || c.durationMs <= 0L || c.card != null) return   // ইন্ট্রো/আউট্রো কার্ডের দৈর্ঘ্য ফিক্সড
        val dur = c.durationMs / 1000f
        val minLen = MIN_PIECE_MS / 1000f
        val s = startSec.coerceIn(0f, (dur - minLen).coerceAtLeast(0f))
        val e = endSec.coerceIn(s + minLen, dur)
        if (s == c.startSec && e == c.effEndSec()) return
        val m = clips.toMutableList()
        m[i] = c.copy(startSec = s, endSec = e)
        commit(m)
    }

    fun setImageSec(id: Long, sec: Float) {
        val i = clips.indexOfFirst { it.id == id }
        if (i < 0) return
        val c = clips[i]
        if (!c.isImage) return
        val v = sec.coerceIn(0.5f, 600f)
        if (v == c.imageSec) return
        val m = clips.toMutableList()
        m[i] = c.copy(imageSec = v)
        // ইন্ট্রো কার্ডের সময় বদলালে ওপরের সব লেয়ার/অডিও সেই পরিমাণ সরে যায়, যাতে ভিডিওর সাথে মিলে থাকে
        if (c.card != null && !c.card.outro) {
            commitAll(m, shiftLayers(layers, Math.round((v - c.imageSec) * 1000f).toLong()))
        } else commit(m)
    }

    /**
     * প্লেহেডের জায়গায় ক্লিপ দুই টুকরো করে। সমস্যা থাকলে বার্তা ফেরত দেয়, ঠিক থাকলে null।
     */
    fun split(): String? {
        val pos = playheadMs
        var acc = 0L
        for ((i, c) in clips.withIndex()) {
            val len = c.lengthMs()
            if (pos >= acc + len) {
                acc += len
                continue
            }
            if (c.card != null) return "ইন্ট্রো/আউট্রো কার্ড কাটা যায় না"
            val off = pos - acc
            if (off < MIN_PIECE_MS || len - off < MIN_PIECE_MS) {
                return "কাটার জায়গা ক্লিপের একদম শুরু বা শেষে। প্লেহেড আরেকটু ভেতরে নিন"
            }
            if (!c.isImage && c.durationMs <= 0L) return "এই ভিডিওর দৈর্ঘ্য জানা যায়নি, তাই কাটা যাবে না"
            val a: Clip
            val b: Clip
            if (c.isImage) {
                a = c.copy(id = newId(), imageSec = off / 1000f)
                b = c.copy(id = newId(), imageSec = (len - off) / 1000f)
            } else {
                val cut = c.startSec + off / 1000f
                val end = c.effEndSec()
                a = c.copy(id = newId(), endSec = cut)
                b = c.copy(id = newId(), startSec = cut, endSec = end)
            }
            val m = clips.toMutableList()
            m[i] = a
            m.add(i + 1, b)
            commit(m)
            selectedId = b.id
            return null
        }
        return "প্লেহেড কোনো ক্লিপের ওপর নেই। স্লাইডার টেনে ক্লিপের ভেতরে আনুন"
    }

    // ================= রেডি ইন্ট্রো / আউট্রো (ধাপ ৭) =================

    /** সব লেয়ারের সময় deltaMs সরায় (ইন্ট্রো যোগ/মোছা/সময় বদলে)। শুরু ০-র নিচে নামে না, কমপক্ষে ০.৩ সে. থাকে */
    private fun shiftLayers(ls: List<Layer>, deltaMs: Long): List<Layer> {
        if (deltaMs == 0L || ls.isEmpty()) return ls
        return ls.map { l ->
            val s = (l.startMs + deltaMs).coerceAtLeast(0L)
            val e = maxOf(l.endMs + deltaMs, s + MIN_LAYER_MS)
            l.withTime(s, e)
        }
    }

    fun cardClip(outro: Boolean): Clip? = clips.firstOrNull { it.card?.outro == outro }

    /** ইন্ট্রো হলে সবার আগে, আউট্রো হলে সবার শেষে কার্ড-ক্লিপ বসায়; ইন্ট্রোর দৈর্ঘ্যে বাকি লেয়ার এগিয়ে যায় */
    fun addCard(spec: CardSpec, uri: Uri, durationMs: Long): String? {
        if (cardClip(spec.outro) != null) return "এই ${if (spec.outro) "আউট্রো" else "ইন্ট্রো"} আগে থেকেই আছে"
        // কার্ড এখন সাউন্ডসহ ভিডিও-ক্লিপ (isImage = false); দৈর্ঘ্য ভিডিও ফাইলের নিজের দৈর্ঘ্য
        val clip = Clip(
            id = newId(), uri = uri, isImage = false, durationMs = durationMs,
            name = if (spec.outro) "🏁 আউট্রো" else "🎬 ইন্ট্রো",
            card = spec,
        )
        val m = clips.toMutableList()
        if (spec.outro) {
            m.add(clip)
            commitAll(m, layers)
        } else {
            m.add(0, clip)
            commitAll(m, shiftLayers(layers, clip.lengthMs()))
        }
        selectedId = clip.id
        selectedLayerId = null
        return null
    }

    /** কার্ডের লেখা/স্টাইল বদলের পর নতুন ছবির Uri বসায় (এক আনডু ধাপ) */
    fun setCard(id: Long, spec: CardSpec, uri: Uri, durationMs: Long) {
        updateClipTx(id, null) { it.copy(card = spec, uri = uri, durationMs = durationMs, startSec = 0f, endSec = 0f) }
    }

    /**
     * "➕ সামনে ভিডিও" / "➕ পিছনে ভিডিও": ইন্ট্রো/আউট্রো বসানোর পরও টাইমলাইনের একদম শুরুতে (ইন্ট্রোর আগে)
     * বা একদম শেষে (আউট্রোর পরে) আরও ভিডিও/ছবি যোগ। সামনে বসলে সব লেয়ার/অডিও নতুন অংশের দৈর্ঘ্যের সমান এগিয়ে যায়।
     */
    fun addAtEdge(list: List<Clip>, front: Boolean) {
        if (list.isEmpty()) return
        if (clips.isEmpty()) { addAll(list); return }
        if (front) {
            commitAll(list + clips, shiftLayers(layers, list.sumOf { it.lengthMs() }))
        } else {
            commit(clips + list)
        }
        selectedId = list.last().id
        selectedLayerId = null
    }

    // ================= ক্লিপ টুল (ধাপ ৬) =================

    private fun updateClipTx(id: Long, key: String?, f: (Clip) -> Clip) {
        val i = clips.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = clips[i]
        val upd = f(old)
        if (upd == old) return
        val m = clips.toMutableList()
        m[i] = upd
        commitAll(m, layers, key)
    }

    /** ডান-বাম (horizontal=true) বা ওপর-নিচ ফ্লিপ চালু/বন্ধ। নতুন অবস্থা ফেরত দেয় */
    fun toggleFlip(id: Long, horizontal: Boolean): Boolean? {
        val c = clipById(id) ?: return null
        val now = if (horizontal) !c.flipH else !c.flipV
        updateClipTx(id, null) { if (horizontal) it.copy(flipH = now) else it.copy(flipV = now) }
        return now
    }

    /** জুম (১..৪) ও অবস্থান (-১..১)। স্লাইডারের পরপর বদল এক আনডু ধাপে মেশে */
    fun setClipZoomPan(id: Long, zoom: Float? = null, panX: Float? = null, panY: Float? = null) {
        updateClipTx(id, "cliptx:$id") {
            it.copy(
                zoom = (zoom ?: it.zoom).coerceIn(ZOOM_MIN, ZOOM_MAX),
                panX = (panX ?: it.panX).coerceIn(-1f, 1f),
                panY = (panY ?: it.panY).coerceIn(-1f, 1f),
            )
        }
    }

    /** জুম/অবস্থান/ফ্লিপ সব আগের অবস্থায় ফেরায় */
    fun resetClipTransform(id: Long) {
        updateClipTx(id, null) { it.copy(flipH = false, flipV = false, zoom = 1f, panX = 0f, panY = 0f) }
    }

    /**
     * ০.১ সেকেন্ডের নাজ: ভিডিওর ট্রিম-শুরু/শেষ, ছবির সময়। deltaSec ধনাত্মক = বাড়ায়।
     * which: 0 = শুরু (ভিডিও), 1 = শেষ (ভিডিও) / সময় (ছবি)।
     * সীমায় আটকে গেলে বার্তা ফেরত দেয়, ঠিক থাকলে null।
     */
    fun nudgeClip(id: Long, which: Int, deltaSec: Float): String? {
        val c = clipById(id) ?: return "ক্লিপ পাওয়া যায়নি"
        fun tenth(v: Float) = Math.round(v * 10f) / 10f
        if (c.isImage) {
            val v = tenth(c.imageSec + deltaSec)
            if (v < 0.5f) return "ছবি কমপক্ষে ০.৫ সেকেন্ড থাকতে হবে"
            if (v > 600f) return "ছবির সময় আর বাড়ানো যাবে না"
            setImageSec(id, v)
            return null
        }
        if (c.durationMs <= 0L) return "এই ভিডিওর দৈর্ঘ্য জানা যায়নি, তাই নাজ চলবে না"
        val dur = c.durationMs / 1000f
        val minLen = MIN_PIECE_MS / 1000f
        val s = c.startSec
        val e = c.effEndSec()
        val ns = if (which == 0) tenth(s + deltaSec) else s
        val ne = if (which == 1) tenth(e + deltaSec) else e
        if (ns < 0f) return "ক্লিপের শুরু আর পিছানো যাবে না"
        if (ne > dur + 0.001f) return "ক্লিপের শেষ আর বাড়ানো যাবে না (ভিডিও এখানেই শেষ)"
        if (ne - ns < minLen) return "ক্লিপ আর ছোট করা যাবে না"
        trim(id, ns, minOf(ne, dur))
        return null
    }

    // ================= টাইমলাইন জুম (ধাপ ৬) =================

    fun zoomTimeline(factor: Float) {
        timelineZoom = (timelineZoom * factor).coerceIn(TL_ZOOM_MIN, TL_ZOOM_MAX)
    }

    fun changeTimelineZoom(z: Float) {
        timelineZoom = z.coerceIn(TL_ZOOM_MIN, TL_ZOOM_MAX)
    }

    // ================= লেয়ার =================

    val visualLayers: List<Layer> get() = layers.filterNot { it.isAudio }
    val audioLayers: List<Layer> get() = layers.filter { it.isAudio }

    fun layerById(id: Long?): Layer? = if (id == null) null else layers.firstOrNull { it.id == id }

    /** এই মুহূর্তে (টাইমলাইনের ms) যেসব লেয়ার চালু */
    fun layersAt(ms: Long): List<Layer> { val stop = repeatStopMs(clips); val cards = cardRangesMs(clips); return layers.filter { it.isActiveAt(ms, stop, cards) } }

    /**
     * প্লেহেডের জায়গায় নতুন লেয়ার যোগ করে ও বাছাই করে। সমস্যা থাকলে বার্তা ফেরত দেয়, ঠিক থাকলে null।
     */
    fun addLayer(kind: LayerKind, uri: Uri? = null, text: String = "", adId: String = ""): String? {
        val total = totalMs
        if (total <= 0L) return "আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন, তারপর লেয়ার দিন"
        // লোগো একটাই (HTML-এর মতো): আগে থাকলে নতুন লেয়ার নয়, আগেরটার ছবি বদলে বাছা হয়
        if (kind == LayerKind.LOGO) {
            val ex = layers.firstOrNull { it.isLogoLayer() }
            if (ex != null) {
                if (uri != null) updateLayer(ex.id) { (it as? ImageLayer)?.copy(uri = uri) ?: it }
                selectedLayerId = ex.id
                return null
            }
        }
        val (s, e) = LayerDefaults.range(kind, playheadMs, total)
        var layer = LayerDefaults.create(kind, newId(), s, e, uri, text)
        if (layer is AdLayer && adId.isNotEmpty()) layer = layer.copy(adId = adId)
        var next: List<Layer> = layers + layer
        // HTML adAdd: নতুন বিজ্ঞাপন আগেরগুলোর পরে বসে, আর একই সারির (নিচে/ওপরে) সব বিজ্ঞাপন পুরো ভিডিও জুড়ে সমান ভাগ হয়
        if (layer is AdLayer) next = splitAds(next, adBars(layers, layer.isTop) + layer, total)
        commitAll(clips, next)
        selectedLayerId = layer.id
        return null
    }

    // ---------- বিজ্ঞাপন বার: অটো ভাগ (HTML adSplit / adFill / adEnd) ----------

    /** একই সারির (isTop) বিজ্ঞাপন বার, শুরুর সময় অনুযায়ী সাজানো */
    private fun adBars(list: List<Layer>, top: Boolean, exceptId: Long? = null): List<AdLayer> =
        list.filterIsInstance<AdLayer>().filter { it.isTop == top && it.id != exceptId }.sortedBy { it.startMs }

    private fun patchTimes(list: List<Layer>, t: Map<Long, Pair<Long, Long>>): List<Layer> =
        list.map { l -> t[l.id]?.let { l.withTime(it.first, it.second) } ?: l }

    /** HTML adSplit: n টা বার পুরো দৈর্ঘ্যে সমান ভাগ (১টা = পুরো, ২টা = অর্ধেক-অর্ধেক …) */
    private fun splitAds(list: List<Layer>, bars: List<AdLayer>, total: Long): List<Layer> {
        val n = bars.size
        if (n == 0 || total <= 0L) return list
        val t = HashMap<Long, Pair<Long, Long>>()
        bars.forEachIndexed { i, o ->
            val a = total * i / n
            val e = if (i == n - 1) total else total * (i + 1) / n
            t[o.id] = a to e
        }
        return patchTimes(list, t)
    }

    /** HTML adFill: একটা বিজ্ঞাপন মুছলে ফাঁকা জায়গা আগের বিজ্ঞাপন নিয়ে নেয় (প্রথমটা ০ থেকে শুরু); বাকিদের মাপ বদলায় না */
    private fun fillAds(list: List<Layer>, top: Boolean, total: Long): List<Layer> {
        val bars = adBars(list, top)
        val n = bars.size
        if (n == 0 || total <= 0L) return list
        val t = HashMap<Long, Pair<Long, Long>>()
        bars.forEachIndexed { i, o ->
            val s0 = if (i == 0) 0L else o.startMs
            val e = if (i < n - 1) bars[i + 1].startMs else total
            t[o.id] = s0 to maxOf(s0 + 200L, e)
        }
        return patchTimes(list, t)
    }

    /** ঠিক পরের লাগানো বিজ্ঞাপন (০.২ সে.-এর মধ্যে লাগোয়া) */
    private fun adNext(list: List<Layer>, l: AdLayer): AdLayer? =
        adBars(list, l.isTop, l.id).filter { it.startMs >= l.endMs - 200L && it.startMs - l.endMs < 200L }.minByOrNull { it.startMs }

    /** HTML adEnd: বিজ্ঞাপনের শেষ প্রান্ত e-তে সরানো; পাশের বিজ্ঞাপন লাগানো থাকলে সীমানা একসঙ্গে সরে (কমপক্ষে ০.৫ সে.) */
    private fun adEnd(list: List<Layer>, l: AdLayer, e0: Long, total: Long): List<Layer> {
        val nx = adNext(list, l)
        var e = maxOf(l.startMs + 500L, minOf(e0, total))
        val t = HashMap<Long, Pair<Long, Long>>()
        if (nx != null) {
            e = minOf(e, nx.endMs - 500L)
            t[l.id] = l.startMs to e
            t[nx.id] = e to nx.endMs
        } else {
            val hi = adBars(list, l.isTop, l.id).filter { it.startMs >= l.endMs - 10L }.minOfOrNull { it.startMs } ?: Long.MAX_VALUE
            e = minOf(e, hi)
            t[l.id] = l.startMs to e
        }
        return patchTimes(list, t)
    }

    /** বিজ্ঞাপন বারের কিনারা টানা: শেষ প্রান্ত টানলে সীমানা সরে; শুরু টানলে আগের লাগোয়া বিজ্ঞাপনের শেষ সরে (প্রথম বারের শুরু ০-তে আটকানো, HTML-এর মতো) */
    private fun resizeAd(l: AdLayer, s0: Long, e0: Long) {
        val total = totalMs
        var next: List<Layer> = layers
        if (e0 != l.endMs) next = adEnd(next, l, e0, total)
        if (s0 != l.startMs) {
            val prev = adBars(next, l.isTop, l.id).filter { it.endMs >= l.startMs - 200L && l.startMs - it.endMs < 200L }.maxByOrNull { it.endMs }
            if (prev != null) next = adEnd(next, prev, s0, total)
        }
        if (next != layers) commitAll(clips, next)
    }

    /** লেয়ারের মান বদলায়। একই লেয়ারের পরপর বদল (স্লাইডার) একটাই আনডু ধাপ */
    fun updateLayer(id: Long, f: (Layer) -> Layer) {
        val i = layers.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = layers[i]
        val upd = f(old)
        if (upd == old || upd.id != id) return
        val m = layers.toMutableList()
        m[i] = upd
        commitAll(clips, m, "layer:$id")
    }

    /** লেয়ারের শুরু-শেষ; ভিডিওর ভেতরে থাকতে বাধ্য, কমপক্ষে ০.৩ সেকেন্ড */
    fun setLayerTime(id: Long, startMs: Long, endMs: Long) {
        val total = totalMs
        (layerById(id) as? AdLayer)?.let { resizeAd(it, startMs, endMs); return }   // বিজ্ঞাপন: পাশের বার সমেত সীমানা সরে
        val s = startMs.coerceIn(0L, (total - MIN_LAYER_MS).coerceAtLeast(0L))
        val e = endMs.coerceIn(s + MIN_LAYER_MS, maxOf(total, s + MIN_LAYER_MS))
        updateLayer(id) { it.withTime(s, e) }
    }

    /**
     * লেয়ার বারের কিনারা টেনে দৈর্ঘ্য বাড়ানো-কমানোর সীমা: (নতুন শুরু, নতুন শেষ) ফেরত দেয়।
     * ভিডিওর ভেতরে থাকতে বাধ্য, কমপক্ষে ০.৩ সেকেন্ড; ফাইলের অডিওতে সোর্সের দৈর্ঘ্যের বাইরে বাড়ানো যায় না
     * (বিপ/সোর্সের দৈর্ঘ্য অজানা হলে সীমা নেই)। যে প্রান্ত বদলায়নি সেটা আগের মতোই থাকে।
     */
    fun clampLayerRange(l: Layer, s0: Long, e0: Long): Pair<Long, Long> {
        val total = totalMs
        val a = l as? AudioLayer
        // বিপ HTML-এর মতো ০.৬ সেকেন্ডের বেশি লম্বা হয় না
        val srcDur = if (a?.kind == LayerKind.BEEP) BeepSound.LEN_MS else (a?.sourceDurationMs ?: 0L)
        val limited = a != null && srcDur > 0L
        val moveS = s0 != l.startMs
        val moveE = e0 != l.endMs
        var s = l.startMs
        if (moveS) {
            s = s0.coerceAtLeast(0L)
            if (limited) s = s.coerceAtLeast(l.startMs - a!!.sourceOffsetMs)
            s = s.coerceAtMost((l.endMs - MIN_LAYER_MS).coerceAtLeast(0L))
        }
        var e = l.endMs
        if (moveE) {
            e = e0.coerceAtMost(maxOf(total, s + MIN_LAYER_MS))
            if (limited) e = e.coerceAtMost(l.startMs + (srcDur - a!!.sourceOffsetMs))
            e = e.coerceAtLeast(s + MIN_LAYER_MS)
        }
        return s to e
    }

    /** লেয়ার বারের শুরু/শেষ টেনে বদল; অডিওতে শুরু সরালে সোর্স-অফসেটও সমান সরে (ফাইলের যে অংশ বাজবে তা ঠিক থাকে) */
    fun resizeLayer(id: Long, startMs: Long, endMs: Long) {
        val l = layerById(id) ?: return
        if (l is AdLayer) { resizeAd(l, startMs, endMs); return }
        val (s, e) = clampLayerRange(l, startMs, endMs)
        if (s == l.startMs && e == l.endMs) return
        updateLayer(id) {
            if (it is AudioLayer) it.copy(startMs = s, endMs = e, sourceOffsetMs = (it.sourceOffsetMs + (s - it.startMs)).coerceAtLeast(0L))
            else it.withTime(s, e)
        }
    }

    fun removeLayer(id: Long) {
        val gone = layers.firstOrNull { it.id == id } ?: return
        var next: List<Layer> = layers.filterNot { it.id == id }
        if (gone is AdLayer) next = fillAds(next, gone.isTop, totalMs)   // HTML adFill
        commitAll(clips, next)
    }

    /** একই গ্রুপের (ভিজ্যুয়াল / অডিও) ভেতরে ওপরে (delta > 0) বা নিচে (delta < 0) সরায় */
    fun moveLayer(id: Long, delta: Int) {
        val i = layers.indexOfFirst { it.id == id }
        if (i < 0) return
        val audio = layers[i].isAudio
        val group = layers.indices.filter { layers[it].isAudio == audio }
        val gj = group.indexOf(i) + delta
        if (gj !in group.indices) return
        val j = group[gj]
        val m = layers.toMutableList()
        val t = m[i]
        m[i] = m[j]
        m[j] = t
        commitAll(clips, m)
    }

    fun duplicateLayer(id: Long) {
        val i = layers.indexOfFirst { it.id == id }
        if (i < 0) return
        val copy = layers[i].withId(newId())
        val m = layers.toMutableList()
        m.add(i + 1, copy)
        // বিজ্ঞাপনের কপি হলে একই সারির সব বার আবার সমান ভাগ (কপি আসলটার ঠিক পরে)
        val out: List<Layer> = if (copy is AdLayer) splitAds(m, adBars(m, copy.isTop), totalMs) else m
        commitAll(clips, out)
        selectedLayerId = copy.id
    }

    // ================= অডিও (ধাপ ৫) =================

    /** ক্লিপের আসল শব্দের ভলিউম ১০% করে বাড়ায়/কমায় (০..২০০%)। নতুন ভলিউম % ফেরত দেয় */
    fun adjustClipVolume(id: Long, deltaPct: Int): Int? {
        val i = clips.indexOfFirst { it.id == id }
        if (i < 0) return null
        val c = clips[i]
        if (c.isImage) return null
        val nv = (Math.round((c.volume * 100f + deltaPct) / 10f) * 10).coerceIn(0, 200)
        val unmute = deltaPct > 0 && c.muted
        if (nv == Math.round(c.volume * 100f) && !unmute) return nv
        val m = clips.toMutableList()
        m[i] = c.copy(volume = nv / 100f, muted = if (unmute) false else c.muted)
        commit(m)
        return nv
    }

    fun toggleClipMute(id: Long) {
        val i = clips.indexOfFirst { it.id == id }
        if (i < 0) return
        val c = clips[i]
        if (c.isImage) return
        val m = clips.toMutableList()
        m[i] = c.copy(muted = !c.muted)
        commit(m)
    }

    /**
     * প্লেহেডের জায়গায় অডিও/ভয়েস/বিপ লেয়ার বসায়। durationMs = সোর্সের দৈর্ঘ্য (বিপ: ৬০০)।
     * সমস্যা থাকলে বার্তা ফেরত দেয়, ঠিক থাকলে null।
     */
    fun addAudioLayer(
        kind: LayerKind,
        uri: Uri?,
        name: String,
        durationMs: Long,
        startAtMs: Long = playheadMs,
    ): String? {
        if (!kind.isAudio) return "এটা অডিও লেয়ার নয়"
        val total = totalMs
        if (total <= 0L) return "আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন, তারপর অডিও দিন"
        val start = startAtMs.coerceIn(0L, (total - MIN_LAYER_MS).coerceAtLeast(0L))
        val len = durationMs.coerceAtLeast(MIN_LAYER_MS)
        val end = minOf(total, start + len).coerceAtLeast(start + MIN_LAYER_MS)
        val layer = AudioLayer(
            id = newId(), startMs = start, endMs = end, kind = kind, uri = uri, name = name,
            sourceDurationMs = if (kind == LayerKind.BEEP) BeepSound.LEN_MS else durationMs,
        )
        commitAll(clips, layers + layer)
        selectedLayerId = layer.id
        selectedId = null
        return null
    }

    /**
     * প্লেহেডের জায়গায় অডিও লেয়ার দুই ভাগ করে (HTML au_split-এর মতো): প্রথম ভাগ ফেড-ইন রাখে, দ্বিতীয় ভাগ ফেড-আউট রাখে,
     * আর দ্বিতীয় ভাগের সোর্স-অফসেট কাটার দূরত্বে এগোয়। সমস্যা থাকলে বার্তা ফেরত দেয়, ঠিক থাকলে null।
     */
    fun splitAudioLayer(id: Long): String? {
        val i = layers.indexOfFirst { it.id == id }
        val l = layers.getOrNull(i) as? AudioLayer ?: return "অডিও লেয়ার পাওয়া যায়নি"
        val pos = playheadMs
        if (pos - l.startMs < MIN_LAYER_MS || l.endMs - pos < MIN_LAYER_MS) {
            return "লাল দাগ অডিও বারের ভেতরে (কিনারা থেকে একটু সরিয়ে) আনুন, তারপর স্প্লিট চাপুন"
        }
        val a = l.copy(endMs = pos, fadeOutMs = 0L)
        val b = l.copy(
            id = newId(), startMs = pos,
            sourceOffsetMs = l.sourceOffsetMs + (pos - l.startMs),
            fadeInMs = 0L,
        )
        val m = layers.toMutableList()
        m[i] = a
        m.add(i + 1, b)
        commitAll(clips, m)
        selectedLayerId = b.id
        return null
    }

    /**
     * ক্লিপের "সব কাট বাতিল" (HTML ⟲ রিসেট): বাছা ক্লিপের পাশাপাশি একই ভিডিওর লাগোয়া টুকরোগুলো
     * (স্প্লিটে যেগুলো ভাগ হয়েছিল) জোড়া লেগে পুরো দৈর্ঘ্যের একটা ক্লিপ হয়, ট্রিমও খুলে যায়।
     * কিছু বদলানোর না থাকলে বার্তা ফেরত দেয়, ঠিক থাকলে null।
     */
    fun resetClipCuts(id: Long): String? {
        val i = clips.indexOfFirst { it.id == id }
        if (i < 0) return "ক্লিপ পাওয়া যায়নি"
        val c = clips[i]
        if (c.isImage) return "ছবিতে কাট নেই"
        fun joinable(a: Clip, b: Clip): Boolean =
            !a.isImage && !b.isImage && a.card == null && b.card == null && a.uri == b.uri &&
                Math.abs(a.effEndSec() - b.startSec) < 0.01f
        var lo = i
        while (lo > 0 && joinable(clips[lo - 1], clips[lo])) lo--
        var hi = i
        while (hi < clips.lastIndex && joinable(clips[hi], clips[hi + 1])) hi++
        val untouched = lo == hi && c.startSec <= 0.001f && (c.endSec <= 0.001f || c.endSec * 1000f >= c.durationMs - 10f)
        if (untouched) return "এই ক্লিপে কোনো কাট নেই"
        val full = clips[lo].copy(startSec = 0f, endSec = 0f)
        val m = clips.toMutableList()
        for (k in hi downTo lo) m.removeAt(k)
        m.add(lo, full)
        commit(m)
        selectedId = full.id
        return null
    }

    /**
     * ভিডিও ক্লিপ থেকে অডিও আলাদা লেয়ার করে, মূল ক্লিপ মিউট করে (একই আনডু ধাপে)।
     * সমস্যা থাকলে বার্তা ফেরত দেয়, ঠিক থাকলে null।
     */
    fun extractAudio(clipId: Long): String? {
        val i = clips.indexOfFirst { it.id == clipId }
        if (i < 0) return "ক্লিপ পাওয়া যায়নি"
        val c = clips[i]
        if (c.isImage) return "ছবিতে অডিও নেই"
        val s = startOf(clipId)
        val e = s + c.lengthMs()
        if (e - s < MIN_LAYER_MS) return "ক্লিপ খুব ছোট"
        val layer = AudioLayer(
            id = newId(), startMs = s, endMs = e, kind = LayerKind.AUDIO, uri = c.uri,
            name = "অডিও (এক্সট্রাক্ট) — ${c.name}".take(60),
            volume = c.volume,
            sourceOffsetMs = Math.round(c.startSec * 1000.0),
            sourceClipId = c.id,
            sourceDurationMs = c.durationMs,
        )
        val m = clips.toMutableList()
        m[i] = c.copy(muted = true)
        commitAll(m, layers + layer)
        selectedLayerId = layer.id
        selectedId = null
        return null
    }
}
