package com.digitalcrafting25.newsapp

import android.net.Uri
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private fun layerSummary(l: Layer): String = when (l) {
    is TextLayer -> l.text.replace('\n', ' ').take(20)
    is TagLayer -> l.text.take(20)
    is ImageLayer -> if (l.uri == null) "ছবি নেই" else ""
    is AdLayer -> if (l.uri == null) "ছবি নেই" else ""
    is TickerLayer -> l.text.replace('\n', ' ').take(20)
    is NameBarLayer -> (l.name + if (l.title.isNotBlank()) " · " + l.title else "").take(24)
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
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null).asImageBitmap() }.getOrNull()
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
    val active = tl.visualLayers.filter { it.isActiveAt(ms) }
    if (active.isEmpty()) return
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        for (l in active) key(l.id) {
            val selMod = if (l.id == tl.selectedLayerId) Modifier.border(1.dp, Color(0xFFFFEB3B)) else Modifier
            when (l) {
                is TextLayer -> if (l.text.isNotEmpty()) {
                    Text(
                        l.text,
                        color = Color(l.color),
                        fontSize = with(density) { (h * l.sizeFrac).toSp() },
                        fontWeight = if (l.bold) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.centerAtFrac(l.x, l.y).widthIn(max = w * 0.95f).then(selMod)
                            .then(if (l.bgColor != 0) Modifier.background(Color(l.bgColor)) else Modifier)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                is TagLayer -> if (l.text.isNotEmpty()) {
                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = cornerAlignment(l.corner)) {
                        Text(
                            l.text,
                            color = Color(l.color),
                            fontSize = with(density) { (h * l.sizeFrac).toSp() },
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.then(selMod)
                                .then(if (l.bgColor != 0) Modifier.background(Color(l.bgColor)) else Modifier)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                is ImageLayer -> {
                    val b = rememberUriBitmap(l.uri, 512)
                    if (b != null) {
                        Image(
                            b, null,
                            Modifier.centerAtFrac(l.x, l.y).width(w * l.widthFrac)
                                .aspectRatio(b.width / b.height.toFloat()).then(selMod).alpha(l.opacity),
                        )
                    }
                }
                is AdLayer -> {
                    val b = rememberUriBitmap(l.uri, 768)
                    if (b != null) {
                        Box(
                            Modifier.fillMaxSize().then(selMod),
                            contentAlignment = if (l.isTop) Alignment.TopCenter else Alignment.BottomCenter,
                        ) {
                            Image(b, null, Modifier.height(h * l.heightFrac).aspectRatio(b.width / b.height.toFloat()))
                        }
                    }
                }
                is TickerLayer -> if (l.text.isNotEmpty()) {
                    Box(Modifier.fillMaxSize().padding(bottom = h * l.bottomFrac), contentAlignment = Alignment.BottomStart) {
                        Box(
                            Modifier.fillMaxWidth().height(h * l.barHeightFrac).clipToBounds()
                                .background(Color(l.bgColor)).then(selMod),
                        ) {
                            Text(
                                l.text,
                                color = Color(l.color),
                                fontSize = with(density) { (h * l.barHeightFrac * 0.55f).toSp() },
                                fontWeight = FontWeight.Bold,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.align(Alignment.CenterStart).layout { m, c ->
                                    val p = m.measure(Constraints())
                                    layout(c.maxWidth, p.height) {
                                        val cycle = (c.maxWidth + p.width).coerceAtLeast(1)
                                        val shift = ((ms - l.startMs).coerceAtLeast(0L) / 1000f * l.speed * c.maxWidth) % cycle
                                        p.place((c.maxWidth - shift).toInt(), 0)
                                    }
                                },
                            )
                        }
                    }
                }
                is NameBarLayer -> if (l.name.isNotBlank() || l.title.isNotBlank()) {
                    Box(Modifier.fillMaxSize().padding(bottom = h * l.bottomFrac), contentAlignment = Alignment.BottomStart) {
                        Row(Modifier.then(selMod).background(Color(l.bgColor)).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                            Box(Modifier.width(5.dp).fillMaxHeight().background(Color(l.accent)))
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                if (l.name.isNotBlank()) Text(
                                    l.name, color = Color(l.textColor), fontWeight = FontWeight.Bold,
                                    fontSize = with(density) { (h * 0.05f).toSp() }, maxLines = 1,
                                )
                                if (l.title.isNotBlank()) Text(
                                    l.title, color = Color(l.textColor),
                                    fontSize = with(density) { (h * 0.035f).toSp() }, maxLines = 1,
                                )
                            }
                        }
                    }
                }
                else -> {}
            }
            Unit
        }
    }
}

// ---------------------------------------------------------------------------
// লেয়ার ডক: যোগ করার বাটন + তালিকা + বাছা লেয়ারের এডিটর
// ---------------------------------------------------------------------------

@Composable
fun LayerDock(tl: Timeline, onMessage: (String?) -> Unit) {
    var pendingKind by rememberSaveable { mutableStateOf<LayerKind?>(null) }
    var replaceId by rememberSaveable { mutableStateOf<Long?>(null) }

    val pickNew = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        val k = pendingKind
        pendingKind = null
        if (u != null && k != null) {
            val m = tl.addLayer(k, uri = u)
            if (m == null) tl.selectedId = null
            onMessage(m)
        }
    }
    val pickReplace = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        val id = replaceId
        replaceId = null
        if (u != null && id != null) tl.updateLayer(id) {
            when (it) {
                is ImageLayer -> it.copy(uri = u)
                is AdLayer -> it.copy(uri = u)
                else -> it
            }
        }
    }

    fun add(kind: LayerKind) {
        when (kind) {
            LayerKind.IMAGE, LayerKind.LOGO, LayerKind.AD_BOTTOM, LayerKind.AD_TOP -> {
                pendingKind = kind
                pickNew.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            else -> {
                val m = tl.addLayer(
                    kind,
                    text = when (kind) {
                        LayerKind.TAG -> "ট্যাগ"
                        LayerKind.TICKER -> "এখানে স্ক্রলিং খবর লিখুন"
                        LayerKind.NAME_BAR -> "আপনার নাম"
                        else -> "আপনার লেখা"
                    },
                )
                if (m == null) tl.selectedId = null
                onMessage(m)
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🧩 লেয়ার", fontWeight = FontWeight.Bold)
            Text(
                "লাল দাগ (প্লেহেড) যেখানে, নতুন লেয়ার সেখান থেকে বসবে। প্রিভিউতে দেখা যায়, রেন্ডার করা ভিডিওতেও একই জায়গায় বসে।",
                color = C.Muted, fontSize = 12.sp,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    LayerKind.TEXT, LayerKind.IMAGE, LayerKind.LOGO, LayerKind.TAG,
                    LayerKind.TICKER, LayerKind.NAME_BAR, LayerKind.AD_BOTTOM, LayerKind.AD_TOP,
                ).forEach { k ->
                    OutlinedButton(
                        onClick = { add(k) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("➕ ${k.label}", fontSize = 13.sp) }
                }
            }

            val visual = tl.visualLayers
            if (visual.isEmpty()) {
                Text("এখনো কোনো লেয়ার নেই।", color = C.Muted, fontSize = 13.sp)
            } else {
                val total = tl.totalMs
                // ওপরের লেয়ার তালিকার ওপরে
                visual.asReversed().forEach { l ->
                    LayerRow(l, total, l.id == tl.selectedLayerId) {
                        tl.selectedLayerId = l.id
                        tl.selectedId = null
                        if (tl.playheadMs !in l.startMs until l.endMs) tl.playheadMs = l.startMs
                    }
                }
            }

            val sel = tl.layerById(tl.selectedLayerId)
            if (sel != null && !sel.isAudio) {
                LayerEditor(tl, sel, onReplaceImage = {
                    replaceId = sel.id
                    pickReplace.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            }
        }
    }
}

@Composable
private fun LayerRow(l: Layer, total: Long, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.Red else C.Border, shape)
            .clickable(onClick = onClick).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(l.kind.label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(layerSummary(l), Modifier.weight(1f), fontSize = 13.sp, color = C.Muted, maxLines = 1)
            Text("${fmtTime(l.startMs)} → ${fmtTime(l.endMs)}", fontSize = 11.sp, color = C.Muted)
        }
        TimeBar(l, total)
    }
}

/** পুরো ভিডিওর তুলনায় লেয়ার কোন অংশে চলবে */
@Composable
private fun TimeBar(l: Layer, total: Long) {
    val t = total.coerceAtLeast(1L).toFloat()
    val s = (l.startMs / t).coerceIn(0f, 1f)
    val e = (l.endMs / t).coerceIn(s, 1f)
    Row(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE0E0E0)),
    ) {
        if (s > 0f) Spacer(Modifier.weight(s))
        Box(Modifier.weight((e - s).coerceAtLeast(0.01f)).fillMaxHeight().background(C.Red))
        if (e < 1f) Spacer(Modifier.weight(1f - e))
    }
}

