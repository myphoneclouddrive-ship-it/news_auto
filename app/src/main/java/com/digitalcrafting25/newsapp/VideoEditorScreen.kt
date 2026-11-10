package com.digitalcrafting25.newsapp

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ভিডিও এডিটর স্ক্রিন — প্রিমিয়াম ডার্ক (CapCut ধাঁচ):
 *  ওপরে: [← পেছনে · 720P · পাবলিশ] → প্রিভিউ স্টেজ → কন্ট্রোল (সময · ◀ ▶ ▷ · আনডু/রিডু) → জুম সারি → টাইমলাইন
 *  নিচে (আটকানো): আইকন টুলবার (অডিও / লেখা / ছবি / ওভারলে / ব্র্যান্ড / বিজ্ঞাপন / ইন্ট্রো-আউট্রো / এডজাস্ট);
 *  কিছু বাছলে সেই ক্লিপ/লেয়ারের টুল; এডিট প্যানেল খুললে নিচে শিট (নাম + ✓)
 */
/** HTML-এ যেসব নিয়ন্ত্রণ নেই (মাপ/শিরোনাম/লোগো/মিউজিক) সেগুলো দেখাবে কিনা */
private const val SHOW_EXTRA_CONTROLS = false

@Composable
fun VideoEditorScreen(vm: EditorViewModel, onBack: () -> Unit = {}, onRendered: (List<RenderOutput>) -> Unit = {}) {
    val ctx = LocalContext.current
    val tl = vm.tl
    val status by RenderBus.status.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addUris(it) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) vm.logo = u }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.chooseMusic(u) }

    val setError: (String?) -> Unit = { m -> error = m; if (m != null) info = null }
    val setInfo: (String?) -> Unit = { m -> info = m; if (m != null) error = null }
    val act = rememberDockActions(tl, setError, setInfo)
    // ডিফল্ট বসানোর পর কিছু না বসলে (লোগো/ব্যানার/ট্যাগ ইত্যাদি) একবার জানানো
    LaunchedEffect(vm.seedWarn, vm.seedMsg) {
        val w = vm.seedWarn
        if (w != null && vm.seedMsg == null) { setError(w); vm.seedWarn = null }
    }
    // প্রতিটা বদলের একটু পরে নিজে নিজে সেভ (অ্যাপ কেটে দিলেও এডিট থাকবে)
    LaunchedEffect(tl.clips, tl.layers) {
        if (tl.clips.isNotEmpty()) { kotlinx.coroutines.delay(700); vm.saveDraft() }
    }
    // ক্লিপ/লেয়ারে ট্যাপ করলে শুধু বাছা হয় (প্যানেল খোলে না); ডকের ✏️ এডিট চাপলে খোলে, ✓ বা অন্য কিছু বাছলে বন্ধ
    var editOpen by remember(tl.selectedLayerId, tl.selectedId) { mutableStateOf(false) }
    val addMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(ED.Screen).padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---------- ওপরের অংশ: প্রিভিউ পুরো বাকি উচ্চতা নেয় ----------
        // এডিট প্যানেল খোলা থাকলে ওপরে ছোট প্রিভিউ + প্লে/সময় বার থেকে যায় (প্রিভিউ ঢাকা পড়ে না), নিচে প্যানেল; টাইমলাইন/জুম লুকানো
        val selClipNow = tl.clipById(tl.selectedId)
        val selLayerNow = tl.layerById(tl.selectedLayerId)
        val panelOpen = editOpen && (selLayerNow != null || selClipNow?.card != null || (selClipNow != null && !selClipNow.isImage))
        if (tl.clips.isNotEmpty()) {
            // weight অবশ্যই এখানেই (সরাসরি এই Column-এর সন্তানে) দিতে হয়; StageCard-এর ভেতরে Surface থাকায় ভেতরে দিলে কাজ করত না,
            // ফলে স্টেজ পুরো উচ্চতা খেয়ে নিচের মেনু (ডক) ০ উচ্চতা পেত
            Box(if (panelOpen) Modifier.weight(0.42f).fillMaxWidth() else Modifier.weight(1f).fillMaxWidth()) {
                StageCard(
                    Modifier.fillMaxSize(),
                    vm, status, error, info, setError, setInfo, addMedia, act.addAudioFile, onRendered, onBack,
                    compact = panelOpen,
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        // ---------- এডিট প্যানেল: ওপরে শিট-হেডার (নাম + ✓), নিচে স্ক্রল হওয়া সেটিং ----------
        if (panelOpen) {
            SheetHeader(
                title = selLayerNow?.kind?.label ?: if (selClipNow?.card != null) "ইন্ট্রো/আউট্রো" else "ক্লিপের নয়েজ ক্যানসেল",
                onDone = { editOpen = false },
            )
        }
        Column(
            (if (panelOpen) Modifier.weight(0.58f) else Modifier.heightIn(max = 0.dp))
                .fillMaxWidth().background(ED.Panel).verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tl.clips.isNotEmpty()) EditorDockPanel(tl, act, vm, editOpen, setError, setInfo)

            // HTML-এ নেই বলে বন্ধ: ভিডিওর মাপ, শিরোনাম, লোগো, মিউজিক, আসল সাউন্ড চেকবক্স।
            // (মাপ এখন পাবলিশ ডায়ালগ থেকে ঠিক হয়; চালু করতে SHOW_EXTRA_CONTROLS = true)
            if (SHOW_EXTRA_CONTROLS) {
            Text("ভিডিওর মাপ")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Aspect.values().forEach { a ->
                    FilterChip(selected = vm.aspect == a, onClick = { vm.aspect = a }, label = { Text(a.label) })
                }
            }

            OutlinedTextField(
                value = vm.caption,
                onValueChange = { vm.caption = it },
                label = { Text("শিরোনাম (নিচে লাল বারে দেখাবে, ঐচ্ছিক)") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (vm.logo == null) "🖼️ লোগো বাছুন" else "🖼️ লোগো বদলান") }
                if (vm.logo != null) TextButton(onClick = { vm.logo = null }) { Text("লোগো সরান") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickMusic.launch(arrayOf("audio/*")) }) {
                    Text(if (vm.music == null) "🎵 মিউজিক বাছুন" else "🎵 বদলান")
                }
                if (vm.music != null) TextButton(onClick = { vm.music = null }) { Text("মিউজিক সরান") }
            }
            if (vm.music != null) {
                Text("মিউজিক: ${vm.musicName}", maxLines = 1)
                Text("মিউজিকের ভলিউম: ${(vm.musicVolume * 100).toInt()}%")
                Slider(value = vm.musicVolume, onValueChange = { vm.musicVolume = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(checked = vm.keepAudio, onCheckedChange = { vm.keepAudio = it })
                Text("ভিডিওর আসল সাউন্ড রাখুন", Modifier.padding(top = 12.dp))
            }

            }

            when (val s = status) {
                is RenderStatus.Running -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(renderLabel(s))
                        LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("অ্যাপ ব্যাকগ্রাউন্ডে গেলেও চলবে। নোটিফিকেশনে % দেখা যাবে।")
                        OutlinedButton(onClick = { vm.cancelRender() }) { Text("বাতিল") }
                    }
                }
                is RenderStatus.Done -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (s.outputs.size > 1) "✅ ${s.outputs.size}টি ভিডিও তৈরি হয়েছে" else "✅ ভিডিও তৈরি হয়েছে")
                        s.outputs.forEach { o ->
                            if (s.outputs.size > 1) Text("${o.label} · ${o.w}×${o.h}", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_VIEW).setDataAndType(VideoShare.shareable(ctx, o.uri), "video/mp4")
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                        )
                                    }
                                }) { Text("▶️ দেখুন") }
                                OutlinedButton(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
                                        .putExtra(Intent.EXTRA_STREAM, VideoShare.shareable(ctx, o.uri))
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    runCatching { ctx.startActivity(Intent.createChooser(send, "শেয়ার করুন")) }
                                }) { Text("📤 শেয়ার") }
                            }
                        }
                    }
                }
                is RenderStatus.Failed -> Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
                RenderStatus.Idle -> {}
            }
        }

        // ---------- নিচে আটকানো মেনু (HTML `#vc_dock`) ----------
        if (tl.clips.isNotEmpty()) {
            EditorDockBar(tl, act, setError, setInfo, vm, editOpen, { editOpen = !editOpen }, onBack)
        }
    }
    // HTML-এর মতো: রেন্ডার শুরু হলে ওপরে পপ-আপ (প্রসেস বার + % + সময়); চলার সময় এডিটরের কোনো অপশন কাজ করে না
    (status as? RenderStatus.Running)?.let { run -> RenderProgressPopup(run) { vm.cancelRender() } }
    }
}

