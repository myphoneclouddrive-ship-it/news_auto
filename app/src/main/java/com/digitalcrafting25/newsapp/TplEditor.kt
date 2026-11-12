package com.digitalcrafting25.newsapp

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 🛠️ ফোটোকার্ড টেমপ্লেট এডিটর (ওয়েবের openCb / cbPanel / cbCanvas-এর মতো)।
 * ডিফল্ট পাঁচটা (dark/white/red/blue/top) আর নিজের কাস্টম — সবই এডিট করা যায়।
 *  • কার্ডের ওপর আঙুল টেনে এলিমেন্ট সরানো, ডান-নিচের নীল বর্গ টেনে মাপ বদলানো
 *  • ➕ এলিমেন্ট যোগ, তালিকা থেকে বাছাই, প্রতিটার সেটিংস, লক, আগে/পরে, মোছা
 *  • আন্ডু / রিডু, শুরুর লেআউট প্রয়োগ, ডিফল্টে ফেরা
 *  • সেভ করলে ওয়েবের একই cfg.dtpl / cfg.customs / cfg.tpls-এ যায়
 */

private val EdSizes = listOf(
    "1080x1350" to "১০৮০×১৩৫০ (ফেসবুক পোর্ট্রেট ৪:৫)",
    "1080x1080" to "১০৮০×১০৮০ (স্কোয়ার)",
    "1080x1920" to "১০৮০×১৯২০ (স্টোরি ৯:১৬)",
    "1200x630" to "১২০০×৬৩০ (আড়াআড়ি)",
)
private val TplIconOptions = listOf(
    "globe" to "🌐 গ্লোব (ওয়েবসাইট)", "fb" to "📘 ফেসবুক", "yt" to "▶️ ইউটিউব", "ig" to "📷 ইনস্টাগ্রাম",
    "phone" to "📞 ফোন", "mail" to "✉️ ইমেইল", "pin" to "📍 ঠিকানা (পিন)",
)
private val EdIconShort = mapOf("globe" to "ওয়েবসাইট", "fb" to "ফেসবুক", "yt" to "ইউটিউব", "ig" to "ইনস্টাগ্রাম", "phone" to "ফোন", "mail" to "ইমেইল", "pin" to "ঠিকানা")
private val EdSrc = listOf(
    "catdate" to "ক্যাটাগরি | তারিখ", "date" to "শুধু তারিখ", "cat" to "শুধু ক্যাটাগরি", "url" to "ওয়েবসাইট",
    "tag" to "ছবির ট্যাগ", "cta" to "নিচের লেখা (সেটিংস থেকে)", "custom" to "নিজের লেখা",
)
private val EdAddTypes = listOf(
    "photo" to "🖼️ খবরের ছবি", "headline" to "📰 শিরোনাম", "text" to "🔤 লেখা (তারিখ/সাইট/নিজের লেখা)",
    "ctext" to "✍️ কাস্টম লেখা + আইকন", "icon" to "🔣 আইকন", "image" to "📷 নিজের ছবি যোগ (লোগো/ব্যানার)",
    "logo" to "🏷️ লোগো (আড়াআড়ি/লম্বা)", "logoicon" to "⭕ আইকন লোগো (স্কোয়ার/গোল)",
    "fb" to "📘 ফেসবুক (আইকন + ইউজারনেম)", "yt" to "▶️ ইউটিউব (আইকন + ইউজারনেম)", "rect" to "⬛ রঙিন বক্স / ছায়া",
)
private val EdNames = mapOf("photo" to "খবরের ছবি", "headline" to "শিরোনাম", "logo" to "লোগো", "rect" to "বক্স", "icon" to "আইকন", "image" to "যোগ করা ছবি")
private val EdAligns = listOf("l" to "বাঁয়ে", "c" to "মাঝে", "r" to "ডানে")
private val EdPalette = listOf(
    "#ffffff", "#000000", "#ffd400", "#ffe600", "#d50000", "#c62828", "#b30012", "#1a3ea8", "#0d3b8e",
    "#08224f", "#1e1e6e", "#6a1bb1", "#2e7d32", "#f2f2f2", "#dddddd", "#333333",
)

