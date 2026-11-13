package com.digitalcrafting25.newsapp

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🛍️ টেমপ্লেট স্টোর: ২০টি করে পাতায় হালকা তালিকা (নাম + ছোট থাম্বনেইল)। প্রিভিউ/ডাউনলোডে তবেই একটা একটা করে পুরো লেআউট আসে।
 * দুটো ট্যাব: 🖼️ ফটোকার্ড (cat = "photo") ও 💬 বক্তব্য কার্ড (cat = "quote") — প্রতিটার তালিকা/পেজিং আলাদা।
 */
class TemplateStoreViewModel(private val app: Application) : AndroidViewModel(app), TplEditHost {
    /** একটা ক্যাটাগরির তালিকার অবস্থা */
    class CatState {
        var items by mutableStateOf<List<SharedApi.Item>>(emptyList())
        var total by mutableIntStateOf(0)
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var loaded by mutableStateOf(false)
        var next: Int? = 0
        /** রেডিমেড তালিকার কয়টা এখনো সার্ভারে নেই (০ হলে বাটন বন্ধ; null = জানা নেই) */
        var readyLeft by mutableStateOf<Int?>(null)
    }

    private val states = mapOf(SharedTpls.CAT_PHOTO to CatState(), SharedTpls.CAT_QUOTE to CatState())

    /** এখন কোন ট্যাব খোলা: "photo" | "quote" */
    var cat by mutableStateOf(SharedTpls.CAT_PHOTO)
        private set
    private val cs: CatState get() = states.getValue(cat)

    fun selectCat(c: String) { cat = if (c == SharedTpls.CAT_QUOTE) SharedTpls.CAT_QUOTE else SharedTpls.CAT_PHOTO }

    val items: List<SharedApi.Item> get() = cs.items
    val total: Int get() = cs.total
    val loading: Boolean get() = cs.loading
    val error: String? get() = cs.error
    val loaded: Boolean get() = cs.loaded
    val readyLeft: Int? get() = cs.readyLeft

    var msg by mutableStateOf<String?>(null)
    /** ফোনে ডাউনলোড করা টেমপ্লেটের আইডি (স্ক্রিন এটা দেখে বাটন বদলায়) */
    var downloaded by mutableStateOf<Set<String>>(emptySet())
        private set
    var busyId by mutableStateOf<String?>(null)   // যে টেমপ্লেটের ডাউনলোড/মোছা/এডিট-লোড চলছে
        private set
    var importing by mutableStateOf(false)   // রেডিমেড টেমপ্লেটগুলো একসাথে যোগ হচ্ছে (শুধু অ্যাডমিন)
        private set

    private fun readyFile(c: String) = if (c == SharedTpls.CAT_QUOTE) "shtpl_quote_ready.json" else "shtpl_ready.json"

    private suspend fun readyArr(c: String): JSONArray = withContext(Dispatchers.IO) { JSONArray(app.assets.open(readyFile(c)).bufferedReader(Charsets.UTF_8).use { it.readText() }) }