/** রেন্ডার চলাকালীন ওপরের পপ-আপ: পুরো পর্দা ঢেকে সব ট্যাপ/টানা আটকায় (পেছনে এডিটর কাজ করে না), ব্যাক বোতামও বন্ধ */
@Composable
private fun RenderProgressPopup(s: RenderStatus.Running, onCancel: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = true) { }
    val t0 = remember { System.currentTimeMillis() }
    var now by remember { mutableStateOf(t0) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    val all = (s.progress / 100f).coerceIn(0f, 1f)
    val el = ((now - t0) / 1000f).coerceAtLeast(0f)
    fun fmt(sec: Float): String { val t = sec.toInt(); return "${t / 60}:${(t % 60).toString().padStart(2, '0')}" }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
            },
    ) {
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth()
                .background(ED.Panel, RoundedCornerShape(14.dp))
                .border(1.dp, ED.Border, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("⏳ রেন্ডার হচ্ছে…", color = ED.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { all }, modifier = Modifier.fillMaxWidth().height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("${s.progress}%", color = ED.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "সময়: " + fmt(el) + (if (all > 0.03f && all < 1f) " · বাকি ≈ " + fmt(el / all * (1f - all)) else ""),
                    color = ED.Sub, fontSize = 13.sp,
                )
            }
            Text(
                if (s.passCount > 1) "${s.label} ভিডিও রেন্ডার হচ্ছে (${s.pass}/${s.passCount})" else "ভিডিও রেন্ডার হচ্ছে",
                color = ED.Sub, fontSize = 13.sp,
            )
            Text("অ্যাপ ব্যাকগ্রাউন্ডে গেলেও চলবে, নোটিফিকেশনে % দেখা যাবে। রেন্ডার চলাকালীন এডিট করা যাবে না।", color = ED.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("⏹ বাতিল") }
        }
    }
}

/** রেন্ডারের অগ্রগতির লেখা; দুই ভিডিও হলে HTML-এর মতো "ফেইসবুক ভিডিও রেন্ডার হচ্ছে (১/২)" */
private fun renderLabel(s: RenderStatus.Running): String =
    if (s.passCount > 1) "${s.label} ভিডিও রেন্ডার হচ্ছে (${s.pass}/${s.passCount})… ${s.progress}%" else "রেন্ডার হচ্ছে… ${s.progress}%"

