package com.digitalcrafting25.newsapp

import android.app.Application
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** একটা অফার: price টাকায় কিনলে মেয়াদ (days) দিনের মধ্যে প্রতিটা কাজের দামে disc% ছাড় */
data class OfferItem(val id: String, val name: String, val desc: String, val price: Int, val disc: Int, val days: Int, val active: Boolean, val badge: String = "")

/** ইউজারের কেনা চলমান অফার: left = বাকি টাকা; unl = আনলিমিটেড এক্সেস (১০০% ছাড়: টাকা কাটে না, শুধু মেয়াদ) */
data class PoolItem(val name: String, val left: Double, val disc: Int, val exp: Long, val unl: Boolean = false)

fun parsePools(p: org.json.JSONArray?): List<PoolItem> = buildList {
    if (p != null) for (i in 0 until p.length()) {
        val o = p.optJSONObject(i) ?: continue
        add(PoolItem(o.optString("name"), o.optDouble("left", 0.0), o.optInt("disc"), o.optLong("exp"), o.optBoolean("unl", o.optInt("disc") >= 100)))
    }
}

/** মেয়াদের বাকি দিন (আজ শেষ হলেও কমপক্ষে ১) */
fun daysLeft(exp: Long): Int = ((exp - System.currentTimeMillis() + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(1)

class OffersViewModel(private val app: Application) : AndroidViewModel(app) {
    var offers by mutableStateOf<List<OfferItem>>(emptyList())
        private set
    var pools by mutableStateOf<List<PoolItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var pendingOffer by mutableStateOf(false)   // অফার কেনার অনুরোধ অনুমোদনের অপেক্ষায়
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var msg by mutableStateOf<String?>(null)

    /** ওপরের বারের অফার আইকনে দেখানো সংখ্যা = চালু অফার */
    val count: Int get() = offers.count { it.active }

    fun reset() { offers = emptyList(); pools = emptyList(); pendingOffer = false; loaded = false; error = null; msg = null }

    private fun parse(j: JSONObject) {
        val a = j.optJSONArray("offers")
        offers = buildList {
            if (a != null) for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                add(OfferItem(o.optString("id"), o.optString("name"), o.optString("desc"), o.optInt("price"), o.optInt("disc"), o.optInt("days"), o.optBoolean("active", true), o.optString("badge")))
            }
        }
        pools = parsePools(j.optJSONArray("pools"))
        pendingOffer = j.optBoolean("pendingOffer", false)
    }

    /** all = true হলে বন্ধ করা অফারসহ সব (শুধু অ্যাডমিন) */
    fun load(all: Boolean = false) {
        if (loading) return
        viewModelScope.launch {
            loading = true; error = null
            try {
                parse(Worker.post(app, "/offers-get", JSONObject().put("all", all), 20000))
                loaded = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "অফার আনা যায়নি"
            } finally { loading = false }
        }
    }

    /** অ্যাডমিন: নতুন অফার (id = null) বা আগেরটা বদল */
    fun save(id: String?, name: String, desc: String, price: String, disc: String, days: String, active: Boolean, badge: String, onDone: () -> Unit) {
        if (saving) return
        viewModelScope.launch {
            saving = true; error = null; msg = null
            try {
                val body = JSONObject().put("name", name).put("desc", desc).put("price", price).put("disc", disc).put("days", days).put("active", active).put("badge", badge.trim())
                if (!id.isNullOrEmpty()) body.put("id", id)
                parse(Worker.post(app, "/offer-set", body, 30000))
                msg = if (id.isNullOrEmpty()) "✅ নতুন অফার যোগ হয়েছে। ইউজাররা অফার আইকনে দেখবে" else "✅ অফার বদলানো হয়েছে"
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "সেভ করা যায়নি"
            } finally { saving = false }
        }
    }

    fun remove(id: String) {
        if (saving) return
        viewModelScope.launch {
            saving = true; error = null; msg = null
            try {
                parse(Worker.post(app, "/offer-del", JSONObject().put("id", id), 30000))
                msg = "🗑️ অফার সরানো হয়েছে (যারা আগেই কিনেছে তাদের অফার ও টাকা অক্ষত থাকবে)"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "সরানো যায়নি"
            } finally { saving = false }
        }
    }
}

private fun offerTerms(o: OfferItem): String =
    if (o.disc >= 100) "৳${Bn.num(o.price)} একবারে কিনলে ${o.days} দিন সব কাজ আনলিমিটেড (কোনো টাকা কাটবে না)। মেয়াদ শেষ হলে আগের মতো পুরো রেটে মেইন ব্যালেন্স থেকে কাটবে; কোনো টাকা ফেরত বা ব্যালেন্সে যোগ হবে না।"
    else "৳${Bn.num(o.price)} একবারে কিনলে ${o.days} দিনের মধ্যে ওই টাকার প্রতিটা খরচে ${o.disc}% ছাড় (যেমন ১০ টাকার কাজে ${Bn.num(10.0 * (100 - o.disc) / 100)} টাকা কাটবে)। " +
        "টাকা শেষ হলে আগের মতো পুরো রেটে কাটবে। ${o.days} দিনে না ফুরালে বাকি টাকা মেইন ব্যালেন্সে যোগ হবে (সেখানে পুরো রেটে কাটবে)।"

private fun dateBn(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.Builder().setLanguage("bn").setRegion("BD").build()).format(Date(ms))

/** ইউজারের একটা চলমান অফার: নাম, অফার ব্যালেন্স (টাকা), ছাড়, মেয়াদ। আনলিমিটেডে শুধু "আনলিমিটেড এক্সেস" ও কত দিন বাকি */
@Composable
fun OfferPoolLine(p: PoolItem) {
    Text(p.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    if (p.unl) {
        Text("♾️ আনলিমিটেড এক্সেস", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
        Text("⏳ ${Bn.num(daysLeft(p.exp))} দিন বাকি", fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
    } else {
        Text("৳ " + Bn.num(p.left), color = C.Green, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(top = 4.dp))
        Small("প্রতিটা খরচে ${p.disc}% ছাড় • ⏳ ${Bn.num(daysLeft(p.exp))} দিন বাকি (${dateBn(p.exp)} পর্যন্ত)। না ফুরালে বাকি টাকা মেইন ব্যালেন্সে যোগ হবে।", Modifier.padding(top = 2.dp))
    }
}

// ───────────────────────── ইউজারের অফার পেইজ (ফেইসবুক স্টোরির ধাঁচে ব্যানার) ─────────────────────────

/** প্রতিটা অফার স্টোরিতে কত মিলিসেকেন্ড থাকবে */
private const val STORY_MS = 10_000L

private val storyBrushes = listOf(
    listOf(Color(0xFFFF512F), Color(0xFFDD2476)),
    listOf(Color(0xFF396AFC), Color(0xFF2948FF)),
    listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
    listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)),
    listOf(Color(0xFFF7971E), Color(0xFFFF5E62)),
    listOf(Color(0xFF0F2027), Color(0xFF2C5364)),
)

/** স্টোরির একটা পেইজ: রঙিন ব্যাকগ্রাউন্ড, বড় ছাড়ের লেখা, নাম, বর্ণনা, দাম/মেয়াদ ও কেনার বাটন */
@Composable
private fun StorySlide(o: OfferItem, i: Int, blockedMsg: String?, onTerms: () -> Unit, onBuy: () -> Unit) {
    val blocked = blockedMsg != null
    val cols = storyBrushes[i % storyBrushes.size]
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(cols))) {
        // সাজানো গোল আকৃতি
        Box(Modifier.size(260.dp).offset(x = 150.dp, y = (-70).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)))
        Box(Modifier.size(200.dp).offset(x = (-80).dp, y = 330.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)))
        Box(Modifier.size(90.dp).offset(x = 270.dp, y = 250.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))

        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 52.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text("🎁 " + o.badge.ifBlank { "বিশেষ অফার" }, color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.20f)).padding(horizontal = 14.dp, vertical = 5.dp))
            Spacer(Modifier.height(14.dp))
            if (o.disc >= 100) {
                Text("♾️", fontSize = 72.sp, textAlign = TextAlign.Center)
                Text("আনলিমিটেড", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                Text("সব কাজ ${Bn.num(o.days)} দিন", color = Color.White.copy(alpha = 0.95f), fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${Bn.num(o.disc)}%", color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Black, lineHeight = 92.sp)
                    Text(" ছাড়", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
                }
                Text("প্রতিটা কাজের দামে", color = Color.White.copy(alpha = 0.95f), fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Text(o.name, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            if (o.desc.isNotBlank()) Text(o.desc, color = Color.White.copy(alpha = 0.90f), fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("৳${Bn.num(o.price)} একবারে", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.25f)).padding(horizontal = 12.dp, vertical = 6.dp))
                Text("⏳ ${Bn.num(o.days)} দিন", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.25f)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Text("শর্তাবলী", color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, textDecoration = TextDecoration.Underline,
                modifier = Modifier.padding(top = 12.dp).clickable(onClick = onTerms).padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1f))
            if (blockedMsg != null) Text(blockedMsg, color = Color.White, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (blocked) Color.White.copy(alpha = 0.40f) else Color.White)
                    .clickable(enabled = !blocked, onClick = onBuy)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("🛒 কিনুন (৳${Bn.num(o.price)})", color = if (blocked) Color.White else cols[0], fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/**
 * ফেইসবুক স্টোরির মতো অফার ব্যানার: ওপরে সেগমেন্ট বার, প্রতিটা অফার ১০ সেকেন্ড করে।
 * শেষ অফারে এসে আটকে থাকে; একটাই অফার থাকলে কখনো বদলায় না।
 * ডানে চাপ = পরের, বাঁয়ে চাপ = আগের, চেপে ধরলে থামে।
 */
@Composable
private fun OfferStory(list: List<OfferItem>, blockedMsg: String?, hold: Boolean, modifier: Modifier, onTerms: (OfferItem) -> Unit, onBuy: (OfferItem) -> Unit) {
    val n = list.size
    var idx by remember { mutableStateOf(0) }
    var elapsed by remember { mutableStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    val cur = idx.coerceIn(0, (n - 1).coerceAtLeast(0))
    if (cur != idx) { idx = cur; elapsed = 0L }

    // টাইমার: একটাই অফার থাকলে বা থামানো থাকলে চলে না; শেষ অফারে পৌঁছে ১০ সেকেন্ড পর থেমে যায়, আর এগোয় না
    LaunchedEffect(cur, n, paused, hold) {
        if (n <= 1 || paused || hold) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (elapsed < STORY_MS) {
            val t = withFrameMillis { it }
            elapsed += t - last
            last = t
        }
        elapsed = STORY_MS
        if (cur < n - 1) { idx = cur + 1; elapsed = 0L }
    }

    fun go(i: Int) { idx = i; elapsed = 0L }

    Box(modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFF222222))) {
        // টাচ স্তর (সবার নিচে, যাতে কেনার বাটন নিজের চাপ পায়)
        Box(
            Modifier.matchParentSize().pointerInput(n, cur) {
                detectTapGestures(
                    onPress = { paused = true; tryAwaitRelease(); paused = false },
                    onTap = { pos ->
                        if (pos.x < size.width * 0.33f) { if (cur > 0) go(cur - 1) else elapsed = 0L }
                        else { if (cur < n - 1) go(cur + 1) else elapsed = STORY_MS }
                    },
                )
            }
        )
        Crossfade(targetState = cur, animationSpec = androidx.compose.animation.core.tween(350), label = "offerStory") { i ->
            val o = list.getOrNull(i)
            if (o != null) StorySlide(o, i, blockedMsg, { onTerms(o) }) { onBuy(o) }
        }
        // ওপরের সেগমেন্ট বার
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until n) {
                val f = when {
                    n <= 1 || i < cur -> 1f
                    i == cur -> (elapsed.toFloat() / STORY_MS).coerceIn(0f, 1f)
                    else -> 0f
                }
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.35f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White))
                }
            }
        }
    }
}

