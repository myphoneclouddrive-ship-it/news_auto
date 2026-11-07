package com.digitalcrafting25.newsapp

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// টাইমলাইনের মাপ-ধ্রুবক ও x ↔ সময় রূপান্তর
// (ক্লিপের চওড়া = দৈর্ঘ্য × ১৮dp/সে., ন্যূনতম ৪৪dp, মাঝে ৩dp ফাঁক — লেয়ার-বার ও প্লেহেডও একই মাপে বসে)
// ---------------------------------------------------------------------------

private const val DP_PER_SEC = 18f
private const val MIN_CLIP_DP = 44f
private const val GAP_DP = 3f
private const val TRACK_H = 62
private const val CLIP_H = 48
private const val ADD_W = 48f
private const val LANE_H = 24

/** রুলারের দাগ (ms) ও লেবেলের (ms) ধাপ; প্রতিটা লেবেল-ধাপ দাগ-ধাপের গুণিতক */
private val TICK_STEPS_MS = listOf(500L, 1000L, 5000L, 10_000L, 30_000L, 60_000L, 300_000L, 600_000L)
private val LABEL_STEPS_MS = listOf(1000L, 5000L, 10_000L, 30_000L, 60_000L, 300_000L, 600_000L, 1_800_000L)

private fun clipWidthDp(c: Clip, zoom: Float): Float = maxOf(MIN_CLIP_DP, c.lengthMs() / 1000f * DP_PER_SEC * zoom)

/** টাইমলাইনের সময় (ms) → স্ট্রিপের বাঁ প্রান্ত থেকে কত dp ডানে (zoom = টাইমলাইন জুম, ১ = ১৮dp/সে.) */
internal fun xOfMs(clips: List<Clip>, pos: Long, zoom: Float): Float {
    var x = 0f
    var acc = 0L
    for ((i, c) in clips.withIndex()) {
        val len = c.lengthMs()
        val w = clipWidthDp(c, zoom)
        if (pos < acc + len || i == clips.lastIndex) {
            val frac = if (len > 0) ((pos - acc).toFloat() / len).coerceIn(0f, 1f) else 0f
            return x + frac * w
        }
        x += w + GAP_DP
        acc += len
    }
    return 0f
}

/** স্ট্রিপের x (dp) → টাইমলাইনের সময় (ms) */
internal fun msOfX(clips: List<Clip>, xDp: Float, zoom: Float): Long {
    var x = 0f
    var acc = 0L
    for (c in clips) {
        val len = c.lengthMs()
        val w = clipWidthDp(c, zoom)
        if (xDp < x + w) {
            val frac = ((xDp - x) / w).coerceIn(0f, 1f)
            return acc + (frac * len).toLong()
        }
        x += w + GAP_DP
        acc += len
    }
    return acc
}

/** ড্যাশ-করা বর্ডার (HTML-এর `border:2px dashed`) */
internal fun Modifier.dashedBorder(color: Color, radius: Dp = 10.dp, width: Dp = 2.dp): Modifier = this.drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = width.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
    )
}

internal fun selectLayer(tl: Timeline, l: Layer) {
    tl.selectedLayerId = l.id
    tl.selectedId = null
    if (tl.playheadMs !in l.startMs until l.endMs) tl.playheadMs = l.startMs
}

private fun layerBarLabel(l: Layer): String =
    if (l is AudioLayer) l.kind.label.substringBefore(' ') + " " + l.name
    else l.kind.label + layerSummary(l).let { if (it.isBlank()) "" else " · $it" }

// ---------------------------------------------------------------------------
// টাইমলাইন
// ---------------------------------------------------------------------------

/**
 * রুলার (টেনে লাল দাগ সরানো যায়) → অডিও লেয়ারের সারি → ক্লিপ ট্র্যাক (শেষে ➕) → ভিজ্যুয়াল লেয়ারের সারি (HTML-এর মতো)।
 * ওপর থেকে নিচ পর্যন্ত একটা লাল প্লেহেড।
 */
