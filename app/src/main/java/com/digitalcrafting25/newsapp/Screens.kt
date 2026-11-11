package com.digitalcrafting25.newsapp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

// ───────────────────────── লগইন ─────────────────────────

@Composable
fun LoginScreen(vm: AuthViewModel) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val go = { vm.login(email, pass) }
    Column(
        Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).padding(14.dp),
    ) {
        Spacer(Modifier.height(46.dp))
        Surface(shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                H1("📰 লগইন করুন")
                FieldLabel("ইমেইল")
                AppField(email, { email = it }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                FieldLabel("পাসওয়ার্ড")
                AppField(
                    pass, { pass = it },
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    visual = PasswordVisualTransformation(),
                    onDone = { go() },
                )
                Text(vm.error, color = C.Red, modifier = Modifier.padding(top = 8.dp).height(24.dp))
                AppBtn(if (vm.busy) "অপেক্ষা করুন..." else "লগইন", enabled = !vm.busy, onClick = { go() })
            }
        }
    }
}

// ───────────────────────── কাঠামো: উপরের বার + নিচের মেনু ─────────────────────────

@Composable
fun TopBar(onLogout: () -> Unit) {
    Column(Modifier.background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📰 নিউজ অটো পাবলিশার", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            AppBtn("লগআউট", color = C.Gray, small = true, onClick = onLogout)
        }
        HorizontalDivider(color = Color(0xFFE3E3E3))
    }
}

enum class Page(val emoji: String, val label: String) {
    NEW("✍️", "নতুন পোস্ট"),
    CARD("🖼️", "ফোটোকার্ড"),
    DASH("🏠", "ড্যাশবোর্ড"),
    SCHEDULE("⏰", "সিডিউল"),
    SETTINGS("⚙️", "সেটিংস"),
    BOOKMARKS("🔖", "বুকমার্ক"),   // নিচের মেনুতে নেই (ওয়েবেও তাই), ড্যাশবোর্ড থেকে খোলে
}

/** নিচের মেনু। মাঝের ড্যাশবোর্ডের গোল বাটন আলাদা স্তরে (NavCircle) বসে, যাতে উঁচু অংশেও টাচ কাজ করে */
@Composable
fun BottomNav(current: Page, onSelect: (Page) -> Unit, onHeight: (Int) -> Unit) {
    Column(Modifier.background(Color.White).onSizeChanged { onHeight(it.height) }) {
        HorizontalDivider(color = C.NavLine)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            listOf(Page.NEW, Page.CARD).forEach { NavItem(it, current == it, Modifier.weight(1f), onSelect) }
            Column(
                Modifier.width(76.dp).clickable { onSelect(Page.DASH) }.padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(34.dp))
                Text(Page.DASH.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (current == Page.DASH) C.Red else C.Muted)
            }
            listOf(Page.SCHEDULE, Page.SETTINGS).forEach { NavItem(it, current == it, Modifier.weight(1f), onSelect) }
        }
    }
}