/** ডার্ক কার্ড: স্টেজ + কন্ট্রোল বার + নাজ বার + টাইমলাইন + বার্তা */
@Composable
private fun StageCard(
    modifier: Modifier,
    vm: EditorViewModel,
    status: RenderStatus,
    error: String?,
    info: String?,
    setError: (String?) -> Unit,
    setInfo: (String?) -> Unit,
    onAddMedia: () -> Unit,
    onAddAudio: () -> Unit,
    onRendered: (List<RenderOutput>) -> Unit,
    onBack: () -> Unit,
    compact: Boolean = false,   // এডিট প্যানেল খোলা: শুধু প্রিভিউ + প্লে বার (টপ বার, জুম, টাইমলাইন নেই)
) {
    val tl = vm.tl
    val total = tl.totalMs
    val running = status is RenderStatus.Running
    val shape = RoundedCornerShape(14.dp)
    var playing by remember { mutableStateOf(false) }
    var showPublish by remember { mutableStateOf(false) }
    // পাবলিশ চেপে রেন্ডার শুরু হলে, শেষ হওয়ামাত্র "২ ভিডিও" পেইজে যাবে (আগের পুরোনো Done ধরে নয়: আগে Running দেখতে হবে)
    var awaiting by remember { mutableStateOf(false) }
    var sawRunning by remember { mutableStateOf(false) }
    LaunchedEffect(status, awaiting) {
        if (!awaiting) return@LaunchedEffect
        when (status) {
            is RenderStatus.Running -> sawRunning = true
            is RenderStatus.Done -> if (sawRunning) { awaiting = false; sawRunning = false; onRendered(status.outputs) }
            is RenderStatus.Failed -> if (sawRunning) { awaiting = false; sawRunning = false }
            else -> {}
        }
    }
    // আসল প্রিভিউ প্লেয়ার: ক্লিপ বদলালেই আগে থেকে prepare হয়ে থাকে
    val ctx = LocalContext.current
    val pp = remember { PreviewPlayer(ctx) }
    DisposableEffect(Unit) { onDispose { pp.release() } }
    val clipSig = PreviewPlayer.signature(tl.clips)
    // ট্রিম টানার সময় প্রতি ধাপে পুরো প্লেলিস্ট prepare না করে একটু থেমে (১৫০ মি.সে.) একবার করে
    LaunchedEffect(clipSig) {
        // প্লে চলার সময় ক্লিপ বদলালে প্লেলিস্ট নতুন করে সাজাতে প্লেয়ার থেমে যায়; বোতামও তাই থামে
        // (না হলে বোতাম "চলছে" দেখায় অথচ ভিডিও চলে না)
        if (playing) playing = false
        // ইন্ট্রো/আউট্রো এনকোডের সময় ডিকোডার ধরা হয় না; শেষে cardBusy-র ইফেক্ট refresh করে
        if (vm.cardBusy) return@LaunchedEffect
        if (pp.hasClips) kotlinx.coroutines.delay(150)
        if (vm.cardBusy) return@LaunchedEffect
        pp.setClips(tl.clips); pp.seekTo(tl.playheadMs)
    }
    // ইন্ট্রো/আউট্রো এনকোড চলার সময় প্লেয়ারের ডিকোডার ছেড়ে দেয়, শেষে আবার সাজায়
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(vm.cardBusy) {
        if (vm.cardBusy) { wasBusy = true; playing = false; pp.suspendForEncode() }
        else if (wasBusy) { wasBusy = false; if (tl.clips.isNotEmpty()) { pp.refresh(tl.clips); pp.seekTo(tl.playheadMs) } }
    }
    // অ্যাপ ব্যাকগ্রাউন্ডে গেলে থামে, ফিরলে সার্ফেস/ডিকোডার নতুন করে সাজায়
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { playing = false; pp.pause() }
                Lifecycle.Event.ON_START -> if (tl.clips.isNotEmpty() && !vm.cardBusy) { pp.refresh(tl.clips); pp.seekTo(tl.playheadMs) }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // থামা অবস্থায় লাল দাগ সরালে প্লেয়ারও সেখানে যায় (ফ্রেম দেখায়)
    LaunchedEffect(tl.playheadMs, playing, clipSig) { if (!playing) pp.seekTo(tl.playheadMs) }
    LaunchedEffect(playing) {
        if (!playing) { pp.pause(); return@LaunchedEffect }
        val total = tl.totalMs
        if (total <= 0L) { playing = false; return@LaunchedEffect }
        pp.setClips(tl.clips)
        if (tl.playheadMs >= total - 50) tl.playheadMs = 0L
        pp.seekTo(tl.playheadMs)
        pp.play()
        var lastWritten = tl.playheadMs
        // আটকে যাওয়া ধরা: ৪ সেকেন্ড পজিশন না এগোলে একবার প্লেলিস্ট নতুন করে সাজায়; তবুও না চললে কারণ দেখিয়ে থামে
        var stallPos = -1L
        var stallAt = System.nanoTime()
        var recovered = false
        while (playing) {
            withFrameNanos { it }
            // প্লের মধ্যে ব্যবহারকারী লাল দাগ সরালে সেখান থেকে চলবে
            if (kotlin.math.abs(tl.playheadMs - lastWritten) > 150L) pp.seekTo(tl.playheadMs)
            val pos = pp.positionMs().coerceAtMost(total)
            tl.playheadMs = pos
            lastWritten = pos
            pp.syncLayers(tl.layers, pos, true)
            if (pp.ended() || pos >= total - 30L) playing = false
            val now = System.nanoTime()
            if (pos != stallPos) { stallPos = pos; stallAt = now }
            else if (playing && now - stallAt > 4_000_000_000L) {
                if (!recovered) {
                    recovered = true
                    pp.refresh(tl.clips); pp.seekTo(pos); pp.play()
                    stallAt = now
                } else {
                    setError("প্রিভিউ চলছে না" + (pp.lastError?.let { " ($it)" } ?: "") + " — আবার প্লে চাপুন, না হলে এডিটর বন্ধ করে খুলুন")
                    playing = false
                }
            }
        }
        pp.pause()
    }
    LaunchedEffect(running) { if (running) playing = false }
    if (showPublish) PublishDialog(
        vm = vm,
        aspect = vm.aspect,
        hasTopAd = tl.layers.any { it.kind == LayerKind.AD_TOP },
        hasBottomAd = tl.layers.any { it.kind == LayerKind.AD_BOTTOM },
        onDismiss = { showPublish = false },
        onOld = { showPublish = false; vm.reopenLast() },
        onRender = { fb, yt, wp ->
            showPublish = false
            val err = vm.startRender(fb = fb, yt = yt, wp = wp)
            setError(err)
            if (err == null) { awaiting = true; sawRunning = false }
        },
    )
    EditorDarkTheme {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    // প্রিভিউ সর্বোচ্চ ~৪৮% উচ্চতা নেয় (১৬:৯ ভিডিও নিজের মাপেই বসে, ওপর-নিচে ফাঁকা থাকে না); বাকিটা টাইমলাইন
    val previewMaxH = if (compact) (maxHeight - 56.dp).coerceAtLeast(80.dp) else maxHeight * 0.48f
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ওপরের বার: পেছনে · 720P · পাবলিশ
        if (!compact) EditorTopBar(onBack = onBack, publishEnabled = !running, onPublish = { playing = false; showPublish = true }, aspect = vm.aspect, onAspect = { vm.changeAspect(it) })

        FramePreview(tl, vm.aspect, pp, playing, Modifier.heightIn(max = previewMaxH))

        // কন্ট্রোল বার: সময় (বাঁয়ে) · ◀০.১ ▶প্লে ০.১▶ (মাঝে) · আনডু/রিডু (ডানে)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(tl.playheadMs), color = ED.Text, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1, softWrap = false)
                Text(" / ${fmtTime(total)}", color = ED.Muted, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1, softWrap = false)
            }
            IconBtn("chevL", size = 36.dp, iconSize = 20.dp) { tl.playheadMs = (tl.playheadMs - 100).coerceAtLeast(0) }
            IconBtn(if (playing) "pause" else "play", circle = true, size = 46.dp, iconSize = 22.dp) {
                if (tl.totalMs <= 0L) return@IconBtn
                if (running) { setInfo("⏳ রেন্ডার চলছে — শেষ হলে প্লে করা যাবে"); return@IconBtn }
                if (vm.cardBusy) { setInfo("🎬 ইন্ট্রো/আউট্রো বানানো চলছে — শেষ হলে প্লে করুন"); return@IconBtn }
                playing = !playing
            }
            IconBtn("chevR", size = 36.dp, iconSize = 20.dp) { tl.playheadMs = (tl.playheadMs + 100).coerceAtMost(tl.totalMs) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconBtn("undo", enabled = tl.canUndo, size = 36.dp, iconSize = 19.dp) { playing = false; tl.undo(); setError(null); setInfo(null) }
                IconBtn("redo", enabled = tl.canRedo, size = 36.dp, iconSize = 19.dp) { playing = false; tl.redo(); setError(null); setInfo(null) }
            }
        }

        // জুম (টাইমলাইন): 🔍− স্লাইডার ＋ ⤢ — এক সারিতে
        if (!compact) {
            NudgeZoomBar(tl)
            // টাইমলাইন বাকি জায়গা নেয়; ট্র্যাক বেশি হলে ওপর-নিচে স্ক্রল হয়
            Column(Modifier.weight(1f, fill = true).fillMaxWidth().verticalScroll(rememberScrollState())) {
                EditorTimeline(tl, onAddMedia, onAddAudio)
            }
        }

        // বার্তা / রেন্ডারের অগ্রগতি
        error?.let { Text("⚠️ $it", color = ED.Err, fontSize = 13.sp) }
        info?.let { Text(it, color = ED.Ok, fontSize = 13.sp) }
        vm.seedAsk?.let { SeedChoiceDialog(vm, it) }
        if (vm.aspectMsg != null) AspectProgressDialog(vm)
        if (vm.seedMsg != null) SeedProgressDialog(vm, onBack)
    }
    }
    }
}


