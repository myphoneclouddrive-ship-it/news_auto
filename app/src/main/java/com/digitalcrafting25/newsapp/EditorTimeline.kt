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
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.ui.unit.IntOffset
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
private const val SEAM_HIT = 26f   // দুই ক্লিপের মাঝের + বাটনের ট্যাপ-এলাকা (দেখতে ২০dp)

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

    val shape = RoundedCornerShape(14.dp)
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
                    Modifier.offset(x = (x - 5f).dp).width(10.dp).height(9.dp)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomEnd = 5.dp, bottomStart = 5.dp))
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
                    color = if (major) Color(0xFF8A8A96) else Color(0xFF4A4A55),
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
                fontSize = 10.sp,
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
    val vis = tl.visualLayers.filterNot { it.isSingletonLayer() }   // লোগো/স্ক্রলিং লেখা লেয়ারের সারিতে দেখায় না
    if (vis.isEmpty()) return
    Divider()
    // আলাদা স্ক্রল নেই: পুরো টাইমলাইন (অডিও + মূল ভিডিও + লেয়ার) বাইরের একটাই ওপর-নিচ স্ক্রলে চলে
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
        vis.asReversed().forEach { l ->
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(LANE_H.dp)) {
                LayerBar(tl, l)
            }
        }
    }
}

@Composable
private fun LayerBar(tl: Timeline, l: Layer) {
    val zoom = tl.timelineZoom
    val curL by rememberUpdatedState(l)
    // টানার সময় শুধু এই বারের লোকাল স্টেট (ms) বদলায়; মডেলে আঙুল ছাড়লে একবারই বসে (মূল ভিডিওর হাতলের মতো)
    var dS by remember { mutableStateOf<Long?>(null) }
    var dE by remember { mutableStateOf<Long?>(null) }
    val sMs = dS ?: l.startMs
    val eMs = dE ?: l.endMs
    val x0 = xOfMs(tl.clips, sMs, zoom)
    val x1 = xOfMs(tl.clips, eMs, zoom)
    val w = maxOf(10f, x1 - x0)
    val selected = l.id == tl.selectedLayerId
    val showHandles = selected || w >= 40f
    val hitW = (w / 2f).coerceIn(10f, 22f)
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.offset(x = x0.dp).width(w.dp).height(LANE_H.dp).clip(shape)
            .background(ED.layerColor(l.kind).copy(alpha = 0.95f))
            .then(if (selected) Modifier.border(2.dp, ED.Amber, shape) else Modifier)
            .clickable { selectLayer(tl, l) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            layerBarLabel(l),
            modifier = Modifier.padding(horizontal = if (showHandles && w >= 48f) (hitW * 0.7f).dp else 6.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (showHandles) {
            // বাঁ কিনারা: শুরু; ডান কিনারা: শেষ। ডেল্টা dp-তে (dpPerSec = ১), সময় msOfX দিয়ে — রুলারের সাথে মেলে
            TrimHandle("◀", Modifier.align(Alignment.CenterStart), 1f, enabled = true, selected = selected,
                hitW = hitW.dp, knobW = 8.dp, knobH = 16.dp,
                onStart = { tl.selectedLayerId = curL.id; tl.selectedId = null; dS = null; dE = null },
                onDrag = { dDp ->
                    val k = curL
                    val ms = msOfX(tl.clips, xOfMs(tl.clips, k.startMs, tl.timelineZoom) + dDp, tl.timelineZoom)
                    dS = tl.clampLayerRange(k, ms, k.endMs).first
                },
                onCancel = { dS = null; dE = null },
            ) { dDp ->
                val k = curL
                val ms = msOfX(tl.clips, xOfMs(tl.clips, k.startMs, tl.timelineZoom) + dDp, tl.timelineZoom)
                tl.resizeLayer(k.id, ms, k.endMs)
                dS = null; dE = null
            }
            TrimHandle("▶", Modifier.align(Alignment.CenterEnd), 1f, enabled = true, selected = selected,
                hitW = hitW.dp, knobW = 8.dp, knobH = 16.dp,
                onStart = { tl.selectedLayerId = curL.id; tl.selectedId = null; dS = null; dE = null },
                onDrag = { dDp ->
                    val k = curL
                    val ms = msOfX(tl.clips, xOfMs(tl.clips, k.endMs, tl.timelineZoom) + dDp, tl.timelineZoom)
                    dE = tl.clampLayerRange(k, k.startMs, ms).second
                },
                onCancel = { dS = null; dE = null },
            ) { dDp ->
                val k = curL
                val ms = msOfX(tl.clips, xOfMs(tl.clips, k.endMs, tl.timelineZoom) + dDp, tl.timelineZoom)
                tl.resizeLayer(k.id, k.startMs, ms)
                dS = null; dE = null
            }
        }
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
                    drawLine(Color(0x0FFFFFFF), Offset(gx, 0f), Offset(gx, size.height), 1f)
                    gx += 60f * d
                }
            }
            .padding(vertical = ((TRACK_H - CLIP_H) / 2).dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            tl.clips.forEachIndexed { i, c -> ClipBlock(tl, c, i) }
            Box(
                Modifier.size(ADD_W.dp, CLIP_H.dp).clip(RoundedCornerShape(9.dp)).background(Color.White)
                    .clickable(onClick = onAddMedia),
                contentAlignment = Alignment.Center,
            ) { LineIcon("plus", Color(0xFF111114), iconSize = 22.dp, stroke = 2.2f) }
        }
        // দুই ক্লিপের মাঝের ছোট + বাটন (HTML-এর .vcseam): চাপলে ট্রানজিশন বাছার মেনু; বসানো থাকলে সোনালি আইকন
        var xAcc = 0f
        tl.clips.forEachIndexed { i, c ->
            xAcc += clipWidthDp(c, tl.timelineZoom)
            if (i < tl.clips.lastIndex) {
                SeamButton(tl, c, Modifier.offset(x = (xAcc + GAP_DP / 2f - SEAM_HIT / 2f).dp, y = (CLIP_H - SEAM_HIT).dp))
                xAcc += GAP_DP
            }
        }
    }
}