@Composable
private fun NavItem(p: Page, on: Boolean, modifier: Modifier, onSelect: (Page) -> Unit) {
    Column(
        modifier.clickable { onSelect(p) }.padding(top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(p.emoji, fontSize = 20.sp)
        Text(p.label, fontSize = 12.sp, color = if (on) C.Red else C.Muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

/** মাঝের উঁচু গোল ড্যাশবোর্ড বাটন (ওয়েবের nav button.mid) */
@Composable
fun NavCircle(on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(58.dp)
            .shadow(6.dp, CircleShape, ambientColor = C.Red, spotColor = C.Red)
            .border(4.dp, Color.White, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(if (on) C.DarkRed else C.Red)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text("🏠", fontSize = 26.sp) }
}

// ───────────────────────── ড্যাশবোর্ড ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(vm: DashboardViewModel, onOpenBookmarks: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    var topup by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        H1("🏠 ড্যাশবোর্ড")

        // স্বাগতম
        Box(
            Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFE8F0FE), Color(0xFFF3E8FF))))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(Lang.t("👋 স্বাগতম, "))
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                        append(st.siteName.ifBlank { Cfg.SITE })
                        pop()
                    },
                    fontSize = 15.sp,
                )
                Text("📅 " + Bn.today(), fontSize = 13.sp, color = Color(0xFF555555))
            }
        }

        st.error?.let {
            Text("⚠️ ডেটা আনা যায়নি: $it\n(Firestore চালু ও Rules ঠিক আছে কিনা দেখুন)", color = C.Red, modifier = Modifier.padding(bottom = 10.dp), fontSize = 14.sp)
        }

        // ব্যালেন্স
        InfoCard {
            Text("💰 ব্যবহারযোগ্য ব্যালেন্স", fontSize = 13.sp, color = C.Label)
            Text(
                st.balance?.let { "৳ " + Bn.num(it) } ?: if (st.loading) "..." else "—",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp),
            )
            HBtn("➕ ব্যালেন্স যোগ করুন", Modifier.padding(top = 8.dp), padH = 12.dp, padV = 8.dp) { topup = true }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("আজকের পোস্ট", st, st.today, Modifier.weight(1f))
            StatCard("এই মাসের পোস্ট", st, st.month, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        StatCard("মোট পোস্ট", st, st.total, Modifier)

        Spacer(Modifier.height(4.dp))
        AppBtn("🔖 বুকমার্ক করা সাইট দেখুন", color = C.Blue, modifier = Modifier.padding(top = 6.dp), onClick = onOpenBookmarks)

        H2("🕒 শেষ পোস্ট")
        InfoCard {
            if (st.recent.isEmpty()) Text(if (st.loaded) "এখনও কোনো পোস্ট নেই" else "...") else PostRow(st.recent.first(), Modifier.padding(vertical = 10.dp))
        }

        H2("📋 সর্বশেষ ৫টি পোস্ট")
        InfoCard {
            if (st.recent.isEmpty()) Text(if (st.loaded) "এখনও কোনো পোস্ট নেই" else "...")
            else st.recent.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = C.Line)
                PostRow(p, Modifier.padding(vertical = 10.dp))
            }
        }
        AppBtn(if (st.loading) "অপেক্ষা করুন..." else "🔄 রিফ্রেশ", color = C.Gray, enabled = !st.loading, modifier = Modifier.padding(top = 10.dp), onClick = { vm.load() })
        Spacer(Modifier.height(16.dp))
    }

    if (topup) TopupDialog(vm, st.balance, onClose = { topup = false })
}

