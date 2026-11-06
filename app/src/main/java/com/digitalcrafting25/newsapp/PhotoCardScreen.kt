package com.digitalcrafting25.newsapp

import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun PhotoCardScreen(vm: PhotoCardViewModel, ads: AdsViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load(force = true) }   // সাইট ট্যাবে লোগো/নাম বদলালে এখানে নতুনটা আসে
    // লেখা / টেমপ্লেট / ছবি / সাইটের তথ্য বদলালেই প্রিভিউ নতুন করে আঁকা হয়
    LaunchedEffect(vm.title, vm.category, vm.tag, vm.cta, vm.mode, vm.name, vm.role, vm.rashi, vm.mnSite, vm.mnVer, vm.tplId, vm.photoVer, vm.fx, vm.fy, vm.useAd, vm.nextAdId) { vm.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        if (uris.isNotEmpty()) vm.setPhotos(uris)
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val ocrPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.ocr(uri) }
    var schedOpen by remember { mutableStateOf(false) }
    var confirmPost by remember { mutableStateOf(false) }
    var cropOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshBalance() }
    LaunchedEffect(vm.adTick) { if (vm.adTick > 0) ads.load(force = true) }

    val morning = vm.mode == "morning"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        H1("🖼️ ফোটোকার্ড")
        Text("ছবি ও শিরোনাম দিন, টেমপ্লেট বেছে নিন, তারপর গ্যালারিতে সেভ বা শেয়ার করুন।", color = C.Label, fontSize = 13.sp)

        vm.loadError?.let { err ->
            Column(Modifier.padding(top = 10.dp)) {
                Text("⚠️ $err", color = C.Red, fontSize = 14.sp)
                AppBtn("🔄 আবার চেষ্টা করুন", Modifier.padding(top = 8.dp), color = C.Blue) { vm.load(force = true) }
            }
        }

        // ধরন: ফটোকার্ড / বক্তব্য কার্ড
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.mode == "photo", onClick = { vm.switchMode("photo") }, label = { Text("🖼️ ফটোকার্ড") })
            FilterChip(selected = vm.mode == "quote", onClick = { vm.switchMode("quote") }, label = { Text("💬 বক্তব্য কার্ড") })
            FilterChip(selected = morning, onClick = { vm.switchMode("morning") }, label = { Text("🌅 শুভ সকাল") })
        }
        if (morning) Text("আজকের বার, বাংলা-ইংরেজি-আরবি তারিখ, সূর্যোদয়-সূর্যাস্ত ও ঢাকার আবহাওয়া নিজে থেকে বসে। চাইলে রাশি লিখুন।", color = C.Label, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (vm.mode == "quote") Text("বক্তব্যের ঘরে বক্তব্য লিখুন, বক্তার নাম ও পদবি দিন, আর ছবিতে বক্তার ছবি দিন।", color = C.Label, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))

        // প্রিভিউ
        Box(
            Modifier.padding(top = 10.dp).fillMaxWidth().aspectRatio(CardRender.aspect(vm.tplId))
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFFDDDDDD))
                .pointerInput(vm.hasPhoto, vm.mode) {   // আঙুল টেনে ছবির অবস্থান ঠিক করা (শুভ সকালে ছবি নেই)
                    if (vm.hasPhoto && vm.mode != "morning") detectDragGestures { change, drag ->
                        change.consume()
                        val k = CardRender.W / size.width.toFloat()
                        vm.pan(drag.x * k, drag.y * k)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val pv = vm.preview
            if (pv != null) {
                Image(pv.asImageBitmap(), contentDescription = "কার্ডের প্রিভিউ", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                CircularProgressIndicator(color = C.Red)
            }
        }

        if (morning) {
            FieldLabel("🔮 আজ যাদের দিনটি ভালো যাবে (রাশি, কমা দিয়ে — ঐচ্ছিক)")
            AppField(vm.rashi, { vm.rashi = it }, placeholder = "যেমন: মেষ, সিংহ, ধনু")
            Text("খালি রাখলে কার্ডে রাশির অংশটা আসবে না। সর্বোচ্চ ৪টা রাশি বসবে।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        } else {
            // ছবি
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn(if (vm.hasPhoto) "📷 ছবি বদলান" else "📷 ছবি বেছে নিন", Modifier.weight(1f), color = C.Blue) { pick() }
                if (vm.hasPhoto) AppBtn("✖ ছবি সরান", color = C.Gray, small = true) { vm.clearPhoto() }
            }
            Text(if (vm.hasPhoto) "💡 প্রিভিউতে আঙুল টেনে ছবির অবস্থান ঠিক করুন, বা ✂️ ক্রপ দিয়ে ছবির অংশ কেটে নিন।" else "ছবি না দিলে নমুনা রঙ দেখাবে।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

            if (vm.hasPhoto) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppBtn("↔️ ছবি ফ্লিপ (ডান-বাম উল্টান)", color = C.Gray, small = true) { vm.flipPhoto() }
                    AppBtn("✂️ ক্রপ", color = C.Blue, small = true) { cropOpen = true }
                    if (vm.canUndoCrop) AppBtn("↩️ ক্রপ ফেরত", color = C.Gray, small = true) { vm.undoCrop() }
                }
            }
            Text("১টি ছবি দিলে সরাসরি যোগ হবে। একাধিক (সর্বোচ্চ ৬টি) একসাথে বাছলে সব মিলিয়ে একটা কোলাজ ছবি হবে, তারপর টেনে পজিশন ঠিক করা যাবে।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !vm.collVertical, onClick = { vm.setCollVertical(false) }, label = { Text("◀️▶️ পাশাপাশি") })
                FilterChip(selected = vm.collVertical, onClick = { vm.setCollVertical(true) }, label = { Text("⬆️⬇️ উপর-নিচ") })
            }
            Text("দুইটা ছবির সাজানো (যেকোনো সময় বদলানো যাবে)", color = C.Muted, fontSize = 12.sp)

            // ✨ AI দিয়ে ছবি তৈরী
            AppBtn(if (vm.aiOpen) "▲ AI ছবি বন্ধ করুন" else "✨ ছবি তৈরী করুন (AI)", Modifier.padding(top = 8.dp), color = C.Blue) { vm.aiOpen = !vm.aiOpen }
            if (vm.aiOpen) {
                InfoCard(Modifier.padding(top = 8.dp)) {
                    Text("কেমন ছবি চান? (প্রম্পট লিখুন)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    AppField(vm.aiPrompt, { vm.aiPrompt = it }, placeholder = "যেমন: ঢাকার রাস্তায় বন্যার পানিতে রিকশা", singleLine = false, modifier = Modifier.padding(top = 6.dp))
                    Text("বাংলাদেশের প্রেক্ষাপটে বাস্তব ছবির ধাঁচে ছবি হবে (কার্টুন নয়)। ছবির ট্যাগে \"AI জেনারেটেড\" লেখা বসবে। ছবি পছন্দ না হলে আবার চাপুন।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppBtn(if (vm.aiBusy) "⏳ বানাচ্ছি…" else "🎨 ছবি বানাও", Modifier.weight(1f), enabled = !vm.aiBusy) { vm.aiImage() }
                        AppBtn("বন্ধ করুন", color = C.Gray, small = true) { vm.aiOpen = false }
                    }
                    if (vm.aiMsg.isNotEmpty()) Text(vm.aiMsg, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp), color = if (vm.aiMsg.startsWith("⚠️")) C.Red else C.Green)
                }
            }

            val quote = vm.mode == "quote"
            if (quote) {
                AppBtn(if (vm.ocrBusy) "⏳ পড়া হচ্ছে…" else "🖼️ ছবি থেকে লেখা", Modifier.padding(top = 8.dp), color = C.Blue, enabled = !vm.ocrBusy) {
                    ocrPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (vm.ocrMsg.isNotEmpty()) Text(vm.ocrMsg, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp), color = if (vm.ocrMsg.startsWith("⚠️")) C.Red else C.Green)
                if (vm.ocrText.isNotBlank()) {
                    FieldLabel("📋 ছবি থেকে পাওয়া পুরো লেখা")
                    AppField(vm.ocrText, { vm.ocrText = it }, singleLine = false)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppBtn("📋 কপি", color = C.Gray, small = true) {
                            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("ocr", vm.ocrText))
                        }
                        AppBtn("✖ বন্ধ", color = C.Gray, small = true) { vm.ocrText = ""; vm.ocrMsg = "" }
                    }
                }
            }
            FieldLabel(if (quote) "💬 বক্তব্য" else "📰 শিরোনাম")
            AppField(vm.title, { vm.title = it }, placeholder = if (quote) "বক্তার কথা লিখুন" else "খবরের শিরোনাম লিখুন", singleLine = false)
            if (!quote) AppBtn("✨ জেনারেট (AI টাইটেল, ৳১)", Modifier.padding(top = 6.dp), color = C.Blue, enabled = !vm.fcBusy) { vm.genTitle() }

            if (quote) {
                FieldLabel("👤 বক্তার নাম")
                AppField(vm.name, { vm.name = it }, placeholder = "যেমন: নাহিদ ইসলাম")
                FieldLabel("🎖️ পদবি")
                AppField(vm.role, { vm.role = it }, placeholder = "যেমন: আহ্বায়ক, এনসিপি")
            } else {
                FieldLabel("🏷️ ক্যাটাগরি")
                AppField(vm.category, { vm.category = it }, placeholder = "যেমন: বাংলাদেশ")

                FieldLabel("📌 ছবির ট্যাগ")
                AppField(vm.tag, { vm.tag = it }, placeholder = "যেমন: সংগৃহীত (ফাঁকা রাখলে দেখাবে না)")

                FieldLabel("💬 নিচের লেখা (ব্লু নিউজ ও রেড নিউজে)")
                AppField(vm.cta, { vm.cta = it }, placeholder = "যেমন: বিস্তারিত কমেন্টে")
                Text("ক্যাটাগরি, ট্যাগ ও এই লেখা শুধু এই কার্ডের জন্য বদলায়; সাইট ট্যাবের ডিফল্ট অক্ষত থাকে।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                FieldLabel("🔗 লিংক (ঐচ্ছিক)")
                AppField(vm.link, { vm.link = it }, placeholder = "https://...", keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                Text("লিংক দিলে Facebook পোস্টের কমেন্টে \"বিস্তারিত পড়ুন: লিংক\" যাবে। না দিলে কমেন্ট হবে না, শুধু ফোটোকার্ড পোস্ট হবে।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }

        }

        // বিজ্ঞাপন
        if (vm.nextAdId.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = vm.useAd, onCheckedChange = { vm.useAd = it })
                Column(Modifier.clickable { vm.useAd = !vm.useAd }) {
                    Text("📢 কার্ডে বিজ্ঞাপন বসান", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("এই কার্ডে বসবে: ${vm.nextAdLabel}। সেভ/শেয়ার করলে পরের কার্ডে পরের বিজ্ঞাপন আসবে।", color = C.Muted, fontSize = 12.sp)
                }
            }
        }

        // টেমপ্লেট
        H2("🎨 টেমপ্লেট")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            cardTpls(vm.mode).forEach { (id, name) ->
                val on = vm.tplId == id
                Column(
                    Modifier.width(96.dp).clickable { vm.tplId = id },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.width(96.dp).aspectRatio(CardRender.aspect(id)).clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFDDDDDD))
                            .border(BorderStroke(if (on) 3.dp else 1.dp, if (on) C.Red else C.Border), RoundedCornerShape(8.dp)),
                    ) {
                        vm.thumbs[id]?.let { Image(it.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Text(
                        name + if (on) " ✔" else "", fontSize = 12.sp, textAlign = TextAlign.Center,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) C.Red else C.Text, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // সেভ / শেয়ার
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn(if (working) "⏳ অপেক্ষা করুন…" else "💾 গ্যালারিতে সেভ করুন", color = C.Green, enabled = !working && vm.preview != null) {
                working = true
                scope.launch {
                    if (!vm.chargeSave()) { working = false; return@launch }
                    val uri = vm.save()
                    ads.load(force = true)   // বিজ্ঞাপন ট্যাবেও "পরেরটা" নতুন করে দেখাবে
                    vm.notice = if (uri != null) "✅ সেভ হয়েছে (Pictures/NewsPublisher)" else "❌ সেভ করা যায়নি"
                    working = false
                }
            }
            AppBtn("📤 শেয়ার করুন", color = C.Blue, enabled = !working && vm.preview != null) {
                working = true
                scope.launch {
                    if (!vm.chargeSave()) { working = false; return@launch }
                    val uri = vm.save()
                    ads.load(force = true)
                    working = false
                    if (uri == null) { vm.notice = "❌ শেয়ার করা যায়নি"; return@launch }
                    val send = Intent(Intent.ACTION_SEND).setType("image/jpeg")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { ctx.startActivity(Intent.createChooser(send, "কার্ড শেয়ার করুন")) }
                }
            }
        }
        // Facebook-এ পোস্ট / সিডিউল (শুধু Facebook পেইজ, WordPress-এ কিছু যায় না)
        if (!morning) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn(if (vm.fcBusy) "⏳ অপেক্ষা করুন…" else "📘 এখনই Facebook-এ পোস্ট", color = C.Green, enabled = !vm.fcBusy && !vm.used && vm.preview != null) {
                    vm.problem()?.let { vm.fcOut = "❌ $it" } ?: run { confirmPost = true }
                }
                AppBtn("⏰ সিডিউল পোস্ট", color = C.Blue, enabled = !vm.fcBusy && !vm.used && vm.preview != null) {
                    vm.problem()?.let { vm.fcOut = "❌ $it" } ?: run { schedOpen = true }
                }
                AppBtn("🧹 ক্লিয়ার (নতুন করে শুরু)", color = C.Gray) { vm.clearAll() }
            }
            Text("এখান থেকে শুধু Facebook পেইজে ফোটোকার্ড পোস্ট হবে। একটা কার্ডের জন্য মোট ৳১ কাটে (পোস্ট, সিডিউল বা সেভ — যেটা আগে হয়, একবারই)।" +
                (vm.balance?.let { " ব্যালেন্স: ৳" + PostUtil.bn(it.toInt()) } ?: ""), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            vm.fcOut?.let { Text(it, modifier = Modifier.padding(top = 8.dp), fontSize = 14.sp, color = if (it.startsWith("❌")) C.Red else if (it.startsWith("⚠️")) Color(0xFFB26A00) else C.Green) }
        }
        vm.notice?.let { Text(it, modifier = Modifier.padding(top = 10.dp), fontSize = 14.sp, color = if (it.startsWith("❌")) C.Red else C.Green) }
        if (confirmPost) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmPost = false },
            title = { Text("📘 Facebook-এ পোস্ট") },
            text = { Text("Facebook পেইজে এখনই ফোটোকার্ড পোস্ট করবেন? (ওয়াটারমার্ক ছাড়া যাবে)" + if (vm.link.isNotBlank()) "\nকমেন্টে লিংকও যাবে।" else "") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmPost = false; vm.postNow() }) { Text("✅ পোস্ট করুন") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmPost = false }) { Text("বাতিল") } },
        )
        if (schedOpen) SchedulePickDialog(busy = vm.fcBusy, error = vm.fcOut?.takeIf { it.startsWith("❌") }, onConfirm = { at -> vm.schedule(at) { schedOpen = false } }, onDismiss = { schedOpen = false })

        if (cropOpen) {
            val cb = vm.photoForCrop()
            if (cb == null) cropOpen = false
            else CropDialog(cb, vm.cropRatio(), onApply = { l, t, r, b -> vm.applyCrop(l, t, r, b); cropOpen = false }, onCancel = { cropOpen = false })
        }

        if (morning) MorningAutoPanel(vm)
        Box(Modifier.height(24.dp))
    }
}