private fun JSONObject.fl(k: String, d: Float = 0f): Float = if (has(k) && !isNull(k)) optDouble(k, d.toDouble()).toFloat() else d
private fun JSONObject.st(k: String, d: String = ""): String = if (has(k) && !isNull(k)) optString(k, d) else d
private fun JSONObject.elsArr(): JSONArray = optJSONArray("els") ?: JSONArray().also { put("els", it) }
private fun JSONArray.items(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun arrOf(l: List<JSONObject>): JSONArray = JSONArray().also { a -> l.forEach { a.put(it) } }
private fun colorOf(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)
private fun cp(o: JSONObject): JSONObject = JSONObject(o.toString())

private fun elName(e: JSONObject): String {
    val t = e.st("type")
    return when {
        t == "logo" && e.st("mode") == "icon" -> "আইকন লোগো"
        t == "logo" && e.st("mode") == "wide" -> "আড়াআড়ি লোগো"
        t == "social" -> (EdIconShort[e.st("ico").ifBlank { if (e.st("net") == "yt") "yt" else "fb" }] ?: "সোশ্যাল") + (if (e.st("text").isNotBlank()) " " + e.st("text").take(10) else "")
        t == "text" -> if (e.st("src") == "custom") "“" + e.st("text").ifBlank { "লেখা" }.take(12) + "”" else (EdSrc.firstOrNull { it.first == e.st("src") }?.second?.substringBefore(" (") ?: "লেখা")
        else -> EdNames[t] ?: t
    }
}

/** এডিটরের চলমান অবস্থা: লেআউট JSON (কপি), লোগো মোড, বাছাই, আন্ডু/রিডু */
private class EdState(init: JSONObject, ts0: JSONObject?) {
    var S: JSONObject = init
    var ts: JSONObject = ts0 ?: JSONObject()
    var ver by mutableIntStateOf(0)
    var tick by mutableIntStateOf(0)
    var sel by mutableStateOf("")
    private val hist = ArrayList<String>()
    private var hi = 0

    init { hist.add(snap()) }

    private fun snap(): String = JSONObject().put("S", S).put("ts", ts).toString()
    fun touch() { ver++ }
    fun w(): Int = S.optInt("w", 1080).coerceAtLeast(1)
    fun h(): Int = S.optInt("h", 1350).coerceAtLeast(1)
    fun els(): List<JSONObject> = S.elsArr().items()
    fun selEl(): JSONObject? = if (sel.isEmpty()) null else els().firstOrNull { it.st("id") == sel }
    fun canUndo() = tick >= 0 && hi > 0
    fun canRedo() = tick >= 0 && hi < hist.size - 1
    val changed: Boolean get() = hist.size > 1

    fun commit() {
        val j = snap()
        if (j == hist[hi]) return
        while (hist.size > hi + 1) hist.removeAt(hist.size - 1)
        hist.add(j)
        if (hist.size > 60) hist.removeAt(0)
        hi = hist.size - 1
        tick++
    }

    fun go(d: Int) {
        commit()
        val k = hi + d
        if (k < 0 || k >= hist.size) return
        hi = k
        val o = JSONObject(hist[k])
        S = o.getJSONObject("S"); ts = o.optJSONObject("ts") ?: JSONObject()
        if (els().none { it.st("id") == sel }) sel = ""
        tick++; ver++
    }

    fun setLayout(n: JSONObject) { S = n; sel = ""; touch(); commit() }
    fun setEls(l: List<JSONObject>) { S.put("els", arrOf(l)); touch() }
    fun add(e: JSONObject) { setEls(els() + e); sel = e.st("id"); commit() }
    fun remove(e: JSONObject) { setEls(els().filter { it !== e }); sel = ""; commit() }
    fun move(e: JSONObject, d: Int) {
        val a = els().toMutableList(); val i = a.indexOf(e); val j = i + d
        if (i < 0 || j < 0 || j >= a.size) return
        a[i] = a[j].also { a[j] = a[i] }
        setEls(a); commit()
    }
}

/** এডিটরের যা যা লাগে: প্রিভিউয়ের নমুনা কার্ড-ডেটা আর "নিজের ছবি যোগ"-এর ছোট করা ছবি। NewPostViewModel ও অ্যাডমিনের টেমপ্লেট-স্টোর দুটোই দেয় */
interface TplEditHost {
    fun editorCardData(): CardData
    fun tplImageData(uri: Uri): Pair<String, Float>?
}

typealias TplSaveFn = (id: String, layout: JSONObject, ts: JSONObject?, done: (String?) -> Unit) -> Unit

/** ইউজারের নিজের (ডিফল্ট/কাস্টম) টেমপ্লেট এডিট — সেভ হয় Firestore-এর cfg-তে (আগের মতোই) */
@Composable
fun TplEditorDialog(vm: NewPostViewModel, tplId: String, onClose: () -> Unit) {
    TplEditorDialog(vm, tplId, null, null, null, { id, l, ts, done -> vm.saveTpl(id, l, ts, done) }, onClose)
}

/**
 * সাধারণ রূপ। initial = এডিট করার লেআউট S (ভেতরে "ts" থাকতে পারে) — দিলে সেটাই খোলে (শেয়ার্ড টেমপ্লেটের জন্য);
 * title/saveLabel দিলে ওপরের লেখা ও সেভ বাটনের লেখা বদলায়।
 */
@Composable
fun TplEditorDialog(host: TplEditHost, tplId: String, initial: JSONObject?, title: String?, saveLabel: String?, onSave: TplSaveFn, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = C.Bg) { TplEditorBody(host, tplId, initial, title, saveLabel, onSave, onClose) }
    }
}