@Composable
fun EditorTimeline(tl: Timeline, onAddMedia: () -> Unit, onAddAudio: () -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val halfScreenDp = LocalConfiguration.current.screenWidthDp / 2f
    val scrubbing = remember { mutableStateOf(false) }

    val clips = tl.clips
    val total = tl.totalMs
    val zoom = tl.timelineZoom
    val x = xOfMs(clips, tl.playheadMs, zoom)
    val contentW = xOfMs(clips, total, zoom) + GAP_DP + ADD_W + 16f

    // রুলারে টানার সময় স্ক্রল করা হয় না (নইলে আঙুলের নিচ থেকে সরে যায়); জুম বদলালেও x বদলায়, তাই লাল দাগ মাঝে থাকে
    LaunchedEffect(x) {
        if (!scrubbing.value) {
            val px = with(density) { (x - halfScreenDp).coerceAtLeast(0f).dp.roundToPx() }
            scroll.scrollTo(px)
        }
    }

    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(ED.Deep).border(1.dp, ED.Border, shape)) {
        Box(Modifier.horizontalScroll(scroll)) {
            Column(Modifier.width(contentW.dp)) {
                Ruler(tl, scrubbing)
                AudioLanes(tl, onAddAudio)
                Track(tl, onAddMedia)
                VisualLanes(tl)
            }
            // লাল প্লেহেড: ওপর থেকে নিচ পর্যন্ত
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier.offset(x = (x - 1f).dp).width(2.dp).fillMaxHeight()
                        .background(ED.Playhead),
                )
                Box(
                    Modifier.offset(x = (x - 6f).dp).width(12.dp).height(10.dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomEnd = 6.dp, bottomStart = 6.dp))
                        .background(ED.Playhead),
                )
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ED.Border))
}

// ---------------------------------------------------------------------------
// রুলার (সময়ের দাগ + লেবেল); ট্যাপ/টানলে প্লেহেড সরে
// ---------------------------------------------------------------------------

