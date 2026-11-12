package com.digitalcrafting25.newsapp

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * অ্যাডমিন অ্যাপ (flavor = admin): নতুন পোস্ট, ফোটোকার্ড, সিডিউল ও ইউজারের সেটিংস কিছুই নেই।
 * আছে: 📊 হিসাব (ইউজার সংখ্যা, কে কত পোস্ট করল, কে কত টপ-আপ করল), 💳 ব্যালেন্স (SMS অটো কনফার্ম + হাতে অনুমোদন),
 * 💰 রেট (সব কাজের দাম কম-বেশি করা), ⚙️ সেটিংস (AI কী, অটো শুভ সকাল, আপডেট)। সব তথ্য Worker-এর /admin-stats থেকে; শুধু OWNER_EMAIL-এর অ্যাকাউন্ট পায়।
 */
data class AdminUser(
    val uid: String, val email: String, val owner: Boolean,
    val posts: Int, val cards: Int, val today: Int, val month: Int, val last: Long,
    val topup: Double, val topupN: Int, val bal: Double?, val pending: Int,
)

data class AdminTopup(val email: String, val amount: Double, val at: Long, val auto: Boolean, val trx: String)

class AdminViewModel(private val app: Application) : AndroidViewModel(app) {
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** null = এখনো জানা নেই; false = এই অ্যাকাউন্ট অ্যাডমিন নয় */
    var isAdmin by mutableStateOf<Boolean?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var users by mutableStateOf(0)
        private set
    var posts by mutableStateOf(0)
        private set
    var cards by mutableStateOf(0)
        private set
    var today by mutableStateOf(0)
        private set
    var month by mutableStateOf(0)
        private set
    var topup by mutableStateOf(0.0)
        private set
    var topupN by mutableStateOf(0)
        private set
    var balance by mutableStateOf(0.0)
        private set
    var pending by mutableStateOf(0)
        private set
    var list by mutableStateOf<List<AdminUser>>(emptyList())
        private set
    var topups by mutableStateOf<List<AdminTopup>>(emptyList())
        private set

    fun reset() {
        loading = false; error = null; isAdmin = null; loaded = false
        users = 0; posts = 0; cards = 0; today = 0; month = 0; topup = 0.0; topupN = 0; balance = 0.0; pending = 0
        list = emptyList(); topups = emptyList()
    }

    fun load() {
        if (loading) return
        loading = true; error = null
        viewModelScope.launch {
            try {
                val w = Worker.post(app, "/whoami", JSONObject(), 15000)
                isAdmin = w.optBoolean("owner", false)
                if (isAdmin != true) return@launch
                val d = Worker.post(app, "/admin-stats", JSONObject(), 60000)
                users = d.optInt("users"); posts = d.optInt("posts"); cards = d.optInt("cards")
                today = d.optInt("today"); month = d.optInt("month")
                topup = d.optDouble("topup", 0.0); topupN = d.optInt("topupN")
                balance = d.optDouble("balance", 0.0); pending = d.optInt("pending")
                val a = d.optJSONArray("list") ?: JSONArray()
                list = List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    AdminUser(
                        uid = o.optString("uid"), email = o.optString("email"), owner = o.optBoolean("owner"),
                        posts = o.optInt("posts"), cards = o.optInt("cards"), today = o.optInt("today"), month = o.optInt("month"),
                        last = o.optLong("last"), topup = o.optDouble("topup", 0.0), topupN = o.optInt("topupN"),
                        bal = if (o.isNull("bal")) null else o.optDouble("bal"), pending = o.optInt("pending"),
                    )
                }
                val t = d.optJSONArray("topups") ?: JSONArray()
                topups = List(t.length()) { i ->
                    val o = t.getJSONObject(i)
                    AdminTopup(o.optString("email"), o.optDouble("amount", 0.0), o.optLong("at"), o.optBoolean("auto"), o.optString("trx"))
                }
                loaded = true
            } catch (e: Exception) {
                error = e.message ?: "হিসাব আনা যায়নি"
            } finally {
                loading = false
            }
        }
    }
}

private fun taka(v: Double): String = "৳" + Bn.num(Math.round(v))