    /** সার্ভারে এখন এই ক্যাটাগরিতে যত টেমপ্লেট আছে, সবার নাম (সব পাতা ঘুরে) */
    private suspend fun serverNames(c: String): MutableSet<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>(); var off: Int? = 0; var guard = 0
        while (off != null && guard++ < 40) { val p = SharedApi.list(app, c, off); p.items.forEach { out.add(it.name.trim()) }; off = p.next }
        out
    }

    fun refreshReady() {
        val c = cat; val st = cs
        viewModelScope.launch {
            st.readyLeft = try { val have = serverNames(c); val a = readyArr(c); (0 until a.length()).count { a.getJSONObject(it).getString("name").trim() !in have } } catch (e: Exception) { null }
        }
    }

    // ── প্রিভিউ ──
    class Preview(val id: String, val name: String, val at: Long) {
        var bmp by mutableStateOf<Bitmap?>(null)
        var err by mutableStateOf<String?>(null)
        var s: JSONObject? = null
    }
    var preview by mutableStateOf<Preview?>(null)
        private set

    // ── প্রিভিউ/এডিটরের নমুনা কার্ড (সেটিংসের লোগো, সাইটের তথ্য, আজকের তারিখ) — ক্যাটাগরি অনুযায়ী আলাদা ──
    private val samples = HashMap<String, CardData>()

    private fun placeholder(): Bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).also {
        val pt = Paint().apply { shader = LinearGradient(0f, 0f, 800f, 600f, android.graphics.Color.parseColor("#5b7fa6"), android.graphics.Color.parseColor("#c9a46c"), Shader.TileMode.CLAMP) }
        Canvas(it).drawRect(0f, 0f, 800f, 600f, pt)
    }

    private fun buildSample(c: CardCfg, forCat: String): CardData {
        val quote = forCat == SharedTpls.CAT_QUOTE
        return CardData(
            photo = placeholder(),
            head = if (quote) "আজকের গুরুত্বপূর্ণ সব খবর, এক নজরে দেখে নিন। দেশ ও মানুষের জন্য কাজ করাই আমাদের লক্ষ্য।" else "এখানে খবরের শিরোনাম এভাবে সাজানো দেখাবে, আপনার লোগো ও আজকের তারিখসহ",
            cat = PostUtil.catParts(c.category).firstOrNull() ?: "",
            date = runCatching { SimpleDateFormat("d MMMM yyyy", Locale("bn", "BD")).format(Date()) }.getOrDefault(""),
            url = c.siteUrl, tag = c.photoTag.trim(), cta = c.ctaText.trim(), siteName = c.siteName,
            logo = AdRender.decode(c.logo), icon = AdRender.decode(c.icon), iconRound = c.iconShape != "square",
            fx = 0.5f, fy = 0.5f,
            name = if (quote) "ড. পাগল কবি" else "", role = if (quote) "বিশ্বখ্যাত কবি ও সাহিত্যিক" else "",
        )
    }

    private fun sampleFor(c: String): CardData = samples[c] ?: buildSample(CardCfg(), c).also { samples[c] = it }

    /** নমুনা কার্ড তৈরি (ক্যাটাগরি পিছু একবারই; সাইটের তথ্য না এলে ফাঁকা লোগো দিয়ে চলে) */
    suspend fun prepare() {
        val c = cat
        if (samples[c] != null) return
        val cfg = runCatching { Fs.cardCfg(app) }.getOrNull() ?: CardCfg()
        samples[c] = withContext(Dispatchers.Default) { buildSample(cfg, c) }
    }

    override fun editorCardData(): CardData = sampleFor(cat)

    /** এডিটরে "নিজের ছবি যোগ" (শুধু অ্যাডমিন): ছোট করে data URL; বেশি বড় হলে null */
    override fun tplImageData(uri: Uri): Pair<String, Float>? = runCatching {
        val r = app.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / s > 1120 || o.outHeight / s > 1120) s *= 2
        var b = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
        val k = 560f / maxOf(b.width, b.height)
        if (k < 1f) b = Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        val mime = runCatching { r.getType(uri) }.getOrNull().orEmpty()
        val png = Regex("png|webp|gif").containsMatchIn(mime)
        val out = java.io.ByteArrayOutputStream()
        if (png) b.compress(Bitmap.CompressFormat.PNG, 100, out) else b.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val data = (if (png) "data:image/png;base64," else "data:image/jpeg;base64,") + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        if (data.length > 420000) return null
        data to (b.width.toFloat() / maxOf(1, b.height))
    }.getOrNull()

    // ── তালিকা ও পেজিং (খোলা ট্যাবের) ──
    fun load(refresh: Boolean = false) {
        SharedTpls.init(app); downloaded = SharedTpls.list().map { it.id }.toSet()
        val st = cs
        if (st.loading || (st.loaded && !refresh)) return
        fetch(cat, st, 0, replace = true)
    }

    fun loadMore() {
        val st = cs
        val n = st.next ?: return
        if (st.loading) return
        fetch(cat, st, n, replace = false)
    }

    private fun fetch(c: String, st: CatState, offset: Int, replace: Boolean) {
        st.loading = true; st.error = null
        viewModelScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { SharedApi.list(app, c, offset) }
                st.items = if (replace) p.items else st.items + p.items.filter { n -> st.items.none { it.id == n.id } }
                st.total = p.total; st.next = p.next; st.loaded = true
            } catch (e: Exception) {
                st.error = e.message ?: "তালিকা আনা যায়নি"
            } finally { st.loading = false }
        }
    }

    // ── প্রিভিউ: শুধু এই একটার পুরো লেআউট আনা হয়, ফোনে সেভ হয় না ──
    fun openPreview(item: SharedApi.Item) {
        val pv = Preview(item.id, item.name, item.at)
        preview = pv
        val c = cat
        viewModelScope.launch {
            try {
                prepare()
                // ডাউনলোড করা থাকলে ফোনের কপি (সার্ভারে যেতে হয় না), নইলে সার্ভার থেকে শুধু এই একটা
                val s = withContext(Dispatchers.IO) { SharedTpls.layout(item.id) ?: SharedApi.get(app, item.id).third }
                pv.s = s
                val d = sampleFor(c)
                pv.bmp = withContext(Dispatchers.Default) { CardRender.render(item.id, d, 0.6f, null, TplOv(s, s.optJSONObject("ts") ?: JSONObject())) }
            } catch (e: Exception) { pv.err = e.message ?: "প্রিভিউ আনা যায়নি" }
        }
    }

    fun closePreview() { preview = null }

    // ── ডাউনলোড / ফোন থেকে সরানো ──
    fun download(id: String, name: String, at: Long, onChanged: () -> Unit) {
        if (busyId != null) return
        busyId = id; msg = null
        val c = cat
        viewModelScope.launch {
            try {
                val s = preview?.takeIf { it.id == id }?.s ?: withContext(Dispatchers.IO) { SharedApi.get(app, id) }.third
                withContext(Dispatchers.IO) { SharedTpls.save(app, id, name, at, s, c) }
                downloaded = SharedTpls.list().map { it.id }.toSet()
                msg = "✅ \"$name\" ডাউনলোড হয়েছে। এখন " + (if (c == SharedTpls.CAT_QUOTE) "বক্তব্য কার্ডের" else "ফোটোকার্ড") + " মেনুতে পাবেন, অফলাইনেও চলবে।"
                onChanged()
            } catch (e: Exception) { msg = "❌ " + (e.message ?: "ডাউনলোড হয়নি") } finally { busyId = null }
        }
    }

    fun removeLocal(id: String, onChanged: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { SharedTpls.remove(app, id) }
            downloaded = SharedTpls.list().map { it.id }.toSet()
            msg = "🗑 ফোন থেকে সরানো হয়েছে (স্টোরে থেকে যাবে, চাইলে আবার ডাউনলোড করতে পারবেন)।"
            onChanged()
        }
    }

    // ── শুধু অ্যাডমিন ──
    /** এডিটে খোলার জন্য একটা টেমপ্লেটের পুরো S আনে */
    fun fetchForEdit(id: String, onDone: (JSONObject?, String?) -> Unit) {
        if (busyId != null) return
        busyId = id; msg = null
        viewModelScope.launch {
            try { prepare(); val s = withContext(Dispatchers.IO) { SharedApi.get(app, id) }.third; onDone(s, null) }
            catch (e: Exception) { onDone(null, e.message ?: "আনা যায়নি") } finally { busyId = null }
        }
    }

    /** এডিটরের সেভ: ~২০০px থাম্বনেইল এখানেই বানিয়ে (সার্ভার আঁকতে পারে না) লেআউটসহ /shtpl-save-এ পাঠানো */
    fun adminSave(id: String?, name: String, layout: JSONObject, ts: JSONObject?, done: (String?) -> Unit) {
        val c = cat
        viewModelScope.launch {
            try {
                val s = JSONObject(layout.toString())
                if (ts != null && ts.length() > 0) s.put("ts", ts) else s.remove("ts")
                val d = sampleFor(c)
                val thumb = withContext(Dispatchers.Default) {
                    val sc = 200f / s.optInt("w", 1080).coerceAtLeast(1)
                    PostUtil.toDataUrl(CardRender.render(id ?: "sh_newtpl", d, sc, null, TplOv(s, s.optJSONObject("ts") ?: JSONObject())), 72)
                }
                withContext(Dispatchers.IO) { SharedApi.save(app, id, c, name, s, thumb) }
                msg = "✅ \"$name\" সেভ হয়েছে। ইউজাররা স্টোরে দেখতে পাবে।"
                load(refresh = true)
                done(null)
            } catch (e: Exception) { done(e.message ?: "সেভ হয়নি") }
        }
    }

    /** অ্যাপের ভেতরের assets-এর রেডিমেড টেমপ্লেটগুলো (ফটোকার্ড: shtpl_ready.json, বক্তব্য কার্ড: shtpl_quote_ready.json) একটা একটা করে স্টোরে যোগ (শুধু অ্যাডমিন) */
    fun importReady() {
        if (busyId != null || importing || loading) return
        importing = true; msg = "⏳ সার্ভারে আগে কী আছে দেখা হচ্ছে…"
        val c = cat
        viewModelScope.launch {
            var ok = 0
            try {
                val have = serverNames(c)
                val all = readyArr(c)
                val todo = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("name").trim() !in have }
                if (todo.isEmpty()) { msg = "✅ সব রেডিমেড টেমপ্লেট আগেই যোগ করা আছে, আবার যোগ হবে না।"; return@launch }
                prepare()
                val d = sampleFor(c)
                for ((i, o) in todo.withIndex()) {
                    val name = o.getString("name"); val s = o.getJSONObject("S")
                    msg = "⏳ (${i + 1}/${todo.size}) \"$name\" যোগ হচ্ছে…"
                    val thumb = withContext(Dispatchers.Default) {
                        val sc = 200f / s.optInt("w", 1080).coerceAtLeast(1)
                        PostUtil.toDataUrl(CardRender.render("sh_newtpl", d, sc, null, TplOv(s, s.optJSONObject("ts") ?: JSONObject())), 72)
                    }
                    withContext(Dispatchers.IO) { SharedApi.save(app, null, c, name, s, thumb) }
                    ok++
                }
                msg = "✅ $ok টি নতুন রেডিমেড টেমপ্লেট যোগ হয়েছে (যেগুলো আগেই ছিল সেগুলো বাদ)। ইউজাররা এখন দেখতে পাবে।"
            } catch (e: Exception) {
                msg = "❌ " + (e.message ?: "যোগ করা যায়নি") + (if (ok > 0) " ($ok টি আগেই যোগ হয়েছে)" else "")
            } finally {
                importing = false
                load(refresh = true)
                refreshReady()
            }
        }
    }

    fun adminDelete(id: String) {
        if (busyId != null) return
        busyId = id; msg = null
        val st = cs
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { SharedApi.del(app, id) }
                st.items = st.items.filter { it.id != id }; st.total = (st.total - 1).coerceAtLeast(0); refreshReady()
                msg = "🗑 মুছে ফেলা হয়েছে। যাদের ফোনে ডাউনলোড করা আছে তাদের কপি থাকবে।"
            } catch (e: Exception) { msg = "❌ " + (e.message ?: "মোছা যায়নি") } finally { busyId = null }
        }
    }

    fun reset() {
        states.values.forEach { it.items = emptyList(); it.total = 0; it.loading = false; it.error = null; it.loaded = false; it.next = 0; it.readyLeft = null }
        msg = null; busyId = null; preview = null; samples.clear()
    }
}