/** ক্লিপ c ও তার পরের ক্লিপের জোড়ার ট্রানজিশন বাটন (+) ও বাছাইয়ের মেনু */
@Composable
private fun SeamButton(tl: Timeline, c: Clip, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val has = c.trans.isNotEmpty() && ClipTr.valid(c.trans)
    Box(modifier.size(SEAM_HIT.dp).clickable { open = true }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(if (has) Color(0xFFFBBF24) else Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (has) Text(ClipTr.LIST.first { it.first == c.trans }.second, fontSize = 11.sp, maxLines = 1, softWrap = false)
            else Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111114), maxLines = 1, softWrap = false)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("🎞️ কোনোটি নয়" + if (!has) "  ✓" else "") },
                onClick = { tl.setClipTransition(c.id, ""); open = false },
            )
            ClipTr.LIST.forEach { (k, ic, nm) ->
                DropdownMenuItem(
                    text = { Text("$ic $nm" + if (c.trans == k) "  ✓" else "") },
                    onClick = { tl.setClipTransition(c.id, k); open = false },
                )
            }
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
    val shape = RoundedCornerShape(9.dp)
    val (colIdx, roleName) = segRole(tl, c)
    val col = ED.segColor(colIdx)
    val dpPerSec = DP_PER_SEC * tl.timelineZoom
    // টানার সময় শুধু এই ক্লিপের ছোট স্টেট বদলায় (সেকেন্ডে); প্রিভিউ প্লেয়ার/মডেল আঙুল ছাড়লে একবারই বদলায় — তাই ল্যাগ নেই
    var dragS by remember { mutableFloatStateOf(0f) }
    var dragE by remember { mutableFloatStateOf(0f) }
    val baseW = clipWidthDp(c, tl.timelineZoom)
    val w = if (dragS == 0f && dragE == 0f) baseW else maxOf(MIN_CLIP_DP, (c.lengthMs() / 1000f - dragS + dragE) * dpPerSec)
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
        // ফিল্মস্ট্রিপ আগের মাপেই থাকে (টানার সময় নতুন করে লোড/স্ট্রেচ হয় না); বাঁ দিক ছাঁটলে ভেতরের ছবি বাঁয়ে সরে
        Box(
            Modifier.fillMaxSize().wrapContentWidth(Alignment.Start, unbounded = true)
                .width(baseW.dp).fillMaxHeight()
                .offset { IntOffset((-dragS * dpPerSec * density).toInt(), 0) },
        ) { ClipStrip(c, baseW, Modifier.fillMaxSize()) }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x99000000), 0.55f to Color.Transparent),
            ),
        )
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(col))
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
        // ট্রিম হাতল: আঙুল ধরা মাত্র (স্লপ ছাড়া) লাইভ বদলায়, ছাড়লে একবারে মডেলে বসে
        val cur by rememberUpdatedState(c)
        TrimHandle("◀", Modifier.align(Alignment.CenterStart), dpPerSec, enabled = !c.isImage && c.card == null, selected = selected,
            onStart = { tl.selectedId = c.id; tl.selectedLayerId = null; dragS = 0f; dragE = 0f },
            onDrag = { dSec ->
                val k = cur
                val e = k.effEndSec()
                val minD = -k.startSec
                val maxD = (e - 0.3f - k.startSec).coerceAtLeast(0f)
                dragS = dSec.coerceIn(minD, maxD)
            },
            onCancel = { dragS = 0f; dragE = 0f },
        ) { dSec ->
            val k = cur
            if (!k.isImage && k.durationMs > 0L) {
                val e = k.effEndSec()
                tl.trim(k.id, (k.startSec + dSec).coerceIn(0f, (e - 0.2f).coerceAtLeast(0f)), e)
            }
            dragS = 0f; dragE = 0f
        }
        TrimHandle("▶", Modifier.align(Alignment.CenterEnd), dpPerSec, enabled = true, selected = selected,
            onStart = { tl.selectedId = c.id; tl.selectedLayerId = null; dragS = 0f; dragE = 0f },
            onDrag = { dSec ->
                val k = cur
                if (k.isImage) {
                    dragE = dSec.coerceIn(0.5f - k.imageSec, 600f - k.imageSec)
                } else if (k.card == null && k.durationMs > 0L) {
                    val dur = k.durationMs / 1000f
                    val minD = -(k.lengthMs() / 1000f - 0.3f).coerceAtLeast(0f)
                    val maxD = (dur - k.effEndSec()).coerceAtLeast(0f)
                    dragE = dSec.coerceIn(minD, maxD)
                }
            },
            onCancel = { dragS = 0f; dragE = 0f },
        ) { dSec ->
            val k = cur
            if (k.isImage) tl.setImageSec(k.id, (k.imageSec + dSec).coerceIn(0.5f, 600f))
            else if (k.card == null && k.durationMs > 0L) {
                val dur = k.durationMs / 1000f
                tl.trim(k.id, k.startSec, (k.effEndSec() + dSec).coerceIn(k.startSec + 0.2f, dur))
            }
            dragS = 0f; dragE = 0f
        }
        if (selected) Box(Modifier.fillMaxSize().border(2.dp, ED.Amber, shape))
    }
}

