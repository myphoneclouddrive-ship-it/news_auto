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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DP_PER_SEC = 18f
private const val MIN_CLIP_DP = 44f
private const val GAP_DP = 3f
private const val STRIP_H = 56

private fun clipWidthDp(c: Clip): Float = maxOf(MIN_CLIP_DP, c.lengthMs() / 1000f * DP_PER_SEC)

/** প্লেহেড টাইমলাইন-স্ট্রিপের কত dp ডানে */
private fun playheadXDp(tl: Timeline): Float {
    val pos = tl.playheadMs
    var x = 0f
    var acc = 0L
    val clips = tl.clips
    for ((i, c) in clips.withIndex()) {
        val len = c.lengthMs()
        val w = clipWidthDp(c)
        if (pos < acc + len || i == clips.lastIndex) {
            val frac = if (len > 0) ((pos - acc).toFloat() / len).coerceIn(0f, 1f) else 0f
            return x + frac * w
        }
        x += w + GAP_DP
        acc += len
    }
    return 0f
}

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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("➕ ভিডিও / ছবি যোগ করুন") }

        if (tl.clips.isEmpty()) {
            Text("এখনো কিছু যোগ করা হয়নি। যোগ করা ক্লিপগুলো নিচের টাইমলাইনে পরপর জোড়া লাগবে; এরপর কাটা, ট্রিম ও সাজানো যাবে।")
        } else {
            FramePreview(tl, vm.aspect)

            // সময় + প্লেহেড
            val total = tl.totalMs
            Text("${fmtTime(tl.playheadMs)} / ${fmtTime(total)}", fontSize = 14.sp)
            if (total > 0) {
                Slider(
                    value = tl.playheadMs.toFloat().coerceIn(0f, total.toFloat()),
                    onValueChange = { tl.playheadMs = it.toLong(); info = null; error = null },
                    valueRange = 0f..total.toFloat(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StepBtn("−১সে") { tl.playheadMs = (tl.playheadMs - 1000).coerceAtLeast(0) }
                StepBtn("−০.১") { tl.playheadMs = (tl.playheadMs - 100).coerceAtLeast(0) }
                StepBtn("+০.১") { tl.playheadMs = (tl.playheadMs + 100).coerceAtMost(total) }
                StepBtn("+১সে") { tl.playheadMs = (tl.playheadMs + 1000).coerceAtMost(total) }
            }

            // টুলবার
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { tl.undo(); error = null; info = null }, enabled = tl.canUndo) { Text("↶ আনডু") }
                OutlinedButton(onClick = { tl.redo(); error = null; info = null }, enabled = tl.canRedo) { Text("↷ রিডু") }
                Button(onClick = {
                    val m = tl.split()
                    error = m
                    info = if (m == null) "✂️ কাটা হয়েছে" else null
                }) { Text("✂️ এখানে কাটুন") }
            }
            error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error) }
            info?.let { Text(it, color = C.Green) }

            TimelineStrip(tl)

            val sel = tl.clipById(tl.selectedId)
            if (sel == null) {
                Text("টাইমলাইনের কোনো ক্লিপে ট্যাপ করে বাছুন (লাল ফ্রেম = বাছাই করা)।", color = C.Muted, fontSize = 13.sp)
            } else {
                ClipPanel(tl, sel)
            }
        }

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

        Button(
            onClick = { error = vm.startRender() },
            enabled = status !is RenderStatus.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("🎬 রেন্ডার শুরু করুন") }
    }
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, fontSize = 12.sp)
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
            Modifier.heightIn(max = 260.dp).aspectRatio(aspect.w / aspect.h.toFloat())
                .clip(MaterialTheme.shapes.small).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
}

/** ক্লিপগুলো দৈর্ঘ্যের আনুপাতিক চওড়া ব্লক হিসেবে; ওপরে লাল প্লেহেড লাইন */
@Composable
private fun TimelineStrip(tl: Timeline) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val halfScreenDp = LocalConfiguration.current.screenWidthDp / 2f
    val x = playheadXDp(tl)

    LaunchedEffect(x) {
        val px = with(density) { (x - halfScreenDp).coerceAtLeast(0f).dp.roundToPx() }
        scroll.scrollTo(px)
    }

    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            tl.clips.forEachIndexed { i, c ->
                val selected = c.id == tl.selectedId
                Box(
                    Modifier.width(clipWidthDp(c).dp).height(STRIP_H.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(if (c.isImage) Color(0xFFB0BEC5) else Color(0xFF90A4AE))
                        .then(if (selected) Modifier.border(2.dp, C.Red, MaterialTheme.shapes.small) else Modifier)
                        .clickable {
                            tl.selectedId = c.id
                            val s = tl.startOf(c.id)
                            val e = s + c.lengthMs()
                            if (tl.playheadMs < s || tl.playheadMs >= e) tl.playheadMs = s
                        },
                ) {
                    Thumb(c.uri)
                    Text(
                        "${i + 1}",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(horizontal = 4.dp),
                    )
                    Text(
                        fmtTime(c.lengthMs()),
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 3.dp),
                    )
                }
            }
        }
        Box(Modifier.offset(x = x.dp).width(2.dp).height(STRIP_H.dp).background(Color(0xFFFF1744)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipPanel(tl: Timeline, c: Clip) {
    val idx = tl.clips.indexOfFirst { it.id == c.id }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ক্লিপ ${idx + 1}: ${c.name}", maxLines = 1)
            Text(
                (if (c.isImage) "ছবি" else "ভিডিও") + " · টাইমলাইনে ${fmtTime(c.lengthMs())}",
                color = C.Muted, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { tl.move(c.id, -1) }, enabled = idx > 0) { Text("⬅️ আগে") }
                TextButton(onClick = { tl.move(c.id, 1) }, enabled = idx in 0 until tl.clips.lastIndex) { Text("পরে ➡️") }
                TextButton(onClick = { tl.duplicate(c.id) }) { Text("⧉ কপি") }
                TextButton(onClick = { tl.remove(c.id) }) { Text("🗑️ মুছুন") }
            }
            if (c.isImage) {
                Text("ছবি দেখানোর সময়: ${"%.1f".format(c.imageSec)} সে.")
                var v by remember(c.id, c.imageSec) { mutableStateOf(c.imageSec) }
                Slider(
                    value = v.coerceIn(0.5f, 30f),
                    onValueChange = { v = it },
                    onValueChangeFinished = { tl.setImageSec(c.id, v) },
                    valueRange = 0.5f..30f,
                )
            } else if (c.durationMs > 0L) {
                val dur = c.durationMs / 1000f
                var r by remember(c.id, c.startSec, c.endSec) {
                    mutableStateOf(c.startSec.coerceIn(0f, dur)..c.effEndSec().coerceIn(0f, dur))
                }
                Text("ট্রিম: ${"%.1f".format(r.start)} সে. → ${"%.1f".format(r.endInclusive)} সে. (ভিডিও ${"%.1f".format(dur)} সে.)")
                RangeSlider(
                    value = r,
                    onValueChange = { r = it },
                    onValueChangeFinished = { tl.trim(c.id, r.start, r.endInclusive) },
                    valueRange = 0f..dur,
                )
            } else {
                Text("এই ভিডিওর দৈর্ঘ্য জানা যায়নি, তাই ট্রিম/কাটা বন্ধ।", color = C.Muted, fontSize = 13.sp)
            }
        }
    }
}
