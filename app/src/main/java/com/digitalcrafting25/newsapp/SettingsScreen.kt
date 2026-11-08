package com.digitalcrafting25.newsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Amber = Color(0xFFB26A00)

/** "হ্যাঁ/না" নিশ্চিতকরণ (ওয়েবের confirm()) */
private class Ask(val text: String, val onYes: () -> Unit)

/** ওয়েবের `#p-settings .card.acc`: শিরোনামে চাপলে খোলে/বন্ধ হয়, ডানে ▾/▴ */
@Composable
private fun AccCard(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(if (open) "▴" else "▾", color = Color(0xFF888888), fontSize = 18.sp)
            }
            if (open) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                Column(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun NoticeBar(text: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), fontSize = 14.sp)
        TextButton(onClick = onClose) { Text("✕", color = C.Gray) }
    }
}

/** ওয়েবের `.cr-grp`: কার্ডের ভেতরের উপ-অংশ, ওপরে হালকা রেখা */
@Composable
internal fun CrGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), content = content)
    }
}

/**
 * ওয়েবের ⚙️ সেটিংস পেইজ: এক পেইজে সব কার্ড (অ্যাকর্ডিয়ন, শুরুতে সব বন্ধ, একটা খুললে বাকিগুলো বন্ধ)
 * আর একদম নিচে একটাই "💾 সেটিংস সেভ করুন"। কার্ডের ক্রম ওয়েবের মতো।
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, site: SiteViewModel, ads: AdsViewModel, uv: UpdateViewModel, auto: AutoPostViewModel, np: NewPostViewModel, dash: DashboardViewModel, ev: EditorViewModel) {
    var open by rememberSaveable { mutableStateOf("") }
    var ask by remember { mutableStateOf<Ask?>(null) }

    LaunchedEffect(Unit) {
        vm.loadCreds()
        vm.loadAi()
        if (BuildConfig.IS_ADMIN) auto.load()
        site.load()
        ads.load()
        vm.loadWallet()
    }
    // সাইটের লোগো/তথ্য সেভ হলে টেমপ্লেটের নমুনায় নতুন লোগো আনা (সেভ-না-হওয়া টেমপ্লেট বদল থাকলে ছোঁয়া হয় না)
    var siteWasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(site.busy) {
        if (siteWasBusy && !site.busy && !np.tplDirty) np.load(true)
        siteWasBusy = site.busy
    }

    fun toggle(id: String) { open = if (open == id) "" else id }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        H1("⚙️ সেটিংস")

        vm.notice?.let { n -> NoticeBar(n) { vm.notice = null } }
        site.notice?.let { n -> NoticeBar(n) { site.notice = null } }
        ads.notice?.let { n -> NoticeBar(n) { ads.notice = null } }

        AccCard("🖼️ ফোটোকার্ড টেমপ্লেট", open == "tpl", { toggle("tpl") }) { TplSettingsBody(np, dash) }
        AccCard("🏷️ লোগো ও সাইটের তথ্য", open == "site", { toggle("site") }) { SiteInfoBody(site) }
        AccCard("📢 বিজ্ঞাপন সেকশন", open == "ads", { toggle("ads") }) { AdsBody(ads) }
        AccCard("🖼️ ওয়ার্ডপ্রেস ফিচার ইমেজ", open == "fi", { toggle("fi") }) { FeatureImageBody(site) }
        // অ্যাডমিন না হলে AI কী কার্ডই দেখায় না (ওয়েবে কার্ড লুকানো থাকে)
        if (vm.aiOwner == true) {
            AccCard("🤖 AI কী (সবার জন্য)", open == "ai", { toggle("ai") }) { AiBody(vm) { ask = it } }
        }
        AccCard("🔑 WordPress ও Facebook সংযোগ", open == "conn", { toggle("conn") }) { ConnBody(vm) { ask = it } }
        if (BuildConfig.IS_ADMIN && auto.allowed != false) {
            AccCard("🌅 অটো \"শুভ সকাল\" পোস্ট", open == "auto", { toggle("auto") }) { AutoPostBody(auto) }
        }
        // শুধু অ্যাডমিনের জন্য (ওয়েবের #csOwner)
        if (vm.csOwner) {
            AccCard("💳 ব্যালেন্স যোগের অনুরোধ", open == "wallet", { toggle("wallet") }) { WalletReqBody(vm) }
        }
        AccCard("🎬 ভিডিও ক্যাশ ও ডাটা", open == "vclear", { toggle("vclear") }) { VideoClearBody(vm, ev) { ask = it } }
        // শুধু অ্যাপে আছে (ওয়েবে নেই)
        AccCard("🔄 অ্যাপ আপডেট", open == "update", { toggle("update") }) { UpdateScreen(uv, embedded = true) }

        if (ads.dirty) {
            Text("⚠️ বিজ্ঞাপনের বদল সেভ করা হয়নি। নিচের \"সেটিংস সেভ করুন\" চাপুন।", color = C.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (np.tplDirty) {
            Text("⚠️ ফোটোকার্ড টেমপ্লেটের বদল সেভ করা হয়নি। নিচের \"সেটিংস সেভ করুন\" চাপুন।", color = C.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        AppBtn(
            if (site.busy || ads.busy || np.tplSaving) "অপেক্ষা করুন..." else "💾 সেটিংস সেভ করুন",
            color = C.Green,
            enabled = !site.busy && !ads.busy && !np.tplSaving,
            onClick = {
                var any = false
                if (site.loaded) { site.save(); any = true }
                if (ads.loaded && ads.dirty) { ads.save(); any = true }
                if (np.tplDirty) {
                    np.saveTplStaged { e -> vm.notice = if (e == null) "✅ ফোটোকার্ড টেমপ্লেট সেভ হয়েছে" else "⚠️ $e" }
                    any = true
                }
                if (!any) vm.notice = "⚠️ সেটিংস এখনো লোড হয়নি, একটু পরে আবার চেষ্টা করুন"
            },
        )
        Spacer(Modifier.height(20.dp))
    }

    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { ask = null },
            text = { Text(a.text) },
            confirmButton = { TextButton(onClick = { ask = null; a.onYes() }) { Text("হ্যাঁ", color = C.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("না") } },
        )
    }
}

// ───────────────────────── 💳 ব্যালেন্স যোগের অনুরোধ (অ্যাডমিন) ─────────────────────────

/** ওয়েবের `#csOwner`: ইউজার টাকা পাঠিয়ে TrxID দিলে এখানে আসে; অ্যাডমিন পরিমাণ মিলিয়ে অনুমোদন/বাতিল করে */
@Composable
private fun WalletReqBody(vm: SettingsViewModel) {
    // 📩 বিকাশ/নগদের "টাকা পেয়েছেন" বা ক্যাশ-ইন SMS এলে TrxID + পরিমাণ (+ সেন্ড মানিতে নম্বর) মিললে ৳৫০০০ পর্যন্ত নিজে অনুমোদন (Worker-এ মেলানো হয়)
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var smsOk by remember { mutableStateOf(granted(android.Manifest.permission.RECEIVE_SMS)) }
    var readOk by remember { mutableStateOf(granted(android.Manifest.permission.READ_SMS)) }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        smsOk = r[android.Manifest.permission.RECEIVE_SMS] == true || granted(android.Manifest.permission.RECEIVE_SMS)
        readOk = r[android.Manifest.permission.READ_SMS] == true || granted(android.Manifest.permission.READ_SMS)
        vm.updateBkAuto(smsOk)
    }
    LaunchedEffect(vm.csLoading) { vm.refreshBk() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("📩 SMS দিয়ে অটো কনফার্ম", fontWeight = FontWeight.Bold)
            Small("এই ফোনে বিকাশ/নগদের \"টাকা পেয়েছেন\" বা \"Cash In\" SMS এলে TrxID ও টাকার পরিমাণ (সেন্ড মানিতে পাঠানো নম্বরও) মিললে ৳৫০০০ পর্যন্ত নিজে অনুমোদন হয়ে যাবে। মিলল না বা বেশি টাকা হলে নিচে হাতে অনুমোদনের জন্য থাকবে।")
        }
        Switch(checked = vm.bkAuto && smsOk, onCheckedChange = { on -> if (on && !smsOk) askSms.launch(arrayOf(android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS)) else vm.updateBkAuto(on) })
    }
    if (vm.bkAuto && !smsOk) Small("⚠️ SMS পড়ার অনুমতি নেই। ফোনের অ্যাপ সেটিংস থেকে অনুমতি দিন।", Modifier.padding(top = 4.dp), color = C.Red)
    if (vm.bkAuto && smsOk && !readOk) Small("ℹ️ \"SMS পড়ার\" অনুমতিও দিলে অ্যাপ/ফোন বন্ধ থাকার সময় আসা SMS পরে নিজে খুঁজে ধরবে। (সুইচ বন্ধ করে আবার চালু করলে অনুমতি চাইবে।)", Modifier.padding(top = 4.dp))
    if (vm.bkLast.isNotBlank()) Small("শেষ SMS: " + vm.bkLast, Modifier.padding(top = 4.dp))
    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(1.dp).background(Color(0xFFEEEEEE)))
    Small("ইউজার টাকা পাঠিয়ে TrxID দিলে এখানে আসে। টাকা পেয়ে থাকলে পরিমাণ মিলিয়ে (দরকারে বদলে) অনুমোদন দিন, ইউজারের ব্যালেন্সে যোগ হবে।")
    vm.csMsg?.let { (ok, t) -> Text(t, fontSize = 13.sp, color = if (ok) C.Green else C.Red, modifier = Modifier.padding(top = 8.dp)) }
    if (vm.csItems.isEmpty()) {
        Text(if (vm.csLoading) "লোড হচ্ছে..." else "কোনো অনুরোধ নেই।", fontSize = 14.sp, color = C.Muted, modifier = Modifier.padding(top = 10.dp))
    }
    vm.csItems.forEach { r -> WalletReqRow(vm, r) }
    AppBtn("🔄 রিফ্রেশ", Modifier.padding(top = 12.dp), color = C.Gray, enabled = !vm.csLoading && vm.csBusyKey == null) { vm.csMsg = null; vm.loadCsList() }
}

