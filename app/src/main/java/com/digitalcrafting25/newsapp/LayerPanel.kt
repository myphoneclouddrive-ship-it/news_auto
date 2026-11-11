package com.digitalcrafting25.newsapp

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// সাহায্যকারী
// ---------------------------------------------------------------------------

private val PALETTE: List<Int> = listOf(
    0xFFFFFFFFL, 0xFF000000L, 0xFFD32F2FL, 0xFFFFEB3BL,
    0xFF2E7D32L, 0xFF1565C0L, 0xFFFF9800L, 0xFF1B1E26L,
).map { it.toInt() }

/** লেয়ারের মান বদলানোর শর্টকাট: শুধু T ধরনের লেয়ার হলে বদলায় */
private inline fun <reified T : Layer> Timeline.edit(id: Long, crossinline f: (T) -> T) {
    updateLayer(id) { if (it is T) f(it) else it }
}

internal fun layerSummary(l: Layer): String = when (l) {
    is TextLayer -> l.text.replace('\n', ' ').take(20)
    is TagLayer -> l.text.take(20)
    is WatermarkLayer -> l.text.replace('\n', ' ').take(20)
    is ImageLayer -> if (l.uri == null) "ছবি নেই" else ""
    is AdLayer -> if (l.uri == null) "ছবি নেই" else ""
    is TickerLayer -> l.text.replace('\n', ' ').take(20)
    is NameBarLayer -> (l.name + if (l.title.isNotBlank()) " · " + l.title else "").take(24)
    is OverlayLayer -> "${Math.round(l.alpha * 100)}%"
    is AdjustLayer -> "☀️" + (if (l.brightness > 0f) "+" else "") + "${Math.round(l.brightness * 100)}%" +
        if (l.contrast != 0f) " ◐" + (if (l.contrast > 0f) "+" else "") + "${Math.round(l.contrast * 100)}%" else ""
    else -> ""
}

/** (x, y) ফ্রেমের ভগ্নাংশে কেন্দ্র ধরে কন্টেন্ট বসায় */
private fun Modifier.centerAtFrac(x: Float, y: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(c.maxWidth, c.maxHeight) {
        p.place((c.maxWidth * x - p.width / 2f).toInt(), (c.maxHeight * y - p.height / 2f).toInt())
    }
}

@Composable
private fun rememberUriBitmap(uri: Uri?, px: Int): ImageBitmap? {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri, px) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            loadImageThumb(ctx, uri, px)?.asImageBitmap()
        }
    }
    return bmp
}

private fun cornerAlignment(c: Corner): Alignment = when (c) {
    Corner.TOP_LEFT -> Alignment.TopStart
    Corner.TOP_RIGHT -> Alignment.TopEnd
    Corner.BOTTOM_LEFT -> Alignment.BottomStart
    Corner.BOTTOM_RIGHT -> Alignment.BottomEnd
}

// ---------------------------------------------------------------------------
// প্রিভিউয়ের ওপর লেয়ার আঁকা (FramePreview-র ভেতরে বসে)
// ---------------------------------------------------------------------------