/** ⏰ প্রতিদিনের অটো-পোস্ট সেটিং (ওয়েবের শুভ সকাল ট্যাবের ফর্ম) */
@Composable
private fun MorningAutoPanel(vm: PhotoCardViewModel) {
    val ctx = LocalContext.current
    H2("⏰ প্রতিদিনের অটো-পোস্ট")
    Text("সময় হলে নিজে থেকে কার্ড বানিয়ে আপনার Facebook পেইজে পোস্ট হবে।", color = C.Label, fontSize = 13.sp)

    FieldLabel("⏰ কখন পোস্ট হবে (বাংলাদেশ সময়)")
    AppBtn(MnCal.clock(vm.mnTimeMin) + " — বদলান", color = C.Blue) {
        val t = vm.mnTimeMin
        TimePickerDialog(ctx, { _, h, m -> vm.setMnTime(h, m) }, t / 60, t % 60, false).show()
    }
    Text("সকাল ৬:৩০ থেকে ৮:৩০-এর মধ্যে সময় দিতে পারবেন। এই সময়ের পর পোস্ট হবে (সামান্য দেরি হতে পারে)।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

    FieldLabel("🏷️ সাইটের নাম")
    AppField(vm.mnSite, { vm.mnSite = it }, placeholder = "যেমন: শীর্ষ নিউজ")
    Text("লোগো না থাকলে কার্ডের নিচের বারে এই নাম বসবে।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

    FieldLabel("📢 ব্যানার")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.mnChoices.forEach { (v, label) ->
            FilterChip(selected = vm.mnAd == v, onClick = { vm.mnAd = v }, label = { Text(label) })
        }
    }
    Text("“অটো” বাছলে বিজ্ঞাপন ট্যাবের বিজ্ঞাপনগুলো থেকে প্রতিদিন একটির পর একটি বসবে। চাইলে নির্দিষ্ট একটা বিজ্ঞাপন, বা ব্যানার ছাড়াও বাছতে পারেন।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = vm.mnOn, onCheckedChange = { vm.mnOn = it })
        Text("✅ এই কার্ড প্রতিদিন আমার Facebook পেইজে পোস্ট হবে", Modifier.clickable { vm.mnOn = !vm.mnOn }.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
    vm.mnBalance?.let { b ->
        val t = if (b < 0) "⚠️ ব্যালেন্স মাইনাস (৳" + MnCal.n(b.toInt()) + ")। পোস্ট চলবে, কিন্তু মাইনাস ৭ দিনের বেশি থাকলে বন্ধ হবে। ড্যাশবোর্ড থেকে ব্যালেন্স যোগ করুন।"
        else if (b < 2) "⚠️ ব্যালেন্স ২ টাকার কম। পোস্ট হবে, ব্যালেন্স মাইনাস হবে (৭ দিনের মধ্যে যোগ করুন)।" else ""
        if (t.isNotEmpty()) Text(t, color = C.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }

    AppBtn(if (vm.mnBusy) "⏳ সেভ হচ্ছে…" else "💾 সেভ করুন", Modifier.padding(top = 12.dp), color = C.Green, enabled = !vm.mnBusy) { vm.saveMorning() }
    vm.mnMsg?.let { Text(it, modifier = Modifier.padding(top = 8.dp), fontSize = 14.sp, color = if (it.startsWith("✅")) C.Green else if (it.startsWith("❌")) C.Red else C.Muted) }
}
