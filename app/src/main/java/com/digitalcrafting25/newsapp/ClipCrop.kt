package com.digitalcrafting25.newsapp

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ভিডিও/ছবি ক্লিপের ক্রপ: ক্রপের সময় **মূল ভিডিওর সম্পূর্ণ ছবি** (কোনো কাটছাঁট/জুম/ফ্লিপ ছাড়া, নিজের আসল মাপে) দেখায়,
 * তার ওপর একটা আয়তক্ষেত্র বাছা হয় — শুধু ওই অংশটুকুই ভিডিওতে দেখা যায়।
 * বাছা অংশ অনুপাত না বদলে ফ্রেমে ফিট হয় (অনুপাত ফ্রেমের মতো হলে ফ্রেম পুরো ভরে, অন্য অনুপাতে দুই পাশে/ওপর-নিচে কালো জায়গা থাকে)।
 * ক্লিপে cropX/Y/W/H = **মূল ভিডিও-ফ্রেমের** ০..১ ভগ্নাংশে; প্রিভিউ (CropFrame) ও রেন্ডার (RenderComposer) একই হিসাব মানে।
 * ক্রম: মূল ভিডিও → ক্রপ → ফ্রেমে ফিট → (জুম/পজিশন/ফ্লিপ)।
 */
object ClipCrop {
    /** প্রিভিউ ফ্রেমের অনুপাত (VideoEditorScreen.PREVIEW_ASPECT-এর মতো) */
    val FRAME_ASPECT: Float get() = FrameAspect.ratio

    /** ডায়ালগে দেখানোর জন্য মূল ফ্রেম (হার্ডওয়্যার বিটম্যাপ হলে সফটওয়্যার কপি) */
    fun softBitmap(src0: Bitmap): Bitmap =
        if (src0.config == Bitmap.Config.HARDWARE) src0.copy(Bitmap.Config.ARGB_8888, false) ?: src0 else src0
}

/**
 * প্রিভিউ ফ্রেমের ভেতরে ক্রপ-করা ভিডিও/ছবি দেখায়। ক্রপ না থাকলে content সরাসরি ফ্রেম ভরে (আগের মতোই)।
 * ক্রপ থাকলে content-কে মূল ভিডিওর আসল অনুপাতের (srcAspect) একটা বক্সে বসানো হয়, তারপর শুধু বাছা অংশ
 * ফ্রেমের মাঝে ফিট হয়ে দেখায় (রেন্ডারের Crop → Presentation(FIT) এর একই হিসাব)।
 * @param layerTx জুম/পজিশন/ফ্লিপ — ফ্রেম-সাইজের কন্টেইনারের ওপর বসে (রেন্ডারে ক্রপ-ফিটের পরে বসে, তাই একই)
 */
@Composable
fun CropFrame(
    c: Clip?,
    srcAspect: Float,
    layerTx: GraphicsLayerScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val on = c != null && c.hasCrop()
    Box(Modifier.fillMaxSize().clipToBounds().graphicsLayer(layerTx)) {
        if (!on || c == null) {
            content()
        } else {
            val cx = c.cropX.coerceIn(0f, 0.95f)
            val cy = c.cropY.coerceIn(0f, 0.95f)
            val cw = c.cropW.coerceIn(0.05f, 1f - cx)
            val ch = c.cropH.coerceIn(0.05f, 1f - cy)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val fw: Dp = maxWidth
                val fh: Dp = maxHeight
                val fa: Float = fw / fh                                        // ফ্রেমের অনুপাত
                val ra: Float = (cw / ch * srcAspect).coerceAtLeast(0.01f)      // বাছা অংশের (পিক্সেলের) অনুপাত
                val winW: Dp = if (ra > fa) fw else fh * ra                     // বাছা অংশ ফ্রেমে ফিট হয়ে যতটুকু জায়গা নেয়
                val winH: Dp = if (ra > fa) fw / ra else fh
                val srcW: Dp = winW / cw                                        // মূল ছবি ওই জানালায় যত বড় দেখাবে
                val srcH: Dp = winH / ch
                Box(Modifier.align(Alignment.Center).size(winW, winH).clipToBounds()) {
                    Box(
                        Modifier.align(Alignment.TopStart)
                            .requiredSize(srcW, srcH)
                            .offset(-srcW * cx, -srcH * cy),
                    ) { content() }
                }
            }
        }
    }
}