@Composable
private fun TplEditorBody(vm: TplEditHost, id: String, initial: JSONObject?, titleText: String?, saveLabel: String?, onSave: TplSaveFn, onClose: () -> Unit) {
    val isDef = TplRegistry.isDef(id)
    val shared = initial != null || SharedTpls.isShared(id)   // শেয়ার্ড: ts (লোগো-মোড/রঙ) লেআউটের ভেতরেই থাকে, cfg.tpls-এ নয়
    val st = remember(id) {
        val base = (if (initial != null) cp(initial).also { it.remove("ts") } else TplRegistry.layout(id)?.let { cp(it) }) ?: if (isDef) TplDefaults.defStarter(id) else TplDefaults.starter("dark")
        base.elsArr()
        val ts0 = if (shared) initial?.optJSONObject("ts")?.let { cp(it) } else TplRegistry.store.tpls.optJSONObject(id)?.let { cp(it) }
        EdState(base, ts0)
    }
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmStart by remember { mutableStateOf(false) }
    var startKind by remember { mutableStateOf("dark") }
    var imgTarget by remember { mutableStateOf<String?>(null) }   // খালি = নতুন ছবি যোগ, নইলে এই এলিমেন্টের ছবি বদল

    val data = remember { vm.editorCardData() }
    // প্রিভিউ: একটাই কাজের লুপ — টানার সময় শুধু সর্বশেষ অবস্থা আঁকে, জমে থাকে না
    LaunchedEffect(id) {
        var last = -1
        while (true) {
            val v = st.ver
            if (v != last) {
                last = v
                val cs = cp(st.S); val cts = cp(st.ts)
                preview = withContext(Dispatchers.Default) { CardRender.render(id, data, 0.6f, null, TplOv(cs, cts)) }
            } else delay(16)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { vm.tplImageData(uri) }
            if (r == null) { err = "ছবিটা বেশি বড় বা পড়া যায়নি। ছোট ছবি দিন।"; return@launch }
            err = null
            val tgt = imgTarget?.let { t -> st.els().firstOrNull { it.st("id") == t } }
            if (tgt != null) { tgt.put("data", r.first); st.sel = tgt.st("id"); st.touch(); st.commit() }
            else {
                val w0 = Math.round(min(st.w() * 0.3f, st.w().toFloat()))
                val h0 = max(20, min(Math.round(w0 / r.second), st.h()))
                val e = JSONObject().put("id", TplDefaults.newId()).put("type", "image").put("data", r.first).put("fit", "contain").put("alpha", 100)
                    .put("x", Math.round((st.w() - w0) / 2f)).put("y", Math.round(st.h() * 0.05f)).put("w", w0).put("h", h0)
                st.add(e)
            }
            imgTarget = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            titleText ?: if (isDef) "✏️ ডিফল্ট টেমপ্লেট এডিট করুন — " + (CARD_TPLS.firstOrNull { it.first == id }?.second ?: id) else "✏️ কাস্টম টেমপ্লেট এডিট করুন",
            fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
        )
        // প্রিভিউ (ওপরে স্থির, নিচের অংশ স্ক্রল করে)
        val ar = st.w().toFloat() / st.h()
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.heightIn(max = 300.dp).aspectRatio(ar).clip(RoundedCornerShape(8.dp)).background(Color(0xFFDDDDDD))
                    .pointerInput(st) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val k = st.w() / size.width.toFloat()
                            val px = down.position.x * k; val py = down.position.y * k
                            var target: JSONObject? = null
                            var resize = false
                            var dx = 0f; var dy = 0f
                            val cur = st.selEl()
                            if (cur != null && !cur.optBoolean("locked")) {
                                val r = 24.dp.toPx() * k
                                if (Math.abs(px - (cur.fl("x") + cur.fl("w"))) <= r && Math.abs(py - (cur.fl("y") + cur.fl("h"))) <= r) { target = cur; resize = true }
                            }
                            if (target == null) {
                                val l = st.els()
                                for (i in l.indices.reversed()) {
                                    val e = l[i]
                                    if (e.optBoolean("locked")) continue
                                    if (px >= e.fl("x") && px <= e.fl("x") + e.fl("w") && py >= e.fl("y") && py <= e.fl("y") + e.fl("h")) { target = e; break }
                                }
                                if (target != null) { dx = px - target.fl("x"); dy = py - target.fl("y") }
                            }
                            val t = target
                            if (t == null) { return@awaitEachGesture }
                            st.sel = t.st("id"); st.touch()
                            down.consume()
                            drag(down.id) { ch ->
                                val x = ch.position.x * k; val y = ch.position.y * k
                                val W = st.w(); val H = st.h()
                                if (resize) {
                                    val ex = t.fl("x"); val ey = t.fl("y")
                                    val ratio = t.fl("ar")
                                    if (t.optBoolean("lock") && ratio > 0f) {
                                        var w = max(20, min(W - ex.toInt(), Math.round(x - ex)))
                                        var h = max(20, Math.round(w / ratio))
                                        if (ey + h > H) { h = max(20, H - ey.toInt()); w = max(20, Math.round(h * ratio)) }
                                        t.put("w", w); t.put("h", h)
                                    } else {
                                        t.put("w", max(20, min(W - ex.toInt(), Math.round(x - ex))))
                                        t.put("h", max(20, min(H - ey.toInt(), Math.round(y - ey))))
                                    }
                                } else {
                                    t.put("x", max(0, min(W - 20, Math.round(x - dx))))
                                    t.put("y", max(0, min(H - 20, Math.round(y - dy))))
                                }
                                st.touch()
                                ch.consume()
                            }
                            st.commit()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val pv = preview
                if (pv != null) Image(pv.asImageBitmap(), contentDescription = "টেমপ্লেট প্রিভিউ", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Canvas(Modifier.fillMaxSize()) {
                    val v = st.ver
                    val e = st.selEl()
                    if (e != null && v >= 0) {
                        val sc = size.width / st.w()
                        val col = if (e.optBoolean("locked")) Color(0xFFFF9100) else Color(0xFF00E5FF)
                        drawRect(col, Offset(e.fl("x") * sc, e.fl("y") * sc), Size(e.fl("w") * sc, e.fl("h") * sc),
                            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f))))
                        if (!e.optBoolean("locked")) {
                            val hs = 14.dp.toPx()
                            drawRect(col, Offset((e.fl("x") + e.fl("w")) * sc - hs / 2, (e.fl("y") + e.fl("h")) * sc - hs / 2), Size(hs, hs))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            AppBtn("↶ আন্ডু", color = C.Gray, small = true, enabled = st.canUndo()) { st.go(-1) }
            AppBtn("↷ রিডু", color = C.Gray, small = true, enabled = st.canRedo()) { st.go(1) }
        }

        // নিচের অংশ: সেটিংস (স্ক্রল)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            val ver = st.ver
            Text("কার্ডের ওপর আঙুল টেনে এলিমেন্ট সরান। বাছাই করা এলিমেন্টের ডান-নিচের নীল বর্গ টেনে মাপ বদলান।", fontSize = 12.sp, color = C.Label)

            // কার্ডের মাপ, ব্যাকগ্রাউন্ড, লোগোর ধরন
            FieldLabel("কার্ডের মাপ")
            val curSize = st.w().toString() + "x" + st.h()
            val sizes = if (EdSizes.any { it.first == curSize }) EdSizes else EdSizes + (curSize to (st.w().toString() + "×" + st.h()))
            EdSelect(sizes, curSize) { v ->
                val p = v.split("x"); st.S.put("w", p[0].toInt()); st.S.put("h", p[1].toInt()); st.touch(); st.commit()
            }
            EdColor("ব্যাকগ্রাউন্ডের রঙ", st.S.st("bg", "#111111"), onChange = { st.S.put("bg", it); st.touch() }, onDone = { st.commit() })
            FieldLabel("লোগোর ধরন (লোগো এলিমেন্টে কী বসবে)")
            EdSelect(listOf("auto" to "স্বয়ংক্রিয় (যেটা আছে)", "wide" to "লম্বা/আড়াআড়ি লোগো", "icon" to "স্কোয়ার/রাউন্ড আইকন", "text" to "সাইটের নাম (লেখা)"),
                st.ts.st("mode", "auto").ifBlank { "auto" }) { v -> st.ts.put("mode", v); st.touch(); st.commit() }

            FieldLabel("➕ এলিমেন্ট যোগ করুন")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EdAddTypes.forEach { (t, label) ->
                    FilterChip(selected = false, onClick = {
                        if (t == "image") { imgTarget = null; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        else st.add(TplDefaults.newElement(t, st.w(), st.h()))
                    }, label = { Text(label, fontSize = 13.sp) })
                }
            }
            FieldLabel("কার্ডের এলিমেন্ট (বাঁ থেকে ডানে = পেছন থেকে সামনে)")
            val all = st.els()
            if (all.isEmpty()) Text("কিছু নেই — ওপরে ➕ চেপে এলিমেন্ট যোগ করুন।", fontSize = 13.sp, color = C.Label)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                all.forEach { e ->
                    val inBox = all.take(all.indexOf(e)).any { r -> r.st("type") == "rect" && inside(r, e) }
                    FilterChip(selected = e.st("id") == st.sel, onClick = { st.sel = e.st("id"); st.touch() },
                        label = { Text((if (inBox) "↳ " else "") + (if (e.optBoolean("locked")) "🔒 " else "") + elName(e), fontSize = 13.sp) })
                }
            }

            // বাছাই করা এলিমেন্টের সেটিংস
            val sel = st.selEl()
            if (sel == null) {
                Text("কোনো এলিমেন্ট বাছাই করা নেই। তালিকা থেকে বা কার্ডে চেপে বাছুন।", fontSize = 13.sp, color = C.Label, modifier = Modifier.padding(top = 10.dp))
            } else {
                ElementPanel(st, sel, ver, onPickImage = { imgTarget = sel.st("id"); picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            }

            FieldLabel("শুরুর লেআউট বদলাতে চান?")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("dark" to "ডার্ক ছবি + শিরোনাম", "split" to "ছবি উপরে, শিরোনাম নিচে", "blank" to "একদম সাদামাটা").forEach { (k, l) ->
                    FilterChip(selected = startKind == k, onClick = { startKind = k }, label = { Text(l, fontSize = 13.sp) })
                }
                AppBtn("প্রয়োগ", color = C.Gray, small = true) { if (st.els().isEmpty()) st.setLayout(TplDefaults.starter(startKind)) else confirmStart = true }
            }
            err?.let { Text("⚠️ $it", color = C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Box(Modifier.size(12.dp))
        }

        Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppBtn(if (saving) "⏳ সেভ হচ্ছে…" else (saveLabel ?: "✔ সেভ করে বেছে নিন"), Modifier.weight(1f), color = C.Green, small = true, enabled = !saving) {
                saving = true; err = null
                val mode = st.ts.st("mode", "auto")
                val hadTs = if (shared) initial?.has("ts") == true else TplRegistry.store.tpls.has(id)
                val tsOut = if (mode.isBlank() || mode == "auto") (if (hadTs) cp(st.ts).also { it.put("mode", "auto") } else null) else cp(st.ts)
                onSave(id, cp(st.S), tsOut) { e -> saving = false; if (e == null) onClose() else err = e }
            }
            if (isDef) AppBtn("↺ ডিফল্টে ফিরুন", color = C.Gray, small = true, enabled = !saving) { confirmReset = true }
            AppBtn("✕ বাতিল", color = C.Gray, small = true, enabled = !saving) { if (st.changed) confirmClose = true else onClose() }
        }
    }

    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        text = { Text("সেভ না করে বের হলে এই এডিটগুলো থাকবে না। বের হবেন?") },
        confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("হ্যাঁ, বের হই") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("না") } },
    )
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        text = { Text("এই টেমপ্লেটের সব এডিট মুছে আসল ডিফল্ট ডিজাইনে ফিরবেন?") },
        confirmButton = { TextButton(onClick = { confirmReset = false; st.ts.remove("mode"); st.setLayout(TplDefaults.defStarter(id)) }) { Text("হ্যাঁ") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("না") } },
    )
    if (confirmStart) AlertDialog(
        onDismissRequest = { confirmStart = false },
        text = { Text("এখনকার সাজানো মুছে নতুন লেআউট বসবে। চালিয়ে যাবেন?") },
        confirmButton = { TextButton(onClick = { confirmStart = false; st.setLayout(TplDefaults.starter(startKind)) }) { Text("হ্যাঁ") } },
        dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("না") } },
    )
}

