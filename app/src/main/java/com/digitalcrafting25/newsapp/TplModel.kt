package com.digitalcrafting25.newsapp

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * ওয়েবের cfg.customs / cfg.dtpl / cfg.tpls / cfg.template — একই JSON গড়ন, তাই ওয়েব আর অ্যাপ একই টেমপ্লেট দেখে।
 * customs[id] = { w, h, bg, cat, els: [ {id, type, x, y, w, h, ...} ] }
 * dtpl[id]    = ডিফল্ট টেমপ্লেটের (dark/white/red/blue/top) এডিট করা লেআউট
 * tpls[id]    = { mode: auto|wide|icon|text|none, size, c }
 */
class TplStore(
    var customs: JSONObject = JSONObject(),
    var dtpl: JSONObject = JSONObject(),
    var tpls: JSONObject = JSONObject(),
    var template: String = "",
    var extraSlots: Int = 0,
) {
    fun copyStore(): TplStore = TplStore(JSONObject(customs.toString()), JSONObject(dtpl.toString()), JSONObject(tpls.toString()), template, extraSlots)
}

/** আঁকার সময় এডিটরের অস্থায়ী কপি দিতে (থ্রেড-নিরাপদ: আঁকার আগে JSON-এর কপি নেওয়া হয়) */
class TplOv(val s: JSONObject?, val ts: JSONObject?)

object TplRegistry {
    @Volatile var store = TplStore()

    val DEFS = listOf("dark", "white", "red", "blue", "top")
    private val customRe = Regex("^custom\\d*$")

    fun isDef(id: String) = id in DEFS

    /** এডিট করা/কাস্টম লেআউট থাকলে সেটা, নইলে null (তখন আগের ডিফল্ট আঁকা হয়) */
    fun layout(id: String): JSONObject? {
        val st = store
        val o = if (isDef(id)) st.dtpl.optJSONObject(id) else if (customRe.matches(id)) st.customs.optJSONObject(id)
        else if (SharedTpls.isShared(id)) SharedTpls.layout(id) else null   // sh_… = ডাউনলোড করা শেয়ার্ড টেমপ্লেট (ফোনের filesDir/shtpl)
        return o?.takeIf { it.optJSONArray("els") != null }
    }

    /** টেমপ্লেটের লোগো-মোড/সাইজ/রঙ (ts): ডিফল্ট ও কাস্টমের জন্য cfg.tpls[id]; শেয়ার্ডের জন্য লেআউটের ভেতরের "ts" */
    fun tsOf(id: String): JSONObject? =
        if (SharedTpls.isShared(id)) SharedTpls.layout(id)?.optJSONObject("ts") else store.tpls.optJSONObject(id)

    fun sizeOf(id: String, ov: TplOv? = null): Pair<Int, Int>? {
        val s = ov?.s ?: (if (SharedTpls.isShared(id)) null else layout(id))
        if (s == null) return if (SharedTpls.isShared(id)) SharedTpls.sizeOf(id) else null   // শেয়ার্ড: ছোট তালিকা থেকে মাপ — মূল থ্রেডে বড় ফাইল পড়তে হয় না
        return s.optInt("w", 1080).coerceAtLeast(1) to s.optInt("h", 1350).coerceAtLeast(1)
    }