@Composable
private fun StatCard(label: String, st: DashState, value: Int, modifier: Modifier) {
    InfoCard(modifier) {
        Text(label, fontSize = 13.sp, color = C.Label)
        Text(if (st.loaded) Bn.num(value) else "...", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun PostRow(p: PostItem, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth()) {
        Text(
            p.title.ifBlank { "(শিরোনাম নেই)" },
            color = C.Blue, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            modifier = Modifier.clickable(enabled = p.link.isNotBlank()) { openUrl(ctx, p.link) },
        )
        val meta = (p.at?.let { Bn.dateTime(it) } ?: "") + if (p.by.isNotBlank()) " • ${p.by}" else ""
        if (meta.isNotBlank()) Text(meta, fontSize = 13.sp, color = Color(0xFF777777), modifier = Modifier.padding(top = 2.dp))
    }
}

fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
internal fun TopupDialog(vm: DashboardViewModel, balance: Double?, onClose: () -> Unit) {
    var amt by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var trx by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(true) }
    var reading by remember { mutableStateOf(false) }
    var shotMsg by remember { mutableStateOf("") }
    var shotOk by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    // 📷 Send Money-এর স্ক্রিনশট বা USSD-র পর্দার ছবি (গ্যালারি/ক্যামেরা) দিলে TrxID ও টাকা নিজে বসে; পাঠানো নম্বর ছবিতে থাকে না, ওটা নিজে লিখতে হয়
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val readShot: (android.net.Uri) -> Unit = { uri ->
            reading = true; shotMsg = "⏳ ছবি থেকে পড়া হচ্ছে..."; shotOk = true
            scope.launch {
                try {
                    val d = vm.readPayShot(uri)
                    val t = d.optString("trx"); val a = d.optDouble("amount", 0.0); val to = d.optString("to")
                    if (t.isBlank()) { shotMsg = "⚠️ পড়া যায়নি, হাতে লিখুন"; shotOk = false }
                    else {
                        trx = t
                        if (a > 0) amt = a.toLong().toString()
                        val mine = Regex("01\\d{9}").find(Cfg.PAY_INFO)?.value
                        val wrongTo = mine != null && to.length >= 10 && to.takeLast(10) != mine.takeLast(10)
                        shotMsg = "✅ TrxID ও টাকা বসেছে। অক্ষর/সংখ্যা (O-0, I-1) মিলিয়ে দেখুন। পাঠানো নম্বর ছবিতে থাকে না, নিচে নিজে লিখুন।" +
                            (if (wrongTo) "\n⚠️ ছবির প্রাপক নম্বর ($to) আমাদের নম্বর নয়, ঠিক নম্বরে পাঠিয়েছেন তো?" else "")
                        shotOk = !wrongTo
                    }
                } catch (e: Exception) { shotMsg = "⚠️ " + (e.message ?: "পড়া যায়নি, হাতে লিখুন"); shotOk = false }
                finally { reading = false }
            }
    }
    val pickShot = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> if (uri != null) readShot(uri) }
    var camUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takeShot = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { done -> if (done) camUri?.let { readShot(it) } }
    fun openCamera() {
        runCatching {
            val f = java.io.File(ctx.cacheDir, "payshots").apply { mkdirs() }.let { java.io.File(it, "pay_" + System.currentTimeMillis() + ".jpg") }
            val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            camUri = u; takeShot.launch(u)
        }.onFailure { shotMsg = "⚠️ ক্যামেরা খোলা যায়নি, গ্যালারি থেকে ছবি বাছুন বা হাতে লিখুন"; shotOk = false }
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color.White,
        confirmButton = {},
        title = null,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("➕ ব্যালেন্স যোগ করুন", fontWeight = FontWeight.Bold)
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("বর্তমান ব্যালেন্স: ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                        append("৳ " + Bn.num(balance ?: 0.0))
                        pop()
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
                Surface(
                    shape = RoundedCornerShape(8.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE3E3E3)),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("টাকা পাঠান: ${Cfg.PAY_INFO}", fontSize = 13.sp, color = C.Label)
                        Text("টাকা পাঠিয়ে নিচের ঘরগুলো পূরণ করুন। অ্যাডমিন যাচাই করে অনুমোদন দিলে ব্যালেন্সে যোগ হবে।", fontSize = 13.sp, color = C.Label, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Text("📷 Send Money বা USSD-র ছবি দিলে TrxID ও টাকা নিজে বসবে", fontSize = 13.sp, color = C.Label, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppBtn(if (reading) "অপেক্ষা..." else "📷 ছবি তুলুন", Modifier.weight(1f), color = C.Blue, small = true, enabled = !reading && !busy) { openCamera() }
                    AppBtn(if (reading) "অপেক্ষা..." else "🖼️ গ্যালারি থেকে", Modifier.weight(1f), color = C.Blue, small = true, enabled = !reading && !busy) { pickShot.launch("image/*") }
                }
                if (shotMsg.isNotEmpty()) Text(shotMsg, fontSize = 13.sp, color = if (shotOk) C.Green else C.Red, modifier = Modifier.padding(top = 6.dp))
                FieldLabel("কত টাকা পাঠিয়েছেন")
                AppField(amt, { amt = it.filter(Char::isDigit) }, placeholder = "যেমন ৫০০", keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                FieldLabel("যে নম্বর থেকে টাকা পাঠিয়েছেন")
                AppField(phone, { phone = it }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone))
                FieldLabel("ট্রানজেকশন আইডি (TrxID)")
                AppField(trx, { trx = it })
                AppBtn(
                    if (busy) "অপেক্ষা করুন..." else "📨 অনুরোধ পাঠান", color = C.Green, enabled = !busy,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    busy = true; msg = ""
                    scope.launch {
                        try {
                            msg = vm.walletRequest(amt, phone, trx); ok = true; trx = ""
                        } catch (e: Exception) {
                            msg = "⚠️ " + (e.message ?: "পাঠানো যায়নি"); ok = false
                        } finally { busy = false }
                    }
                }
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = if (ok) C.Green else C.Red, modifier = Modifier.padding(top = 8.dp))
                AppBtn("✕ বন্ধ করুন", color = C.Gray, modifier = Modifier.padding(top = 8.dp), onClick = onClose)
            }
        },
    )
}