/** প্লেহেডের জায়গার ফ্রেম (ভিডিও ১৬:৯ ফ্রেমে ক্রপ করে দেখায়) */
@Composable
private fun FramePreview(tl: Timeline, aspect: Aspect, pp: PreviewPlayer, playing: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val loc = tl.locate(tl.playheadMs)
    val uri = loc?.first?.uri
    val isImg = loc?.first?.isImage == true
    val q = if (loc == null) -1L else loc.second / 100L
    val bmp by produceState<ImageBitmap?>(null, uri, q, playing && !isImg) {
        // প্লের সময় ভিডিও ক্লিপে প্লেয়ারই ছবি দেখায়, তাই ফ্রেম টানা বন্ধ
        value = if (uri == null || (playing && !isImg)) null else withContext(Dispatchers.IO) {
            runCatching {
                val b: Bitmap? = if (isImg) {
                    loadImageThumb(ctx, uri, 640)
                } else {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, uri)
                        r.getFrameAtTime(q * 100L * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        runCatching { r.release() }
                    }
                }
                b?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.aspectRatio(aspect.w / aspect.h.toFloat())   // পোস্ট = ১৬:৯ (১২৮০×৭২০), রিলস = ৯:১৬ (৭২০×১২৮০)
                .clip(RoundedCornerShape(10.dp)).background(Color.Black)
                .border(1.dp, ED.Border, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // ধাপ ৬: ফ্লিপ/জুম/পজিশন — রেন্ডারের ম্যাট্রিক্সের (RenderComposer.clipTransform) সাথে একই নিয়ম
            val c = loc?.first
            val z = (c?.zoom ?: 1f).coerceIn(1f, 4f)
            val fh = c?.flipH == true
            val fv = c?.flipV == true
            val px = (c?.panX ?: 0f).coerceIn(-1f, 1f)
            val py = (c?.panY ?: 0f).coerceIn(-1f, 1f)
            // কন্ট্রাস্ট (এডজাস্ট লেয়ার): ভিডিও/ছবির ওপর রঙের ম্যাট্রিক্স; লেখা-বার-বিজ্ঞাপন (LayerOverlay) এর বাইরে
            // একাধিক এডজাস্ট লেয়ার একসাথে থাকলে রেন্ডারের মতোই গুণ হয় (প্রতিটার গুণক ১ + c)
            // ক্লিপ-জোড়ার ট্রানজিশন (ClipTr): স্লাইড/জুম ভিডিওটাকে সরায়, বাকিগুলো ওপরে ওভারলে (রেন্ডারের একই হিসাব)
            val trNow = ClipTr.at(ClipTr.cuts(tl.clips), tl.playheadMs)
            val trMode = trNow?.first
            val trU = trNow?.second ?: 0f
            val csc = tl.layersAt(tl.playheadMs).filterIsInstance<AdjustLayer>()
                .fold(1f) { a, l -> a * (1f + l.contrast.coerceIn(-1f, 1f)) }.coerceAtLeast(0f)
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    if (trMode == "slide") { translationX = ClipTr.slideFrac(trU) * size.width }
                    if (trMode == "zoom") { val s = ClipTr.zoomScale(trU); scaleX = s; scaleY = s }
                }.then(
                    if (csc == 1f) Modifier else Modifier.drawWithContent {
                        val sc = csc
                        val off = 127.5f * (1f - sc)
                        val paint = androidx.compose.ui.graphics.Paint().apply {
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                                androidx.compose.ui.graphics.ColorMatrix(
                                    floatArrayOf(
                                        sc, 0f, 0f, 0f, off,
                                        0f, sc, 0f, 0f, off,
                                        0f, 0f, sc, 0f, off,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                        drawIntoCanvas { c ->
                            c.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), paint)
                            drawContent()
                            c.restore()
                        }
                    },
                ),
            ) {
            // মূল ভিডিওর অনুপাত: ক্রপ থাকলে বাছা অংশ মূল ফ্রেম থেকেই কেটে ফ্রেমে ফিট হয় (রেন্ডারের Crop → Presentation-এর একই হিসাব)
            val srcAspect: Float = if (isImg) (bmp?.let { it.width / it.height.toFloat() } ?: (aspect.w / aspect.h.toFloat())) else pp.videoAspect
            CropFrame(
                c, srcAspect,
                // জুম/পজিশন/ফ্লিপ ফ্রেম-সাইজের কন্টেইনারে (ক্রপ-ফিটের পরে), রেন্ডারের clipTransform-এর মতো
                layerTx = {
                    scaleX = if (fh) -z else z
                    scaleY = if (fv) -z else z
                    translationX = -px * (z - 1f) * size.width / 2f
                    translationY = -py * (z - 1f) * size.height / 2f
                },
            ) {
            bmp?.let {
                Image(
                    it, null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            // আসল ভিডিও (ExoPlayer) — শুধু ভিডিও ক্লিপে; ছবি-ক্লিপে ওপরের ছবিটাই দেখায়
            // ভিডিও নিজের আসল অনুপাতে বসে ফ্রেম ভরে (বাড়তিটা ক্রপ) — কখনো চ্যাপ্টা হয় না
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val vr: Float = pp.videoAspect
                val br: Float = maxWidth / maxHeight
                val vw: androidx.compose.ui.unit.Dp = if (vr > br) maxHeight * vr else maxWidth
                val vh: androidx.compose.ui.unit.Dp = if (vr > br) maxHeight else maxWidth / vr
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { c -> android.view.TextureView(c).also { pp.player.setVideoTextureView(it) } },
                    onRelease = { tv -> runCatching { pp.player.clearVideoTextureView(tv) } },
                    modifier = Modifier.align(Alignment.Center)
                        .requiredSize(vw, vh)
                        .alpha(if (loc != null && !isImg) 1f else 0f),
                )
            }
            }
            }
            if (trMode != null && trMode != "slide") {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { c -> ClipTr.drawOverlay(c.nativeCanvas, size.width, size.height, trMode, trU) }
                }
            }
            LayerOverlay(tl)
            StageDragLayer(tl, playing)
        }
    }
}

