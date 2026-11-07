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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
 * ভিডিও এডিটর স্ক্রিন — index.html-এর "ভিডিও পোস্ট" এডিটরের মতো সাজানো:
 *  ওপরে (স্থির): ডার্ক কার্ড = প্রিভিউ স্টেজ → কন্ট্রোল বার (আনডু/রিডু, সময়, রেন্ডার) → ০.১ সে. নাজ বার → টাইমলাইন
 *  নিচে (স্ক্রল): আইকন ডক (মেনু / ক্লিপ / লেয়ার / অডিও টুল) → এডিট প্যানেল → ভিডিওর মাপ, শিরোনাম, লোগো, মিউজিক, রেন্ডারের অবস্থা
 */
/** HTML-এ যেসব নিয়ন্ত্রণ নেই (মাপ/শিরোনাম/লোগো/মিউজিক) সেগুলো দেখাবে কিনা */
private const val SHOW_EXTRA_CONTROLS = false

@Composable
fun VideoEditorScreen(vm: EditorViewModel, onBack: () -> Unit = {}) {
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
    // নতুন লেয়ার বাছলেই এডিট প্যানেল খোলা; ডকের ✏️ চেপে বন্ধ/চালু
    var editOpen by remember(tl.selectedLayerId) { mutableStateOf(true) }
    val addMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------- ওপরের স্থির অংশ ----------
        if (tl.clips.isNotEmpty()) {
            StageCard(vm, status, error, info, setError, setInfo, addMedia, act.addAudioFile)
        }

        // ---------- নিচের স্ক্রল অংশ ----------
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
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
    vm: EditorViewModel,
    status: RenderStatus,
    error: String?,
    info: String?,
    setError: (String?) -> Unit,
    setInfo: (String?) -> Unit,
    onAddMedia: () -> Unit,
    onAddAudio: () -> Unit,
) {
    val tl = vm.tl
    val total = tl.totalMs
    val running = status is RenderStatus.Running
    val shape = RoundedCornerShape(14.dp)
    var playing by remember { mutableStateOf(false) }
    var showPublish by remember { mutableStateOf(false) }
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
        onDismiss = { showPublish = false },
        onRender = { yt ->
            vm.aspect = Aspect.LANDSCAPE
            showPublish = false
            setError(vm.startRender())
        },
    )
    Column(
        Modifier.fillMaxWidth().clip(shape).background(ED.Panel).border(1.dp, ED.Border, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FramePreview(tl, vm.aspect, pp, playing)

        // কন্ট্রোল বার (HTML `#vc_ctl`): প্লে · সময় · আনডু · রিডু ······ পাবলিশ
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PlayBtn(label = if (playing) "⏸ থামান" else "▶ প্লে") {
                if (running || tl.totalMs <= 0L) return@PlayBtn
                playing = !playing
            }
            TimePill("${fmtTime(tl.playheadMs)} / ${fmtTime(total)}", fontSize = 11.sp, hPad = 5.dp)
            CircleBtn("↶", tl.canUndo, size = 34.dp) { playing = false; tl.undo(); setError(null); setInfo(null) }
            CircleBtn("↷", tl.canRedo, size = 34.dp) { playing = false; tl.redo(); setError(null); setInfo(null) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(ED.Publish)
                    .clickable(enabled = !running) { playing = false; showPublish = true }   // HTML-এর মতো পাবলিশ ডায়ালগ
                    .alpha(if (running) 0.5f else 1f)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text("পাবলিশ", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
        }

        // ০.১ নাজ + জুম (HTML `#vc_zbar`) — এক সারিতে
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

/** প্লেহেডের জায়গার ফ্রেম (ভিডিওর মাপ অনুযায়ী ক্রপ করে দেখায়) */
@Composable
private fun FramePreview(tl: Timeline, aspect: Aspect, pp: PreviewPlayer, playing: Boolean) {
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
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.heightIn(max = 170.dp).aspectRatio(aspect.w / aspect.h.toFloat())
                .clip(RoundedCornerShape(8.dp)).background(Color.Black)
                .border(1.dp, ED.Border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // ধাপ ৬: ফ্লিপ/জুম/পজিশন — রেন্ডারের ম্যাট্রিক্সের (RenderComposer.clipTransform) সাথে একই নিয়ম
            val c = loc?.first
            val z = (c?.zoom ?: 1f).coerceIn(1f, 4f)
            val fh = c?.flipH == true
            val fv = c?.flipV == true
            val px = (c?.panX ?: 0f).coerceIn(-1f, 1f)
            val py = (c?.panY ?: 0f).coerceIn(-1f, 1f)
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
                val vr = pp.videoAspect
                val br = maxWidth / maxHeight
                val vw = if (vr > br) maxHeight * vr else maxWidth
                val vh = if (vr > br) maxHeight else maxWidth / vr
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
            LayerOverlay(tl)
        }
    }
}


/** HTML `#vc_pub`: কোথায় কোথায় পাবলিশ হবে — ফেইসবুক / ইউটিউব / ওয়ার্ডপ্রেস (ওয়ার্ডপ্রেসে ইউটিউব লাগে) */
@Composable
private fun PublishDialog(onDismiss: () -> Unit, onRender: (yt: Boolean) -> Unit) {
    var fb by remember { mutableStateOf(false) }
    var yt by remember { mutableStateOf(false) }
    var wp by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📤 পাবলিশ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("কোথায় কোথায় পাবলিশ হবে?", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(fb, { fb = it }); Text("১। ফেইসবুক")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(yt, { yt = it; if (!it) wp = false }); Text("২। ইউটিউব (১৬:৯)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wp, { wp = it; if (it) yt = true }); Text("৩। ওয়ার্ডপ্রেস")
                }
                Text("ইউটিউব ছাড়া ওয়ার্ডপ্রেস হবে না; টিক দিলে ইউটিউব নিজে টিক হবে।", fontSize = 12.sp)
                Text("এখন শুধু রেন্ডার হয়ে ফোনে সেভ হবে; সরাসরি আপলোড এখনো যোগ হয়নি।", fontSize = 12.sp)
            }
        },
        confirmButton = { Button(enabled = fb || yt || wp, onClick = { onRender(yt) }) { Text("🎬 রেন্ডার") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বন্ধ করুন") } },
    )
}
