package com.digitalcrafting25.newsapp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val editorVm: EditorViewModel by viewModels()
    private val updateVm: UpdateViewModel by viewModels()
    private val authVm: AuthViewModel by viewModels()
    private val dashVm: DashboardViewModel by viewModels()
    private val bmVm: BookmarkViewModel by viewModels()
    private val setVm: SettingsViewModel by viewModels()
    private val siteVm: SiteViewModel by viewModels()
    private val adsVm: AdsViewModel by viewModels()
    private val schedVm: ScheduleViewModel by viewModels()
    private val cardVm: PhotoCardViewModel by viewModels()
    private val newPostVm: NewPostViewModel by viewModels()
    private val autoVm: AutoPostViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // অ্যাপ খোলা থাকলে স্ক্রিন নিজে থেকে বন্ধ হবে না
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            NewsTheme {
                val locked by Net.locked.collectAsStateWithLifecycle()
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                var checking by remember { mutableStateOf(false) }
                var offMsg by remember { mutableStateOf("") }
                // লগইন হলে (ও নেট এলে) Worker থেকে সর্বশেষ রেট আনা — অ্যাডমিন যেটা 💰 রেট মেনুতে ঠিক করেছে
                val rateLoggedIn by authVm.loggedIn.collectAsStateWithLifecycle()
                LaunchedEffect(rateLoggedIn, locked) { if (rateLoggedIn && !locked) Rates.refresh(ctx) }
                Box(Modifier.fillMaxSize()) {
                    // নিচের অ্যাপ চালু থাকে (স্টেট হারায় না); ইন্টারনেট গেলে ওপরে ওয়ার্নিং স্ক্রিন বসে সবকিছু ঢেকে দেয়
                    if (BuildConfig.IS_ADMIN) AdminRoot(authVm, updateVm, setVm, autoVm, !locked)   // অ্যাডমিন অ্যাপ: শুধু হিসাব/ব্যালেন্স/কন্ট্রোল
                    else AppRoot(editorVm, updateVm, authVm, dashVm, bmVm, setVm, siteVm, adsVm, schedVm, cardVm, newPostVm, autoVm, !locked)
                    if (locked) {
                        OfflineScreen(checking = checking, message = offMsg) {
                            if (checking) return@OfflineScreen
                            checking = true; offMsg = ""
                            scope.launch {
                                val ok = try { Net.verify(ctx) } catch (e: Exception) { false }
                                if (!ok) offMsg = "এখনো ইন্টারনেট পাওয়া যায়নি। সংযোগ ঠিক করে আবার চেক করুন।"
                                checking = false
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); AppState.foreground = true; Net.recheck() }
    override fun onStop() { super.onStop(); AppState.foreground = false; if (!BuildConfig.IS_ADMIN) editorVm.saveDraft(sync = true) }
}

@Composable
fun AppRoot(ev: EditorViewModel, uv: UpdateViewModel, auth: AuthViewModel, dash: DashboardViewModel, bm: BookmarkViewModel, sv: SettingsViewModel, site: SiteViewModel, adsVm: AdsViewModel, sched: ScheduleViewModel, card: PhotoCardViewModel, np: NewPostViewModel, autoVm: AutoPostViewModel, online: Boolean = true) {
    val loggedIn by auth.loggedIn.collectAsStateWithLifecycle()
    if (!loggedIn) {
        LoginScreen(auth)
        return
    }
    val upd by uv.state.collectAsStateWithLifecycle()
    LaunchedEffect(online) { if (online) uv.autoCheck() }
    if (upd.showPopup && upd.available != null) {
        val info = upd.available!!
        AlertDialog(
            onDismissRequest = { if (!upd.busy) uv.dismissPopup() },
            title = { Text("🎉 নতুন আপডেট এসেছে") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (upd.downloaded) {
                    Button(onClick = { uv.openDownloads() }) { Text("📂 Download ফোল্ডার খুলুন") }
                } else {
                    Button(onClick = { uv.startDownload() }, enabled = !upd.busy) { Text(if (upd.busy) "ডাউনলোড হচ্ছে…" else "⬇️ ডাউনলোড") }
                }
            },
            dismissButton = { if (!upd.busy) TextButton(onClick = { uv.dismissPopup() }) { Text(if (upd.downloaded) "বন্ধ" else "পরে") } },
        )
    }
    SideEffect { np.onOldCancelled = { sched.markGone(it) } }   // এডিট করা পোস্ট নতুন করে সিডিউল হলে পুরনোটা তালিকা থেকে সরে যায়
    var page by rememberSaveable { mutableStateOf(if (ev.resultOutputs != null) Page.NEW else Page.DASH) }   // আগের রেন্ডার থাকলে অ্যাপ খুলেই সেখানে
    var navH by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    BackHandler(enabled = page != Page.DASH) { page = Page.DASH }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Column(Modifier.fillMaxSize()) {
            if (!EditorUi.fullscreen) TopBar(onLogout = { dash.reset(); bm.reset(); sv.reset(); site.reset(); adsVm.reset(); sched.reset(); card.reset(); np.reset(); autoVm.reset(); ev.clearRender(); auth.logout() })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // সফল চেকের পর পেজ নতুন করে বসে, ফলে Firebase থেকে ডেটা আবার আসে (নতুন পোস্ট এডিটরের অবস্থা অক্ষত থাকে)
                val tick by Net.refreshTick.collectAsStateWithLifecycle()
                key(if (page == Page.NEW) 0 else tick) {
                when (page) {
                    Page.DASH -> DashboardScreen(dash, onOpenBookmarks = { page = Page.BOOKMARKS })
                    Page.BOOKMARKS -> BookmarkScreen(bm, onBack = { page = Page.DASH })
                    Page.NEW -> NewPostHost(ev, np, adsVm, onScheduled = { page = Page.SCHEDULE }, onDelete = {
                        // এডিটের মাঝে ডিলিট: সার্ভারের সিডিউল মুছে, ফোনের কপি মুছে, সিডিউল পাতায় ফেরা
                        np.editItem?.let { sched.cancel(it) }
                        np.discardEdit()
                        page = Page.SCHEDULE
                    })
                    Page.CARD -> PhotoCardScreen(card, adsVm, np)
                    Page.SCHEDULE -> ScheduleScreen(sched, hasDraft = { np.hasWork() }, onEdit = { item ->
                        sched.markBusy(item.id, true)   // লেখা আনার সময় বাটন বন্ধ
                        np.beginEdit(item) { err ->
                            sched.markBusy(item.id, false)
                            if (err == null) page = Page.NEW else sched.showNotice(err)
                        }
                    })
                    Page.SETTINGS -> SettingsScreen(sv, site, adsVm, uv, autoVm, np, dash, ev, card)
                }
                }
            }
            if (!EditorUi.fullscreen) BottomNav(page, onSelect = { page = it }, onHeight = { navH = it })
        }
        // গোল ড্যাশবোর্ড বাটন: মেনুর উপরে ভাসে; নিচের কিনারা মেনুর ভেতরে ৩৫dp নিচে
        if (navH > 0 && !EditorUi.fullscreen) {
            val bottomPad = with(density) { navH.toDp() } - 35.dp
            NavCircle(
                on = page == Page.DASH,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad),
                onClick = { page = Page.DASH },
            )
        }
    }
}

