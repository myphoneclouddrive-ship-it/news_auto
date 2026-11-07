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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun VideoEditorScreen(vm: EditorViewModel, onBack: () -> Unit = {}, onRendered: (android.net.Uri) -> Unit = {}) {
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
    // প্রতিটা বদলের একটু পরে নিজে নিজে সেভ (অ্যাপ কেটে দিলেও এডিট থাকবে)
    LaunchedEffect(tl.clips, tl.layers) {
        if (tl.clips.isNotEmpty()) { kotlinx.coroutines.delay(700); vm.saveDraft() }
    }
    // নতুন লেয়ার বাছলেই এডিট প্যানেল খোলা; ডকের ✏️ চেপে বন্ধ/চালু
    var editOpen by remember(tl.selectedLayerId) { mutableStateOf(true) }
    val addMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    Column(
        Modifier.fillMaxSize().background(ED.Screen).padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---------- ওপরের অংশ: প্রিভিউ পুরো বাকি উচ্চতা নেয় ----------
        // এডিট প্যানেল খোলা থাকলে শুধু সেটাই দেখায়: প্রিভিউ/কন্ট্রোল/টাইমলাইন লুকানো (প্লেয়ার ভেতরে থেকে যায়, তাই ফিরে এলে আগের মতো)
        val selClipNow = tl.clipById(tl.selectedId)
        val selLayerNow = tl.layerById(tl.selectedLayerId)
        val panelOpen = (selClipNow?.card != null) || (selLayerNow != null && editOpen)
        if (tl.clips.isNotEmpty()) {
            StageCard(
                if (panelOpen) Modifier.size(0.dp) else Modifier.weight(1f),
                vm, status, error, info, setError, setInfo, addMedia, act.addAudioFile, onRendered, onBack,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }

        // ---------- এডিট প্যানেল: ওপরে শিট-হেডার (নাম + ✓), নিচে স্ক্রল হওয়া সেটিং ----------
        if (panelOpen) {
            SheetHeader(
                title = selLayerNow?.kind?.label ?: "ইন্ট্রো/আউট্রো",
                onDone = { if (selLayerNow != null) editOpen = false else tl.selectedId = null },
            )
        }
        Column(
            (if (panelOpen) Modifier.weight(1f) else Modifier.heightIn(max = 0.dp))
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
                        Text("রেন্ডার হচ্ছে… ${s.progress}%")
                        LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("অ্যাপ ব্যাকগ্রাউন্ডে গেলেও চলবে। নোটিফিকেশনে % দেখা যাবে।")
                        OutlinedButton(onClick = { vm.cancelRender() }) { Text("বাতিল") }
                    }
                }
                is RenderStatus.Done -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✅ ভিডিও তৈরি হয়েছে (Movies/NewsPublisher)")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, "video/mp4")
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    )
                                }
                            }) { Text("▶️ দেখুন") }
                            OutlinedButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
                                    .putExtra(Intent.EXTRA_STREAM, s.uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { ctx.startActivity(Intent.createChooser(send, "শেয়ার করুন")) }
                            }) { Text("📤 শেয়ার") }
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
}

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
    onRendered: (android.net.Uri) -> Unit,
    onBack: () -> Unit,
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
            is RenderStatus.Done -> if (sawRunning) { awaiting = false; sawRunning = false; onRendered(status.uri) }
            is RenderStatus.Failed -> if (sawRunning) { awaiting = false; sawRunning = false }
            else -> {}
        }
    }
    // আসল প্রিভিউ প্লেয়ার: ক্লিপ বদলালেই আগে থেকে prepare হয়ে থাকে
    val ctx = LocalContext.current
    val pp = remember { PreviewPlayer(ctx) }
    DisposableEffect(Unit) { onDispose { pp.release() } }
    val clipSig = PreviewPlayer.signature(tl.clips)
    LaunchedEffect(clipSig) { pp.setClips(tl.clips); pp.seekTo(tl.playheadMs) }
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
        while (playing) {
            withFrameNanos { it }
            // প্লের মধ্যে ব্যবহারকারী লাল দাগ সরালে সেখান থেকে চলবে
            if (kotlin.math.abs(tl.playheadMs - lastWritten) > 150L) pp.seekTo(tl.playheadMs)
            val pos = pp.positionMs().coerceAtMost(total)
            tl.playheadMs = pos
            lastWritten = pos
            pp.syncLayers(tl.layers, pos, true)
            if (pp.ended() || pos >= total - 30L) playing = false
        }
        pp.pause()
    }
    LaunchedEffect(running) { if (running) playing = false }
    if (showPublish) PublishDialog(
        vm = vm,
        hasTopAd = tl.layers.any { it.kind == LayerKind.AD_TOP },
        hasBottomAd = tl.layers.any { it.kind == LayerKind.AD_BOTTOM },
        onDismiss = { showPublish = false },
        onRender = { yt, fbOnly ->
            vm.aspect = Aspect.LANDSCAPE
            showPublish = false
            val err = vm.startRender(fbBand = fbOnly)
            setError(err)
            if (err == null) { awaiting = true; sawRunning = false }
        },
    )
    EditorDarkTheme {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ওপরের বার: পেছনে · 720P · পাবলিশ
        EditorTopBar(onBack = onBack, publishEnabled = !running, onPublish = { playing = false; showPublish = true })

        FramePreview(tl, vm.aspect, pp, playing, Modifier.weight(1f))

        // কন্ট্রোল বার: সময় (বাঁয়ে) · ◀০.১ ▶প্লে ০.১▶ (মাঝে) · আনডু/রিডু (ডানে)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(tl.playheadMs), color = ED.Text, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1, softWrap = false)
                Text(" / ${fmtTime(total)}", color = ED.Muted, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1, softWrap = false)
            }
            IconBtn("chevL", size = 36.dp, iconSize = 20.dp) { tl.playheadMs = (tl.playheadMs - 100).coerceAtLeast(0) }
            IconBtn(if (playing) "pause" else "play", circle = true, size = 46.dp, iconSize = 22.dp) {
                if (running || tl.totalMs <= 0L) return@IconBtn
                playing = !playing
            }
            IconBtn("chevR", size = 36.dp, iconSize = 20.dp) { tl.playheadMs = (tl.playheadMs + 100).coerceAtMost(tl.totalMs) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconBtn("undo", enabled = tl.canUndo, size = 36.dp, iconSize = 19.dp) { playing = false; tl.undo(); setError(null); setInfo(null) }
                IconBtn("redo", enabled = tl.canRedo, size = 36.dp, iconSize = 19.dp) { playing = false; tl.redo(); setError(null); setInfo(null) }
            }
        }

        // জুম (টাইমলাইন): 🔍− স্লাইডার ＋ ⤢ — এক সারিতে
        NudgeZoomBar(tl)
        EditorTimeline(tl, onAddMedia, onAddAudio)

        // বার্তা / রেন্ডারের অগ্রগতি
        error?.let { Text("⚠️ $it", color = ED.Err, fontSize = 13.sp) }
        info?.let { Text(it, color = ED.Ok, fontSize = 13.sp) }
        if (status is RenderStatus.Running) {
            Text("রেন্ডার হচ্ছে… ${status.progress}%", color = ED.Sub, fontSize = 13.sp)
            LinearProgressIndicator(progress = { status.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
    }
}

/** প্লেহেডের জায়গার ফ্রেম (ভিডিওর মাপ অনুযায়ী ক্রপ করে দেখায়) */
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
            Modifier.aspectRatio(aspect.w / aspect.h.toFloat())
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
            val ct = tl.layersAt(tl.playheadMs).filterIsInstance<AdjustLayer>().sumOf { it.contrast.toDouble() }.toFloat().coerceIn(-1f, 1f)
            Box(
                Modifier.fillMaxSize().then(
                    if (ct == 0f) Modifier else Modifier.drawWithContent {
                        val sc = 1f + ct
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
            bmp?.let {
                Image(
                    it, null,
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = if (fh) -z else z
                        scaleY = if (fv) -z else z
                        translationX = -px * (z - 1f) * size.width / 2f
                        translationY = -py * (z - 1f) * size.height / 2f
                    },
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
                    modifier = Modifier.align(Alignment.Center)
                        .requiredSize(vw, vh)
                        .alpha(if (loc != null && !isImg) 1f else 0f)
                        .graphicsLayer {
                            scaleX = if (fh) -z else z
                            scaleY = if (fv) -z else z
                            translationX = -px * (z - 1f) * size.width / 2f
                            translationY = -py * (z - 1f) * size.height / 2f
                        },
                )
            }
            }
            LayerOverlay(tl)
            StageDragLayer(tl, playing)
        }
    }
}