@Composable
fun AdminRoot(auth: AuthViewModel, uv: UpdateViewModel, sv: SettingsViewModel, autoVm: AutoPostViewModel, online: Boolean = true) {
    val loggedIn by auth.loggedIn.collectAsStateWithLifecycle()
    if (!loggedIn) {
        LoginScreen(auth)
        return
    }
    val avm: AdminViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val rvm: RatesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val tsvm: TemplateStoreViewModel = androidx.lifecycle.viewmodel.compose.viewModel()   // 🎨 শেয়ার্ড টেমপ্লেট (অ্যাডমিন বানায়/মোছে)
    val upd by uv.state.collectAsStateWithLifecycle()
    LaunchedEffect(online) { if (online) uv.autoCheck() }
    LaunchedEffect(loggedIn) { if (!avm.loaded && !avm.loading) avm.load() }
    val logout = { avm.reset(); rvm.reset(); tsvm.reset(); sv.reset(); autoVm.reset(); auth.logout() }

    if (upd.showPopup && upd.available != null) {
        val info = upd.available!!
        AlertDialog(
            onDismissRequest = { if (!upd.busy) uv.dismissPopup() },
            title = { Text("🎉 নতুন আপডেট এসেছে") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("নতুন সংস্করণ: ${info.versionName}")
                    upd.progress?.let { p ->
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("ডাউনলোড হচ্ছে… ${(p * 100).toInt()}%")
                    }
                    if (upd.progress == null && upd.message.startsWith("❌")) Text(upd.message)
                    if (upd.downloaded) Text("✅ ডাউনলোড শেষ। ফোনের Download ফোল্ডারে APK সেভ হয়েছে। নিচের বাটনে ফোল্ডার খুলে APK-তে ট্যাপ করে ইন্সটল করুন (বলা হলে ‘এই উৎস থেকে অনুমতি’ দিন)।")
                }
            },
            confirmButton = {
                if (upd.downloaded) Button(onClick = { uv.openDownloads() }) { Text("📂 Download ফোল্ডার খুলুন") }
                else Button(onClick = { uv.startDownload() }, enabled = !upd.busy) { Text(if (upd.busy) "ডাউনলোড হচ্ছে…" else "⬇️ ডাউনলোড") }
            },
            dismissButton = { if (!upd.busy) TextButton(onClick = { uv.dismissPopup() }) { Text(if (upd.downloaded) "বন্ধ" else "পরে") } },
        )
    }

    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        AdminTopBar(onLogout = logout)
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when {
                avm.isAdmin == false -> NotAdmin(onLogout = logout)
                avm.isAdmin == null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(avm.error?.let { "⚠️ $it" } ?: "লোড হচ্ছে...", textAlign = TextAlign.Center, color = if (avm.error != null) C.Red else C.Muted)
                    if (avm.error != null) AppBtn("🔄 আবার চেষ্টা করুন", Modifier.padding(top = 16.dp), color = C.Gray, enabled = !avm.loading) { avm.load() }
                }
                tab == 0 -> AdminStatsScreen(avm)
                tab == 1 -> AdminWalletScreen(sv)
                tab == 2 -> AdminRatesScreen(rvm)
                tab == 3 -> TemplateStoreScreen(tsvm, admin = true, onClose = null, onChanged = {})
                else -> AdminSettingsScreen(sv, uv, autoVm)
            }
        }
        if (avm.isAdmin == true) AdminBottomBar(tab) { tab = it }
    }
}

@Composable
private fun AdminTopBar(onLogout: () -> Unit) {
    Column(Modifier.background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🛡️ নিউজ অ্যাডমিন", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            AppBtn("লগআউট", color = C.Gray, small = true, onClick = onLogout)
        }
        HorizontalDivider(color = Color(0xFFE3E3E3))
    }
}

