package com.digitalcrafting25.newsapp

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * রেডি ইন্ট্রো/আউট্রো বাছাইয়ের অংশ (HTML-এর "ব্যাকগ্রাউন্ড ডিজাইন"/"আউট্রোর স্টাইল" থাম্বনেইলের মতো):
 * আগে স্টাইলের আসল প্রিভিউ-ছবি + লেখা দেখে বাছা হয়, তারপর "বানান" চাপলে তবেই ভিডিও জেনারেট হয়।
 */

private fun previewDims(aspect: Aspect, longSide: Int): Pair<Int, Int> {
    val r = aspect.w.toFloat() / aspect.h
    return if (r >= 1f) longSide to max(2, (longSide / r).roundToInt()) else max(2, (longSide * r).roundToInt()) to longSide
}

/** স্পেক অনুযায়ী কার্ডের একটা স্থির ফ্রেম। লেখা বদলালে একটু থেমে (ডিবাউন্স) নতুন করে আঁকে; তখন আগের ছবিই থাকে */
@Composable
fun rememberCardPreview(spec: CardSpec, aspect: Aspect, longSide: Int): State<ImageBitmap?> {
    val ctx = LocalContext.current.applicationContext
    return produceState<ImageBitmap?>(null, spec.style, spec.outro, spec.title, spec.subtitle, spec.logo, aspect, longSide) {
        if (value != null) delay(250)
        value = withContext(Dispatchers.Default) {
            runCatching {
                val (w, h) = previewDims(aspect, longSide)
                CardVideoMaker.preview(ctx, spec, w, h).asImageBitmap()
            }.getOrNull()
        }
    }
}

/** বড় প্রিভিউ: এখন যে স্টাইল/লেখা বাছা, কার্ড ঠিক এমন দেখাবে */
@Composable
fun CardPreviewBox(spec: CardSpec, aspect: Aspect, maxHeight: androidx.compose.ui.unit.Dp = 220.dp, modifier: Modifier = Modifier) {
    val img by rememberCardPreview(spec, aspect, 720)
    val ratio = aspect.w.toFloat() / aspect.h
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.heightIn(max = maxHeight).aspectRatio(ratio)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFF222228))
                .border(1.dp, ED.Border, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val b = img
            if (b != null) Image(b, null, Modifier.fillMaxSize())
            else Text("প্রিভিউ আঁকা হচ্ছে…", color = ED.Sub, fontSize = 12.sp)
        }
    }
}

/** স্টাইলের থাম্বনেইল সারি: প্রতিটায় ওই স্টাইলের আসল প্রিভিউ (বাছা লেখা/লোগোসহ) + নাম */
@Composable
fun CardStyleStrip(spec: CardSpec, aspect: Aspect, onSelect: (CardStyle) -> Unit) {
    val ratio = aspect.w.toFloat() / aspect.h
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardStyle.values().forEach { st ->
            val img by rememberCardPreview(spec.copy(style = st), aspect, 240)
            val sel = st == spec.style
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { onSelect(st) }.padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.height(68.dp).aspectRatio(ratio)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xFF222228))
                        .border(if (sel) 2.dp else 1.dp, if (sel) Color.White else ED.Border, RoundedCornerShape(8.dp)),
                ) {
                    val b = img
                    if (b != null) Image(b, null, Modifier.fillMaxSize())
                }
                Text(
                    st.label, fontSize = 11.sp, color = if (sel) ED.Text else ED.Sub,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/**
 * "রেডি ইন্ট্রো"/"রেডি আউট্রো" চাপলে প্রথমে এই পর্দা: স্টাইল (প্রিভিউসহ) ও লেখা বেছে নিয়ে "বানান" চাপলে তবেই কার্ড জেনারেট হয়।
 */
@Composable
fun CardMakeDialog(vm: EditorViewModel, outro: Boolean, onInfo: (String?) -> Unit, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        EditorDarkTheme { CardMakeBody(vm, outro, onInfo, onClose) }
    }
}