// ───────────────────────── বুকমার্ক ─────────────────────────

@Composable
fun BookmarkScreen(vm: BookmarkViewModel, onBack: () -> Unit) {
    var showForm by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<BookmarkItem?>(null) }
    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        H1("🔖 বুকমার্ক করা সাইট")
        AppBtn("← ড্যাশবোর্ড", color = C.Gray, onClick = onBack)
        AppBtn(if (showForm) "✕ বন্ধ করুন" else "➕ এড বুকমার্ক", color = C.Green, modifier = Modifier.padding(top = 10.dp), onClick = { showForm = !showForm })

        if (showForm) {
            InfoCard(Modifier.padding(top = 10.dp)) {
                Text("সাইটের লিংক", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                AppField(url, { url = it }, placeholder = "https://example.com", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
                FieldLabel("নাম (ঐচ্ছিক)")
                AppField(name, { name = it }, placeholder = "না দিলে সাইটের ডোমেইন নাম থাকবে")
                vm.formError?.let { Text("⚠️ $it", color = C.Red, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                AppBtn(if (vm.adding) "অপেক্ষা করুন..." else "✅ সেভ করুন", color = C.Green, enabled = !vm.adding, modifier = Modifier.padding(top = 10.dp)) {
                    vm.add(url, name) { url = ""; name = ""; showForm = false }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        when {
            vm.loadError != null -> Text("⚠️ ${vm.loadError}", color = C.Red)
            vm.loading && vm.items.isEmpty() -> Text("লোড হচ্ছে...")
            vm.items.isEmpty() -> Text("কোনো বুকমার্ক নেই। উপরে সাইট এড করুন।")
        }
        vm.items.forEach { it0 -> BookmarkCard(it0, vm, onDelete = { toDelete = it0 }) }
        Spacer(Modifier.height(16.dp))
    }

    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = Color.White,
            text = { Text("এই বুকমার্ক মুছবেন?") },
            confirmButton = { TextButton(onClick = { vm.delete(item); toDelete = null }) { Text("মুছুন", color = C.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("বাতিল", color = C.Gray) } },
        )
    }
}

@Composable
private fun BookmarkCard(item: BookmarkItem, vm: BookmarkViewModel, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val ps = vm.posts[item.id]
    InfoCard(Modifier.padding(top = 10.dp)) {
        Text(item.name, fontWeight = FontWeight.Bold)
        Text(
            item.url, fontSize = 13.sp, color = C.Blue, textDecoration = TextDecoration.Underline,
            modifier = Modifier.padding(top = 2.dp).clickable { openUrl(ctx, item.url) },
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn(
                if (ps?.busy == true) "অপেক্ষা করুন..." else "📰 সর্বশেষ ৫টি পোস্ট দেখুন", color = C.Blue,
                enabled = ps?.busy != true, modifier = Modifier.weight(1f),
            ) { vm.showPosts(item) }
            AppBtn("🗑️ মুছুন", color = C.Gray, modifier = Modifier.weight(0.6f), onClick = onDelete)
        }
        ps?.error?.let { Text("⚠️ $it", color = C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        ps?.posts?.let { list ->
            if (list.isEmpty()) Text("কোনো পোস্ট পাওয়া যায়নি", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            list.forEach { p ->
                HorizontalDivider(color = C.Line, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        p.title, color = C.Blue, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.weight(1f).clickable { openUrl(ctx, p.link) },
                    )
                    CopyBtn(p.link, clip)
                }
            }
        }
    }
}

@Composable
private fun CopyBtn(link: String, clip: androidx.compose.ui.platform.ClipboardManager) {
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) { kotlinx.coroutines.delay(1500); done = false } }
    Surface(
        shape = RoundedCornerShape(6.dp), color = Color.White, border = BorderStroke(1.dp, C.Border),
        modifier = Modifier.clickable { clip.setText(AnnotatedString(link)); done = true },
    ) {
        Text(if (done) "✅ কপি হয়েছে" else "📋 কপি", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}
