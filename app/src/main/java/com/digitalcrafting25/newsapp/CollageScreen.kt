package com.digitalcrafting25.newsapp

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 🧩 কোলাজ এডিট (পুরো স্ক্রিন)।
 * কোলাজের যে ঘরে চাপবেন সেই ছবি বাছা হবে (লাল ফ্রেম)। তারপর:
 *  • আঙুল টেনে ওই ছবির অংশ সরানো, দুই আঙুলে চিমটি দিয়ে জুম (অথবা নিচের স্লাইডার)
 *  • বাকি ছবিগুলো যেমন ছিল তেমনই থাকে
 * ২টি ছবির কোলাজে পাশাপাশি ↔ উপর-নিচ বদলানো যায়।
 */
@Composable
fun CollageDialog(
    initial: List<PostUtil.CollageItem>,
    initialVertical: Boolean,
    onApply: (List<PostUtil.CollageItem>, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        CollageBody(initial, initialVertical, onApply, onCancel)
    }
}

private const val PREV = 700           // প্রিভিউয়ের পিক্সেল মাপ (চূড়ান্ত কোলাজ ১২০০)
private const val FULL = 1200f
private const val MAX_ZOOM = 4f

@Composable
private fun CollageBody(
    initial: List<PostUtil.CollageItem>,
    initialVertical: Boolean,
    onApply: (List<PostUtil.CollageItem>, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var items by remember { mutableStateOf(initial) }
    var vert by remember { mutableStateOf(initialVertical) }
    var sel by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    // আঙুল নড়লেই আবার আঁকা; আগেরটা বাতিল হয়ে নতুনটা চলে
    LaunchedEffect(items, vert) {
        preview = withContext(Dispatchers.Default) { PostUtil.collageItems(items, vert, PREV) }
    }

    fun update(i: Int, f: (PostUtil.CollageItem) -> PostUtil.CollageItem) {
        if (i !in items.indices) return
        items = items.toMutableList().also { it[i] = f(it[i]) }
    }

    /** [dx],[dy] = ১২০০×১২০০ ক্যানভাসের পিক্সেলে আঙুলের সরণ */
    fun panSel(dx: Float, dy: Float) {
        val i = sel
        val im = items.getOrNull(i) ?: return
        val r = PostUtil.collageCells(items.size, vert, FULL).getOrNull(i) ?: return
        val k = max(r.width() / im.bmp.width, r.height() / im.bmp.height) * im.zoom
        val ox = im.bmp.width * k - r.width(); val oy = im.bmp.height * k - r.height()
        update(i) { m ->
            m.copy(
                fx = if (ox > 1f) (m.fx - dx / ox).coerceIn(0f, 1f) else m.fx,
                fy = if (oy > 1f) (m.fy - dy / oy).coerceIn(0f, 1f) else m.fy,
            )
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF111111)).padding(top = 28.dp, bottom = 12.dp)) {
        Text("🧩 কোলাজ এডিট", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 14.dp))
        Text(
            "যে ছবি বদলাতে চান তার ঘরে চাপুন, তারপর আঙুল টেনে সরান বা চিমটি দিয়ে জুম করুন। বাকি ছবি অক্ষত থাকবে।",
            color = Color(0xFFBBBBBB), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        )

        // কোন ছবি বাছা আছে
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.indices.forEach { i ->
                FilterChip(selected = sel == i, onClick = { sel = i }, label = { Text("ছবি " + PostUtil.bn(i + 1)) })
            }
            if (items.size == 2) {
                FilterChip(selected = !vert, onClick = { vert = false }, label = { Text("◀️▶️ পাশাপাশি") })
                FilterChip(selected = vert, onClick = { vert = true }, label = { Text("⬆️⬇️ উপর-নিচ") })
            }
        }

        // কোলাজ প্রিভিউ: চাপ = ঘর বাছা, টানা/চিমটি = বাছা ছবির সরানো/জুম
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .pointerInput(Unit) {
                        detectTapGestures { p ->
                            val k = FULL / size.width.toFloat()
                            val cells = PostUtil.collageCells(items.size, vert, FULL)
                            val hit = cells.indexOfFirst { it.contains(p.x * k, p.y * k) }
                            if (hit >= 0) sel = hit
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val k = FULL / size.width.toFloat()
                            if (zoom != 1f) update(sel) { m -> m.copy(zoom = (m.zoom * zoom).coerceIn(1f, MAX_ZOOM)) }
                            if (pan != Offset.Zero) panSel(pan.x * k, pan.y * k)
                        }
                    },
            ) {
                preview?.let { Image(it.asImageBitmap(), contentDescription = "কোলাজ", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                Canvas(Modifier.fillMaxSize()) {
                    val k = this.size.width / FULL
                    val cells = PostUtil.collageCells(items.size, vert, FULL)
                    cells.forEachIndexed { i, r ->
                        val on = i == sel
                        drawRect(
                            if (on) Color(0xFFE53935) else Color(0x55FFFFFF),
                            Offset(r.left * k, r.top * k), Size(r.width() * k, r.height() * k),
                            style = Stroke(width = if (on) 6f else 2f),
                        )
                    }
                }
            }
        }

        // বাছা ছবির জুম স্লাইডার
        val cur = items.getOrNull(sel)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("🔍 জুম", color = Color.White, fontSize = 13.sp)
            Slider(
                value = cur?.zoom ?: 1f,
                onValueChange = { v -> update(sel) { m -> m.copy(zoom = v) } },
                valueRange = 1f..MAX_ZOOM,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(PostUtil.bn(Math.round((cur?.zoom ?: 1f) * 100)) + "%", color = Color(0xFFBBBBBB), fontSize = 12.sp)
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("বাতিল", Modifier.weight(1f), color = C.Gray) { onCancel() }
            AppBtn("↺ এই ছবি রিসেট", Modifier.weight(1.2f), color = C.Blue) { update(sel) { m -> m.copy(zoom = 1f, fx = 0.5f, fy = 0.5f) } }
            AppBtn("✅ প্রয়োগ", Modifier.weight(1.2f), color = C.Green) { onApply(items, vert) }
        }
    }
}