@Composable
private fun CardMakeBody(vm: EditorViewModel, outro: Boolean, onInfo: (String?) -> Unit, onClose: () -> Unit) {
    var spec by remember { mutableStateOf(if (outro) CardDefaults.outro(vm.logo) else CardDefaults.intro(vm.caption, vm.logo)) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF111111)).verticalScroll(rememberScrollState())
            .padding(top = 28.dp, bottom = 12.dp, start = 14.dp, end = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (outro) "✨ রেডি আউট্রো" else "🎬 রেডি ইন্ট্রো", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
            if (outro) "স্টাইল বেছে নিন — বানানোর আগে নিচে প্রিভিউ দেখাবে।" else "স্টাইল ও লেখা ঠিক করুন — বানানোর আগে নিচে প্রিভিউ দেখাবে।",
            color = Color(0xFFBBBBBB), fontSize = 12.sp,
        )
        CardPreviewBox(spec, vm.aspect)
        Text(if (outro) "আউট্রোর স্টাইল" else "ব্যাকগ্রাউন্ড ডিজাইন", color = ED.Sub, fontSize = 13.sp)
        CardStyleStrip(spec, vm.aspect) { spec = spec.copy(style = it) }
        if (!outro) {
            OutlinedTextField(
                value = spec.title, onValueChange = { spec = spec.copy(title = it.take(40)) },
                label = { Text("বড় লেখা (যেমন: ব্রেকিং নিউজ)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = spec.subtitle, onValueChange = { spec = spec.copy(subtitle = it.take(70)) },
                label = { Text("নিচের ছোট লেখা (যেমন: সর্বশেষ সংবাদ • সবার আগে)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            if (vm.logo != null) "আপনার লোগো কার্ডে বসবে (প্রিভিউতেও দেখাচ্ছে)।" else "লোগো বাছা থাকলে কার্ডে তা-ও বসবে (লোগো না থাকলে বাদ যাবে)।",
            color = ED.Muted, fontSize = 11.sp,
        )
        err?.let { Text("⚠️ $it", color = ED.Err, fontSize = 13.sp) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("বাতিল", Modifier.weight(1f), color = C.Gray) { onClose() }
            AppBtn(
                if (outro) "✨ আউট্রো বানিয়ে শেষে বসান" else "🎬 ইন্ট্রো বানিয়ে শুরুতে বসান",
                Modifier.weight(2f), color = C.Green,
            ) {
                val m = vm.addCard(outro, onInfo, spec)
                if (m != null) err = m else {
                    onInfo(if (outro) "🏁 আউট্রো বানানো হচ্ছে…" else "🎬 ইন্ট্রো বানানো হচ্ছে…")
                    onClose()
                }
            }
        }
    }
}


/**
 * নতুন ভিডিও দেওয়ার পর, নিচের বিজ্ঞাপন/ইন্ট্রো-আউট্রো নিজে বসার আগে এই পপ-আপ: ইন্ট্রো-আউট্রোর টেমপ্লেট ও ইন্ট্রোর লেখা বাছা হয়।
 * আগেরবার বাছা টেমপ্লেট/লেখা আগে থেকে বসানো থাকে; একই টেমপ্লেট-লেখায় আগে বানানো কার্ড সেভ করা আছে, তাই সরাসরি বসে যায়।
 */
@Composable
fun SeedChoiceDialog(vm: EditorViewModel, init: EditorViewModel.SeedChoice) {
    var intro by remember { mutableStateOf(init.intro) }
    var outro by remember { mutableStateOf(init.outro) }
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        EditorDarkTheme {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF111111)).verticalScroll(rememberScrollState())
                    .padding(top = 28.dp, bottom = 12.dp, start = 14.dp, end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("🎬 ইন্ট্রো-আউট্রো বাছুন", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "ইন্ট্রো-আউট্রো ও নিচের বিজ্ঞাপন বসার আগে টেমপ্লেট আর লেখা ঠিক করুন। একই টেমপ্লেট-লেখা আগে বানানো থাকলে সেভ করা কার্ডটাই বসবে, নতুন করে বানাতে হবে না।",
                    color = Color(0xFFBBBBBB), fontSize = 12.sp,
                )
                Text("🎬 ইন্ট্রো", color = ED.Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                CardPreviewBox(intro, vm.aspect, maxHeight = 150.dp)
                CardStyleStrip(intro, vm.aspect) { intro = intro.copy(style = it) }
                OutlinedTextField(
                    value = intro.title, onValueChange = { intro = intro.copy(title = it.take(40)) },
                    label = { Text("বড় লেখা (যেমন: ব্রেকিং নিউজ)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = intro.subtitle, onValueChange = { intro = intro.copy(subtitle = it.take(70)) },
                    label = { Text("নিচের ছোট লেখা (যেমন: সর্বশেষ সংবাদ • সবার আগে)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("🏁 আউট্রো", color = ED.Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                CardPreviewBox(outro, vm.aspect, maxHeight = 150.dp)
                CardStyleStrip(outro, vm.aspect) { outro = outro.copy(style = it) }
                Text("আউট্রোর লেখা ফিক্সড (ধন্যবাদ + লাইক/শেয়ার/সাবস্ক্রাইব), শুধু টেমপ্লেট বদলানো যায়।", color = ED.Muted, fontSize = 11.sp)
                AppBtn("✅ ঠিক আছে — বসান", Modifier.fillMaxWidth().padding(top = 4.dp), color = C.Green) {
                    vm.answerSeedChoice(EditorViewModel.SeedChoice(intro, outro))
                }
            }
        }
    }
}