@Composable
fun OffersScreen(vm: OffersViewModel, dash: DashboardViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.load() }
    val st by dash.state.collectAsState()
    var buy by remember { mutableStateOf<OfferItem?>(null) }
    var terms by remember { mutableStateOf<OfferItem?>(null) }
    // স্ক্রল নেই: ব্যানার পুরো পেইজ জুড়ে। ফেরা = ফোনের ব্যাক (MainActivity-র BackHandler)
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.error?.let { StatusLine(false, "⚠️ $it") }
        if (vm.loading && !vm.loaded) Text("লোড হচ্ছে...", fontSize = 14.sp, color = C.Muted)

        // একটা অফার শেষ না হলে (বা কেনার অনুরোধ অপেক্ষায় থাকলে) আরেকটা কেনা যায় না
        val blockedMsg: String? = if (vm.pools.isNotEmpty()) "আপনার অফার চলছে। সেটা শেষ হলে আরেকটা কিনতে পারবেন।"
            else if (vm.pendingOffer) "আপনার অফার কেনার অনুরোধ অনুমোদনের অপেক্ষায় আছে।" else null
        val list = remember(vm.offers) { vm.offers.filter { it.active } }
        if (vm.loaded && list.isEmpty()) Text("এখন কোনো অফার নেই", fontSize = 14.sp, color = C.Muted)
        if (list.isNotEmpty()) OfferStory(list, blockedMsg, terms != null, Modifier.weight(1f).fillMaxWidth(), onTerms = { terms = it }) { buy = it }
    }
    buy?.let { o -> TopupDialog(dash, st.balance, onClose = { buy = null; vm.load() }, offer = o) }
    terms?.let { o ->
        AlertDialog(
            onDismissRequest = { terms = null },
            title = { Text("শর্তাবলী") },
            text = { Text(offerTerms(o), fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { terms = null }) { Text("ঠিক আছে", fontWeight = FontWeight.Bold) } },
        )
    }
}

