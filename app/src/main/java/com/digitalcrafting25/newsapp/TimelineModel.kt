package com.digitalcrafting25.newsapp

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
    var selectedId by mutableStateOf<Long?>(null)
    var playheadMs by mutableLongStateOf(0L)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<List<Clip>>()
    private val redoStack = ArrayDeque<List<Clip>>()
    private var nextId = 1L

    companion object {
        const val MAX_HISTORY = 60
        const val MIN_PIECE_MS = 300L
    }

    fun newId(): Long = nextId++

    val totalMs: Long get() = clips.sumOf { it.lengthMs() }

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        val sel = selectedId
        if (sel != null && clips.none { it.id == sel }) selectedId = null
        playheadMs = playheadMs.coerceIn(0L, totalMs)
    }

    private fun commit(next: List<Clip>) {
        undoStack.addLast(clips)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        clips = next
        refresh()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(clips)
        clips = prev
        refresh()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(clips)
        clips = next
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
}