@Composable
private fun Ruler(tl: Timeline, scrubbing: MutableState<Boolean>) {
    val d = LocalDensity.current.density
    val clips = tl.clips
    val total = tl.totalMs
    val zoom = tl.timelineZoom
    // জুম অনুযায়ী দাগ ও লেবেলের ব্যবধান: দাগ ≥ ৮dp, লেবেল ≥ ৫৬dp ফাঁকে
    val dpPerSec = DP_PER_SEC * zoom
    val tickMs = TICK_STEPS_MS.firstOrNull { it / 1000f * dpPerSec >= 8f } ?: TICK_STEPS_MS.last()
    val labelMs = LABEL_STEPS_MS.firstOrNull { it >= tickMs && it / 1000f * dpPerSec >= 56f } ?: LABEL_STEPS_MS.last()
    val labels = remember(clips, total, zoom, labelMs) {
        val out = ArrayList<Pair<Float, String>>()
        var t = 0L
        while (t <= total) {
            val s = t / 1000
            out.add(xOfMs(clips, t, zoom) to "%d:%02d".format(s / 60, s % 60))
            t += labelMs
        }
        out
    }

    Box(
        Modifier.fillMaxWidth().height(24.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scrubbing.value = true
                    tl.playheadMs = msOfX(tl.clips, down.position.x / d, tl.timelineZoom)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        tl.playheadMs = msOfX(tl.clips, ch.position.x / d, tl.timelineZoom)
                        ch.consume()
                    }
                    scrubbing.value = false
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            var t = 0L
            while (t <= total) {
                val xp = xOfMs(clips, t, zoom) * d
                val major = t % labelMs == 0L
                val h = (if (major) 11f else 6f) * d
                drawLine(
                    color = if (major) Color(0xFF8B92A3) else Color(0xFF4B5160),
                    start = Offset(xp, size.height),
                    end = Offset(xp, size.height - h),
                    strokeWidth = 1f,
                )
                t += tickMs
            }
            drawLine(ED.Border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
        }
        labels.forEach { (xp, text) ->
            Text(
                text,
                modifier = Modifier.offset(x = (xp + 3f).dp, y = 2.dp),
                fontSize = 10.5.sp,
                color = ED.Muted,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ভিজ্যুয়াল লেয়ারের সারি (ওপরের লেয়ার ওপরে)
// ---------------------------------------------------------------------------

@Composable
private fun VisualLanes(tl: Timeline) {
    val vis = tl.visualLayers
    if (vis.isEmpty()) return
    Divider()
    Column(Modifier.fillMaxWidth().heightIn(max = 78.dp).verticalScroll(rememberScrollState()).padding(top = 2.dp, bottom = 1.dp)) {
        vis.asReversed().forEach { l ->
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(LANE_H.dp)) {
                LayerBar(tl, l)
            }
        }
    }
}

@Composable
private fun LayerBar(tl: Timeline, l: Layer) {
    val clips = tl.clips
    val zoom = tl.timelineZoom
    val x0 = xOfMs(clips, l.startMs, zoom)
    val x1 = xOfMs(clips, l.endMs, zoom)
    val w = maxOf(10f, x1 - x0)
    val selected = l.id == tl.selectedLayerId
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.offset(x = x0.dp).width(w.dp).height(LANE_H.dp).clip(shape)
            .background(ED.layerColor(l.kind).copy(alpha = 0.92f))
            .then(if (selected) Modifier.border(2.dp, ED.Amber, shape) else Modifier)
            .clickable { selectLayer(tl, l) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            layerBarLabel(l),
            modifier = Modifier.padding(horizontal = 6.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// ক্লিপ ট্র্যাক
// ---------------------------------------------------------------------------

@Composable
private fun Track(tl: Timeline, onAddMedia: () -> Unit) {
    val d = LocalDensity.current.density
    Box(
        Modifier.fillMaxWidth().height(TRACK_H.dp)
            .drawBehind {
                var gx = 59f * d
                while (gx < size.width) {
                    drawLine(Color(0x09FFFFFF), Offset(gx, 0f), Offset(gx, size.height), 1f)
                    gx += 60f * d
                }
            }
            .padding(vertical = ((TRACK_H - CLIP_H) / 2).dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            tl.clips.forEachIndexed { i, c -> ClipBlock(tl, c, i) }
            Box(
                Modifier.size(ADD_W.dp).dashedBorder(Color(0xFF4B5160)).clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onAddMedia),
                contentAlignment = Alignment.Center,
            ) { Text("+", fontSize = 26.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** HTML-এর মতো ক্লিপের ভূমিকা: ইন্ট্রো(০) / মূল(১) / আউট্রো(২), বাকিরা "ভিডিও ১, ২…" (আলাদা রঙে) */
private fun segRole(tl: Timeline, c: Clip): Pair<Int, String> {
    val card = c.card
    if (card != null) return if (card.outro) 2 to "আউট্রো" else 0 to "ইন্ট্রো"
    val idx = tl.clips.filter { it.card == null }.indexOfFirst { it.id == c.id }.coerceAtLeast(0)
    return if (idx == 0) 1 to "মূল" else (idx + 2) to "ভিডিও ${idx}"
}

@Composable
private fun ClipBlock(tl: Timeline, c: Clip, i: Int) {
    val selected = c.id == tl.selectedId
    val shape = RoundedCornerShape(7.dp)
    val (colIdx, roleName) = segRole(tl, c)
    val col = ED.segColor(colIdx)
    val w = clipWidthDp(c, tl.timelineZoom)
    val shadow = TextStyle(shadow = Shadow(Color.Black, Offset(1f, 1f), 3f))
    Box(
        Modifier.width(w.dp).height(CLIP_H.dp).clip(shape).background(col)
            .pointerInput(c.id, c.lengthMs()) {
                detectTapGestures { off ->
                    tl.selectedId = c.id
                    tl.selectedLayerId = null
                    val s = tl.startOf(c.id)
                    val frac = (off.x / size.width.toFloat()).coerceIn(0f, 1f)
                    tl.playheadMs = s + (c.lengthMs() * frac).toLong()
                }
            },
    ) {
        // ফিল্মস্ট্রিপ: ক্লিপের চওড়া জুড়ে পরপর ফ্রেম
        ClipStrip(c, w, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x99000000), 0.55f to Color.Transparent),
            ),
        )
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(col))
        Text(
            roleName + "  " + fmtTime(c.lengthMs()) +
                if (c.isImage) "" else if (c.muted) " 🔇" else if (c.volume != 1f) " 🔊${Math.round(c.volume * 100)}%" else "",
            modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 5.dp, vertical = 3.dp),
            style = shadow,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        // ট্রিম হাতল (HTML-এর মতো): টেনে ক্লিপের শুরু/শেষ ছাঁটা
        val cur by rememberUpdatedState(c)
        val dpPerSec = DP_PER_SEC * tl.timelineZoom
        TrimHandle("◀", Modifier.align(Alignment.CenterStart), dpPerSec, enabled = !c.isImage,
            onStart = { tl.selectedId = c.id; tl.selectedLayerId = null }) { dSec ->
            val k = cur
            if (!k.isImage && k.durationMs > 0L) {
                val e = k.effEndSec()
                tl.trim(k.id, (k.startSec + dSec).coerceIn(0f, (e - 0.2f).coerceAtLeast(0f)), e)
            }
        }
        TrimHandle("▶", Modifier.align(Alignment.CenterEnd), dpPerSec, enabled = true,
            onStart = { tl.selectedId = c.id; tl.selectedLayerId = null }) { dSec ->
            val k = cur
            if (k.isImage) tl.setImageSec(k.id, (k.imageSec + dSec).coerceIn(0.5f, 600f))
            else if (k.durationMs > 0L) {
                val dur = k.durationMs / 1000f
                tl.trim(k.id, k.startSec, (k.effEndSec() + dSec).coerceIn(k.startSec + 0.2f, dur))
            }
        }
        if (selected) Box(Modifier.fillMaxSize().border(2.dp, ED.Amber, shape))
    }
}

/** টেনে ছাঁটার হাতল: টানা শেষ হলে সেকেন্ডে মোট সরানো (dSec) পাঠায়; ডান দিকে টানলে +  */
@Composable
private fun TrimHandle(glyph: String, modifier: Modifier, dpPerSec: Float, enabled: Boolean, onStart: () -> Unit, onCommit: (Float) -> Unit) {
    val dps by rememberUpdatedState(dpPerSec)
    val commit by rememberUpdatedState(onCommit)
    Box(
        modifier.width(26.dp).fillMaxHeight()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f; onStart() },
                    onDragEnd = { if (acc != 0f) commit(acc / density / dps); acc = 0f },
                    onDragCancel = { acc = 0f },
                ) { _, dx -> acc += dx }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(12.dp).height(22.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x88000000)),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, fontSize = 8.sp, color = Color.White, maxLines = 1, softWrap = false) }
    }
}

/** ক্লিপের ফিল্মস্ট্রিপ — চওড়া অনুযায়ী n টা ফ্রেম (ভিডিওতে ট্রিমের ভেতরের সমান ব্যবধানের মুহূর্ত, ছবিতে একই ছবি) */
@Composable
private fun ClipStrip(c: Clip, widthDp: Float, modifier: Modifier) {
    val ctx = LocalContext.current
    val tileDp = CLIP_H * 16f / 9f
    val n = Math.ceil((widthDp / tileDp).toDouble()).toInt().coerceIn(1, 60)
    val frames by produceState<List<ImageBitmap>>(emptyList(), c.uri, c.isImage, c.startSec, c.endSec, c.imageSec, n) {
        delay(150)   // জুম স্লাইডার টানার সময় বারবার লোড না করতে
        value = withContext(Dispatchers.IO) { loadStripFrames(ctx, c, n) }
    }
    Row(modifier) {
        for (k in 0 until n) {
            val b = if (frames.isEmpty()) null else frames[(k * frames.size / n).coerceAtMost(frames.size - 1)]
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

private fun loadStripFrames(ctx: android.content.Context, c: Clip, n: Int): List<ImageBitmap> {
    if (c.isImage || c.card != null) {
        return listOfNotNull(loadImageThumb(ctx, c.uri, 240)?.asImageBitmap())
    }
    val out = ArrayList<ImageBitmap>()
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, c.uri)
        val lenMs = c.lengthMs().coerceAtLeast(1L)
        for (k in 0 until n) {
            val tMs = (c.startSec * 1000f).toLong() + ((k + 0.5f) / n * lenMs).toLong()
            val bm: Bitmap? = runCatching {
                r.getScaledFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
            }.getOrNull()
            if (bm != null) out.add(bm.asImageBitmap()) else if (out.isNotEmpty()) out.add(out.last())
        }
    } catch (_: Throwable) {
    } finally {
        runCatching { r.release() }
    }
    if (out.isEmpty()) return listOfNotNull(loadImageThumb(ctx, c.uri, 240)?.asImageBitmap())
    return out
}

// ---------------------------------------------------------------------------
// অডিও লেয়ারের সারি (সময় না মিললে একই সারিতে, মিললে নতুন সারি)
// ---------------------------------------------------------------------------

@Composable
private fun AudioLanes(tl: Timeline, onAddAudio: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(ED.AudioLane).padding(top = 1.dp, bottom = 2.dp)) {
        Text(
            "🎵 অডিও লেয়ার",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            fontSize = 10.sp,
            color = ED.Muted,
        )
        val audio = tl.audioLayers
        if (audio.isEmpty()) {
            Box(
                Modifier.padding(horizontal = 6.dp, vertical = 3.dp).height(LANE_H.dp)
                    .dashedBorder(Color(0xFF4B5160), radius = 6.dp, width = 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAddAudio)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text("＋ অডিও যোগ করুন", fontSize = 11.sp, color = ED.Sub, maxLines = 1, softWrap = false) }
        } else {
            val rows = ArrayList<ArrayList<Layer>>()
            for (l in audio.sortedBy { it.startMs }) {
                val row = rows.firstOrNull { r -> r.all { l.startMs >= it.endMs - 10L || l.endMs <= it.startMs + 10L } }
                if (row != null) row.add(l) else rows.add(arrayListOf(l))
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 80.dp).verticalScroll(rememberScrollState())) {
                rows.forEach { r ->
                    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(LANE_H.dp)) {
                        r.forEach { LayerBar(tl, it) }
                    }
                }
            }
        }
    }
    Divider()
}