private fun inside(r: JSONObject, k: JSONObject): Boolean =
    k.fl("x") >= r.fl("x") - 2 && k.fl("y") >= r.fl("y") - 2 && k.fl("x") + k.fl("w") <= r.fl("x") + r.fl("w") + 2 && k.fl("y") + k.fl("h") <= r.fl("y") + r.fl("h") + 2

/** রেশিও টিক থাকলে চওড়া/উচ্চতার একটা বদলালে অন্যটাও অনুপাত ধরে বদলায় (ওয়েবের cbLockSize) */
private fun lockSize(e: JSONObject, st: EdState, which: String, v: Int) {
    val ar = e.fl("ar")
    if (!e.optBoolean("lock") || ar <= 0f) { e.put(which, v); return }
    var w = if (which == "w") v else Math.round(v * ar)
    var h = if (which == "h") v else Math.round(v / ar)
    if (w > st.w()) { w = st.w(); h = Math.round(w / ar) }
    if (h > st.h()) { h = st.h(); w = Math.round(h * ar) }
    if (w < 20) { w = 20; h = Math.round(w / ar) }
    if (h < 20) { h = 20; w = Math.round(h * ar) }
    e.put("w", min(w, st.w())); e.put("h", min(h, st.h()))
}

@Composable
private fun ElementPanel(st: EdState, e: JSONObject, ver: Int, onPickImage: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rv = ver   // ver বদলালে প্যানেল নতুন করে আঁকা হয়
    val type = e.st("type")
    val locked = e.optBoolean("locked")
    if (!e.has("lock") && (type == "logo" || type == "image")) { e.put("lock", true); e.put("ar", (e.fl("w") / max(1f, e.fl("h"))).toDouble()) }

    Text("✏️ " + elName(e) + " — সেটিংস", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppBtn(if (locked) "🔓 আনলক" else "🔒 লক করুন", color = C.Gray, small = true) { e.put("locked", !locked); st.touch(); st.commit() }
        AppBtn("◀ পেছনে", color = C.Gray, small = true) { st.move(e, -1) }
        AppBtn("সামনে ▶", color = C.Gray, small = true) { st.move(e, 1) }
        AppBtn("🗑️ মুছুন", color = C.Red, small = true) { st.remove(e) }
    }
    if (locked) Text("🔒 এলিমেন্টটি লক করা: কার্ডে চাপ বা টান ধরবে না, অবস্থান ও মাপও বদলাবে না। বদলাতে আগে \"আনলক\" করুন।", fontSize = 12.sp, color = C.Label, modifier = Modifier.padding(top = 4.dp))
    if (type == "rect") {
        val n = st.els().let { a -> val i = a.indexOf(e); if (i < 0) 0 else a.drop(i + 1).count { inside(e, it) } }
        Text(if (n > 0) "🔗 এই বক্সের ভেতরে ${PostUtil.bn(n)}টি এলিমেন্ট আছে (এগুলো আলাদাভাবে সরাতে/ছোট-বড় করতে হবে)।" else "বক্সের ভেতরে (বক্সের পরে বসানো) এলিমেন্ট রাখতে পারেন।", fontSize = 12.sp, color = C.Label, modifier = Modifier.padding(top = 4.dp))
    }

    EdSlider("বাঁ থেকে দূরত্ব (X)", e.fl("x"), 0f, st.w().toFloat(), !locked, { e.put("x", it.roundToInt()); st.touch() }) { st.commit() }
    EdSlider("উপর থেকে দূরত্ব (Y)", e.fl("y"), 0f, st.h().toFloat(), !locked, { e.put("y", it.roundToInt()); st.touch() }) { st.commit() }
    EdSlider("চওড়া", e.fl("w"), 20f, st.w().toFloat(), !locked, { lockSize(e, st, "w", it.roundToInt()); st.touch() }) { st.commit() }
    EdSlider("উচ্চতা", e.fl("h"), 20f, st.h().toFloat(), !locked, { lockSize(e, st, "h", it.roundToInt()); st.touch() }) { st.commit() }
    EdCheck("🔒 রেশিও ঠিক রাখুন (চওড়া বদলালে উচ্চতাও সাথে বদলাবে)", e.optBoolean("lock")) { on ->
        e.put("lock", on); if (on) e.put("ar", (e.fl("w") / max(1f, e.fl("h"))).toDouble()); st.touch(); st.commit()
    }

    fun setS(k: String, v: Any) { e.put(k, v); st.touch(); st.commit() }
    @Composable
    fun col(k: String, label: String, d: String) {
        EdColor(label, e.st(k, d), onChange = { v -> e.put(k, v); st.touch() }, onDone = { st.commit() })
    }

    when (type) {
        "rect" -> {
            col("color", "রঙ", "#000000")
            EdSlider("স্বচ্ছতা (০ = অদৃশ্য)", e.fl("alpha", 100f), 0f, 100f, true, { e.put("alpha", it.roundToInt()); st.touch() }) { st.commit() }
            FieldLabel("আকৃতি")
            EdSelect(listOf("rect" to "আয়তক্ষেত্র", "circle" to "গোল/ডিম্বাকৃতি"), e.st("shape", "rect")) { setS("shape", it) }
            FieldLabel("গ্র্যাডিয়েন্ট ছায়া")
            EdSelect(listOf("none" to "নেই (সমান রঙ)", "down" to "উপর থেকে নিচে গাঢ়", "up" to "নিচ থেকে উপরে গাঢ়", "two" to "দুই রঙের (উপর → নিচ)"), e.st("grad", "none")) { setS("grad", it) }
            if (e.st("grad") == "two") col("color2", "নিচের রঙ (গ্র্যাডিয়েন্টের শেষ)", e.st("color", "#000000"))
        }
        "headline" -> {
            col("c1", if (e.optBoolean("two")) "শিরোনাম (উপরের অংশ)" else "শিরোনামের রঙ", "#ffffff")
            EdCheck("নিচের অর্ধেকে আলাদা রঙ", e.optBoolean("two")) { setS("two", it) }
            if (e.optBoolean("two")) col("c2", "শিরোনাম (নিচের অংশ)", "#ffe600")
            EdSlider("সর্বোচ্চ অক্ষরের সাইজ (বক্সে না ধরলে নিজে ছোট হবে)", e.fl("size", 78f), 30f, 120f, true, { e.put("size", it.roundToInt()); st.touch() }) { st.commit() }
        }
        "logo" -> {
            FieldLabel("কোন লোগো বসবে")
            EdSelect(listOf("auto" to "টেমপ্লেট অনুযায়ী (আগের মতো)", "wide" to "আড়াআড়ি / লম্বা লোগো", "icon" to "স্কোয়ার / গোল আইকন", "text" to "শুধু সাইটের নাম (লেখা)"),
                e.st("mode").ifBlank { "auto" }) { setS("mode", if (it == "auto") "" else it) }
            if (e.st("mode") == "icon") {
                FieldLabel("আইকনের আকৃতি")
                EdSelect(listOf("" to "সেটিংসে যা দেওয়া আছে", "round" to "গোল (রাউন্ড)", "square" to "স্কোয়ার"), e.st("shape")) { setS("shape", it) }
            }
            FieldLabel("কোন দিকে বসবে")
            EdSelect(EdAligns, e.st("align", "l").ifBlank { "l" }) { setS("align", it) }
            col("color", "সাইট নামের রঙ (লোগো না থাকলে)", "#ffffff")
        }
        "social" -> {
            FieldLabel("আইকন")
            EdSelect(TplIconOptions, e.st("ico").ifBlank { if (e.st("net") == "yt") "yt" else "fb" }) { setS("ico", it) }
            EdText("লেখা (ইউজারনেম / নাম / নম্বর)", e.st("text"), e.st("id")) { e.put("text", it); st.touch() }
            col("color", "আইকন ও লেখার রঙ", "#ffffff")
            EdSlider("অক্ষরের সাইজ", e.fl("size", 30f), 14f, 120f, true, { e.put("size", it.roundToInt()); e.put("fx", true); st.touch() }) { st.commit() }
            FieldLabel("কোন দিকে বসবে")
            EdSelect(EdAligns, e.st("align", "c").ifBlank { "c" }) { setS("align", it) }
        }
        "icon" -> {
            FieldLabel("কোন আইকন")
            EdSelect(TplIconOptions, e.st("ico", "globe")) { setS("ico", it) }
            col("color", "আইকনের রঙ", "#ffffff")
            FieldLabel("কোন দিকে বসবে")
            EdSelect(EdAligns, e.st("align", "c").ifBlank { "c" }) { setS("align", it) }
        }
        "image" -> {
            AppBtn("🔄 অন্য ছবি দিন", Modifier.padding(top = 8.dp), color = C.Blue, small = true) { onPickImage() }
            FieldLabel("ছবি কীভাবে বসবে")
            EdSelect(listOf("contain" to "পুরোটা দেখাবে (বক্সে ধরিয়ে)", "cover" to "বক্স ভরে (কিছু কাটা পড়তে পারে)"), e.st("fit", "contain")) { setS("fit", it) }
            EdSlider("স্বচ্ছতা (০ = অদৃশ্য)", e.fl("alpha", 100f), 0f, 100f, true, { e.put("alpha", it.roundToInt()); st.touch() }) { st.commit() }
        }
        "text" -> {
            FieldLabel("কী লেখা বসবে")
            EdSelect(EdSrc, e.st("src", "catdate")) { setS("src", it) }
            if (e.st("src") == "custom") EdText("আপনার লেখা", e.st("text"), e.st("id")) { e.put("text", it); st.touch() }
            FieldLabel("লেখার আগে আইকন")
            EdSelect(listOf("" to "নেই") + TplIconOptions, e.st("icon")) { setS("icon", it) }
            col("color", "লেখার রঙ", "#ffffff")
            EdSlider("অক্ষরের সাইজ", e.fl("size", 30f), 14f, 120f, true, { e.put("size", it.roundToInt()); e.put("fx", true); st.touch() }) { st.commit() }
            FieldLabel("কোন দিকে বসবে")
            EdSelect(EdAligns, e.st("align", "l").ifBlank { "l" }) { setS("align", it) }
            EdCheck("লেখার পেছনে রঙিন বক্স", e.optBoolean("box")) { setS("box", it) }
            if (e.optBoolean("box")) {
                col("bg", "বক্সের রঙ", "#000000")
                EdSlider("বক্সের স্বচ্ছতা", e.fl("balpha", 60f), 0f, 100f, true, { e.put("balpha", it.roundToInt()); st.touch() }) { st.commit() }
            }
        }
    }
}