private class EditSession(val id: String?, val name: String, val initial: JSONObject?)

@Composable
fun TemplateStoreScreen(vm: TemplateStoreViewModel, admin: Boolean, onClose: (() -> Unit)?, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var nameAsk by remember { mutableStateOf<Triple<String?, String, JSONObject?>?>(null) }   // (id, নাম, S) — নাম জিজ্ঞেসের ডায়ালগ
    var session by remember { mutableStateOf<EditSession?>(null) }
    var delAsk by remember { mutableStateOf<SharedApi.Item?>(null) }
    var importAsk by remember { mutableStateOf(false) }

    val quoteTab = vm.cat == SharedTpls.CAT_QUOTE
    LaunchedEffect(vm.cat) { vm.load(); listState.scrollToItem(0) }
    LaunchedEffect(admin, vm.cat) { if (admin) vm.refreshReady() }
    // শেষের কাছাকাছি পৌঁছালে পরের ২০টি
    LaunchedEffect(listState, vm.cat) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            if (last >= 0 && last >= vm.items.size - 3) vm.loadMore()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (admin) "🎨 শেয়ার্ড টেমপ্লেট (অ্যাডমিন)" else "🛍️ টেমপ্লেট স্টোর", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (onClose != null) AppBtn("✕ বন্ধ", color = C.Gray, small = true, onClick = onClose)
        }
        // ক্যাটাগরি ট্যাব: 🖼️ ফটোকার্ড | 💬 বক্তব্য কার্ড
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(SharedTpls.CAT_PHOTO to "🖼️ ফটোকার্ড", SharedTpls.CAT_QUOTE to "💬 বক্তব্য কার্ড").forEach { (k, t) ->
                val on = vm.cat == k
                Text(
                    t, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (on) Color.White else C.Text,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(if (on) Color(0xFF0A3D91) else Color.White)
                        .border(1.dp, if (on) Color(0xFF0A3D91) else C.Border, RoundedCornerShape(20.dp))
                        .clickable(enabled = !on && !vm.importing && vm.busyId == null) { vm.selectCat(k) }
                        .padding(horizontal = 6.dp, vertical = 9.dp),
                )
            }
        }
        HSmall(
            if (admin) "এখানে বানানো " + (if (quoteTab) "বক্তব্য কার্ড" else "ফোটোকার্ড") + " টেমপ্লেট সব ইউজার স্টোরে দেখবে ও ডাউনলোড করতে পারবে। (সর্বোচ্চ ৩০০টি)"
            else "প্রিভিউ দেখতে কিছু ডাউনলোড করতে হয় না। পছন্দ হলে ⬇️ ডাউনলোড চাপুন — ফোনে সেভ থাকবে, অফলাইনেও " + (if (quoteTab) "বক্তব্য কার্ডের" else "ফোটোকার্ড") + " মেনুতে চলবে।",
            Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        if (admin) AppBtn(if (quoteTab) "➕ নতুন বক্তব্য কার্ড টেমপ্লেট" else "➕ নতুন শেয়ার্ড টেমপ্লেট", Modifier.padding(bottom = 8.dp), color = C.Green) { nameAsk = Triple(null, "", null) }
        if (admin) AppBtn(
            if (vm.importing) "⏳ যোগ হচ্ছে…" else if (vm.readyLeft == 0) "✅ রেডিমেড টেমপ্লেট সব যোগ করা আছে" else (if (quoteTab) "📦 রেডিমেড বক্তব্য কার্ড (১১টি ডিজাইন) যোগ করুন" else "📦 রেডিমেড টেমপ্লেট যোগ করুন") + (vm.readyLeft?.let { " (নতুন ${Bn.num(it)}টি)" } ?: ""),
            Modifier.padding(bottom = 8.dp), color = C.Blue, enabled = !vm.importing && vm.busyId == null && vm.readyLeft != 0,
        ) { importAsk = true }
        vm.msg?.let { Text(it, fontSize = 14.sp, color = if (it.startsWith("❌")) C.Red else C.Text, modifier = Modifier.padding(bottom = 8.dp)) }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.items, key = { it.id }) { it ->
                StoreRow(it, vm, admin, onChanged,
                    onEdit = { item -> vm.fetchForEdit(item.id) { s, e -> if (s != null) nameAsk = Triple(item.id, item.name, s) else vm.msg = "❌ $e" } },
                    onDelete = { delAsk = it })
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    when {
                        vm.loading -> CircularProgressIndicator(color = C.Red)
                        vm.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚠️ " + vm.error, color = C.Red, fontSize = 14.sp)
                            AppBtn("🔄 আবার চেষ্টা করুন", Modifier.padding(top = 8.dp), color = C.Blue, small = true) { if (vm.items.isEmpty()) vm.load(refresh = true) else vm.loadMore() }
                        }
                        vm.loaded && vm.items.isEmpty() -> HSmall(if (quoteTab) "এখনো কোনো বক্তব্য কার্ড টেমপ্লেট দেওয়া হয়নি।" else "এখনো কোনো টেমপ্লেট দেওয়া হয়নি।", center = true)
                        vm.loaded -> HSmall("মোট ${Bn.num(vm.total)}টি টেমপ্লেট", center = true)
                    }
                }
            }
        }
    }

    // ── প্রিভিউ ──
    vm.preview?.let { pv ->
        Dialog(onDismissRequest = { vm.closePreview() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(16.dp)) {
                Text(pv.name, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    val b = pv.bmp
                    when {
                        b != null -> Image(b.asImageBitmap(), contentDescription = pv.name, modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                        pv.err != null -> Text("⚠️ " + pv.err, color = C.Red, fontSize = 14.sp)
                        else -> CircularProgressIndicator(color = C.Red, modifier = Modifier.padding(40.dp))
                    }
                }
                HSmall("এটা আপনার লোগো ও নমুনা লেখা দিয়ে আঁকা প্রিভিউ।", Modifier.padding(top = 6.dp), center = true)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val has = pv.id in vm.downloaded
                    if (!admin) HBtn(if (has) "✅ ডাউনলোড করা আছে" else if (vm.busyId == pv.id) "⏳ নামছে…" else "⬇️ ডাউনলোড", Modifier.weight(1f), color = C.Green, enabled = !has && vm.busyId == null && pv.s != null) { vm.download(pv.id, pv.name, pv.at, onChanged) }
                    HBtn("✕ বন্ধ", Modifier.weight(1f), color = C.Gray) { vm.closePreview() }
                }
            }
        }
    }

    // ── অ্যাডমিন: নাম জিজ্ঞেস → এডিটর ──
    nameAsk?.let { (id, name0, s) ->
        var name by remember(id, name0) { mutableStateOf(name0) }
        AlertDialog(
            onDismissRequest = { nameAsk = null },
            title = { Text(if (id == null) (if (quoteTab) "নতুন বক্তব্য কার্ড টেমপ্লেট" else "নতুন শেয়ার্ড টেমপ্লেট") else "টেমপ্লেটের নাম") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("টেমপ্লেটের নাম (ইউজাররা এটাই দেখবে)") }, modifier = Modifier.fillMaxWidth())
                    HSmall("ছবি যোগ করলে ছোট ছবি দিন — সবটা ১.৫ MB-এর মধ্যে হতে হবে।", Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    nameAsk = null
                    scope.launch { vm.prepare(); session = EditSession(id, name.trim(), s) }
                }) { Text("এডিটর খুলুন") }
            },
            dismissButton = { TextButton(onClick = { nameAsk = null }) { Text("বাতিল") } },
        )
    }
    session?.let { ss ->
        TplEditorDialog(
            vm, ss.id ?: "sh_newtpl", ss.initial,
            (if (quoteTab) "বক্তব্য কার্ড — " else "") + (if (ss.id == null) "➕ নতুন শেয়ার্ড টেমপ্লেট" else "✏️ শেয়ার্ড টেমপ্লেট এডিট — " + ss.name),
            "✔ সেভ করে ইউজারদের জন্য দিন",
            { _, layout, ts, done -> vm.adminSave(ss.id, ss.name, layout, ts, done) },
        ) { session = null }
    }
    if (importAsk) AlertDialog(
        onDismissRequest = { importAsk = false },
        text = { Text("অ্যাপের ভেতরের রেডিমেড " + (if (quoteTab) "বক্তব্য কার্ড" else "ফোটোকার্ড") + " টেমপ্লেটের মধ্যে যেগুলো স্টোরে এখনো নেই শুধু সেগুলো যোগ হবে। যেগুলো আগেই আছে সেগুলো আবার যোগ হবে না। চালিয়ে যাবেন?") },
        confirmButton = { TextButton(onClick = { importAsk = false; vm.importReady() }) { Text("হ্যাঁ, যোগ করুন") } },
        dismissButton = { TextButton(onClick = { importAsk = false }) { Text("না") } },
    )
    delAsk?.let { it ->
        AlertDialog(
            onDismissRequest = { delAsk = null },
            text = { Text("\"" + it.name + "\" সব ইউজারের স্টোর থেকে মুছে ফেলবেন? (যাদের ফোনে ডাউনলোড করা আছে, তাদের কপি থাকবে।)") },
            confirmButton = { TextButton(onClick = { delAsk = null; vm.adminDelete(it.id) }) { Text("হ্যাঁ, মুছুন") } },
            dismissButton = { TextButton(onClick = { delAsk = null }) { Text("না") } },
        )
    }
}