@Composable
private fun WalletReqRow(vm: SettingsViewModel, r: WalletReq) {
    var amt by remember(r.key) { mutableStateOf(if (r.amount % 1.0 == 0.0) r.amount.toLong().toString() else r.amount.toString()) }
    val busy = vm.csBusyKey != null
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
        Text(r.email + " — ৳" + Bn.num(r.amount), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Small("পাঠানো নম্বর: " + r.phone + " · TrxID: " + r.trx + (if (r.at > 0) " · " + Bn.dateTime(r.at) else ""))
        AppField(amt, { amt = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.padding(top = 6.dp), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn(if (vm.csBusyKey == r.key) "অপেক্ষা করুন..." else "✔ অনুমোদন (যোগ হবে)", color = C.Green, small = true, enabled = !busy && amt.isNotBlank()) { vm.csDecide(r, true, amt) }
            AppBtn("✕ বাতিল", color = C.Gray, small = true, enabled = !busy) { vm.csDecide(r, false, amt) }
        }
    }
}

// ───────────────────────── 🎬 ভিডিও ক্যাশ ও ডাটা ─────────────────────────

/** ওয়েবের `#vcClearCard`: ভিডিও এডিটরের জমে থাকা ফাইল ও পছন্দ মুছে রিসেট; অসমাপ্ত এডিট থাকে */
@Composable
private fun VideoClearBody(vm: SettingsViewModel, ev: EditorViewModel, ask: (Ask) -> Unit) {
    Small(
        "এগুলো মুছে রিসেট হবে: রেন্ডার করে রাখা ভিডিওর অস্থায়ী ফাইল, বানানো ইন্ট্রো/আউট্রো কার্ডের ভিডিও, " +
            "সেটিংস থেকে বানানো বিজ্ঞাপন/লোগোর ছবি, রেকর্ড করা ভয়েস, আর ইন্ট্রোর লেখা ও টাইটেল/ব্যানারের লেখা। " +
            "এরপর নতুন ভিডিও দিলে ইন্ট্রো-আউট্রো, ব্যানার, স্ক্রলিং লেখা, ট্যাগ ও লোগো আবার নতুন করে নিজে বসবে।",
    )
    Small("📝 অসমাপ্ত ভিডিও এডিট (ড্রাফট) ও তার ফাইল থাকবে। গ্যালারিতে সেভ করা ভিডিও, সেটিংস, লগইন ও সংযোগ ছোঁয়া হবে না।", Modifier.padding(top = 6.dp))
    AppBtn(
        if (vm.vcBusy) "⏳ ক্লিয়ার হচ্ছে..." else "🧹 ক্লিয়ার ভিডিও ক্যাশ এন্ড ডাটা",
        Modifier.padding(top = 10.dp), color = C.Red, enabled = !vm.vcBusy,
    ) {
        vm.vcMsg = null
        ask(Ask("রেন্ডার করে রাখা ভিডিও ও তথ্য, আর ভিডিও সেটিংসের সব সেভ (ইন্ট্রো, আউট্রো, লোগো, লেখা, ট্যাগ) মুছে রিসেট হবে।\n\nঅসমাপ্ত ভিডিও এডিট থাকবে। চালিয়ে যাবেন?") { vm.clearVideoData(ev) })
    }
    vm.vcMsg?.let { (ok, t) -> Text(t, fontSize = 13.sp, color = if (ok) C.Green else C.Red, modifier = Modifier.padding(top = 8.dp)) }
}

// ───────────────────────── ছোট সাহায্যকারী ─────────────────────────

@Composable
internal fun StatusLine(ok: Boolean, text: String) {
    Text(
        text,
        color = if (ok) C.Green else C.Red,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth()
            .background(if (ok) Color(0xFFE8F5E9) else Color(0xFFFDECEA), RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}

@Composable
internal fun Small(text: String, modifier: Modifier = Modifier, color: Color = C.Muted) =
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp, modifier = modifier)

/** ওয়েবের <details class="guide"> */
@Composable
internal fun Guide(title: String, lines: List<String>) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            (if (open) "▾ " else "▸ ") + title,
            color = C.Blue, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 6.dp),
        )
        if (open) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFFF7F8FA), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                lines.forEach { l -> Text(l, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF333333)) }
            }
        }
    }
}