// ───────── ছোট কন্ট্রোল ─────────
@Composable
private fun EdSlider(label: String, value: Float, min: Float, max: Float, enabled: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val hi = if (max <= min) min + 1f else max
    Text(label + ": " + value.roundToInt(), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    Slider(value = value.coerceIn(min, hi), onValueChange = onChange, valueRange = min..hi, enabled = enabled, onValueChangeFinished = onDone)
}

@Composable
private fun EdCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = checked, onCheckedChange = { onChange(it) })
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EdSelect(options: List<Pair<String, String>>, cur: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (k, l) -> FilterChip(selected = cur == k, onClick = { onPick(k) }, label = { Text(l, fontSize = 13.sp) }) }
    }
}

@Composable
private fun EdText(label: String, value: String, key: String, onChange: (String) -> Unit) {
    var t by remember(key) { mutableStateOf(value) }
    FieldLabel(label)
    OutlinedTextField(
        value = t, onValueChange = { t = it; onChange(it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp), textStyle = TextStyle(fontSize = 16.sp),
    )
}

@Composable
private fun EdColor(label: String, hex: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    var t by remember(hex) { mutableStateOf(hex) }
    FieldLabel(label)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(colorOf(hex)).border(1.dp, C.Border, RoundedCornerShape(6.dp)))
        OutlinedTextField(
            value = t,
            onValueChange = { v ->
                t = v
                val x = v.trim()
                if (Regex("^#[0-9a-fA-F]{6}$").matches(x)) { onChange(x.lowercase()); onDone() }
            },
            singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), textStyle = TextStyle(fontSize = 16.sp),
        )
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EdPalette.forEach { c ->
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(colorOf(c))
                    .border(if (c.equals(hex, true)) 3.dp else 1.dp, if (c.equals(hex, true)) C.Red else C.Border, RoundedCornerShape(6.dp))
                    .clickable { t = c; onChange(c); onDone() },
            )
        }
    }
}