/** টেনে ছাঁটার হাতল: আঙুলের সাথে সাথে onDrag(মোট সরানো সেকেন্ড) ডাকে, ছাড়লে onCommit; ডান দিকে টানলে + */
@Composable
private fun TrimHandle(
    glyph: String, modifier: Modifier, dpPerSec: Float, enabled: Boolean, selected: Boolean,
    onStart: () -> Unit, onDrag: (Float) -> Unit, onCancel: () -> Unit,
    hitW: Dp = 32.dp, knobW: Dp = 12.dp, knobH: Dp = 24.dp,
    onCommit: (Float) -> Unit,
) {
    val dps by rememberUpdatedState(dpPerSec)
    val start by rememberUpdatedState(onStart)
    val dragCb by rememberUpdatedState(onDrag)
    val cancelCb by rememberUpdatedState(onCancel)
    val commit by rememberUpdatedState(onCommit)
    Box(
        modifier.width(hitW).fillMaxHeight()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    start()
                    var acc = 0f
                    val finished = drag(down.id) { ch ->
                        acc += ch.positionChange().x
                        dragCb(acc / density / dps)
                        ch.consume()
                    }
                    if (finished && acc != 0f) commit(acc / density / dps) else cancelCb()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(knobW).height(knobH).clip(RoundedCornerShape(4.dp))
                .background(if (selected) Color(0xF2FFFFFF) else Color(0x88000000)),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, fontSize = 8.sp, color = if (selected) Color(0xFF111114) else Color.White, maxLines = 1, softWrap = false) }
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
    if (c.isImage) {
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
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LineIcon("music", ED.Muted, iconSize = 12.dp, stroke = 2f)
            Text("অডিও", fontSize = 10.sp, color = ED.Muted)
        }
        val audio = tl.audioLayers
        if (audio.isEmpty()) {
            Box(
                Modifier.padding(horizontal = 6.dp, vertical = 3.dp).height(LANE_H.dp)
                    .dashedBorder(Color(0xFF55555F), radius = 7.dp, width = 1.dp)
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
            Column(Modifier.fillMaxWidth()) {
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
    val availDp = (screenDp - 24f - ADD_W - GAP_DP - 16f).coerceAtLeast(120f)
    val z = tl.timelineZoom
    val zLabel = if (kotlin.math.abs(z - Math.round(z)) < 0.05f) "${Math.round(z)}x" else "%.1fx".format(z)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn("zoom", size = 32.dp, iconSize = 17.dp, tint = ED.Sub, enabled = z > Timeline.TL_ZOOM_MIN + 0.001f) { tl.zoomTimeline(1f / 1.5f) }
        ZoomSlider(tl, Modifier.weight(1f))
        IconBtn("plus", size = 32.dp, iconSize = 17.dp, tint = ED.Sub, enabled = z < tl.maxTimelineZoom - 0.001f) { tl.zoomTimeline(1.5f) }
        IconBtn("full", size = 32.dp, iconSize = 17.dp, tint = ED.Sub, enabled = tl.totalMs > 0L) { tl.changeTimelineZoom(fitZoom(tl.clips, availDp, tl.maxTimelineZoom)) }
        Text(
            zLabel,
            modifier = Modifier.widthIn(min = 26.dp).padding(end = 4.dp),
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
                    Color(0xFF33333D), Offset(r, cy - 1.5f * density),
                    androidx.compose.ui.geometry.Size(size.width - 2 * r, 3f * density), CornerRadius(1.5f * density),
                )
                drawCircle(Color.White, r, Offset(r + frac * (size.width - 2 * r), cy))
            },
    )
}

/** সব ক্লিপ স্ক্রিনের চওড়ায় আঁটে এমন জুম (ছোট ক্লিপের ন্যূনতম ৪৪dp ধরে বাইনারি সার্চ) */
private fun fitZoom(clips: List<Clip>, availDp: Float, maxZoom: Float = Timeline.TL_ZOOM_MAX): Float {
    if (clips.isEmpty()) return 1f
    fun width(z: Float): Float = clips.sumOf { clipWidthDp(it, z).toDouble() }.toFloat() + GAP_DP * (clips.size - 1)
    var lo = Timeline.TL_ZOOM_MIN
    var hi = maxZoom
    if (width(lo) >= availDp) return lo
    if (width(hi) <= availDp) return hi
    repeat(18) {
        val mid = (lo + hi) / 2f
        if (width(mid) <= availDp) lo = mid else hi = mid
    }
    return lo
}
