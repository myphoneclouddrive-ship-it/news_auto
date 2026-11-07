package com.digitalcrafting25.newsapp

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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

private fun clipWidthDp(c: Clip): Float = maxOf(MIN_CLIP_DP, c.lengthMs() / 1000f * DP_PER_SEC)

/** টাইমলাইনের সময় (ms) → স্ট্রিপের বাঁ প্রান্ত থেকে কত dp ডানে */
internal fun xOfMs(clips: List<Clip>, pos: Long): Float {
    var x = 0f
    var acc = 0L
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

/** স্ট্রিপের x (dp) → টাইমলাইনের সময় (ms) */
internal fun msOfX(clips: List<Clip>, xDp: Float): Long {
    var x = 0f
    var acc = 0L
    for (c in clips) {
        val len = c.lengthMs()
        val w = clipWidthDp(c)
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
 * রুলার (টেনে লাল দাগ সরানো যায়) → ভিজ্যুয়াল লেয়ারের সারি → ক্লিপ ট্র্যাক (শেষে ➕) → অডিও লেয়ারের সারি।
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
    val x = xOfMs(clips, tl.playheadMs)
    val contentW = xOfMs(clips, total) + GAP_DP + ADD_W + 16f

    // রুলারে টানার সময় স্ক্রল করা হয় না (নইলে আঙুলের নিচ থেকে সরে যায়)
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
                VisualLanes(tl)
                Track(tl, onAddMedia)
                AudioLanes(tl, onAddAudio)
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
    val tickMs = if (total <= 300_000L) 1000L else 5000L
    val labelMs = when {
        total <= 90_000L -> 5000L
        total <= 300_000L -> 15_000L
        else -> 30_000L
    }
    val labels = remember(clips, total) {
        val out = ArrayList<Pair<Float, String>>()
        var t = 0L
        while (t <= total) {
            val s = t / 1000
            out.add(xOfMs(clips, t) to "%d:%02d".format(s / 60, s % 60))
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
                    tl.playheadMs = msOfX(tl.clips, down.position.x / d)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        tl.playheadMs = msOfX(tl.clips, ch.position.x / d)
                        ch.consume()
                    }
                    scrubbing.value = false
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            var t = 0L
            while (t <= total) {
                val xp = xOfMs(clips, t) * d
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
    Column(Modifier.fillMaxWidth().heightIn(max = 78.dp).verticalScroll(rememberScrollState()).padding(top = 2.dp, bottom = 1.dp)) {
        vis.asReversed().forEach { l ->
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(LANE_H.dp)) {
                LayerBar(tl, l)
            }
        }
    }
    Divider()
}

@Composable
private fun LayerBar(tl: Timeline, l: Layer) {
    val clips = tl.clips
    val x0 = xOfMs(clips, l.startMs)
    val x1 = xOfMs(clips, l.endMs)
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

@Composable
private fun ClipBlock(tl: Timeline, c: Clip, i: Int) {
    val selected = c.id == tl.selectedId
    val shape = RoundedCornerShape(7.dp)
    val col = ED.segColor(i)
    Box(
        Modifier.width(clipWidthDp(c).dp).height(CLIP_H.dp).clip(shape).background(col)
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
        ClipThumb(c.uri, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0xA6000000), 0.6f to Color.Transparent),
            ),
        )
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(col))
        Text(
            "${i + 1} ${c.name}",
            modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 5.dp, vertical = 3.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            fmtTime(c.lengthMs()) + if (c.isImage) "" else if (c.muted) " 🔇" else if (c.volume != 1f) " 🔊${Math.round(c.volume * 100)}%" else "",
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 4.dp).background(Color(0x99000000)).padding(horizontal = 3.dp),
            fontSize = 10.sp,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )
        if (selected) Box(Modifier.fillMaxSize().border(2.dp, ED.Amber, shape))
    }
}

@Composable
private fun ClipThumb(uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(240, 240), null).asImageBitmap() }.getOrNull()
        }
    }
    bmp?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) }
}

// ---------------------------------------------------------------------------
// অডিও লেয়ারের সারি (সময় না মিললে একই সারিতে, মিললে নতুন সারি)
// ---------------------------------------------------------------------------

@Composable
private fun AudioLanes(tl: Timeline, onAddAudio: () -> Unit) {
    Divider()
    Column(Modifier.fillMaxWidth().background(ED.AudioLane).padding(top = 1.dp, bottom = 2.dp)) {
        Text(
            "🎵 অডিও লেয়ার",
            modifier = Modifier.padding(horizontal = 6.dp),
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
}