// ---------------------------------------------------------------------------
// বাছা লেয়ারের এডিটর
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayerEditor(tl: Timeline, l: Layer, onReplaceImage: () -> Unit) {
    val group = tl.layers.filter { it.isAudio == l.isAudio }
    val gi = group.indexOfFirst { it.id == l.id }
    val total = tl.totalMs

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFF7F7F9)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("✏️ ${l.kind.label} লেয়ার", fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            TextButton(onClick = { tl.moveLayer(l.id, 1) }, enabled = gi in 0 until group.lastIndex) { Text("⬆ ওপরে") }
            TextButton(onClick = { tl.moveLayer(l.id, -1) }, enabled = gi > 0) { Text("⬇ নিচে") }
            TextButton(onClick = { tl.duplicateLayer(l.id) }) { Text("⧉ কপি") }
            TextButton(onClick = { tl.removeLayer(l.id) }) { Text("🗑️ মুছুন") }
        }

        // সময়
        if (total > LayerDefaults.MIN_LAYER_MS) {
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
            }

            is ImageLayer -> {
                OutlinedButton(onClick = onReplaceImage) {
                    Text(if (l.uri == null) "🖼️ ছবি বাছুন" else "🖼️ ছবি বদলান")
                }
                LSlider("মাপ (প্রস্থ)", l.widthFrac, 0.05f..1f) { v -> tl.edit<ImageLayer>(l.id) { it.copy(widthFrac = v) } }
                LSlider("স্বচ্ছতা (ধোঁয়াশা কম = বেশি)", l.opacity, 0.1f..1f) { v -> tl.edit<ImageLayer>(l.id) { it.copy(opacity = v) } }
                LSlider("ডান-বাম অবস্থান", l.x, 0f..1f) { v -> tl.edit<ImageLayer>(l.id) { it.copy(x = v) } }
                LSlider("ওপর-নিচ অবস্থান", l.y, 0f..1f) { v -> tl.edit<ImageLayer>(l.id) { it.copy(y = v) } }
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
                LSlider("মাপ", l.sizeFrac, 0.03f..0.1f) { v -> tl.edit<TagLayer>(l.id) { it.copy(sizeFrac = v) } }
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
                LSlider("বারের উচ্চতা", l.barHeightFrac, 0.04f..0.2f) { v -> tl.edit<TickerLayer>(l.id) { it.copy(barHeightFrac = v) } }
                LSlider("গতি", l.speed, 0.03f..0.5f) { v -> tl.edit<TickerLayer>(l.id) { it.copy(speed = v) } }
                LSlider("নিচ থেকে দূরত্ব", l.bottomFrac, 0f..0.5f) { v -> tl.edit<TickerLayer>(l.id) { it.copy(bottomFrac = v) } }
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
                ColorRow("ব্যাকগ্রাউন্ড", l.bgColor, false) { c -> tl.edit<NameBarLayer>(l.id) { it.copy(bgColor = c) } }
                ColorRow("লেখার রং", l.textColor, false) { c -> tl.edit<NameBarLayer>(l.id) { it.copy(textColor = c) } }
                LSlider("নিচ থেকে দূরত্ব", l.bottomFrac, 0f..0.6f) { v -> tl.edit<NameBarLayer>(l.id) { it.copy(bottomFrac = v) } }
            }

            is AdLayer -> {
                OutlinedButton(onClick = onReplaceImage) {
                    Text(if (l.uri == null) "🖼️ বিজ্ঞাপনের ছবি বাছুন" else "🖼️ ছবি বদলান")
                }
                LSlider("উচ্চতা (ফ্রেমের তুলনায়)", l.heightFrac, 0.05f..0.5f) { v -> tl.edit<AdLayer>(l.id) { it.copy(heightFrac = v) } }
            }

            else -> Text("এই লেয়ারের বিস্তারিত সেটিং পরের ধাপে আসবে।", color = C.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${(value * 100).toInt()}%", fontSize = 12.sp, color = C.Gray)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

/** রঙের গোল বাটন; allowNone হলে প্রথমে "নেই" (০) বাটন */
@Composable
private fun ColorRow(label: String, current: Int, allowNone: Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = C.Gray)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = if (allowNone) listOf(0) + PALETTE else PALETTE
            options.forEach { c ->
                val selected = c == current
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(if (c == 0) Color.White else Color(c))
                        .border(if (selected) 3.dp else 1.dp, if (selected) C.Red else C.Border, CircleShape)
                        .clickable { onPick(c) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (c == 0) Text("✕", fontSize = 12.sp, color = C.Muted)
                }
            }
        }
    }
}