/** প্লেহেডে চালু ভিজ্যুয়াল লেয়ারগুলো প্রিভিউ ফ্রেমের ওপর দেখায়; বাছা লেয়ারে হলুদ ফ্রেম */
@Composable
fun LayerOverlay(tl: Timeline) {
    val ms = tl.playheadMs
    val repStop = repeatStopMs(tl.clips)
    val cards = cardRangesMs(tl.clips)
    val active = tl.visualLayers.filter { it.isActiveAt(ms, repStop, cards, tl.totalMs) }
    if (active.isEmpty()) return
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        // HTML-এর tH / (h − bH − kH): ওপর-নিচের বিজ্ঞাপন/টিকার এড়িয়ে ট্যাগ ও নাম-বার বসে (০..১ নয়, h-এর একক)
        val hPx = with(density) { h.toPx() }
        val brandInsets = HtmlBrand.insets(active, hPx)
        for (l in active) key(l.id) {
            val selMod = if (l.id == tl.selectedLayerId) Modifier.border(1.dp, ED.Amber) else Modifier
            when (l) {
                is TextLayer -> if (l.text.isNotEmpty()) {
                    Text(
                        l.text,
                        color = Color(l.color),
                        fontSize = with(density) { (h * l.sizeFrac).toSp() },
                        fontWeight = if (l.bold) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = BrandFont.family(l.bold),
                        modifier = Modifier.centerAtFrac(l.x, l.y).widthIn(max = w * 0.95f)
                            .rotate(l.rotation)
                            .then(selMod)
                            .then(if (l.bgColor != 0) Modifier.background(Color(l.bgColor)) else Modifier)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                // ট্যাগ ও নাম/পদবি: রেন্ডারের সাথে একই কোডে (HtmlBrand) আঁকা — HTML-এর হুবহু স্টাইল
                is TagLayer -> if (l.text.isNotBlank()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { c ->
                            val r = HtmlBrand.drawTag(c.nativeCanvas, size.width, size.height, l, brandInsets.first * size.height / hPx, brandInsets.second * size.height / hPx)
                            if (r != null && l.id == tl.selectedLayerId) drawRect(ED.Amber, Offset(r.left, r.top), Size(r.width(), r.height()), style = Stroke(2f))
                        }
                    }
                }
                // ওয়াটারমার্ক: রেন্ডারের সাথে একই কোডে (WatermarkDraw)
                is WatermarkLayer -> if (l.text.isNotBlank()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { c ->
                            WatermarkDraw.draw(
                                c.nativeCanvas, size.width, size.height, l,
                                if (l.id == tl.selectedLayerId) ED.Amber.toArgb() else 0,
                            )
                        }
                    }
                }
                is ImageLayer -> {
                    val b = rememberUriBitmap(l.uri, 512)
                    if (b != null) {
                        if (l.isLogo && l.corner != null) {
                            // HTML drawLogo: কোণায় বসা লোগো (ফাঁক ২%), ওপর/নিচের বিজ্ঞাপন-টিকার এড়িয়ে — রেন্ডারের সাথে একই HtmlBrand.logoRect
                            val wPx = with(density) { w.toPx() }
                            val r = HtmlBrand.logoRect(wPx, hPx, l, b.width, b.height, brandInsets.first, brandInsets.second)
                            Image(
                                b, null,
                                Modifier.offset { androidx.compose.ui.unit.IntOffset(r.left.toInt(), r.top.toInt()) }
                                    .size(with(density) { r.width().toDp() }, with(density) { r.height().toDp() })
                                    .then(selMod).alpha(l.opacity),
                                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                            )
                        } else {
                            Image(
                                b, null,
                                Modifier.centerAtFrac(l.x, l.y).width(w * l.widthFrac)
                                    .aspectRatio(b.width / b.height.toFloat())
                                    .rotate(l.rotation)   // রেন্ডারের মতোই কেন্দ্র ঘিরে ঘোরে (আগে প্রিভিউতে ঘুরত না)
                                    .then(selMod).alpha(l.opacity),
                            )
                        }
                    }
                }
                is AdLayer -> {
                    val b = rememberUriBitmap(l.uri, 768)
                    // আগের ব্যানারের শেষে এলে HTML-এর `downup` ট্রানজিশন (আগেরটা নিচে নামে, নতুনটা নিচ থেকে ওঠে)
                    val adTr = AdTr.at(tl.layers, l, ms)
                    val pb = rememberUriBitmap(adTr?.prev?.uri, 768)
                    val (showPrev, shiftF) = if (adTr != null) AdTr.shift(adTr.k) else (false to 0f)
                    val shown = if (adTr != null && showPrev) pb else b
                    if (shown != null) {
                        Box(
                            Modifier.fillMaxSize().then(selMod),
                            contentAlignment = if (l.isTop) Alignment.TopCenter else Alignment.BottomCenter,
                        ) {
                            // ব্যানার: পুরো প্রস্থ × ফ্রেমের ১৫% (১২৮০×১০৮), ছবি যে মাপেরই হোক এই মাপে বসে (রেন্ডারের মতো)
                            Box(Modifier.fillMaxWidth().height(h * l.heightFrac).clipToBounds()) {
                                Box(
                                    Modifier.fillMaxSize().graphicsLayer { translationY = shiftF * size.height }.background(Color.White),
                                ) {
                                    Image(shown, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                                }
                            }
                        }
                    }
                }
                // স্ক্রলিং লেখা: রেন্ডারের সাথে একই কোডে (HtmlBrand.drawTicker) — HTML ticker()-এর হুবহু স্টাইল, নিচের বিজ্ঞাপনের ঠিক ওপরে
                is TickerLayer -> if (l.text.isNotBlank()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { c ->
                            val r = HtmlBrand.drawTicker(c.nativeCanvas, size.width, size.height, l, ms, active)
                            if (r != null && l.id == tl.selectedLayerId) drawRect(ED.Amber, Offset(r.left, r.top), Size(r.width(), r.height()), style = Stroke(2f))
                        }
                    }
                }
                is NameBarLayer -> if (l.name.isNotBlank()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { c ->
                            val r = HtmlBrand.drawNameBar(c.nativeCanvas, size.width, size.height, l, ms, brandInsets.second * size.height / hPx, tl.totalMs)
                            if (r != null && l.id == tl.selectedLayerId) drawRect(ED.Amber, Offset(r.left, r.top), Size(r.width(), r.height()), style = Stroke(2f))
                        }
                    }
                }
                // ধাপ ৭: পুরো ফ্রেমের রঙের আস্তরণ (রেন্ডারের `OverlayLayer` আঁকার মতোই)
                is OverlayLayer -> Box(
                    Modifier.fillMaxSize().background(Color(l.color).copy(alpha = l.alpha.coerceIn(0f, 1f))).then(selMod),
                )
                // ব্রাইটনেস: বাড়ালে সাদা, কমালে কালো স্বচ্ছ আস্তরণ (রেন্ডারেও একই)
                is AdjustLayer -> if (l.brightness != 0f) Box(
                    Modifier.fillMaxSize()
                        .background((if (l.brightness > 0f) Color.White else Color.Black).copy(alpha = kotlin.math.abs(l.brightness).coerceIn(0f, 1f)))
                        .then(selMod),
                )
                else -> {}
            }
            Unit
        }
    }
}