/** ইন্টারনেট না থাকলে হোম/লগইন সবকিছুর ওপরে এই ওয়ার্নিং দেখায়; সংযোগ ফিরলে "চেক করুন" চাপলে তবেই সরে যায় */
@Composable
fun OfflineScreen(checking: Boolean, message: String, onCheck: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F5F7))
            .pointerInput(Unit) { detectTapGestures { } } // নিচের স্ক্রিনে ট্যাপ যেতে দেয় না
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📡", fontSize = 56.sp)
        Text(
            "ইন্টারনেট সংযোগ নেই",
            fontSize = 20.sp,
            color = Color(0xFF222222),
            modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
        )
        Text(
            "সংযোগ ফিরলে নিচের বাটন চেপে চেক করুন।",
            fontSize = 15.sp,
            color = Color(0xFF666666),
            textAlign = TextAlign.Center,
        )
        if (message.isNotEmpty()) {
            Text(
                "⚠️ $message", fontSize = 14.sp, color = C.Red, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        AppBtn(
            if (checking) "চেক হচ্ছে..." else "🔄 চেক করুন", enabled = !checking,
            modifier = Modifier.padding(top = 20.dp), onClick = onCheck,
        )
    }
}

@Composable
fun ComingSoon(title: String, text: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        H1(title)
        InfoCard { Text(text) }
    }
}

