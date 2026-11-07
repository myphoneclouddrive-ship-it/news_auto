package com.digitalcrafting25.newsapp

import android.net.Uri
import androidx.compose.runtime.getValue
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
    }

    fun newId(): Long = nextId++

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
        commit(clips + list)
        selectedId = list.last().id
    }

    fun remove(id: Long) {
        if (clips.none { it.id == id }) return
        commit(clips.filterNot { it.id == id })
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
        if (i < 0) return
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
        if (c.isImage || c.durationMs <= 0L) return
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
        commit(m)
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

    // ================= লেয়ার =================

    val visualLayers: List<Layer> get() = layers.filterNot { it.isAudio }
    val audioLayers: List<Layer> get() = layers.filter { it.isAudio }

    fun layerById(id: Long?): Layer? = if (id == null) null else layers.firstOrNull { it.id == id }

    /** এই মুহূর্তে (টাইমলাইনের ms) যেসব লেয়ার চালু */
    fun layersAt(ms: Long): List<Layer> = layers.filter { it.isActiveAt(ms) }

    /**
     * প্লেহেডের জায়গায় নতুন লেয়ার যোগ করে ও বাছাই করে। সমস্যা থাকলে বার্তা ফেরত দেয়, ঠিক থাকলে null।
     */
    fun addLayer(kind: LayerKind, uri: Uri? = null, text: String = ""): String? {
        val total = totalMs
        if (total <= 0L) return "আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন, তারপর লেয়ার দিন"
        val (s, e) = LayerDefaults.range(kind, playheadMs, total)
        val layer = LayerDefaults.create(kind, newId(), s, e, uri, text)
        commitAll(clips, layers + layer)
        selectedLayerId = layer.id
        return null
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
        val s = startMs.coerceIn(0L, (total - MIN_LAYER_MS).coerceAtLeast(0L))
        val e = endMs.coerceIn(s + MIN_LAYER_MS, maxOf(total, s + MIN_LAYER_MS))
        updateLayer(id) { it.withTime(s, e) }
    }

    fun removeLayer(id: Long) {
        if (layers.none { it.id == id }) return
        commitAll(clips, layers.filterNot { it.id == id })
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
        commitAll(clips, m)
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
            sourceDurationMs = if (kind == LayerKind.BEEP) 0L else durationMs,
        )
        commitAll(clips, layers + layer)
        selectedLayerId = layer.id
        selectedId = null
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