/** ডিগ্রি -১৮০..১৮০ এর ভেতরে রাখে */
private fun wrapDeg(d: Float): Float {
    var r = d % 360f
    if (r > 180f) r -= 360f
    if (r < -180f) r += 360f
    return r
}

/**
 * স্টেজে টেনে সরানো ও দুই আঙুলে বড়-ছোট করা (HTML-এর স্টেজ ড্র্যাগ): বাছা যেকোনো ভিজ্যুয়াল লেয়ারের জন্য।
 * - লেখা, ওয়াটারমার্ক, ছবি/লোগো: এক আঙুলে অবস্থান (x, y), চিমটিতে মাপ, দুই আঙুল ঘোরালে ঘোরে।
 * - ট্যাগ: টানলে যে কোণার দিকে নিয়ে যাবেন সেই কোণায় বসে (৪ কোণা); চিমটিতে মাপ (প্রস্থের ৩–১০%)।
 * - স্ক্রলিং লেখা: ওপর-নিচে টানলে নিচ থেকে দূরত্ব বদলায়; চিমটিতে বারের উচ্চতা।
 * - নাম/পদবি: বাঁ-ডানে টানলে পাশ বদলায় (বাম/ডান), ওপর-নিচে টানলে "ওপরে তোলা"; চিমটিতে বারের মাপ।
 * - ব্যানার: ওপর-নিচে অনেকটা টানলে ওপরের/নিচের সারিতে চলে যায় (মাপ স্থির, তাই চিমটি নেই)।
 * প্লে চলার সময় বা লেয়ার এই মুহূর্তে না থাকলে বন্ধ। প্যানেলের স্লাইডারও আগের মতো কাজ করে।
 */