    /** ফোটোকার্ড-ধরনের কাস্টম টেমপ্লেটের id (মেনুর ক্রম) */
    fun customIds(): List<String> {
        val st = store; val out = ArrayList<String>()
        val it = st.customs.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (!customRe.matches(k)) continue
            val o = st.customs.optJSONObject(k) ?: continue
            if (o.optString("cat", "photo").ifBlank { "photo" } != "photo") continue
            if (o.optJSONArray("els") == null) continue
            out.add(k)
        }
        return out
    }

    fun customName(id: String): String {
        val keys = ArrayList<String>(); val it = store.customs.keys(); while (it.hasNext()) keys.add(it.next())
        val n = keys.indexOf(id) + 1
        return "কাস্টম টেমপ্লেট" + if (n > 1) " $n" else ""
    }

    fun photoTpls(): List<Pair<String, String>> = CARD_TPLS + customIds().map { it to customName(it) }

    fun isKnownPhotoTpl(id: String) = CARD_TPLS.any { it.first == id } || id in customIds()

    /** ফোটোকার্ড মেনুর সারি: শুধু ইউজারের নিজের কাস্টম + ডাউনলোড করা শেয়ার্ড। ৫টা ডিফল্ট এখন স্টোর থেকে নামাতে হয়।
     *  (সেটিংস/নতুন পোস্টের তালিকা আগের মতোই photoTpls()) — দুটোই খালি থাকলে মেনু ফাঁকা না থাকতে ৫ ডিফল্ট দেখায় */
    fun menuTpls(): List<Pair<String, String>> {
        val mine = customIds().map { it to customName(it) } + SharedTpls.list().map { it.id to it.name }
        return mine.ifEmpty { CARD_TPLS }
    }

    fun isKnownMenuTpl(id: String) = menuTpls().any { it.first == id }

    /** ওয়েবের cbCanAdd: ফোটোকার্ডে ১টা কাস্টম ফ্রি, তারপর কেনা বাড়তি স্লট */
    fun canAddCustom(): Boolean = customIds().isEmpty() || customIds().size - 1 < store.extraSlots

    fun nextCustomId(): String {
        var i = 1
        while (true) { val k = if (i == 1) "custom" else "custom$i"; if (!store.customs.has(k)) return k; i++ }
    }

    // নিজের আপলোড করা ছবি (e.data = data URL) — একবার নামিয়ে মনে রাখা
    private val imgCache = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > 12
    }

    @Synchronized
    fun image(data: String): Bitmap? {
        if (data.isBlank()) return null
        if (imgCache.containsKey(data)) return imgCache[data]
        val b = PostUtil.fromDataUrl(data)
        imgCache[data] = b
        return b
    }

    fun reset() { store = TplStore(); SharedTpls.clearMem() }
}

/** ওয়েবের TPL[id].colors: ডিফল্ট ফোটোকার্ড টেমপ্লেটের রঙের তালিকা (key, লেবেল, ডিফল্ট) — সেটিংসের টেমপ্লেট-মোডালে দেখায় */
object TplColors {
    private const val LT = "সাইট নামের রঙ (লোগো না থাকলে)"
    val DEFS: Map<String, List<Triple<String, String, String>>> = mapOf(
        "dark" to listOf(Triple("head", "শিরোনামের রঙ", "#ffd400"), Triple("band", "তারিখের বারের রঙ", "#1e1e6e"), Triple("shade", "নিচের ছায়ার রঙ", "#000000"), Triple("sub", "ওয়েবসাইটের লেখার রঙ", "#dddddd"), Triple("lt", LT, "#ffffff")),
        "white" to listOf(Triple("head1", "শিরোনাম (উপরের অংশ)", "#d50000"), Triple("head2", "শিরোনাম (নিচের অংশ)", "#1a3ea8"), Triple("panel", "প্যানেলের রঙ", "#f2f2f2"), Triple("foot", "নিচের বারের রঙ", "#e2e2e2"), Triple("ft", "নিচের লেখার রঙ", "#333333"), Triple("lt", LT, "#222222")),
        "red" to listOf(Triple("bg", "ব্যাকগ্রাউন্ডের রঙ", "#b30012"), Triple("head1", "শিরোনাম (উপরের অংশ)", "#ffffff"), Triple("head2", "শিরোনাম (নিচের অংশ)", "#ffe600"), Triple("foot", "নিচের বারের রঙ", "#8e000d"), Triple("ft", "নিচের লেখার রঙ", "#ffffff"), Triple("lt", LT, "#ffffff")),
        "blue" to listOf(Triple("bg1", "ব্যাকগ্রাউন্ড (উপরে)", "#0d3b8e"), Triple("bg2", "ব্যাকগ্রাউন্ড (নিচে)", "#08224f"), Triple("head1", "শিরোনাম (উপরের অংশ)", "#ffffff"), Triple("head2", "শিরোনাম (নিচের অংশ)", "#ffe600"), Triple("chipc", "বাটন/লেখার বক্সের রঙ", "#6a1bb1"), Triple("ft", "নিচের লেখার রঙ", "#ffffff"), Triple("lt", LT, "#ffffff")),
        "top" to listOf(Triple("bg", "উপরের ব্যাকগ্রাউন্ডের রঙ", "#1c2d52"), Triple("head", "শিরোনামের রঙ", "#ffe600"), Triple("ft", "লেখার রঙ", "#ffffff"), Triple("lt", LT, "#ffffff")),
    )
}