// ───────────────────────── সংযোগ: WordPress ও Facebook ─────────────────────────

@Composable
private fun ConnBody(vm: SettingsViewModel, ask: (Ask) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Small("এখানে নিজের সাইট ও পেইজের তথ্য দিন। তথ্যগুলো Worker-এ এনক্রিপ্ট করে থাকে, অ্যাপে আর ফেরত আসে না। এই সংযোগ শুধু আপনার লগইন অ্যাকাউন্টের।", Modifier.padding(top = 4.dp))
            vm.credsErr?.let { Spacer(Modifier.height(8.dp)); StatusLine(false, "⚠️ $it") }
            if (!vm.kvOk) { Spacer(Modifier.height(8.dp)); StatusLine(false, "⚠️ Worker-এ KV (SCHEDULE) বাইন্ডিং নেই, তাই এখান থেকে সেভ হবে না") }
        }

        // ── WordPress ──
        CrGroup {
            Text("🌐 WordPress", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            StatusLine(
                vm.wpOk(),
                if (vm.wpOk()) "✅ সেট করা আছে" + (if (vm.credEnv(listOf("WP_URL", "WP_USER", "WP_APP_PASS"))) " (কিছু তথ্য Worker ভ্যারিয়েবল থেকে)" else "")
                else "❌ সম্পূর্ণ সেট করা নেই",
            )
            FieldLabel("সাইটের URL")
            AppField(vm.wpUrl, { vm.wpUrl = it }, placeholder = "https://আপনারসাইট.com", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
            FieldLabel("ইউজারনেম")
            AppField(vm.wpUser, { vm.wpUser = it }, placeholder = "WordPress লগইন ইউজারনেম")
            FieldLabel("অ্যাপ্লিকেশন পাসওয়ার্ড")
            AppField(
                vm.wpPass, { vm.wpPass = it },
                placeholder = vm.secretHint("WP_APP_PASS")?.let { "$it (বদলাতে চাইলে নতুনটা বসান)" } ?: "xxxx xxxx xxxx xxxx xxxx xxxx",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                visual = PasswordVisualTransformation(),
            )
            Guide(
                "📖 এগুলো কোথায় পাবেন (ধাপে ধাপে)",
                listOf(
                    "১. আপনার WordPress ড্যাশবোর্ডে (আপনারসাইট.com/wp-admin) লগইন করুন।",
                    "২. বাঁ মেনু থেকে Users → Profile (নিজের ইউজার) খুলুন।",
                    "৩. একদম নিচে Application Passwords অংশে যেকোনো নাম দিন (যেমন News App), তারপর Add New Application Password চাপুন।",
                    "৪. যে পাসওয়ার্ডটি দেখাবে (স্পেসসহ ২৪ অক্ষর) সঙ্গে সঙ্গে কপি করে উপরের ঘরে বসান। এটা দ্বিতীয়বার দেখাবে না।",
                    "৫. ইউজারনেম হলো ওই প্রোফাইলের Username ঘরের লেখা, এটা আপনার লগইন পাসওয়ার্ড নয়।",
                    "৬. সাইটের URL https:// দিয়ে লিখুন, শেষে / লাগবে না।",
                    "সমস্যা হলে: Application Passwords অংশ না দেখালে সাইটে HTTPS চালু আছে কিনা দেখুন; কিছু সিকিউরিটি প্লাগিনও এটা বন্ধ রাখে। ইউজারের রোল Author, Editor বা Administrator হতে হবে। \"টেস্ট করুন\" চাপলে ভুল কোথায় তা বলে দেবে।",
                ),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn("🔌 টেস্ট করুন", color = C.Gray, small = true, enabled = vm.busy == null, onClick = { vm.testCred("wp") })
                AppBtn("🗑️ মুছুন", color = C.Gray, small = true, enabled = vm.busy == null, onClick = {
                    ask(Ask("WordPress-এর সেভ করা তথ্য মুছে ফেলবেন?") { vm.clearCreds("wp") })
                })
            }
            vm.wpMsg?.let { (ok, t) -> Text(t, color = if (ok) C.Green else C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }

        // ── Facebook ──
        CrGroup {
            Text("📘 Facebook পেইজ", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            StatusLine(
                vm.fbOk(),
                if (vm.fbOk()) "✅ সেট করা আছে" + (if (vm.credEnv(listOf("FB_PAGE_ID", "FB_TOKEN"))) " (কিছু তথ্য Worker ভ্যারিয়েবল থেকে)" else "")
                else "❌ সম্পূর্ণ সেট করা নেই",
            )
            FieldLabel("Page ID")
            AppField(vm.fbPage, { vm.fbPage = it }, placeholder = "শুধু সংখ্যা, যেমন 1234567890", keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            FieldLabel("Page Access Token")
            AppField(
                vm.fbToken, { vm.fbToken = it },
                placeholder = vm.secretHint("FB_TOKEN")?.let { "$it (বদলাতে চাইলে নতুনটা বসান)" } ?: "EAAB...",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                visual = PasswordVisualTransformation(),
            )
            Guide(
                "📖 Page ID ও টোকেন কীভাবে পাবেন (সংক্ষেপে)",
                listOf(
                    "১. developers.facebook.com-এ লগইন করুন (অ্যাকাউন্টটি ওই পেইজের অ্যাডমিন হতে হবে)।",
                    "২. My Apps → Create App। Use cases-এ Manage everything on your Page বেছে অ্যাপ বানান।",
                    "৩. Tools → Graph API Explorer খুলে ডানে আপনার অ্যাপটি বেছে নিন।",
                    "৪. শুধু এই চারটা পারমিশন দিন: pages_show_list, pages_read_engagement, pages_manage_posts, pages_manage_engagement।",
                    "৫. Generate Access Token চেপে পপআপে আপনার পেইজটি টিক দিয়ে অনুমতি দিন (পেইজ টিক না দিলে me/accounts ফাঁকা আসবে)।",
                    "৬. টোকেন স্থায়ী করতে Access Token Debugger-এ গিয়ে Extend Access Token করুন, তারপর Graph API Explorer-এ ওই লম্বা টোকেন বসিয়ে me/accounts লিখে Submit চাপুন।",
                    "৭. ফলাফলে আপনার পেইজের access_token হলো Page Access Token, আর id হলো Page ID। দুটো উপরের ঘরে বসিয়ে সেভ করুন।",
                    "মনে রাখুন: অ্যাপ Live মোডে না থাকলে পোস্ট সবাই দেখতে পায় না (App Settings → Basic-এ Privacy Policy URL দিয়ে Live করুন)। পাসওয়ার্ড বদলালে বা পেইজের অ্যাডমিন থেকে সরে গেলে টোকেন বাতিল হয়। টোকেন কারও সাথে শেয়ার করবেন না।",
                ),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn("🔌 টেস্ট করুন", color = C.Gray, small = true, enabled = vm.busy == null, onClick = { vm.testCred("fb") })
                AppBtn("🗑️ মুছুন", color = C.Gray, small = true, enabled = vm.busy == null, onClick = {
                    ask(Ask("Facebook-এর সেভ করা তথ্য মুছে ফেলবেন?") { vm.clearCreds("fb") })
                })
            }
            vm.fbMsg?.let { (ok, t) -> Text(t, color = if (ok) C.Green else C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }

        // ── YouTube (HTML সেটিংসের "▶️ YouTube (ভিডিও পাবলিশের জন্য)") ──
        YtCard(ask)

        Spacer(Modifier.height(14.dp))
        AppBtn("💾 সংযোগ সেভ করুন", color = C.Green, enabled = vm.busy == null, onClick = { vm.saveCreds() })
    }
}

/** ইউটিউব: কোনো সিক্রেট/টোকেন সেভ হয় না। Google লগইন টেস্টে অনুমতি দিলেই চলে; ভিডিও অ্যাপ থেকে সরাসরি চ্যানেলে যায়। */
@Composable
private fun YtCard(ask: (Ask) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val login = rememberYtLogin()
    var ok by remember { mutableStateOf(YouTube.isConnected(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val sha = remember { YouTube.sha1(ctx) }
    CrGroup {
        Text("▶️ YouTube (ভিডিও পাবলিশের জন্য)", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        StatusLine(ok, if (ok) "✅ লগইন ঠিক আছে" else "❌ লগইন টেস্ট করা হয়নি")
        Small("পাসওয়ার্ড বা সিক্রেট লাগে না। ভিডিও অ্যাপ থেকে সরাসরি আপনার YouTube চ্যানেলে যায়।", Modifier.padding(top = 8.dp))
        FieldLabel("Google Cloud-এ Android OAuth client বানাতে যা লাগবে")
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFFF7F8FA), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("প্যাকেজ নাম:", fontSize = 12.sp, color = C.Muted)
                Text(ctx.packageName, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("SHA-1 (সাইনিং সার্টিফিকেট):", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
                Text(sha.ifBlank { "পাওয়া যায়নি" }, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
        Guide(
            "📖 YouTube কীভাবে যোগ করবেন (ধাপে ধাপে)",
            listOf(
                "১. console.cloud.google.com-এ সেই Google অ্যাকাউন্টে লগইন করুন, যে অ্যাকাউন্টে আপনার YouTube চ্যানেল আছে। HTML-এর জন্য আগে প্রজেক্ট বানানো থাকলে সেটাই ব্যবহার করুন (নইলে New Project)।",
                "২. APIs & Services → Library থেকে YouTube Data API v3 খুঁজে Enable করুন (আগে করা থাকলে লাগবে না)।",
                "৩. OAuth consent screen (নতুন ডিজাইনে Google Auth Platform): External, অ্যাপের নাম ও ইমেইল দিন। Scopes-এ .../auth/youtube.upload যোগ করুন। Test users-এ নিজের Google ইমেইল দিন। অ্যাপ Testing অবস্থায় রাখলেই চলবে। (HTML-এর জন্য করা থাকলে এই ধাপ আবার লাগবে না।)",
                "৪. Credentials → Create Credentials → OAuth client ID। Application type: Android। Package name ও SHA-1 ঘরে ওপরের দুটো মান হুবহু বসান। Create চাপুন। কোথাও কিছু কপি করে অ্যাপে দিতে হবে না।",
                "৫. নিচে \"লগইন টেস্ট\" চাপুন। Google পপআপে চ্যানেলের অ্যাকাউন্ট বাছুন। \"Google hasn’t verified this app\" দেখালে Advanced → Go to ... চেপে অনুমতি দিন। এটা নিজের অ্যাপ, তাই নিরাপদ।",
                "জেনে রাখুন: ফোনে Google Play services থাকতে হবে। নতুন API প্রজেক্ট থেকে আপলোড করা ভিডিও Google অডিট না করা পর্যন্ত প্রাইভেট থেকে যেতে পারে; তখন YouTube Studio থেকে হাতে Public করতে হবে, অথবা Google-এর YouTube API Services Audit ফর্ম জমা দিন। ডিফল্ট কোটায় দিনে প্রায় ৬টা ভিডিও আপলোড করা যায়। পাবলিশের সময় লগইন লাগতে পারে; পপআপ এলে অনুমতি দিন। অ্যাপ নতুন কী (keystore) দিয়ে বিল্ড হলে SHA-1 বদলে যায়, তখন ধাপ ৪ আবার করতে হবে।",
            ),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("🔌 লগইন টেস্ট", color = C.Gray, small = true, enabled = !busy, onClick = {
                busy = true; msg = null
                scope.launch {
                    try {
                        login.token()
                        YouTube.setConnected(ctx, true); ok = true
                        msg = true to "✅ YouTube লগইন ঠিক আছে। পাবলিশে ভিডিও এই চ্যানেলে যাবে।"
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        YouTube.setConnected(ctx, false); ok = false
                        msg = false to "⚠️ " + (e.message ?: "লগইন হয়নি")
                    } finally { busy = false }
                }
            })
            AppBtn("🗑️ মুছুন", color = C.Gray, small = true, enabled = !busy, onClick = {
                ask(Ask("YouTube সংযোগের চিহ্ন মুছে ফেলবেন? (Google অ্যাকাউন্টের অনুমতি তুলতে myaccount.google.com → Security → Third-party access)") {
                    YouTube.setConnected(ctx, false); ok = false; msg = true to "মুছে ফেলা হয়েছে"
                })
            })
        }
        msg?.let { (g, t) -> Text(t, color = if (g) C.Green else C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

// ───────────────────────── AI কী ─────────────────────────

private fun statLine(c: KeyCheck?): Pair<Color, String> = when (c?.state) {
    null -> C.Muted to "চেক করা হয়নি"
    "checking" -> C.Muted to "⏳ চেক হচ্ছে..."
    "active" -> C.Green to "✅ সক্রিয় (কাজ করছে)"
    "quota" -> Amber to ("⚠️ কী ঠিক আছে, কিন্তু এখন কোটা/ব্যালেন্স/লিমিট শেষ" + if (c.msg.isNotBlank()) " — ${c.msg}" else "")
    "invalid" -> C.Red to ("❌ কাজ করছে না" + if (c.msg.isNotBlank()) ": ${c.msg}" else "")
    else -> Amber to ("⚠️ চেক করা যায়নি" + if (c.msg.isNotBlank()) ": ${c.msg}" else "")
}

@Composable
private fun AiBody(vm: SettingsViewModel, ask: (Ask) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Small("এখানে দেওয়া Gemini/Groq/Cloudflare/Claude কী অ্যাপের সব ইউজারের জন্য কাজ করবে। শুধু অ্যাডমিন এটা দেখতে ও বদলাতে পারে।", Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            if (vm.aiStatus.isBlank()) Small("লোড হচ্ছে...") else StatusLine(vm.aiStatusOk, vm.aiStatus)
        }

        AiProv.entries.forEach { p ->
            CrGroup {
                Text(p.title, fontWeight = FontWeight.Bold)
                if (p == AiProv.GEMINI && vm.geminiModels.isNotBlank()) Small(vm.geminiModels, Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                val list = vm.aiKeys[p.id].orEmpty()
                if (list.isEmpty()) Small("কোনো ${p.label} কী নেই। নিচে কী বসিয়ে Add চাপুন।")
                list.forEachIndexed { i, k ->
                    val chk = vm.aiChecks["${p.id}:${k.id}"]
                    val (col, txt) = if (k.cooling && chk == null) C.Muted to "" else statLine(chk)
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${Bn.num(i + 1)}.", color = C.Muted, modifier = Modifier.padding(end = 6.dp))
                            Text(k.hint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Small(if (k.fromEnv) "  Worker সিক্রেট" else "  অ্যাপ", color = C.Muted)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.checkKey(p, k.id) }, enabled = chk?.state != "checking") { Text("✅") }
                            TextButton(
                                onClick = { ask(Ask("এই ${p.label} কী (${k.hint}) মুছে ফেলবেন?") { vm.deleteKey(p, k.id) }) },
                                enabled = !k.fromEnv && vm.busy == null,
                            ) { Text("🗑️") }
                        }
                        if (txt.isNotBlank()) Text(txt, color = col, fontSize = 12.sp, lineHeight = 17.sp)
                        if (k.usage.isNotBlank()) Small(k.usage, Modifier.padding(top = 2.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                AppField(
                    vm.aiInputs[p.id].orEmpty(), { vm.aiInputs[p.id] = it },
                    placeholder = p.placeholder, singleLine = false,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppBtn("➕ Add", color = C.Green, small = true, enabled = vm.busy == null, onClick = { vm.addKeys(p) })
                    AppBtn("✅ সব কী চেক করুন", color = C.Gray, small = true, enabled = vm.busy == null && list.isNotEmpty(), onClick = { vm.checkAll(p) })
                }
                if (p == AiProv.GROQ) {
                    FieldLabel("Groq মডেল (ঐচ্ছিক, ফাঁকা থাকলে llama-3.3-70b-versatile)")
                    AppField(vm.groqModel, { vm.groqModel = it })
                }
                if (p == AiProv.CF) {
                    FieldLabel("Cloudflare মডেল (ঐচ্ছিক, ফাঁকা থাকলে @cf/meta/llama-3.3-70b-instruct-fp8-fast)")
                    AppField(vm.cfModel, { vm.cfModel = it })
                }
            }
        }

        CrGroup {
            Guide(
                "📖 কী কোথায় পাবেন",
                listOf(
                    "Gemini: aistudio.google.com-এ লগইন করে Get API key → Create API key। কয়েকটা কী দিলে একটা ফেইল করলে পরেরটা চলবে।",
                    "Claude: console.anthropic.com → API Keys → Create Key। এটার জন্য কনসোলে ব্যালেন্স লাগে।",
                    "Groq: console.groq.com → API Keys → Create API Key (ফ্রি টিয়ার আছে, সীমা কম)।",
                    "Cloudflare: dash.cloudflare.com → AI → Workers AI থেকে Account ID নিন, আর My Profile → API Tokens থেকে Workers AI পারমিশনসহ টোকেন বানান। দুটো এক লাইনে লিখুন: ACCOUNT_ID:TOKEN",
                    "ক্রম: খবর নতুন করে লেখার সময় আগে Gemini, সব কী ব্যর্থ হলে Groq, তারপর Cloudflare, সবশেষে Claude। ছবি দেখার কাজ শুধু Gemini/Claude দিয়ে হয়।",
                    "Groq/Cloudflare-এর বাংলা লেখার মান Gemini-র চেয়ে কম হতে পারে, তাই পাবলিশের আগে পড়ে দেখুন।",
                    "কী কারও সাথে শেয়ার করবেন না। সেভ করার পর এখানে শুধু শেষ ৪ অক্ষর দেখাবে।",
                ),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn("💾 মডেল সেভ", color = C.Green, small = true, enabled = vm.busy == null, onClick = { vm.saveAiModels() })
                AppBtn("🗑️ অ্যাপের সব AI কী মুছুন", color = C.Gray, small = true, enabled = vm.busy == null, onClick = {
                    ask(Ask("অ্যাপে সেভ করা সব AI কী (Gemini, Groq, Cloudflare, Claude, মডেল) মুছে ফেলবেন? একটা একটা করে মুছতে কী-এর পাশের 🗑️ চাপুন। (Worker সিক্রেট থাকলে সেটাই চলবে)") { vm.clearAllAi() })
                })
            }
        }
    }
}

// ───────────────────────── অ্যাডমিন অ্যাপের পাতা (শুধু অ্যাডমিন ফ্লেভারে ব্যবহার হয়) ─────────────────────────

/** "ব্যালেন্স" ট্যাব: SMS অটো কনফার্মের সুইচ + হাতে অনুমোদনের অপেক্ষায় থাকা অনুরোধ */
@Composable
fun AdminWalletScreen(vm: SettingsViewModel) {
    LaunchedEffect(Unit) { vm.loadWallet() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        H1("💳 ব্যালেন্স ও অনুরোধ")
        vm.notice?.let { n -> NoticeBar(n) { vm.notice = null } }
        InfoCard { WalletReqBody(vm) }
        Spacer(Modifier.height(20.dp))
    }
}

/** "সেটিংস" ট্যাব: AI কী (সবার জন্য), অটো "শুভ সকাল" পোস্ট, অ্যাপ আপডেট */
@Composable
fun AdminSettingsScreen(vm: SettingsViewModel, uv: UpdateViewModel, auto: AutoPostViewModel) {
    var open by rememberSaveable { mutableStateOf("") }
    var ask by remember { mutableStateOf<Ask?>(null) }
    LaunchedEffect(Unit) { vm.loadAi(); auto.load() }
    fun toggle(id: String) { open = if (open == id) "" else id }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        H1("⚙️ অ্যাডমিন সেটিংস")
        vm.notice?.let { n -> NoticeBar(n) { vm.notice = null } }
        if (vm.aiOwner == true) {
            AccCard("🤖 AI কী (সবার জন্য)", open == "ai", { toggle("ai") }) { AiBody(vm) { ask = it } }
        } else if (vm.aiOwner == null) {
            Text("AI কী লোড হচ্ছে...", fontSize = 14.sp, color = C.Muted)
        }
        if (auto.allowed != false) {
            AccCard("🌅 অটো \"শুভ সকাল\" পোস্ট", open == "auto", { toggle("auto") }) { AutoPostBody(auto) }
        }
        AccCard("🔄 অ্যাপ আপডেট", open == "update", { toggle("update") }) { UpdateScreen(uv, embedded = true) }
        Spacer(Modifier.height(20.dp))
    }
    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { ask = null },
            text = { Text(a.text) },
            confirmButton = { TextButton(onClick = { ask = null; a.onYes() }) { Text("হ্যাঁ", color = C.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("না") } },
        )
    }
}