@Composable
private fun AdminBottomBar(current: Int, onSelect: (Int) -> Unit) {
    val items = listOf("📊" to "হিসাব", "💳" to "ব্যালেন্স", "💰" to "রেট", "🎨" to "টেমপ্লেট", "⚙️" to "সেটিংস")
    Column(Modifier.background(Color.White)) {
        HorizontalDivider(color = C.NavLine)
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, (emoji, label) ->
                Column(
                    Modifier.weight(1f).clickable { onSelect(i) }.padding(top = 8.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(emoji, fontSize = 20.sp)
                    Text(label, fontSize = 12.sp, color = if (current == i) C.Red else C.Muted, fontWeight = if (current == i) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun NotAdmin(onLogout: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🔒", fontSize = 48.sp)
        Text("এই অ্যাপ শুধু অ্যাডমিনের জন্য", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp), textAlign = TextAlign.Center)
        Text("এই অ্যাকাউন্টটি অ্যাডমিন নয়। সাধারণ অ্যাপ ব্যবহার করুন বা অ্যাডমিনের অ্যাকাউন্টে লগইন করুন।", color = C.Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        AppBtn("লগআউট", Modifier.padding(top = 20.dp), color = C.Gray, onClick = onLogout)
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 12.sp, color = C.Muted)
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 📊 হিসাব: মোট সংখ্যা + ইউজারভিত্তিক তালিকা (পোস্ট / টপ-আপ / ব্যালেন্স / শেষ দেখা দিয়ে সাজানো) + সাম্প্রতিক টপ-আপ */
@Composable
fun AdminStatsScreen(vm: AdminViewModel) {
    var sort by rememberSaveable { mutableStateOf("posts") }
    val sorted = remember(vm.list, sort) {
        when (sort) {
            "topup" -> vm.list.sortedByDescending { it.topup }
            "bal" -> vm.list.sortedByDescending { it.bal ?: -1.0e18 }
            "last" -> vm.list.sortedByDescending { it.last }
            else -> vm.list.sortedByDescending { it.posts }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        H1("📊 হিসাব")
        vm.error?.let { StatusLine(false, "⚠️ $it") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("👥 ইউজার", Bn.num(vm.users), Modifier.weight(1f))
            StatBox("📝 মোট পোস্ট", Bn.num(vm.posts), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("📅 আজকের পোস্ট", Bn.num(vm.today), Modifier.weight(1f))
            StatBox("🗓️ এই মাসের পোস্ট", Bn.num(vm.month), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("💰 মোট টপ-আপ", taka(vm.topup) + " (" + Bn.num(vm.topupN) + " বার)", Modifier.weight(1f))
            StatBox("👛 ইউজারদের ব্যালেন্স", taka(vm.balance), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("⏳ অপেক্ষমাণ অনুরোধ", Bn.num(vm.pending), Modifier.weight(1f))
            StatBox("🖼️ ফোটোকার্ড পোস্ট", Bn.num(vm.cards), Modifier.weight(1f))
        }
        AppBtn(if (vm.loading) "লোড হচ্ছে..." else "🔄 রিফ্রেশ", color = C.Gray, enabled = !vm.loading) { vm.load() }
        Small("ℹ️ পোস্টের গোনা এই আপডেটের পর থেকে শুরু। ইউজার তালিকায় তারাই আসে যারা অ্যাপ খুলেছে, সংযোগ দিয়েছে, টপ-আপ করেছে বা ব্যালেন্স আছে।")

        H2("👤 ইউজারের হিসাব")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("posts" to "📝 পোস্ট", "topup" to "💰 টপ-আপ", "bal" to "👛 ব্যালেন্স", "last" to "🕒 শেষ দেখা").forEach { (k, l) ->
                FilterChip(selected = sort == k, onClick = { sort = k }, label = { Text(l) })
            }
        }
        if (sorted.isEmpty()) Text(if (vm.loading) "লোড হচ্ছে..." else "এখনো কোনো ইউজার নেই।", fontSize = 14.sp, color = C.Muted)
        sorted.forEach { u -> AdminUserRow(u) }

        H2("💰 সাম্প্রতিক টপ-আপ")
        if (vm.topups.isEmpty()) Text(if (vm.loading) "লোড হচ্ছে..." else "এখনো কোনো টপ-আপ নেই।", fontSize = 14.sp, color = C.Muted)
        vm.topups.forEach { t ->
            InfoCard {
                Text(t.email.ifBlank { "ইউজার" } + " — " + taka(t.amount), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Small((if (t.auto) "⚡ অটো" else "✋ হাতে") + (if (t.at > 0) " • " + Bn.dateTime(t.at) else "") + (if (t.trx.isNotBlank()) " • TrxID " + t.trx else ""), Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AdminUserRow(u: AdminUser) {
    InfoCard {
        Text(
            u.email.ifBlank { "ইউজার " + u.uid.take(6) } + (if (u.owner) " (অ্যাডমিন)" else ""),
            fontWeight = FontWeight.Bold, fontSize = 14.sp,
        )
        Small(
            "📝 মোট পোস্ট " + Bn.num(u.posts) + " (আজ " + Bn.num(u.today) + " • এই মাসে " + Bn.num(u.month) + ") • 🖼️ ফোটোকার্ড " + Bn.num(u.cards),
            Modifier.padding(top = 4.dp),
        )
        Small(
            "💰 টপ-আপ " + taka(u.topup) + " (" + Bn.num(u.topupN) + " বার) • 👛 ব্যালেন্স " + (u.bal?.let { taka(it) } ?: "—") +
                (if (u.pending > 0) " • ⏳ অপেক্ষায় " + Bn.num(u.pending) else ""),
            Modifier.padding(top = 2.dp),
        )
        if (u.last > 0) Small("শেষ দেখা: " + Bn.dateTime(u.last), Modifier.padding(top = 2.dp))
    }
}
