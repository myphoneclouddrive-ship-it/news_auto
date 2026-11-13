package com.digitalcrafting25.newsapp

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * অ্যাডমিনের বানানো শেয়ার্ড টেমপ্লেট (id = sh_xxxx) — ফোনে ডাউনলোড করে রাখার জায়গা।
 *
 *   filesDir/shtpl/index.json   = [ {id, name, at, w, h, cat} ]   ← ছোট তালিকা (অ্যাপ খুললে শুধু এটাই পড়া হয়); cat = "photo" | "quote" (না থাকলে "photo")
 *   filesDir/shtpl/<id>.json    = { id, name, at, cat, S }        ← পুরো লেআউট (ছবিসহ, বড় হতে পারে); দরকার হলে একটা একটা করে পড়া হয়
 *
 * এটা Firestore-এর cfg.customs-এ যায় না, তাই settings/card ডকুমেন্ট ফুলে যায় না, আর কাস্টম স্লটও খরচ হয় না।
 */
object SharedTpls {
    const val CAT_PHOTO = "photo"
    const val CAT_QUOTE = "quote"
    class Entry(val id: String, val name: String, val at: Long, val w: Int, val h: Int, val cat: String = CAT_PHOTO)

    private fun normCat(c: String?) = if (c == CAT_QUOTE) CAT_QUOTE else CAT_PHOTO

    private const val MEM_MAX = 8   // একসাথে মেমরিতে রাখা পুরো লেআউটের সংখ্যা (বাকিগুলো দরকারে ফাইল থেকে)
    private var dir: File? = null
    private var entries: List<Entry> = emptyList()
    private var inited = false
    private val mem = object : LinkedHashMap<String, JSONObject>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?) = size > MEM_MAX
    }

    val ID_RE = Regex("^sh_[a-z0-9]{6,20}$")
    fun isShared(id: String) = ID_RE.matches(id)

    /** অ্যাপের কাজ শুরুর আগে একবার ডাকলেই হয় (বারবার ডাকলে কিছু বদলায় না) */
    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        val d = File(ctx.filesDir, "shtpl").also { it.mkdirs() }
        dir = d
        entries = readIndex(d) ?: rebuildIndex(d)
        inited = true
    }

    @Synchronized fun list(): List<Entry> = entries
    /** একটা ক্যাটাগরির (photo | quote) ডাউনলোড করা টেমপ্লেট */
    @Synchronized fun list(cat: String): List<Entry> = entries.filter { it.cat == normCat(cat) }
    @Synchronized fun has(id: String): Boolean = entries.any { it.id == id }
    @Synchronized fun nameOf(id: String): String? = entries.firstOrNull { it.id == id }?.name

    /** মেনুতে দেখানোর জন্য — কার্ডের মাপ (লেআউট ফাইল না খুলেই) */
    @Synchronized fun sizeOf(id: String): Pair<Int, Int>? = entries.firstOrNull { it.id == id }?.let { it.w to it.h }

    /** ফোনের কপি থেকে পুরো লেআউট (ক্যাশে না থাকলে ফাইল থেকে পড়ে); না থাকলে null */
    @Synchronized
    fun layout(id: String): JSONObject? {
        if (!isShared(id)) return null
        mem[id]?.let { return it }
        val d = dir ?: return null
        val f = File(d, "$id.json")
        if (!f.exists()) return null
        val s = runCatching { JSONObject(f.readText(Charsets.UTF_8)).optJSONObject("S") }.getOrNull() ?: return null
        if (s.optJSONArray("els") == null) return null
        mem[id] = s
        return s
    }

    /** ডাউনলোড: ফাইলে সেভ + তালিকায় যোগ (আগে থাকলে বদলে দেয়) */
    @Synchronized
    fun save(ctx: Context, id: String, name: String, at: Long, s: JSONObject, cat: String? = null) {
        init(ctx)
        val d = dir ?: throw ApiError("ফোনে জায়গা পাওয়া যায়নি")
        if (!isShared(id)) throw ApiError("আইডি ঠিক নয়")
        if (s.optJSONArray("els") == null) throw ApiError("টেমপ্লেটের তথ্য ঠিক নয়")
        // ক্যাটাগরি না দিলে আগে যা ছিল তাই থাকে (লোকাল এডিটে বদলায় না); নতুন হলে ফটোকার্ড
        val c = normCat(cat ?: entries.firstOrNull { it.id == id }?.cat)
        val rec = JSONObject().put("id", id).put("name", name).put("at", at).put("cat", c).put("S", s)
        writeAtomic(File(d, "$id.json"), rec.toString())
        val e = Entry(id, name, at, s.optInt("w", 1080).coerceAtLeast(1), s.optInt("h", 1350).coerceAtLeast(1), c)
        // আগে থাকলে একই জায়গায় বদলায় (লোকাল এডিটে মেনুর ক্রম নড়ে না), নতুন হলে শেষে
        entries = if (entries.any { it.id == id }) entries.map { if (it.id == id) e else it } else entries + e
        writeIndex(d, entries)
        mem[id] = s
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        init(ctx)
        val d = dir ?: return
        runCatching { File(d, "$id.json").delete() }
        entries = entries.filter { it.id != id }
        mem.remove(id)
        writeIndex(d, entries)
    }

    /** লগআউট/রিসেটে মেমরির কপি ফেলে দেয় (ফাইল থাকে — ওগুলো অ্যাডমিনের পাবলিক টেমপ্লেট) */
    @Synchronized fun clearMem() { mem.clear() }

    // ─────────── ফাইলের কাজ ───────────
    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw ApiError("ফোনে সেভ করা যায়নি") }
    }

    private fun writeIndex(d: File, l: List<Entry>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("at", it.at).put("w", it.w).put("h", it.h).put("cat", it.cat)) }
        writeAtomic(File(d, "index.json"), a.toString())
    }

    private fun readIndex(d: File): List<Entry>? {
        val f = File(d, "index.json")
        if (!f.exists()) return null
        return runCatching {
            val a = JSONArray(f.readText(Charsets.UTF_8))
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (!isShared(id) || !File(d, "$id.json").exists()) null
                else Entry(id, o.optString("name").ifBlank { "টেমপ্লেট" }, o.optLong("at"), o.optInt("w", 1080), o.optInt("h", 1350), normCat(o.optString("cat")))
            }
        }.getOrNull()
    }

    /** index.json হারালে/নষ্ট হলে ফাইলগুলো থেকেই আবার গড়া (বিরল) */
    private fun rebuildIndex(d: File): List<Entry> {
        val l = (d.listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") && it.name != "index.json" }.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText(Charsets.UTF_8)); val s = o.getJSONObject("S"); val id = o.getString("id")
                if (!isShared(id)) null else Entry(id, o.optString("name").ifBlank { "টেমপ্লেট" }, o.optLong("at"), s.optInt("w", 1080), s.optInt("h", 1350), normCat(o.optString("cat")))
            }.getOrNull()
        }.sortedBy { it.at }
        runCatching { writeIndex(d, l) }
        return l
    }
}