@Composable
private fun StageDragLayer(tl: Timeline, playing: Boolean) {
    val id = tl.selectedLayerId ?: return
    if (playing) return
    val l = tl.layers.firstOrNull { it.id == id } ?: return
    if (l.isAudio || l is OverlayLayer || l is AdjustLayer) return
    if (!l.isActiveAt(tl.playheadMs, repeatStopMs(tl.clips), cardRangesMs(tl.clips), tl.totalMs)) return
    Box(
        Modifier.fillMaxSize().pointerInput(id) {
            // কোণা/পাশ/সারি বদলানো টানার জমা-দূরত্ব (প্রস্থ/উচ্চতার ভগ্নাংশ); কিছুক্ষণ থেমে নতুন করে ধরলে আবার শূন্য থেকে
            var accX = 0f
            var accY = 0f
            var lastNs = 0L
            var startX = 0.5f   // টানা শুরুর সময়ের আনুমানিক জায়গা (ট্যাগের কোণা / নাম-বারের পাশ), পরে কোণা বদলালেও এটা স্থির
            var startY = 0.5f
            detectTransformGestures { _, pan, zoom, rotDeg ->
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                val now = System.nanoTime()
                val live = tl.layerById(id) ?: return@detectTransformGestures
                if (now - lastNs > 250_000_000L) {
                    accX = 0f; accY = 0f
                    when (live) {
                        is TagLayer -> {
                            startX = if (live.corner == Corner.TOP_RIGHT || live.corner == Corner.BOTTOM_RIGHT) 0.85f else 0.15f
                            startY = if (live.corner == Corner.TOP_LEFT || live.corner == Corner.TOP_RIGHT) 0.1f else 0.9f
                        }
                        is NameBarLayer -> startX = if (live.right) 0.75f else 0.25f
                        else -> {}
                    }
                }
                lastNs = now
                accX += pan.x / w
                accY += pan.y / h
                val dx = pan.x / w
                val dy = pan.y / h
                if (live is AdLayer) {
                    // ওপরের সারি → নিচে (নিচের দিকে ২৫%+ টানলে), নিচের সারি → ওপরে (ওপরের দিকে ২৫%+)
                    if (live.isTop && accY > 0.25f) { tl.moveAdSide(id, false); accX = 0f; accY = 0f }
                    else if (!live.isTop && accY < -0.25f) { tl.moveAdSide(id, true); accX = 0f; accY = 0f }
                    return@detectTransformGestures
                }
                tl.updateLayer(id) { cur ->
                    when (cur) {
                        is TextLayer -> cur.copy(
                            x = (cur.x + dx).coerceIn(0f, 1f),
                            y = (cur.y + dy).coerceIn(0f, 1f),
                            sizeFrac = (cur.sizeFrac * zoom).coerceIn(0.03f, 0.2f),
                            rotation = wrapDeg(cur.rotation + rotDeg),
                        )
                        is WatermarkLayer -> cur.copy(
                            x = (cur.x + dx).coerceIn(0f, 1f),
                            y = (cur.y + dy).coerceIn(0f, 1f),
                            sizeFrac = (cur.sizeFrac * zoom).coerceIn(0.03f, 0.3f),
                            rotation = wrapDeg(cur.rotation + rotDeg),
                        )
                        is ImageLayer -> {
                            // কোণায় বসা লোগো টানলে "নিজে সরান" হয়ে যায়: শুরুর জায়গা কোণা থেকে আনুমানিক ধরা হয়
                            val c = cur.corner
                            val bx = if (c == null) cur.x else if (c == Corner.TOP_RIGHT || c == Corner.BOTTOM_RIGHT) 1f - 0.02f - cur.widthFrac / 2f else 0.02f + cur.widthFrac / 2f
                            val by = if (c == null) cur.y else if (c == Corner.TOP_LEFT || c == Corner.TOP_RIGHT) 0.1f else 0.9f
                            val minW = if (cur.isLogo) 0.02f else 0.05f
                            cur.copy(
                                x = (bx + dx).coerceIn(0f, 1f),
                                y = (by + dy).coerceIn(0f, 1f),
                                widthFrac = (cur.widthFrac * zoom).coerceIn(minW, if (cur.isLogo) 0.3f else 1f),
                                rotation = wrapDeg(cur.rotation + rotDeg),
                                corner = null,
                            )
                        }
                        is TagLayer -> {
                            // টানা শুরুর কোণার আনুমানিক কেন্দ্র + জমা-দূরত্ব = আঙুল এখন কোন চার ভাগে
                            val px = startX + accX
                            val py = startY + accY
                            val corner = when {
                                py < 0.5f && px < 0.5f -> Corner.TOP_LEFT
                                py < 0.5f -> Corner.TOP_RIGHT
                                px < 0.5f -> Corner.BOTTOM_LEFT
                                else -> Corner.BOTTOM_RIGHT
                            }
                            cur.copy(corner = corner, sizeFrac = (cur.sizeFrac * zoom).coerceIn(0.03f, 0.1f))
                        }
                        is TickerLayer -> cur.copy(
                            bottomFrac = (cur.bottomFrac - dy).coerceIn(0f, 0.3f),
                            barHeightFrac = (cur.barHeightFrac * zoom).coerceIn(0.034f, 0.102f),
                        )
                        is NameBarLayer -> {
                            val px = startX + accX
                            cur.copy(
                                right = px > 0.5f,
                                liftFrac = (cur.liftFrac - dy).coerceIn(0f, 0.3f),
                                sizeFrac = (cur.sizeFrac * zoom).coerceIn(0.06f, 0.2f),
                            )
                        }
                        else -> cur
                    }
                }
            }
        },
    )
}