/** ওয়েবের cbStarter / cbDefStarter: নতুন কাস্টম ও ডিফল্ট টেমপ্লেটের শুরুর লেআউট */
object TplDefaults {
    private var seq = 0
    fun newId(): String = "e" + java.lang.Long.toString(System.currentTimeMillis(), 36) + (seq++)

    private fun jo(vararg p: Pair<String, Any?>): JSONObject { val o = JSONObject(); p.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }; return o }

    private fun mk(vararg p: Pair<String, Any?>) = jo("id" to newId(), *p)

    private fun tx(vararg p: Pair<String, Any?>): JSONObject {
        val o = jo("id" to newId(), "type" to "text", "color" to "#ffffff", "size" to 30, "align" to "c", "box" to false, "bg" to "#000000", "balpha" to 60, "text" to "")
        p.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    private fun rc(x: Int, y: Int, w: Int, h: Int, color: String, alpha: Int = 100, vararg extra: Pair<String, Any?>): JSONObject {
        val o = jo("id" to newId(), "type" to "rect", "x" to x, "y" to y, "w" to w, "h" to h, "color" to color, "alpha" to alpha, "shape" to "rect", "grad" to "none")
        extra.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    private fun ph(x: Int, y: Int, w: Int, h: Int) = mk("type" to "photo", "x" to x, "y" to y, "w" to w, "h" to h)
    private fun hl(x: Int, y: Int, w: Int, h: Int, c1: String, c2: String? = null) =
        mk("type" to "headline", "x" to x, "y" to y, "w" to w, "h" to h, "c1" to c1, "two" to (c2 != null), "c2" to (c2 ?: "#ffe600"), "size" to 78)
    private fun lg(x: Int, y: Int, w: Int, h: Int, align: String, color: String = "#ffffff") =
        mk("type" to "logo", "x" to x, "y" to y, "w" to w, "h" to h, "align" to align, "color" to color)

    private fun layout(w: Int, h: Int, bg: String, vararg els: JSONObject): JSONObject {
        val a = JSONArray(); els.forEach { a.put(it) }
        return jo("w" to w, "h" to h, "bg" to bg, "els" to a)
    }

    /** k: "dark" | "split" | "blank" */
    fun starter(k: String): JSONObject = when (k) {
        "split" -> layout(1080, 1350, "#f2f2f2",
            ph(0, 0, 1080, 860), hl(60, 900, 960, 290, "#d50000", "#1a3ea8"),
            rc(0, 1270, 1080, 80, "#e2e2e2"),
            tx("src" to "catdate", "x" to 20, "y" to 1270, "w" to 600, "h" to 80, "color" to "#333333", "align" to "l"),
            tx("src" to "url", "x" to 560, "y" to 1270, "w" to 500, "h" to 80, "color" to "#333333", "align" to "r"),
            lg(30, 30, 260, 110, "l"),
            tx("src" to "tag", "x" to 30, "y" to 790, "w" to 200, "h" to 56, "size" to 26, "box" to true))
        "blank" -> layout(1080, 1080, "#111111",
            ph(0, 0, 1080, 720), hl(50, 745, 980, 270, "#ffffff"))
        else -> layout(1080, 1350, "#000000",
            ph(0, 0, 1080, 1350), rc(0, 650, 1080, 700, "#000000", 100, "grad" to "down"),
            tx("src" to "catdate", "x" to 0, "y" to 880, "w" to 1080, "h" to 60, "box" to true, "bg" to "#1e1e6e"),
            hl(60, 960, 960, 290, "#ffd400"),
            tx("src" to "url", "x" to 0, "y" to 1275, "w" to 1080, "h" to 60, "color" to "#dddddd"),
            lg(760, 40, 280, 120, "r"),
            tx("src" to "tag", "x" to 30, "y" to 40, "w" to 200, "h" to 56, "size" to 26, "box" to true))
    }

    /** ডিফল্ট ফোটোকার্ডের এলিমেন্ট-লেআউট: আসল ডিজাইনের মতো */
    fun defStarter(id: String): JSONObject = when (id) {
        "dark" -> layout(1080, 1350, "#000000",
            ph(0, 0, 1080, 1350), rc(0, 650, 1080, 700, "#000000", 100, "grad" to "down"),
            tx("src" to "catdate", "x" to 0, "y" to 880, "w" to 1080, "h" to 60, "box" to true, "bg" to "#1e1e6e"), hl(60, 960, 960, 290, "#ffd400"),
            rc(440, 1268, 200, 3, "#ffffff", 35), tx("src" to "url", "x" to 0, "y" to 1275, "w" to 1080, "h" to 60, "color" to "#dddddd"),
            lg(760, 40, 280, 120, "r"), tx("src" to "tag", "x" to 30, "y" to 40, "w" to 200, "h" to 44, "size" to 26, "box" to true))
        "white" -> layout(1080, 1350, "#f2f2f2",
            ph(0, 0, 1080, 860), rc(0, 860, 1080, 490, "#f2f2f2"), rc(435, 755, 210, 210, "#f2f2f2", 100, "shape" to "circle"),
            lg(450, 800, 180, 120, "c", "#222222"), tx("src" to "tag", "x" to 30, "y" to 780, "w" to 200, "h" to 44, "size" to 26, "box" to true),
            hl(60, 985, 960, 270, "#d50000", "#1a3ea8"), rc(0, 1270, 1080, 80, "#e2e2e2"),
            tx("src" to "catdate", "x" to 24, "y" to 1270, "w" to 600, "h" to 80, "color" to "#333333", "align" to "l"),
            tx("src" to "url", "x" to 456, "y" to 1270, "w" to 600, "h" to 80, "color" to "#333333", "align" to "r"))
        "red" -> layout(1080, 1350, "#b30012",
            ph(0, 0, 1080, 900), rc(0, 900, 1080, 450, "#b30012"), lg(30, 30, 260, 110, "l"),
            tx("src" to "tag", "x" to 830, "y" to 30, "w" to 220, "h" to 44, "size" to 26, "box" to true), hl(50, 925, 980, 340, "#ffffff", "#ffe600"),
            rc(0, 1300, 1080, 50, "#8e000d"), tx("src" to "date", "x" to 8, "y" to 1301, "w" to 400, "h" to 50, "size" to 26, "align" to "l"),
            tx("src" to "cta", "x" to 240, "y" to 1301, "w" to 600, "h" to 50, "size" to 26), tx("src" to "url", "x" to 672, "y" to 1301, "w" to 400, "h" to 50, "size" to 26, "align" to "r"))
        "blue" -> layout(1080, 1350, "#08224f",
            ph(0, 0, 1080, 650), rc(0, 650, 1080, 700, "#0d3b8e", 100, "grad" to "two", "color2" to "#08224f"), lg(30, 30, 260, 110, "l"),
            tx("src" to "tag", "x" to 0, "y" to 592, "w" to 200, "h" to 44, "size" to 26, "box" to true), hl(50, 700, 980, 380, "#ffffff", "#ffe600"),
            tx("src" to "cta", "x" to 290, "y" to 1098, "w" to 500, "h" to 51, "size" to 30, "box" to true, "bg" to "#6a1bb1", "balpha" to 100),
            rc(0, 1290, 1080, 60, "#000000", 25), tx("src" to "date", "x" to 8, "y" to 1295, "w" to 400, "h" to 50, "size" to 26, "align" to "l"),
            tx("src" to "url", "x" to 672, "y" to 1295, "w" to 400, "h" to 50, "size" to 26, "align" to "r"))
        else -> layout(1080, 1350, "#1c2d52",
            rc(0, 0, 1080, 440, "#1c2d52"), lg(790, 24, 260, 90, "r"), hl(50, 130, 980, 300, "#ffe600"), ph(0, 440, 1080, 910),
            tx("src" to "catdate", "x" to 0, "y" to 1270, "w" to 420, "h" to 48, "size" to 28, "box" to true, "balpha" to 65),
            tx("src" to "tag", "x" to 860, "y" to 452, "w" to 220, "h" to 41, "size" to 24, "box" to true),
            tx("src" to "url", "x" to 646, "y" to 1286, "w" to 420, "h" to 48, "size" to 28, "align" to "r"))
    }

    /** এডিটরের "➕" বাটনের নতুন এলিমেন্ট (ওয়েবের cbNew) */
    fun newElement(type: String, W: Int, H: Int): JSONObject = when (type) {
        "photo" -> mk("type" to "photo", "x" to 0, "y" to 0, "w" to W, "h" to Math.round(H * 0.6f))
        "rect" -> mk("type" to "rect", "x" to 0, "y" to Math.round(H * 0.7f), "w" to W, "h" to Math.round(H * 0.3f), "color" to "#000000", "alpha" to 70, "shape" to "rect", "grad" to "none")
        "headline" -> mk("type" to "headline", "x" to 60, "y" to Math.round(H * 0.7f), "w" to W - 120, "h" to Math.round(H * 0.2f), "c1" to "#ffffff", "two" to false, "c2" to "#ffe600", "size" to 78)
        "logo" -> mk("type" to "logo", "x" to W - 300, "y" to 30, "w" to 270, "h" to 110, "align" to "r", "color" to "#ffffff")
        "logoicon" -> mk("type" to "logo", "mode" to "icon", "shape" to "", "x" to 40, "y" to 40, "w" to 130, "h" to 130, "align" to "c", "color" to "#ffffff", "lock" to true, "ar" to 1.0)
        "ctext" -> mk("type" to "text", "src" to "custom", "text" to "আপনার লেখা", "icon" to "globe", "x" to 60, "y" to H - 100, "w" to 420, "h" to 60, "color" to "#ffffff", "size" to 30, "align" to "l", "box" to false, "bg" to "#000000", "balpha" to 60)
        "icon" -> mk("type" to "icon", "ico" to "globe", "x" to 60, "y" to H - 100, "w" to 60, "h" to 60, "align" to "c", "color" to "#ffffff")
        "fb", "yt" -> mk("type" to "social", "net" to type, "text" to "", "x" to Math.round(W * (if (type == "fb") 0.33f else 0.66f)), "y" to H - 100, "w" to Math.round(W * 0.3f), "h" to 60, "color" to "#ffffff", "size" to 30, "align" to "c")
        else -> mk("type" to "text", "src" to "catdate", "x" to 0, "y" to Math.round(H * 0.65f), "w" to W, "h" to 60, "color" to "#ffffff", "size" to 30, "align" to "c", "box" to true, "bg" to "#1e1e6e", "balpha" to 60, "text" to "")
    }
}