// ───────────────────────── অ্যাডমিন অ্যাপের "অফার" মেনু ─────────────────────────

@Composable
fun AdminOffersScreen(vm: OffersViewModel) {
    LaunchedEffect(Unit) { vm.load(all = true) }
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("3000") }
    var disc by remember { mutableStateOf("50") }
    var days by remember { mutableStateOf("30") }
    var active by remember { mutableStateOf(true) }
    var badge by remember { mutableStateOf("") }
    var del by remember { mutableStateOf<OfferItem?>(null) }
    val clear = { editId = null; name = ""; desc = ""; price = "3000"; disc = "50"; days = "30"; active = true; badge = "" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        H1("🎁 অফার")
        Small("এখানে বানানো অফার ইউজারদের অ্যাপে ওপরের 🎁 আইকনে দেখায়। ইউজার অফারের বাটন থেকে কিনলে (দামের সমান টাকা পাঠালে) অনুমোদনে বা বিকাশ SMS মিললে অটো ওই টাকা অফার-ব্যালেন্সে বসে।")
        vm.error?.let { StatusLine(false, "⚠️ $it (Worker এ /offers-get ডিপ্লয় করা আছে কিনা দেখুন)") }
        vm.msg?.let { StatusLine(true, it) }
        if (vm.loading) Text("লোড হচ্ছে...", fontSize = 14.sp, color = C.Muted)

        InfoCard {
            Text(if (editId == null) "➕ নতুন অফার" else "✏️ অফার বদলান", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            FieldLabel("ব্যানারের ট্যাগ (ফাঁকা রাখলে \"বিশেষ অফার\" দেখাবে)")
            AppField(badge, { badge = it.take(24) }, placeholder = "বিশেষ অফার")
            FieldLabel("অফারের নাম")
            AppField(name, { name = it.take(40) }, placeholder = "যেমন ৩০০০ টাকায় ৫০% ছাড়")
            FieldLabel("বর্ণনা (ঐচ্ছিক)")
            AppField(desc, { desc = it.take(200) })
            FieldLabel("দাম (৳, একবারে কিনতে হবে)")
            AppField(price, { price = it.filter(Char::isDigit).take(6) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            FieldLabel("প্রতিটা খরচে ছাড় (%) — ১০০ দিলে আনলিমিটেড এক্সেস")
            AppField(disc, { disc = it.filter(Char::isDigit).take(3) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            FieldLabel("মেয়াদ (দিন)")
            AppField(days, { days = it.filter(Char::isDigit).take(3) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = active, onCheckedChange = { active = it })
                Text("চালু (ইউজাররা দেখবে ও কিনতে পারবে)", fontSize = 14.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn(if (vm.saving) "অপেক্ষা করুন..." else if (editId == null) "➕ অফার যোগ করুন" else "💾 সেভ করুন", Modifier.weight(1f), color = C.Green, enabled = !vm.saving) {
                    vm.save(editId, name, desc, price, disc, days, active, badge) { clear() }
                }
                if (editId != null) AppBtn("বাতিল", Modifier.weight(1f), color = C.Gray, enabled = !vm.saving) { clear() }
            }
        }

        H2("সব অফার (${Bn.num(vm.offers.size)})")
        if (vm.loaded && vm.offers.isEmpty()) Text("কোনো অফার নেই", fontSize = 14.sp, color = C.Muted)
        vm.offers.forEach { o ->
            InfoCard {
                Text(o.name + if (o.active) "" else "  (বন্ধ)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (o.active) Color.Unspecified else C.Muted)
                Small("৳${Bn.num(o.price)} • " + (if (o.disc >= 100) "♾️ আনলিমিটেড এক্সেস (১০০% ছাড়, কোনো টাকা কাটে না)" else "${o.disc}% ছাড়") + " • ${o.days} দিন" + if (o.desc.isNotBlank()) "\n${o.desc}" else "", Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppBtn("✏️ বদলান", Modifier.weight(1f), color = C.Blue, small = true, enabled = !vm.saving) {
                        editId = o.id; name = o.name; desc = o.desc; price = o.price.toString(); disc = o.disc.toString(); days = o.days.toString(); active = o.active; badge = o.badge
                    }
                    AppBtn("🗑️ রিমুভ", Modifier.weight(1f), color = C.Red, small = true, enabled = !vm.saving) { del = o }
                }
            }
        }
        AppBtn(if (vm.loading) "লোড হচ্ছে..." else "🔄 আবার আনুন", Modifier.fillMaxWidth(), color = C.Gray, enabled = !vm.loading) { vm.load(all = true) }
        Spacer(Modifier.height(20.dp))
    }
    del?.let { o ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("অফার রিমুভ করবেন?") },
            text = { Text("\"${o.name}\" আর ইউজাররা দেখবে না। যারা আগেই কিনেছে তাদের অফার-ব্যালেন্স ও ছাড় মেয়াদ পর্যন্ত চলবে।") },
            confirmButton = { TextButton(onClick = { vm.remove(o.id); if (editId == o.id) clear(); del = null }) { Text("হ্যাঁ, রিমুভ", color = C.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("না") } },
        )
    }
}
