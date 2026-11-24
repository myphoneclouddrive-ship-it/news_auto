package com.digitalcrafting25.newsapp

import android.app.Application
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
data class OfferItem(val id: String, val name: String, val desc: String, val price: Int, val disc: Int, val days: Int, val active: Boolean)

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
                add(OfferItem(o.optString("id"), o.optString("name"), o.optString("desc"), o.optInt("price"), o.optInt("disc"), o.optInt("days"), o.optBoolean("active", true)))
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
    fun save(id: String?, name: String, desc: String, price: String, disc: String, days: String, active: Boolean, onDone: () -> Unit) {
        if (saving) return
        viewModelScope.launch {
            saving = true; error = null; msg = null
            try {
                val body = JSONObject().put("name", name).put("desc", desc).put("price", price).put("disc", disc).put("days", days).put("active", active)
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

// ───────────────────────── ইউজারের অফার পেইজ (ফুল পেইজ) ─────────────────────────

@Composable
fun OffersScreen(vm: OffersViewModel, dash: DashboardViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.load() }
    val st by dash.state.collectAsState()
    var buy by remember { mutableStateOf<OfferItem?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            H1("🎁 অফার")
            AppBtn("← ফিরুন", color = C.Gray, small = true, onClick = onBack)
        }
        vm.error?.let { StatusLine(false, "⚠️ $it") }
        if (vm.loading && !vm.loaded) Text("লোড হচ্ছে...", fontSize = 14.sp, color = C.Muted)

        if (vm.pools.isNotEmpty()) {
            H2("আপনার চলমান অফার")
            vm.pools.forEach { p -> InfoCard { OfferPoolLine(p) } }
        }

        H2("কেনার জন্য অফার")
        val list = vm.offers.filter { it.active }
        if (vm.loaded && list.isEmpty()) Text("এখন কোনো অফার নেই", fontSize = 14.sp, color = C.Muted)
        list.forEach { o ->
            InfoCard {
                Text(o.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                if (o.desc.isNotBlank()) Text(o.desc, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                Text(if (o.disc >= 100) "৳${Bn.num(o.price)} → ♾️ আনলিমিটেড এক্সেস, ${o.days} দিন" else "৳${Bn.num(o.price)} → ${o.disc}% ছাড়, ${o.days} দিন", color = C.Red, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Small(offerTerms(o), Modifier.padding(top = 4.dp))
                // একটা অফার শেষ না হলে (বা কেনার অনুরোধ অপেক্ষায় থাকলে) আরেকটা কেনা যায় না
                val blocked = vm.pools.isNotEmpty() || vm.pendingOffer
                AppBtn("🛒 কিনুন (৳${Bn.num(o.price)})", Modifier.fillMaxWidth().padding(top = 10.dp), color = C.Green, enabled = !blocked) { buy = o }
                if (blocked) Small(if (vm.pools.isNotEmpty()) "আপনার অফার চলছে। সেটা শেষ (টাকা ফুরানো বা মেয়াদ শেষ) হলে আরেকটা কিনতে পারবেন।" else "আপনার অফার কেনার অনুরোধ অনুমোদনের অপেক্ষায় আছে।", Modifier.padding(top = 4.dp), color = C.Red)
            }
        }
        AppBtn(if (vm.loading) "অপেক্ষা করুন..." else "🔄 রিফ্রেশ", Modifier.fillMaxWidth(), color = C.Gray, enabled = !vm.loading) { vm.load() }
        Spacer(Modifier.height(16.dp))
    }
    buy?.let { o -> TopupDialog(dash, st.balance, onClose = { buy = null; vm.load() }, offer = o) }
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
    var del by remember { mutableStateOf<OfferItem?>(null) }
    val clear = { editId = null; name = ""; desc = ""; price = "3000"; disc = "50"; days = "30"; active = true }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        H1("🎁 অফার")
        Small("এখানে বানানো অফার ইউজারদের অ্যাপে ওপরের 🎁 আইকনে দেখায়। ইউজার অফারের বাটন থেকে কিনলে (দামের সমান টাকা পাঠালে) অনুমোদনে বা বিকাশ SMS মিললে অটো ওই টাকা অফার-ব্যালেন্সে বসে।")
        vm.error?.let { StatusLine(false, "⚠️ $it (Worker এ /offers-get ডিপ্লয় করা আছে কিনা দেখুন)") }
        vm.msg?.let { StatusLine(true, it) }
        if (vm.loading) Text("লোড হচ্ছে...", fontSize = 14.sp, color = C.Muted)

        InfoCard {
            Text(if (editId == null) "➕ নতুন অফার" else "✏️ অফার বদলান", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                    vm.save(editId, name, desc, price, disc, days, active) { clear() }
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
                        editId = o.id; name = o.name; desc = o.desc; price = o.price.toString(); disc = o.disc.toString(); days = o.days.toString(); active = o.active
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