/**
 * স্টেজে টেনে সরানো ও দুই আঙুলে বড়-ছোট করা (HTML-এর স্টেজ ড্র্যাগ): বাছা লেখা বা ছবি/লোগো লেয়ারের জন্য।
 * এক আঙুলে টানলে অবস্থান (x, y) বদলায়; দুই আঙুলে চিমটি দিলে লেখার মাপ / ছবির প্রস্থ বদলায়।
 * প্লে চলার সময় বা লেয়ার এই মুহূর্তে না থাকলে বন্ধ। প্যানেলের স্লাইডারও আগের মতো কাজ করে।
 */
@Composable
private fun StageDragLayer(tl: Timeline, playing: Boolean) {
    val id = tl.selectedLayerId ?: return
    if (playing) return
    val l = tl.layers.firstOrNull { it.id == id } ?: return
    if (l !is TextLayer && l !is ImageLayer) return
    if (!l.isActiveAt(tl.playheadMs, repeatStopMs(tl.clips))) return
    Box(
        Modifier.fillMaxSize().pointerInput(id) {
            detectTransformGestures { _, pan, zoom, _ ->
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                tl.updateLayer(id) { cur ->
                    when (cur) {
                        is TextLayer -> cur.copy(
                            x = (cur.x + pan.x / w).coerceIn(0f, 1f),
                            y = (cur.y + pan.y / h).coerceIn(0f, 1f),
                            sizeFrac = (cur.sizeFrac * zoom).coerceIn(0.03f, 0.2f),
                        )
                        is ImageLayer -> cur.copy(
                            x = (cur.x + pan.x / w).coerceIn(0f, 1f),
                            y = (cur.y + pan.y / h).coerceIn(0f, 1f),
                            widthFrac = (cur.widthFrac * zoom).coerceIn(0.05f, 1f),
                        )
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
    hasTopAd: Boolean,
    hasBottomAd: Boolean,
    onDismiss: () -> Unit,
    onRender: (yt: Boolean, fbOnly: Boolean) -> Unit,
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
    val showTextBox = fb && !(hasTopAd && hasBottomAd)

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
                "১৬:৯ সাইজ; শুধু নিচের ব্যানার ভিডিওর ভিতরে বসবে (ওপরের ব্যানার ইউটিউবে বসবে না, শুধু ফেইসবুকে)",
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
                    Text("🎨 লেখার রং", color = Color(0xFF222222), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0xFF000000, 0xFFDC2626, 0xFF1D4ED8, 0xFF15803D, 0xFF7E22CE).forEach { c ->
                            val ci = c.toInt()
                            val on = vm.fbColor == ci
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(Color(ci))
                                    .border(3.dp, if (on) Color(0xFF38BDF8) else Color.White, CircleShape)
                                    .clickable { vm.fbColor = ci },
                            )
                        }
                    }
                }
            }

            Text(
                "ইউটিউব: 1280×720 (১৬:৯, শুধু নিচের ব্যানার ভিতরে)\nফেইসবুক: 1280×720",
                color = dSub, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            if (fb && (yt || wp)) {
                Text("ফেইসবুকের সাথে ইউটিউব/ওয়ার্ডপ্রেস টিক দিলে এখন একটাই ১৬:৯ ভিডিও হয় (ব্যানারের লেখা বসে না)। ব্যানারসহ ভিডিও চাইলে শুধু ফেইসবুক টিক দিন।", color = Color(0xFFFBBF24), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text("এখন শুধু রেন্ডার হয়ে ফোনে সেভ হবে; সরাসরি আপলোড এখনো যোগ হয়নি।", color = dSub, fontSize = 12.sp)

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = fb || yt || wp, onClick = { onRender(yt, fb && !yt && !wp) }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = ED.Publish, contentColor = Color.White),
                ) { Text("🎬 রেন্ডার", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