@Composable
fun NewPostHost(ev: EditorViewModel, np: NewPostViewModel, ads: AdsViewModel, onScheduled: () -> Unit, onDelete: () -> Unit = {}) {
    var editor by remember { mutableStateOf(false) }
    var pendingOpen by remember { mutableStateOf(false) }
    // "ভিডিও বাছুন" → গ্যালারি (শুধু ভিডিও) → বাছা ভিডিও টাইমলাইনে যোগ হলে এডিটর খোলে; না বাছলে এখানেই থাকে
    // (গ্যালারি বা ফাইল — .ts সহ; চাপলে কোথা থেকে বাছবেন জিজ্ঞেস করে)
    val pickVideo = rememberMediaPicker(images = false, single = true) { list ->
        val u = list.firstOrNull()
        if (u != null) {
            ev.startNew()   // আগের এডিট ড্রাফটে থাকে; নতুন ভিডিও নতুন করে শুরু (আগেরটার সাথে জোড়া লাগবে না)
            ev.addUris(listOf(u), seed = true)   // ইন্ট্রো-আউট্রো, নিচের বিজ্ঞাপন, লোগো, ট্যাগ, স্ক্রলিং লেখা নিজে বসবে (পরে মোছা/এডিট করা যায়)
            pendingOpen = true
        }
    }
    val hasClips = ev.tl.clips.isNotEmpty()
    // ক্লিপ যোগ হওয়া শেষ হলে তবেই এডিটর খোলে; সব ক্লিপ মুছে গেলে নতুন পোস্টে ফেরে (ফাঁকা এডিটর স্ক্রিন নেই)
    LaunchedEffect(pendingOpen, hasClips) { if (pendingOpen && hasClips) { editor = true; pendingOpen = false } }
    LaunchedEffect(editor, hasClips) { if (editor && !hasClips) editor = false }
    // এডিটর খোলা থাকলে ওপরের হেডার ও নিচের মেনুবার লুকানো
    DisposableEffect(editor) {
        EditorUi.fullscreen = editor
        onDispose { EditorUi.fullscreen = false }
    }
    BackHandler(enabled = editor) { ev.saveDraft(); editor = false }
    // রেন্ডার শেষে HTML-এর "২ ভিডিও" পেইজ; ব্যাক = এডিটে ফেরা, "নতুন পোস্টে ফিরুন" = সাধারণ নতুন পোস্ট
    // (রেন্ডার মনে রাখা হয়: অ্যাপ বন্ধ করে খুললেও ev.resultOutputs ফিরে আসে)
    val nt = np.title
    SideEffect { ev.titleSeed = nt }   // ভিডিও টাইটেল আগে থেকে বসাতে নতুন পোস্টের শিরোনাম
    val resultOutputs = ev.resultOutputs
    if (resultOutputs != null) {
        VideoResultScreen(
            ev, resultOutputs,
            onBack = {
                ev.resultOutputs = null
                // অ্যাপ নতুন করে খোলার পর টাইমলাইন ফাঁকা থাকে; সর্বশেষ এডিট ড্রাফট খুলে এডিটে ফেরা
                ev.loadEditForRender()
                editor = true
            },
            onNewPost = { ev.resultOutputs = null },
        )
    } else if (editor) {
        VideoEditorScreen(ev, onBack = { ev.saveDraft(); editor = false }, onRendered = { o -> ev.saveDraft(); editor = false; ev.openResult(o) })
    } else {
        NewPostScreen(np, ads, onOpenVideo = {
            pickVideo()
        }, onScheduled = onScheduled, onDelete = onDelete, header = {
            DraftList(ev, onOpen = { id -> if (ev.openDraft(id)) editor = true })
        })
    }
}