@Composable
private fun StoreRow(it: SharedApi.Item, vm: TemplateStoreViewModel, admin: Boolean, onChanged: () -> Unit, onEdit: (SharedApi.Item) -> Unit, onDelete: () -> Unit) {
    val has = it.id in vm.downloaded
    val busy = vm.busyId == it.id
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, Color(0xFFE3E3E3), RoundedCornerShape(10.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val b = it.thumb
        Box(Modifier.width(84.dp).aspectRatio(if (b != null) b.width.toFloat() / b.height else 4f / 5f).clip(RoundedCornerShape(6.dp)).background(Color(0xFFDDDDDD))) {
            if (b != null) Image(b.asImageBitmap(), contentDescription = it.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            Text(it.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (!admin && has) HSmall("✅ ফোনে আছে", Modifier.padding(top = 2.dp), color = C.Green)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HBtn("👁 প্রিভিউ", color = C.Blue, padH = 10.dp, padV = 6.dp, size = 13.sp, enabled = !busy) { vm.openPreview(it) }
                if (!admin) {
                    if (has) HBtn("🗑 সরান", color = C.Gray, padH = 10.dp, padV = 6.dp, size = 13.sp, enabled = !busy) { vm.removeLocal(it.id, onChanged) }
                    else HBtn(if (busy) "⏳…" else "⬇️ ডাউনলোড", color = C.Green, padH = 10.dp, padV = 6.dp, size = 13.sp, enabled = vm.busyId == null) { vm.download(it.id, it.name, it.at, onChanged) }
                }
            }
            if (admin) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HBtn(if (busy) "⏳…" else "✏️ এডিট", color = C.Gray, padH = 10.dp, padV = 6.dp, size = 13.sp, enabled = vm.busyId == null) { onEdit(it) }
                HBtn("🗑 মুছুন", color = C.Red, padH = 10.dp, padV = 6.dp, size = 13.sp, enabled = vm.busyId == null, onClick = onDelete)
            }
        }
    }
}