/** HTML `#vc_pub`: কোথায় কোথায় পাবলিশ হবে — ফেইসবুক / ইউটিউব / ওয়ার্ডপ্রেস; ফেইসবুকে ব্যানারের ওপরে/নিচের লেখা ও রং */
@Composable
private fun PublishDialog(
    vm: EditorViewModel,
    aspect: Aspect,
    hasTopAd: Boolean,
    hasBottomAd: Boolean,
    onDismiss: () -> Unit,
    onOld: () -> Unit,   // রেন্ডার ছাড়াই শেষ রেন্ডারের পেইজে (HTML-এর "পুরাতন রেন্ডার")
    onRender: (fb: Boolean, yt: Boolean, wp: Boolean) -> Unit,   // yt = ইউটিউব বা ওয়ার্ডপ্রেস টিক (ওয়ার্ডপ্রেসে ইউটিউব ভিডিও)
) {
    var fb by remember { mutableStateOf(false) }
    var yt by remember { mutableStateOf(false) }
    var wp by remember { mutableStateOf(false) }
    val dBg = Color(0xFF14161B)
    val dCard = Color(0xFF1B1E26)
    val dBorder = Color(0xFF2A2E37)
    val dText = Color(0xFFE5E7EB)
    val dSub = Color(0xFF9CA3AF)
    val cbColors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = ED.Publish, uncheckedColor = dSub)
    // HTML pbInfo: দুই দিকেই ব্যানার থাকলে লেখার বক্স আসে না; যে দিকে ব্যানার আছে সে দিকের ঘরও থাকে না
    val showTextBox = fb && aspect == Aspect.LANDSCAPE && !(hasTopAd && hasBottomAd)   // রিলসে সাদা ব্যান্ড নেই, তাই লেখার বক্সও নেই

    @Composable
    fun Opt(checked: Boolean, onChange: (Boolean) -> Unit, title: String, sub: String? = null) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .clip(RoundedCornerShape(10.dp)).background(dCard)
                .border(1.dp, dBorder, RoundedCornerShape(10.dp))
                .clickable { onChange(!checked) }
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked, onChange, colors = cbColors)
            Column(Modifier.padding(top = 10.dp, end = 6.dp)) {
                Text(title, color = dText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (sub != null) Text(sub, color = dSub, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp)
                .clip(RoundedCornerShape(14.dp)).background(dBg)
                .border(1.dp, dBorder, RoundedCornerShape(14.dp))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("📤 পাবলিশ", color = dText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("✖", color = dSub, fontSize = 20.sp, modifier = Modifier.clickable { onDismiss() }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Text("কোথায় কোথায় পাবলিশ হবে?", color = Color(0xFFC7CBD6), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))

            Opt(fb, { fb = it }, "১। ফেইসবুক")
            Opt(
                yt, { yt = it; if (!it) wp = false }, "২। ইউটিউব",
                (if (aspect == Aspect.PORTRAIT) "৯:১৬ রিলস সাইজ; " else "১৬:৯ সাইজ; ") + "শুধু নিচের ব্যানার ভিডিওর ভিতরে বসবে (ওপরের ব্যানার ইউটিউবে বসবে না, শুধু ফেইসবুকে)",
            )
            Opt(
                wp, { wp = it; if (it) yt = true }, "৩। ওয়ার্ডপ্রেস",
                "ইউটিউব ছাড়া ওয়ার্ডপ্রেস হবে না; টিক দিলে ইউটিউব নিজে থেকে টিক হবে",
            )

            if (showTextBox) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xFFFAFAFA))
                        .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    Text("ফেইসবুকের ব্যানার এরিয়ায় কী লিখবে?", color = Color(0xFF222222), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                    if (!hasTopAd) {
                        Text("⬆️ উপরে", color = Color(0xFF222222), fontSize = 13.sp)
                        OutlinedTextField(
                            value = vm.fbTop, onValueChange = { vm.fbTop = it },
                            placeholder = { Text("যেমন: আমাদের পেইজ ফলো করুন (সর্বোচ্চ ২ লাইন)", fontSize = 12.sp) },
                            maxLines = 2, minLines = 2, modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 6.dp),
                        )
                    }
                    if (!hasBottomAd) {
                        Text("⬇️ নিচে", color = Color(0xFF222222), fontSize = 13.sp)
                        OutlinedTextField(
                            value = vm.fbBottom, onValueChange = { vm.fbBottom = it },
                            placeholder = { Text("যেমন: বিস্তারিত কমেন্টে (সর্বোচ্চ ২ লাইন)", fontSize = 12.sp) },
                            maxLines = 2, minLines = 2, modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                        )
                    }
                    ColorPick("🎨 লেখার রং", vm.fbColor, listOf(0xFF000000, 0xFFDC2626, 0xFF1D4ED8, 0xFF15803D, 0xFF7E22CE, 0xFFFFFFFF)) { vm.fbColor = it }
                    ColorPick("🖌️ ব্যাকগ্রাউন্ডের রং", vm.fbBg, listOf(0xFFFFFFFF, 0xFFFEF3C7, 0xFFDBEAFE, 0xFFDCFCE7, 0xFFFCE7F3, 0xFF111827)) { vm.fbBg = it }
                }
            }

            // HTML pbInfo: দুটোর মাপ আলাদা দেখায়; ফেইসবুকে লেখা (ব্যান্ড) থাকলে উচ্চতা ৭২০ + ২×১০৮ = ৯৩৬
            val fbHasBand = aspect == Aspect.LANDSCAPE && (hasTopAd || hasBottomAd || vm.fbTop.isNotBlank() || vm.fbBottom.isNotBlank())
            Text(
                if (aspect == Aspect.PORTRAIT) "রিলস মাপ: ${aspect.w}×${aspect.h} (৯:১৬); ফেইসবুক ও ইউটিউব দুটোতেই এই মাপ"
                else "ইউটিউব: 1280×720 (১৬:৯, শুধু নিচের ব্যানার ভিতরে)\nফেইসবুক: 1280×" + (if (fb && fbHasBand) (720 + 2 * EDITOR_AD_H) else 720) +
                    (if (fb && fbHasBand) " (ব্যানার ও লেখা ভিডিওর বাইরে ওপরে-নিচের সাদা ব্যান্ডে)" else ""),
                color = dSub, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            if (fb && (yt || wp)) {
                Text("ফেইসবুক আর ইউটিউব দুটোর জন্য আলাদা দুটো ভিডিও রেন্ডার হবে (আগে ফেইসবুক, তারপর ইউটিউব)। সময় প্রায় দ্বিগুণ লাগবে।", color = Color(0xFFFBBF24), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text("রেন্ডার শেষে পেইজে একটাই \"🚀 পাবলিশ করুন\" বাটন আসবে; চাপলে এখানে টিক দেওয়া সব জায়গায় পাবলিশ হবে। টাইটেল আগে থেকে বসানো থাকবে।", color = dSub, fontSize = 12.sp)

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = fb || yt || wp, onClick = { onRender(fb, yt || wp, wp) }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = ED.Publish, contentColor = Color.White),
                ) { Text("🎬 রেন্ডার", fontWeight = FontWeight.Bold) }
            }
            if (vm.lastRender != null) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onOld, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(8.dp),
                ) { Text("🎞️ পুরাতন রেন্ডার (রেন্ডার ছাড়াই দেখুন)", color = dText) }
            }
        }
    }
}


