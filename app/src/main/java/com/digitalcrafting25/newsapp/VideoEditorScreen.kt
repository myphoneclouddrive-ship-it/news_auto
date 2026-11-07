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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
@Composable
fun VideoEditorScreen(vm: EditorViewModel) {
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
    val addMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------- ওপরের স্থির অংশ ----------
        if (tl.clips.isEmpty()) {
            EmptyStage(addMedia)
        } else {
            StageCard(vm, status, error, info, setError, setInfo, addMedia, act.addAudioFile)
        }

        // ---------- নিচের স্ক্রল অংশ ----------
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tl.clips.isNotEmpty()) EditorDock(tl, act, setError, setInfo)

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
    }
}

/** কোনো ক্লিপ নেই: বড় ড্যাশ-করা ➕ বাক্স */
@Composable
private fun EmptyStage(onAdd: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(ED.Panel).border(1.dp, ED.Border, shape).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).dashedBorder(Color(0xFF4B5160), radius = 14.dp).clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) { Text("+", fontSize = 36.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
        Text("➕ ভিডিও / ছবি যোগ করুন", color = ED.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "যোগ করা ক্লিপগুলো নিচের টাইমলাইনে পরপর জোড়া লাগবে; এরপর কাটা, ট্রিম, লেয়ার ও অডিও যোগ করা যাবে।",
            color = ED.Muted, fontSize = 12.sp,
        )
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
    Column(
        Modifier.fillMaxWidth().clip(shape).background(ED.Panel).border(1.dp, ED.Border, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FramePreview(tl, vm.aspect)

        // কন্ট্রোল বার: আনডু · রিডু · সময় · রেন্ডার
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleBtn("↶", tl.canUndo) { tl.undo(); setError(null); setInfo(null) }
            CircleBtn("↷", tl.canRedo) { tl.redo(); setError(null); setInfo(null) }
            TimePill("${fmtTime(tl.playheadMs)} / ${fmtTime(total)}", Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(ED.Publish)
                    .clickable(enabled = !running) { setError(vm.startRender()) }
                    .alpha(if (running) 0.5f else 1f)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text("🎬 রেন্ডার", color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false) }
        }

        // লাল দাগ সরানোর বার (HTML-এর ◀ ০.১ / ০.১ ▶)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallBtn("◀ ১সে", Modifier.weight(1f)) { tl.playheadMs = (tl.playheadMs - 1000).coerceAtLeast(0) }
            SmallBtn("◀ ০.১", Modifier.weight(1f)) { tl.playheadMs = (tl.playheadMs - 100).coerceAtLeast(0) }
            SmallBtn("০.১ ▶", Modifier.weight(1f)) { tl.playheadMs = (tl.playheadMs + 100).coerceAtMost(total) }
            SmallBtn("১সে ▶", Modifier.weight(1f)) { tl.playheadMs = (tl.playheadMs + 1000).coerceAtMost(total) }
        }

        EditorTimeline(tl, onAddMedia, onAddAudio)

        // বার্তা / রেন্ডারের অগ্রগতি
        error?.let { Text("⚠️ $it", color = ED.Err, fontSize = 13.sp) }
        info?.let { Text(it, color = ED.Ok, fontSize = 13.sp) }
        if (status is RenderStatus.Running) {
            Text("রেন্ডার হচ্ছে… ${status.progress}%", color = ED.Sub, fontSize = 13.sp)
            LinearProgressIndicator(progress = { status.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            "রুলারে ট্যাপ করে বা টেনে লাল দাগ সরান। ক্লিপ বা লেয়ারে ট্যাপ করে বাছুন — নিচের ডকে তার টুল আসবে।",
            color = ED.Muted, fontSize = 11.sp,
        )
    }
}

/** প্লেহেডের জায়গার ফ্রেম (ভিডিওর মাপ অনুযায়ী ক্রপ করে দেখায়) */
@Composable
private fun FramePreview(tl: Timeline, aspect: Aspect) {
    val ctx = LocalContext.current
    val loc = tl.locate(tl.playheadMs)
    val uri = loc?.first?.uri
    val isImg = loc?.first?.isImage == true
    val q = if (loc == null) -1L else loc.second / 250L
    val bmp by produceState<ImageBitmap?>(null, uri, q) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val b: Bitmap? = if (isImg) {
                    ctx.contentResolver.loadThumbnail(uri, Size(640, 640), null)
                } else {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, uri)
                        r.getFrameAtTime(q * 250L * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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
            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            LayerOverlay(tl)
        }
    }
}