/**
 * ✂️ ভিডিও ক্রপ ডায়ালগ (পুরো স্ক্রিন): ফ্রেমের ছবির ওপর ক্রপ-বক্স।
 * ভেতরে টানলে বক্স সরে (কোন অংশ দেখাবে), চার কোণা টানলে ছোট-বড় হয়, স্লাইডারে সাইজ বদলায়।
 * মাপের অনুপাত: ফ্রেমের মাপ / ১:১ / ৯:১৬ / ৪:৫ / স্বাধীন। "প্রয়োগ" চাপলে (x, y, w, h) ফ্রেমের ০–১ অনুপাতে ফেরত যায়।
 * @param srcMs ক্লিপের ভিডিওর কোন সময়ের (ms) ফ্রেম দেখাবে
 */
@Composable
fun ClipCropDialog(
    clip: Clip,
    srcMs: Long,
    onApply: (x: Float, y: Float, w: Float, h: Float) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        ClipCropBody(clip, srcMs, onApply, onCancel)
    }
}

@Composable
private fun ClipCropBody(
    clip: Clip,
    srcMs: Long,
    onApply: (Float, Float, Float, Float) -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val grab = with(density) { 44.dp.toPx() }
    val minSide = with(density) { 48.dp.toPx() }
    val handleR = with(density) { 9.dp.toPx() }

    // মূল ভিডিওর সম্পূর্ণ ফ্রেম — কোনো ক্রপ/জুম/ফ্লিপ ছাড়া, নিজের আসল অনুপাতে
    val frame by produceState<Bitmap?>(null, clip.uri, srcMs) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val raw: Bitmap? = if (clip.isImage) {
                    loadImageThumb(ctx, clip.uri, 1280)
                } else {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, clip.uri)
                        r.getFrameAtTime(srcMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        runCatching { r.release() }
                    }
                }
                raw?.let { ClipCrop.softBitmap(it) }
            }.getOrNull()
        }
    }
    val frameImg = remember(frame) { frame?.asImageBitmap() }
    // মূল ভিডিওর অনুপাত (লোড হওয়ার আগে ফ্রেমের অনুপাত ধরা হয়)
    val srcAspect: Float = frame?.let { it.width / it.height.toFloat() } ?: ClipCrop.FRAME_ASPECT

    var box by remember { mutableStateOf(IntSize.Zero) }
    // মূল ভিডিও (নিজের আসল অনুপাতে) বক্সের ভেতরে ফিট হয়ে যেখানে বসবে
    val imgRect: Rect = remember(box, srcAspect) {
        if (box.width <= 0 || box.height <= 0) Rect(0f, 0f, 1f, 1f) else {
            val h = min(box.height.toFloat(), box.width / srcAspect)
            val w = h * srcAspect
            val l = (box.width - w) / 2f; val t = (box.height - h) / 2f
            Rect(l, t, l + w, t + h)
        }
    }

    // imgRect-এর ভেতরে r (চওড়া/উচ্চতা) অনুপাতের সবচেয়ে বড় বক্স, মাঝখানে
    fun centered(r: Float): Rect {
        val w: Float; val h: Float
        if (imgRect.width / imgRect.height > r) { h = imgRect.height; w = h * r } else { w = imgRect.width; h = w / r }
        val cx = imgRect.center.x; val cy = imgRect.center.y
        return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
    // ক্রপ না থাকলে ভিডিও এখন ফ্রেমে যে অংশ দেখায় (মাঝের ফ্রেম-অনুপাতের অংশ) সেটাই শুরুর বক্স
    fun fromClip(): Rect = if (!clip.hasCrop()) centered(ClipCrop.FRAME_ASPECT) else Rect(
        imgRect.left + clip.cropX * imgRect.width, imgRect.top + clip.cropY * imgRect.height,
        imgRect.left + (clip.cropX + clip.cropW) * imgRect.width, imgRect.top + (clip.cropY + clip.cropH) * imgRect.height,
    )
    var crop by remember(imgRect) { mutableStateOf(fromClip()) }
    // বাছা অংশের (পিক্সেলের) অনুপাত ফ্রেমের মতো হলে "ফ্রেমের মাপ" লক, নইলে স্বাধীন
    val frameLike = abs(clip.cropW * srcAspect / clip.cropH - ClipCrop.FRAME_ASPECT) < 0.02f
    var ratioKey by remember(srcAspect) { mutableStateOf(if (!clip.hasCrop() || frameLike) "frame" else "free") }
    var ratio by remember(srcAspect) { mutableStateOf<Float?>(if (!clip.hasCrop() || frameLike) ClipCrop.FRAME_ASPECT else null) }

    fun setRatio(key: String, r: Float?) {
        ratioKey = key; ratio = r
        crop = if (r == null) imgRect else centered(r)
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF111111)).padding(top = 28.dp, bottom = 12.dp)) {
        Text("✂️ ভিডিও ক্রপ করুন", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 14.dp))
        Text(
            "মূল ভিডিওর পুরো ছবি দেখা যাচ্ছে। বক্সের ভেতরে টেনে সরান (ভিডিওর কোন অংশ দেখাবে), কোণা টেনে বা স্লাইডারে সাইজ বদলান। ভিডিওর ফ্রেমের মাপ একই থাকবে।",
            color = Color(0xFFBBBBBB), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = ratioKey == "frame", onClick = { setRatio("frame", ClipCrop.FRAME_ASPECT) }, label = { Text("ফ্রেমের মাপ") })
            FilterChip(selected = ratioKey == "1:1", onClick = { setRatio("1:1", 1f) }, label = { Text("১:১") })
            FilterChip(selected = ratioKey == "9:16", onClick = { setRatio("9:16", 9f / 16f) }, label = { Text("৯:১৬") })
            FilterChip(selected = ratioKey == "4:5", onClick = { setRatio("4:5", 4f / 5f) }, label = { Text("৪:৫") })
            FilterChip(selected = ratioKey == "free", onClick = { setRatio("free", null) }, label = { Text("স্বাধীন") })
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it }
                .pointerInput(imgRect, ratio) {
                    var mode = -1   // ০–৩ = কোণা (TL, TR, BL, BR), ৪ = পুরো বক্স সরানো
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
                drawRect(Color.Black, Offset(imgRect.left, imgRect.top), Size(imgRect.width, imgRect.height))
                frameImg?.let {
                    drawImage(
                        it,
                        dstOffset = IntOffset(imgRect.left.toInt(), imgRect.top.toInt()),
                        dstSize = IntSize(imgRect.width.toInt().coerceAtLeast(1), imgRect.height.toInt().coerceAtLeast(1)),
                    )
                }
                val shade = Color(0xAA000000)
                val c = crop
                // বক্সের বাইরের অংশ ঝাপসা
                drawRect(shade, Offset(imgRect.left, imgRect.top), Size(imgRect.width, c.top - imgRect.top))
                drawRect(shade, Offset(imgRect.left, c.bottom), Size(imgRect.width, imgRect.bottom - c.bottom))
                drawRect(shade, Offset(imgRect.left, c.top), Size(c.left - imgRect.left, c.height))
                drawRect(shade, Offset(c.right, c.top), Size(imgRect.right - c.right, c.height))
                // বর্ডার ও তিন ভাগের গাইড
                drawRect(Color.White, c.topLeft, c.size, style = Stroke(width = 2f))
                val g = Color(0x66FFFFFF)
                for (i in 1..2) {
                    val gx = c.left + c.width * i / 3f; val gy = c.top + c.height * i / 3f
                    drawLine(g, Offset(gx, c.top), Offset(gx, c.bottom), strokeWidth = 1f)
                    drawLine(g, Offset(c.left, gy), Offset(c.right, gy), strokeWidth = 1f)
                }
                listOf(c.topLeft, c.topRight, c.bottomLeft, c.bottomRight).forEach {
                    drawCircle(Color.White, handleR, it)
                    drawCircle(Color(0xFFC62828), handleR * 0.55f, it)
                }
            }
            if (frameImg == null) Text("ফ্রেম লোড হচ্ছে…", color = Color(0xFFBBBBBB), fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        }

        // সাইজ স্লাইডার: অনুপাত না বদলে বক্সের মাঝ ধরে ছোট-বড়
        val aNow = (crop.width / crop.height.coerceAtLeast(1f)).coerceAtLeast(0.01f)
        val sMax = max(0.11f, min(1f, imgRect.height * aNow / imgRect.width))
        val sNow = (crop.width / imgRect.width).coerceIn(0.1f, sMax)
        Text(
            "ক্রপের সাইজ: ${PostUtil.bn(Math.round(crop.width / imgRect.width * 100))}% × ${PostUtil.bn(Math.round(crop.height / imgRect.height * 100))}% (মূল ভিডিওর)",
            color = Color(0xFFBBBBBB), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        )
        Slider(
            value = sNow,
            onValueChange = { s ->
                val w = s * imgRect.width
                val h = w / aNow
                val cc = crop.center
                val l = (cc.x - w / 2f).coerceIn(imgRect.left, max(imgRect.left, imgRect.right - w))
                val t = (cc.y - h / 2f).coerceIn(imgRect.top, max(imgRect.top, imgRect.bottom - h))
                crop = Rect(l, t, l + w, t + h)
            },
            valueRange = 0.1f..sMax,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        if (ratio == null || abs(aNow - ClipCrop.FRAME_ASPECT) > 0.02f) {
            Text(
                "ℹ️ বাছা অংশের অনুপাত ফ্রেমের মতো নয়, তাই ফ্রেমের মাপ একই রেখে দুই পাশে/ওপর-নিচে কালো জায়গা থাকবে।",
                color = Color(0xFFBBBBBB), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("বাতিল", Modifier.weight(1f), color = C.Gray) { onCancel() }
            AppBtn("↺ রিসেট", Modifier.weight(1f), color = C.Blue) { setRatio("frame", ClipCrop.FRAME_ASPECT) }
            AppBtn("✅ ক্রপ করুন", Modifier.weight(1.4f), color = C.Green) {
                val w = imgRect.width; val h = imgRect.height
                val fx = ((crop.left - imgRect.left) / w).coerceIn(0f, 1f)
                val fy = ((crop.top - imgRect.top) / h).coerceIn(0f, 1f)
                val fw = (crop.width / w).coerceIn(0.05f, 1f - fx)
                val fh = (crop.height / h).coerceIn(0.05f, 1f - fy)
                // বক্স ঠিক ডিফল্ট দেখানো অংশের (মাঝের ফ্রেম-অনুপাত) সমান হলে ক্রপ বাদ
                val def = centered(ClipCrop.FRAME_ASPECT)
                val same = abs(crop.left - def.left) < 2f && abs(crop.right - def.right) < 2f &&
                    abs(crop.top - def.top) < 2f && abs(crop.bottom - def.bottom) < 2f
                if (same) onApply(0f, 0f, 1f, 1f) else onApply(fx, fy, fw, fh)
            }
        }
    }
}