/**
 * নতুন ভিডিওর ডিফল্ট (ইন্ট্রো, আউট্রো, বিজ্ঞাপন, লোগো, ট্যাগ, স্ক্রলিং লেখা) বসানো শেষ না হওয়া পর্যন্ত
 * "প্রসেসিং" বক্স: ধাপ-তালিকা + প্রসেসিং বার। বক্স থাকা অবস্থায় এডিটরে কিছু ছোঁয়া যায় না; শেষ হলে নিজে সরে যায়, তখন এডিট শুরু।
 */
@Composable
private fun SeedProgressDialog(vm: EditorViewModel, onBack: () -> Unit) {
    val pct = vm.seedPercent
    val step = vm.seedStep
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onBack,   // পেছনের বোতামে এডিটর থেকে বেরোনো যায়; বাইরে ট্যাপে বন্ধ হয় না
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.fillMaxWidth().background(ED.Screen, RoundedCornerShape(16.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("⏳ প্রসেসিং চলছে…", color = Color.White, fontSize = 17.sp)
            Text("ইন্ট্রো, আউট্রো, বিজ্ঞাপন ও ট্যাগ বসানো শেষ হলে এডিট শুরু করতে পারবেন।", color = ED.Sub, fontSize = 13.sp)
            LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
            Text("$pct%", color = ED.Sub, fontSize = 13.sp)
            val items = listOf("🎬 ইন্ট্রো", "🏁 আউট্রো", "📢 বিজ্ঞাপন, লোগো, ট্যাগ ও স্ক্রলিং লেখা")
            items.forEachIndexed { i, label ->
                val mark = if (step > i) "✅" else if (step == i) "⏳" else "▫️"
                Text("$mark $label", color = if (step >= i) Color.White else ED.Sub, fontSize = 14.sp)
            }
        }
    }
}


/** পোস্ট ↔ রিলস বদলানোর সময় ইন্ট্রো/আউট্রো কার্ড নতুন মাপে বানানোর প্রগ্রেস পপ-আপ: চলার সময় এডিটরে কিছু ছোঁয়া যায় না, শেষে নিজে সরে যায় */
@Composable
private fun AspectProgressDialog(vm: EditorViewModel) {
    val pct = vm.aspectPercent
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.fillMaxWidth().background(ED.Screen, RoundedCornerShape(16.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("⏳ ইন্ট্রো-আউট্রো নতুন মাপে হচ্ছে…", color = Color.White, fontSize = 17.sp)
            Text(vm.aspectMsg ?: "", color = ED.Sub, fontSize = 13.sp)
            LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth()) {
                Text("$pct%", color = ED.Sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (vm.aspectStep.isNotEmpty()) Text("কার্ড ${vm.aspectStep}", color = ED.Sub, fontSize = 13.sp)
            }
            Text("একই টেমপ্লেট-লেখা আগে বানানো থাকলে সেভ করা কার্ডই বসে, নতুন করে বানাতে হয় না।", color = ED.Muted, fontSize = 12.sp)
        }
    }
}

/** রঙের সারি: তৈরি রঙ থেকে বাছা, অথবা নিজে hex কোড লিখে (যেমন FF8800) */
@Composable
private fun ColorPick(title: String, value: Int, presets: List<Long>, onPick: (Int) -> Unit) {
    Text(title, color = Color(0xFF222222), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { c ->
            val ci = c.toInt()
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(ci))
                    .border(3.dp, if (value == ci) Color(0xFF38BDF8) else Color(0xFFBBBBBB), CircleShape)
                    .clickable { onPick(ci) },
            )
        }
    }
    var hex by remember(value) { mutableStateOf(String.format("%06X", value and 0xFFFFFF)) }
    OutlinedTextField(
        value = hex,
        onValueChange = { t ->
            val h = t.trim().removePrefix("#").take(6).uppercase()
            hex = h
            if (h.length == 6 && h.all { it in "0123456789ABCDEF" }) onPick((0xFF000000L or h.toLong(16)).toInt())
        },
        label = { Text("নিজের রং (hex কোড, যেমন FF8800)", fontSize = 12.sp) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}