/** HTML `#vcResume`: 📝 অসমাপ্ত ভিডিও এডিট — ছোট সারি: বাঁয়ে নাম + দৈর্ঘ্য/লেয়ার/সময় (২-৩ লাইন), পাশে ▶️ ও 🗑️ বাটন */
@Composable
fun DraftList(ev: EditorViewModel, onOpen: (Long) -> Unit) {
    val list = ev.drafts
    var delId by remember { mutableStateOf<Long?>(null) }
    if (list.isEmpty()) return
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("📝 অসমাপ্ত ভিডিও এডিট" + if (list.size > 1) " (${list.size}টি)" else " আছে", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
        list.toList().forEach { d ->
            val total = d.clips.sumOf { it.lengthMs() }
            val time = runCatching {
                java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale("bn", "BD")).format(java.util.Date(d.savedAt))
            }.getOrDefault("")
            Row(
                Modifier.fillMaxWidth().clip(shape)
                    .background(androidx.compose.ui.graphics.Color(0xFFF3F4F6))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("🎞️ ${d.clips.firstOrNull()?.name ?: ""}", fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(
                        "দৈর্ঘ্য ${fmtTime(total)}" + (if (d.layers.isNotEmpty()) " · লেয়ার ${d.layers.size}টি" else "") +
                            (if (time.isNotEmpty()) "\nসেভ: $time" else ""),
                        fontSize = 11.sp, color = C.Muted, lineHeight = 14.sp,
                    )
                }
                Box(
                    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(C.Green)
                        .clickable { onOpen(d.id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("▶️ চালান", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, softWrap = false) }
                Box(
                    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(C.Gray)
                        .clickable { delId = d.id }.padding(horizontal = 9.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("🗑️", fontSize = 12.sp) }
            }
        }
    }
    delId?.let { id ->
        AlertDialog(
            onDismissRequest = { delId = null },
            text = { Text(if (ev.lastRender?.draftId == id) "এই অসমাপ্ত এডিট ও পুরাতন রেন্ডার মুছে ফেলবেন?" else "এই অসমাপ্ত এডিট মুছে ফেলবেন?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { ev.removeDraft(id); delId = null }) { Text("মুছুন") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { delId = null }) { Text("না") } },
        )
    }
}

@Composable
fun Thumb(uri: Uri) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(160, 160), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(Color(0xFFDDDDDD))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun NumField(label: String, value: Float, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    var text by remember { mutableStateOf(if (value == 0f) "" else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it.toFloatOrNull() ?: 0f) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun UpdateScreen(vm: UpdateViewModel, embedded: Boolean = false) {
    val st by vm.state.collectAsStateWithLifecycle()
    Column(
        if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("বর্তমান সংস্করণ: ${BuildConfig.VERSION_NAME} (কোড ${BuildConfig.VERSION_CODE})")
        Button(onClick = { vm.check() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("🔍 এখনই আপডেট চেক করুন")
        }
        if (st.message.isNotEmpty()) Text(st.message)
        st.progress?.let { p ->
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            Text("${(p * 100).toInt()}%")
        }
        st.available?.let { info ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("নতুন সংস্করণ: ${info.versionName}")
                    if (st.downloaded) {
                        Text("Download ফোল্ডারে APK সেভ হয়েছে। ফোল্ডার খুলে APK-তে ট্যাপ করে ইন্সটল করুন।")
                        Button(onClick = { vm.openDownloads() }) { Text("📂 Download ফোল্ডার খুলুন") }
                    } else {
                        Button(onClick = { vm.startDownload() }, enabled = !st.busy) { Text("⬇️ ডাউনলোড") }
                    }
                }
            }
        }
    }
}