/** সার্ভারের /shtpl-* ডাক (Worker ধাপ ১) */
object SharedApi {
    class Item(val id: String, val cat: String, val name: String, val at: Long, val thumb: Bitmap?)
    class Page(val items: List<Item>, val total: Int, val next: Int?)

    /** একপাতা (২০টি) হালকা তালিকা — লেআউট আসে না */
    suspend fun list(ctx: Context, cat: String, offset: Int, limit: Int = 20): Page {
        val d = Worker.post(ctx, "/shtpl-list", JSONObject().put("cat", cat).put("offset", offset).put("limit", limit), 30000)
        val a = d.optJSONArray("items") ?: JSONArray()
        val items = (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (!SharedTpls.isShared(id)) return@mapNotNull null
            Item(id, o.optString("cat"), o.optString("name"), o.optLong("at"), o.optString("thumb").takeIf { it.isNotBlank() }?.let { PostUtil.fromDataUrl(it) })
        }
        return Page(items, d.optInt("total", items.size), if (d.isNull("next")) null else d.optInt("next"))
    }

    /** একটা টেমপ্লেটের পুরো লেআউট S (+ নাম ও তারিখ) — প্রিভিউ বা ডাউনলোডে */
    suspend fun get(ctx: Context, id: String): Triple<String, Long, JSONObject> {
        val it = Worker.post(ctx, "/shtpl-get", JSONObject().put("id", id), 60000).optJSONObject("item") ?: throw ApiError("টেমপ্লেটটি পাওয়া যায়নি")
        val s = it.optJSONObject("S") ?: throw ApiError("টেমপ্লেটের তথ্য পাওয়া যায়নি")
        return Triple(it.optString("name"), it.optLong("at"), s)
    }

    /** শুধু অ্যাডমিন: id = null হলে নতুন। ফেরত আসে সার্ভারের দেওয়া id */
    suspend fun save(ctx: Context, id: String?, cat: String, name: String, s: JSONObject, thumbDataUrl: String): String {
        val body = JSONObject().put("cat", cat).put("name", name).put("S", s).put("thumb", thumbDataUrl)
        if (!id.isNullOrBlank()) body.put("id", id)
        val d = Worker.post(ctx, "/shtpl-save", body, 90000)
        return d.optJSONObject("item")?.optString("id").orEmpty().ifBlank { id.orEmpty() }
    }

    suspend fun del(ctx: Context, id: String) { Worker.post(ctx, "/shtpl-del", JSONObject().put("id", id), 30000) }
}