// ---------------------------------------------------------------------------
// বাছা লেয়ারের এডিটর
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerEditor(tl: Timeline, l: Layer, onReplaceImage: () -> Unit) {
    val total = tl.totalMs

    Column(
        Modifier.edPanel(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (l.isSingletonLayer()) "✏️ ${l.kind.label} (ইন্ট্রো/আউট্রো ছাড়া পুরো ভিডিওতে)" else "✏️ ${l.kind.label} লেয়ার", fontWeight = FontWeight.Bold)

        // সময়
        if (total > LayerDefaults.MIN_LAYER_MS && !l.isSingletonLayer()) {
            var r by remember(l.id, l.startMs, l.endMs, total) {
                val s = l.startMs.toFloat().coerceIn(0f, total.toFloat())
                mutableStateOf(s..l.endMs.toFloat().coerceIn(s, total.toFloat()))
            }
            Text("সময়: ${fmtTime(r.start.toLong())} → ${fmtTime(r.endInclusive.toLong())}", fontSize = 13.sp)
            RangeSlider(
                value = r,
                onValueChange = { r = it },
                onValueChangeFinished = { tl.setLayerTime(l.id, r.start.toLong(), r.endInclusive.toLong()) },
                valueRange = 0f..total.toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { tl.setLayerTime(l.id, tl.playheadMs, maxOf(l.endMs, tl.playheadMs + LayerDefaults.MIN_LAYER_MS)) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("▶ এখান থেকে শুরু", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { tl.setLayerTime(l.id, minOf(l.startMs, tl.playheadMs - LayerDefaults.MIN_LAYER_MS).coerceAtLeast(0L), tl.playheadMs) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("⏹ এখানে শেষ", fontSize = 12.sp) }
            }
        }

        when (l) {
            is TextLayer -> {
                OutlinedTextField(
                    value = l.text,
                    onValueChange = { v -> tl.edit<TextLayer>(l.id) { it.copy(text = v) } },
                    label = { Text("লেখা") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = l.bold, onCheckedChange = { v -> tl.edit<TextLayer>(l.id) { it.copy(bold = v) } })
                    Text("বোল্ড")
                }
                ColorRow("লেখার রং", l.color, false) { c -> tl.edit<TextLayer>(l.id) { it.copy(color = c) } }
                ColorRow("ব্যাকগ্রাউন্ড", l.bgColor, true) { c -> tl.edit<TextLayer>(l.id) { it.copy(bgColor = c) } }
                LSlider("লেখার মাপ", l.sizeFrac, 0.03f..0.2f) { v -> tl.edit<TextLayer>(l.id) { it.copy(sizeFrac = v) } }
                LSlider("ডান-বাম অবস্থান", l.x, 0f..1f) { v -> tl.edit<TextLayer>(l.id) { it.copy(x = v) } }
                LSlider("ওপর-নিচ অবস্থান", l.y, 0f..1f) { v -> tl.edit<TextLayer>(l.id) { it.copy(y = v) } }
                RotSlider(l.rotation) { v -> tl.edit<TextLayer>(l.id) { it.copy(rotation = v) } }
            }

            is WatermarkLayer -> {
                OutlinedTextField(
                    value = l.text,
                    onValueChange = { v -> tl.edit<WatermarkLayer>(l.id) { it.copy(text = v) } },
                    label = { Text("ওয়াটারমার্কের লেখা") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("তৈরি স্টাইল (চাপলে নিচের সব সেটিং ওই স্টাইলের মতো হয়, তারপর নিজের মতো বদলানো যায়)", fontSize = 12.sp, color = ED.Sub)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WatermarkStyle.ALL.forEachIndexed { i, p ->
                        FilterChip(
                            selected = l.style == i,
                            onClick = { tl.edit<WatermarkLayer>(l.id) { WatermarkStyle.apply(it, i) } },
                            label = { Text(p.name) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(checked = l.bold, onCheckedChange = { v -> tl.edit<WatermarkLayer>(l.id) { it.copy(bold = v) } })
                    Text("বোল্ড", fontSize = 13.sp)
                    Checkbox(checked = l.shadow, onCheckedChange = { v -> tl.edit<WatermarkLayer>(l.id) { it.copy(shadow = v) } })
                    Text("ছায়া", fontSize = 13.sp)
                    Checkbox(checked = l.round, onCheckedChange = { v -> tl.edit<WatermarkLayer>(l.id) { it.copy(round = v) } })
                    Text("গোল পট্টি", fontSize = 13.sp)
                }
                ColorRow("লেখার রং", l.color, false) { c -> tl.edit<WatermarkLayer>(l.id) { it.copy(color = c) } }
                ColorRow("ব্যাকগ্রাউন্ড (পট্টি)", l.bgColor, true) { c -> tl.edit<WatermarkLayer>(l.id) { it.copy(bgColor = c) } }
                LSlider("স্বচ্ছতা (কম = ঝাপসা)", l.opacity, 0.1f..1f) { v -> tl.edit<WatermarkLayer>(l.id) { it.copy(opacity = v) } }
                LSlider("লেখার মাপ", l.sizeFrac, 0.03f..0.3f) { v -> tl.edit<WatermarkLayer>(l.id) { it.copy(sizeFrac = v) } }
                LSlider("ডান-বাম অবস্থান", l.x, 0f..1f) { v -> tl.edit<WatermarkLayer>(l.id) { it.copy(x = v) } }
                LSlider("ওপর-নিচ অবস্থান", l.y, 0f..1f) { v -> tl.edit<WatermarkLayer>(l.id) { it.copy(y = v) } }
                RotSlider(l.rotation) { v -> tl.edit<WatermarkLayer>(l.id) { it.copy(rotation = v) } }
            }

            is ImageLayer -> {
                OutlinedButton(onClick = onReplaceImage) {
                    Text(if (l.uri == null) "🖼️ ছবি বাছুন" else "🖼️ ছবি বদলান")
                }
                if (l.isLogo) SettingsLogoPicker { u -> tl.edit<ImageLayer>(l.id) { it.copy(uri = u) } }
                if (l.isLogo) {
                    // HTML: লোগোর অবস্থান ৪ কোণা (ওপরে ডানে/বামে, নিচে ডানে/বামে); "নিজে সরান" = স্টেজে টেনে বা স্লাইডারে বসানো
                    Text("অবস্থান", fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Corner.TOP_RIGHT to "↗ ওপরে ডানে", Corner.TOP_LEFT to "↖ ওপরে বামে",
                            Corner.BOTTOM_RIGHT to "↘ নিচে ডানে", Corner.BOTTOM_LEFT to "↙ নিচে বামে",
                        ).forEach { (c, label) ->
                            FilterChip(
                                selected = l.corner == c,
                                onClick = { tl.edit<ImageLayer>(l.id) { it.copy(corner = c, rotation = 0f) } },
                                label = { Text(label) },
                            )
                        }
                        FilterChip(
                            selected = l.corner == null,
                            onClick = { tl.edit<ImageLayer>(l.id) { it.copy(corner = null) } },
                            label = { Text("✋ নিজে সরান") },
                        )
                    }
                }
                LSlider(if (l.isLogo) "লোগোর মাপ (ভিডিওর প্রস্থের %)" else "মাপ (প্রস্থ)", l.widthFrac, (if (l.isLogo) 0.02f else 0.05f)..(if (l.isLogo) 0.3f else 1f)) { v -> tl.edit<ImageLayer>(l.id) { it.copy(widthFrac = v) } }
                LSlider("স্বচ্ছতা (ধোঁয়াশা কম = বেশি)", l.opacity, 0.1f..1f) { v -> tl.edit<ImageLayer>(l.id) { it.copy(opacity = v) } }
                if (!l.isLogo || l.corner == null) {
                    LSlider("ডান-বাম অবস্থান", l.x, 0f..1f) { v -> tl.edit<ImageLayer>(l.id) { it.copy(x = v) } }
                    LSlider("ওপর-নিচ অবস্থান", l.y, 0f..1f) { v -> tl.edit<ImageLayer>(l.id) { it.copy(y = v) } }
                }
                if (!l.isLogo || l.corner == null) RotSlider(l.rotation) { v -> tl.edit<ImageLayer>(l.id) { it.copy(rotation = v) } }
            }

            is TagLayer -> {
                OutlinedTextField(
                    value = l.text,
                    onValueChange = { v -> tl.edit<TagLayer>(l.id) { it.copy(text = v) } },
                    label = { Text("ট্যাগের লেখা (যেমন: রংপুর)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("কোণা", fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Corner.TOP_LEFT to "↖ ওপর-বাঁ", Corner.TOP_RIGHT to "↗ ওপর-ডান",
                        Corner.BOTTOM_LEFT to "↙ নিচ-বাঁ", Corner.BOTTOM_RIGHT to "↘ নিচ-ডান",
                    ).forEach { (c, label) ->
                        FilterChip(
                            selected = l.corner == c,
                            onClick = { tl.edit<TagLayer>(l.id) { it.copy(corner = c) } },
                            label = { Text(label) },
                        )
                    }
                }
                ColorRow("লেখার রং", l.color, false) { c -> tl.edit<TagLayer>(l.id) { it.copy(color = c) } }
                ColorRow("ব্যাকগ্রাউন্ড", l.bgColor, true) { c -> tl.edit<TagLayer>(l.id) { it.copy(bgColor = c) } }
                LSlider("মাপ (প্রস্থের %)", l.sizeFrac, 0.03f..0.1f) { v -> tl.edit<TagLayer>(l.id) { it.copy(sizeFrac = v) } }
            }

            is TickerLayer -> {
                OutlinedTextField(
                    value = l.text,
                    onValueChange = { v -> tl.edit<TickerLayer>(l.id) { it.copy(text = v) } },
                    label = { Text("স্ক্রলিং লেখা") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorRow("লেখার রং", l.color, false) { c -> tl.edit<TickerLayer>(l.id) { it.copy(color = c) } }
                ColorRow("বারের রং", l.bgColor, false) { c -> tl.edit<TickerLayer>(l.id) { it.copy(bgColor = c) } }
                // HTML: লেখার মাপ ৪০–১২০ (ডিফল্ট ৭০) → বারের উচ্চতা ফ্রেমের ৩.৪%–১০.২%; গতি ১–১০ (ডিফল্ট ৪) → ০.০৩×গতি
                LSlider("লেখার মাপ (বারের উচ্চতা)", l.barHeightFrac, 0.034f..0.102f) { v -> tl.edit<TickerLayer>(l.id) { it.copy(barHeightFrac = v) } }
                LSlider("গতি", l.speed, 0.03f..0.3f) { v -> tl.edit<TickerLayer>(l.id) { it.copy(speed = v) } }
                LSlider("নিচের বিজ্ঞাপনের ওপর থেকে অতিরিক্ত দূরত্ব", l.bottomFrac, 0f..0.3f) { v -> tl.edit<TickerLayer>(l.id) { it.copy(bottomFrac = v) } }
            }

            is NameBarLayer -> {
                OutlinedTextField(
                    value = l.name,
                    onValueChange = { v -> tl.edit<NameBarLayer>(l.id) { it.copy(name = v) } },
                    label = { Text("নাম") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = l.title,
                    onValueChange = { v -> tl.edit<NameBarLayer>(l.id) { it.copy(title = v) } },
                    label = { Text("পদবি (ঐচ্ছিক)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorRow("পাশের দাগের রং", l.accent, false) { c -> tl.edit<NameBarLayer>(l.id) { it.copy(accent = c) } }
                // HTML-এর মতো: পট্টি সবসময় কালো, লেখা সাদা; বদলানো যায় দাগের রং, মাপ, উচ্চতা, পাশ, ফেড
                LSlider("মাপ (বারের উচ্চতা, ফ্রেমের %)", l.sizeFrac, 0.06f..0.2f) { v -> tl.edit<NameBarLayer>(l.id) { it.copy(sizeFrac = v) } }
                LSlider("ওপরে তোলা", l.liftFrac, 0f..0.3f) { v -> tl.edit<NameBarLayer>(l.id) { it.copy(liftFrac = v) } }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !l.right, onClick = { tl.edit<NameBarLayer>(l.id) { it.copy(right = false) } }, label = { Text("বাম পাশে") })
                    FilterChip(selected = l.right, onClick = { tl.edit<NameBarLayer>(l.id) { it.copy(right = true) } }, label = { Text("ডান পাশে") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = l.fade, onCheckedChange = { v -> tl.edit<NameBarLayer>(l.id) { it.copy(fade = v) } })
                    Text("স্লাইড/ফেড ইন-আউট")
                }
                // HTML: "আবার দেখান (প্রতি … সেকেন্ডে; ০ = একবার)"
                OutlinedTextField(
                    value = if (l.repeatSec == 0) "" else l.repeatSec.toString(),
                    onValueChange = { v ->
                        val n = v.filter { ch -> ch.isDigit() }.take(3).toIntOrNull() ?: 0
                        tl.edit<NameBarLayer>(l.id) { it.copy(repeatSec = n.coerceIn(0, 600)) }
                    },
                    label = { Text("আবার দেখান (প্রতি … সেকেন্ডে; ০ = একবার)") },
                    placeholder = { Text("০") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "প্রথমবার এই লেয়ারের শুরু থেকে আসবে, তারপর ওই ব্যবধানে (শুরু থেকে শুরু) আবার আসবে। আউট্রো শুরুর ১৫ সেকেন্ড আগে থেকে আর আসবে না। ফাঁকা বা ০ রাখলে একবারই।",
                    color = ED.Muted, fontSize = 12.sp,
                )
            }

            is AdLayer -> {
                OutlinedButton(onClick = onReplaceImage) {
                    Text(if (l.uri == null) "🖼️ বিজ্ঞাপনের ছবি বাছুন" else "🖼️ অন্য ছবি বাছুন")
                }
                DefaultAdButton { u -> tl.edit<AdLayer>(l.id) { it.copy(uri = u, adId = "") } }
                SettingsAdPicker(l.adId) { u, id -> tl.edit<AdLayer>(l.id) { it.copy(uri = u, adId = id) } }
                Text("ব্যানারের মাপ স্থির: ${EDITOR_AD_W}×${EDITOR_AD_H} (ফ্রেমের ১৫%)। ছবি ছোট বা বড় হলেও এই মাপে বসবে।", color = ED.Muted, fontSize = 12.sp)
            }

            is OverlayLayer -> {
                Text("পুরো ফ্রেমের ওপর রঙের পর্দা — ছায়া, রঙের আভা বা ফেড-ডাউনের জন্য।", color = ED.Muted, fontSize = 12.sp)
                ColorRow("আস্তরণের রং", l.color, false) { c -> tl.edit<OverlayLayer>(l.id) { it.copy(color = c) } }
                LSlider("ঘনত্ব", l.alpha, 0.05f..0.9f) { v -> tl.edit<OverlayLayer>(l.id) { it.copy(alpha = v) } }
            }

            is AdjustLayer -> {
                Text(
                    "☀️ ব্রাইটনেস: ০ = স্বাভাবিক; বাঁয়ে কমবে, ডানে বাড়বে (সাদা/কালো আস্তরণ দিয়ে করা, বেশি বাড়ালে ছবি ফ্যাকাশে হবে)।",
                    color = ED.Muted, fontSize = 12.sp,
                )
                LSlider("☀️ ব্রাইটনেস", l.brightness, -0.8f..0.8f) { v -> tl.edit<AdjustLayer>(l.id) { it.copy(brightness = v) } }
                Text(
                    "◐ কন্ট্রাস্ট: ০ = স্বাভাবিক; বাঁয়ে ঝাপসা, ডানে গাঢ়। প্রিভিউয়ে আনুমানিক, রেন্ডারে ভিডিওর ওপর সরাসরি বসে।",
                    color = ED.Muted, fontSize = 12.sp,
                )
                LSlider("◐ কন্ট্রাস্ট", l.contrast, -1f..1f) { v -> tl.edit<AdjustLayer>(l.id) { it.copy(contrast = v) } }
                OutlinedButton(
                    onClick = { tl.edit<AdjustLayer>(l.id) { it.copy(brightness = 0f, contrast = 0f) } },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("↺ রিসেট", fontSize = 12.sp) }
            }

            else -> {}
        }
    }
}

/** ঘোরানোর স্লাইডার (ডিগ্রি); ০° এর কাছে নিজে আটকে যায়, পাশে ↺ চাপলে সোজা */
@Composable
private fun RotSlider(deg: Float, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔄 ঘোরানো: ${deg.toInt()}°", fontSize = 12.sp, color = ED.Sub, modifier = Modifier.weight(1f))
            if (deg != 0f) TextButton(
                onClick = { onChange(0f) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) { Text("↺ সোজা", fontSize = 12.sp) }
        }
        Slider(
            value = deg.coerceIn(-180f, 180f),
            onValueChange = { v -> onChange(if (kotlin.math.abs(v) < 3f) 0f else Math.round(v).toFloat()) },
            valueRange = -180f..180f,
        )
    }
}

@Composable
private fun LSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${(value * 100).toInt()}%", fontSize = 12.sp, color = ED.Sub)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

/** রঙের গোল বাটন; allowNone হলে প্রথমে "নেই" (০) বাটন */
@Composable
private fun ColorRow(label: String, current: Int, allowNone: Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = ED.Sub)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = if (allowNone) listOf(0) + PALETTE else PALETTE
            options.forEach { c ->
                val selected = c == current
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(if (c == 0) Color.White else Color(c))
                        .border(if (selected) 3.dp else 1.dp, if (selected) ED.Amber else ED.Border2, CircleShape)
                        .clickable { onPick(c) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (c == 0) Text("✕", fontSize = 12.sp, color = ED.Muted)
                }
            }
        }
    }
}


/** HTML adLyList-এর "🏷️ ডিফল্ট ব্যানার দেখান": "বিজ্ঞাপন এরিয়া ১২৮০×১০৮ px" লেখা ব্যানার বসে */
@Composable
private fun DefaultAdButton(onPick: (Uri) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    OutlinedButton(onClick = { scope.launch { onPick(SettingsMedia.placeholderAdUri(ctx)) } }) {
        Text("🏷️ ডিফল্ট ব্যানার দেখান")
    }
}

/** সেটিংসের সেভ করা বিজ্ঞাপনের থাম্বনেইল সারি (HTML adLyList); চাপলে এই লেয়ারের ছবি বদলায় */
@Composable
private fun SettingsAdPicker(currentId: String, onPick: (Uri, String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ads by produceState<List<Ad>?>(null) { value = SettingsMedia.ads(ctx) }
    val list = ads ?: return
    if (list.isEmpty()) {
        Text("সেটিংসে সেভ করা বিজ্ঞাপন নেই; \"অন্য ছবি বাছুন\" দিয়ে আপলোড করুন।", color = ED.Muted, fontSize = 12.sp)
        return
    }
    Text("📢 সেটিংসের বিজ্ঞাপন থেকে বাছুন", color = ED.Muted, fontSize = 12.sp)
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEachIndexed { i, a ->
            val thumb = remember(a) { AdRender.render(a, 540, 60).asImageBitmap() }
            val on = a.id == currentId
            Image(
                bitmap = thumb, contentDescription = "বিজ্ঞাপন ${i + 1}",
                modifier = Modifier.width(270.dp).height(30.dp)
                    .border(if (on) 2.dp else 1.dp, if (on) ED.Amber else ED.Border, RoundedCornerShape(4.dp))
                    .clickable { scope.launch { onPick(SettingsMedia.adUri(ctx, a), a.id) } },
            )
        }
    }
}

/** সেটিংসের লোগো/আইকন/সাইটের নাম বাছাই (HTML vcLogoList) */
@Composable
private fun SettingsLogoPicker(onPick: (Uri) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val opts by produceState<List<SettingsMedia.LogoOpt>?>(null) { value = SettingsMedia.logos(ctx) }
    val list = opts ?: return
    if (list.isEmpty()) {
        Text("সেটিংসে এখনো লোগো, আইকন বা সাইটের নাম দেওয়া নেই; \"ছবি বদলান\" দিয়ে আপলোড করুন।", color = ED.Muted, fontSize = 12.sp)
        return
    }
    Text("🏷️ সেটিংসের লোগো/আইকন/সাইটের নাম থেকে বাছুন", color = ED.Muted, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { o ->
            Column(
                Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, ED.Border, RoundedCornerShape(6.dp))
                    .clickable { scope.launch { onPick(SettingsMedia.logoUri(ctx, o)) } }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(remember(o) { o.bmp.asImageBitmap() }, o.label, Modifier.size(width = 96.dp, height = 48.dp))
                Text(o.label, color = ED.Muted, fontSize = 11.sp)
            }
        }
    }
}
