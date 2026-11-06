package com.digitalcrafting25.newsapp

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.min

/**
 * ✂️ ছবি ক্রপ টুল (পুরো স্ক্রিন)।
 * ছবির ওপর ক্রপ-ফ্রেম: ভেতরে টানলে ফ্রেম সরে, চার কোণা টানলে ছোট-বড় হয়।
 * মাপের অনুপাত: স্বাধীন / কার্ডের মাপ / ১:১ / ১৬:৯ / ৪:৩ / ৩:৪।
 * "ক্রপ করুন" চাপলে ফ্রেমের অংশ (ছবির ০–১ অনুপাতে) ফেরত যায়।
 */
@Composable
fun CropDialog(
    bitmap: Bitmap,
    cardRatio: Float,
    onApply: (l: Float, t: Float, r: Float, b: Float) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        CropBody(bitmap, cardRatio, onApply, onCancel)
    }
}

@Composable
private fun CropBody(
    bitmap: Bitmap,
    cardRatio: Float,
    onApply: (Float, Float, Float, Float) -> Unit,
    onCancel: () -> Unit,
) {
    val density = LocalDensity.current
    val grab = with(density) { 44.dp.toPx() }
    val minSide = with(density) { 56.dp.toPx() }
    val handleR = with(density) { 9.dp.toPx() }
    val imageBmp = remember(bitmap) { bitmap.asImageBitmap() }

    var box by remember { mutableStateOf(IntSize.Zero) }
    // ছবি বক্সের ভেতরে যেভাবে বসবে (ফিট)
    val imgRect: Rect = remember(box, bitmap) {
        if (box.width <= 0 || box.height <= 0) Rect(0f, 0f, 1f, 1f) else {
            val s = min(box.width / bitmap.width.toFloat(), box.height / bitmap.height.toFloat())
            val w = bitmap.width * s; val h = bitmap.height * s
            val l = (box.width - w) / 2f; val t = (box.height - h) / 2f
            Rect(l, t, l + w, t + h)
        }
    }
    var crop by remember(imgRect) { mutableStateOf(imgRect) }
    var ratioKey by remember { mutableStateOf("free") }
    var ratio by remember { mutableStateOf<Float?>(null) }

    fun centered(r: Float): Rect {
        val w: Float; val h: Float
        if (imgRect.width / imgRect.height > r) { h = imgRect.height; w = h * r } else { w = imgRect.width; h = w / r }
        val cx = imgRect.center.x; val cy = imgRect.center.y
        return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
    fun setRatio(key: String, r: Float?) {
        ratioKey = key; ratio = r
        crop = if (r == null) imgRect else centered(r)
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF111111)).padding(top = 28.dp, bottom = 12.dp)) {
        Text("✂️ ছবি ক্রপ করুন", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 14.dp))
        Text("ফ্রেমের ভেতরে টেনে সরান, কোণা টেনে ছোট-বড় করুন।", color = Color(0xFFBBBBBB), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = ratioKey == "free", onClick = { setRatio("free", null) }, label = { Text("স্বাধীন") })
            FilterChip(selected = ratioKey == "card", onClick = { setRatio("card", cardRatio) }, label = { Text("কার্ডের মাপ") })
            FilterChip(selected = ratioKey == "1:1", onClick = { setRatio("1:1", 1f) }, label = { Text("১:১") })
            FilterChip(selected = ratioKey == "16:9", onClick = { setRatio("16:9", 16f / 9f) }, label = { Text("১৬:৯") })
            FilterChip(selected = ratioKey == "4:3", onClick = { setRatio("4:3", 4f / 3f) }, label = { Text("৪:৩") })
            FilterChip(selected = ratioKey == "3:4", onClick = { setRatio("3:4", 3f / 4f) }, label = { Text("৩:৪") })
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it }
                .pointerInput(imgRect, ratio) {
                    var mode = -1   // ০–৩ = কোণা (TL, TR, BL, BR), ৪ = পুরো ফ্রেম সরানো
                    var off = Offset.Zero
                    detectDragGestures(
                        onDragStart = { p ->
                            val c = crop
                            val corners = listOf(
                                Offset(c.left, c.top), Offset(c.right, c.top),
                                Offset(c.left, c.bottom), Offset(c.right, c.bottom),
                            )
                            var best = -1; var bd = grab
                            corners.forEachIndexed { i, o ->
                                val d = (o - p).getDistance()
                                if (d < bd) { bd = d; best = i }
                            }
                            mode = if (best >= 0) best else if (c.contains(p)) 4 else -1
                            off = if (best >= 0) corners[best] - p else Offset.Zero
                        },
                        onDragEnd = { mode = -1 },
                        onDragCancel = { mode = -1 },
                    ) { change, drag ->
                        change.consume()
                        val c = crop
                        if (mode == 4) {
                            val dx = drag.x.coerceIn(imgRect.left - c.left, imgRect.right - c.right)
                            val dy = drag.y.coerceIn(imgRect.top - c.top, imgRect.bottom - c.bottom)
                            crop = c.translate(dx, dy)
                        } else if (mode in 0..3) {
                            val target = change.position + off
                            val sx = if (mode % 2 == 0) -1f else 1f
                            val sy = if (mode < 2) -1f else 1f
                            val ax = if (sx < 0) c.right else c.left     // উল্টো কোণা স্থির
                            val ay = if (sy < 0) c.bottom else c.top
                            var w = max(minSide, (target.x - ax) * sx)
                            var h = max(minSide, (target.y - ay) * sy)
                            val availW = if (sx > 0) imgRect.right - ax else ax - imgRect.left
                            val availH = if (sy > 0) imgRect.bottom - ay else ay - imgRect.top
                            val r = ratio
                            if (r != null) {
                                w = max(w, h * r)
                                w = min(w, min(availW, availH * r))
                                h = w / r
                            } else {
                                w = min(w, availW); h = min(h, availH)
                            }
                            val x2 = ax + sx * w; val y2 = ay + sy * h
                            crop = Rect(min(ax, x2), min(ay, y2), max(ax, x2), max(ay, y2))
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    imageBmp,
                    dstOffset = IntOffset(imgRect.left.toInt(), imgRect.top.toInt()),
                    dstSize = IntSize(imgRect.width.toInt().coerceAtLeast(1), imgRect.height.toInt().coerceAtLeast(1)),
                )
                val shade = Color(0xAA000000)
                val c = crop
                // ফ্রেমের বাইরের অংশ ঝাপসা
                drawRect(shade, Offset(imgRect.left, imgRect.top), Size(imgRect.width, c.top - imgRect.top))
                drawRect(shade, Offset(imgRect.left, c.bottom), Size(imgRect.width, imgRect.bottom - c.bottom))
                drawRect(shade, Offset(imgRect.left, c.top), Size(c.left - imgRect.left, c.height))
                drawRect(shade, Offset(c.right, c.top), Size(imgRect.right - c.right, c.height))
                // বর্ডার ও তিন ভাগের গাইড
                drawRect(Color.White, c.topLeft, c.size, style = Stroke(width = 2f))
                val g = Color(0x66FFFFFF)
                for (i in 1..2) {
                    val x = c.left + c.width * i / 3f; val y = c.top + c.height * i / 3f
                    drawLine(g, Offset(x, c.top), Offset(x, c.bottom), strokeWidth = 1f)
                    drawLine(g, Offset(c.left, y), Offset(c.right, y), strokeWidth = 1f)
                }
                listOf(c.topLeft, c.topRight, c.bottomLeft, c.bottomRight).forEach {
                    drawCircle(Color.White, handleR, it)
                    drawCircle(Color(0xFFC62828), handleR * 0.55f, it)
                }
            }
        }

        // নির্বাচিত অংশের মাপ (আসল ছবির পিক্সেলে)
        val pw = (crop.width / imgRect.width * bitmap.width).toInt()
        val ph = (crop.height / imgRect.height * bitmap.height).toInt()
        Text("নির্বাচিত অংশ: $pw × $ph পিক্সেল", color = Color(0xFFBBBBBB), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("বাতিল", Modifier.weight(1f), color = C.Gray) { onCancel() }
            AppBtn("↺ রিসেট", Modifier.weight(1f), color = C.Blue) { setRatio("free", null) }
            AppBtn("✅ ক্রপ করুন", Modifier.weight(1.4f), color = C.Green) {
                val w = imgRect.width; val h = imgRect.height
                onApply(
                    ((crop.left - imgRect.left) / w).coerceIn(0f, 1f),
                    ((crop.top - imgRect.top) / h).coerceIn(0f, 1f),
                    ((crop.right - imgRect.left) / w).coerceIn(0f, 1f),
                    ((crop.bottom - imgRect.top) / h).coerceIn(0f, 1f),
                )
            }
        }
    }
}