// ---------------------------------------------------------------------------
// HTML-এর `#vc_zbar`: ◀ ০.১ · ০.১ ▶ · 🔍− · [জুম স্লাইডার] · 🔍+ · ⤢ ফিট · ১x — সব এক সারিতে
// ---------------------------------------------------------------------------

@Composable
fun NudgeZoomBar(tl: Timeline, modifier: Modifier = Modifier) {
    val screenDp = LocalConfiguration.current.screenWidthDp
    // স্ক্রিন − বাইরের/ভেতরের প্যাডিং (২৪+২০+২) − ➕ বাটন − শেষের ফাঁক
    val availDp = (screenDp - 46f - ADD_W - GAP_DP - 16f).coerceAtLeast(120f)
    val z = tl.timelineZoom
    val zLabel = if (kotlin.math.abs(z - Math.round(z)) < 0.05f) "${Math.round(z)}x" else "%.1fx".format(z)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallBtn("◀ ০.১", fontSize = 11.sp, hPad = 6.dp) { tl.playheadMs = (tl.playheadMs - 100).coerceAtLeast(0) }
        SmallBtn("০.১ ▶", fontSize = 11.sp, hPad = 6.dp) { tl.playheadMs = (tl.playheadMs + 100).coerceAtMost(tl.totalMs) }
        SmallBtn("🔍−", fontSize = 11.sp, hPad = 6.dp, enabled = z > Timeline.TL_ZOOM_MIN + 0.001f) { tl.zoomTimeline(1f / 1.5f) }
        ZoomSlider(tl, Modifier.weight(1f))
        SmallBtn("🔍+", fontSize = 11.sp, hPad = 6.dp, enabled = z < Timeline.TL_ZOOM_MAX - 0.001f) { tl.zoomTimeline(1.5f) }
        SmallBtn("⤢ ফিট", fontSize = 11.sp, hPad = 6.dp, enabled = tl.totalMs > 0L) { tl.changeTimelineZoom(fitZoom(tl.clips, availDp)) }
        Text(
            zLabel,
            modifier = Modifier.widthIn(min = 22.dp),
            fontSize = 11.sp,
            color = ED.Muted,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** টাইমলাইন জুমের পাতলা স্লাইডার (লগ স্কেল; সাদা লাইন + নীল গোল হাতল) */
@Composable
private fun ZoomSlider(tl: Timeline, modifier: Modifier = Modifier) {
    val lo = Timeline.TL_ZOOM_MIN
    val hi = Timeline.TL_ZOOM_MAX
    val frac = (Math.log((tl.timelineZoom / lo).toDouble()) / Math.log((hi / lo).toDouble())).toFloat().coerceIn(0f, 1f)
    val thumbR = 7f
    Box(
        modifier.height(30.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val dd = density
                    fun setFrom(xPx: Float) {
                        val f = ((xPx / dd - thumbR) / (size.width / dd - 2 * thumbR)).coerceIn(0f, 1f)
                        tl.changeTimelineZoom((lo * Math.pow((hi / lo).toDouble(), f.toDouble())).toFloat())
                    }
                    setFrom(down.position.x)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        setFrom(ch.position.x)
                        ch.consume()
                    }
                }
            }
            .drawBehind {
                val r = thumbR * density
                val cy = size.height / 2f
                drawRoundRect(
                    Color(0xFFE5E7EB), Offset(r, cy - 2f * density),
                    androidx.compose.ui.geometry.Size(size.width - 2 * r, 4f * density), CornerRadius(2f * density),
                )
                drawCircle(Color(0xFF3B82F6), r, Offset(r + frac * (size.width - 2 * r), cy))
            },
    )
}

/** সব ক্লিপ স্ক্রিনের চওড়ায় আঁটে এমন জুম (ছোট ক্লিপের ন্যূনতম ৪৪dp ধরে বাইনারি সার্চ) */
private fun fitZoom(clips: List<Clip>, availDp: Float): Float {
    if (clips.isEmpty()) return 1f
    fun width(z: Float): Float = clips.sumOf { clipWidthDp(it, z).toDouble() }.toFloat() + GAP_DP * (clips.size - 1)
    var lo = Timeline.TL_ZOOM_MIN
    var hi = Timeline.TL_ZOOM_MAX
    if (width(lo) >= availDp) return lo
    if (width(hi) <= availDp) return hi
    repeat(18) {
        val mid = (lo + hi) / 2f
        if (width(mid) <= availDp) lo = mid else hi = mid
    }
    return lo
}
