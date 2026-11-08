package com.digitalcrafting25.newsapp

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

// ওয়েবের ইনলাইন রঙ (index.html #p-card)
private val PillBlue = Color(0xFF1976D2)     // .fccats button.on
private val Purple = Color(0xFF6A1B9A)       // ✨ জেনারেট, ছবি তৈরী (AI), ছবি থেকে লেখা
private val Teal = Color(0xFF00695C)         // ফ্লিপ, ক্রপ
private val Orange = Color(0xFFEF6C00)       // 💾 সেভ ইমেজ
private val FpickBlue = Color(0xFF1976D2)    // .fpick (ছবি বাছুন)
private val TplBorder = Color(0xFFDDDDDD)    // .tpl
private val ConfirmGray = Color(0xFF777777)

private const val CAT_PHOTO = "photo"
private const val CAT_QUOTE = "quote"
private const val CAT_MORNING = "morning"

/** ওয়েবের পেইজ `#p-card` (🖼️ ফোটোকার্ড) — একই ক্রম, একই লেখা, একই রং */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoCardScreen(vm: PhotoCardViewModel, ads: AdsViewModel, np: NewPostViewModel) {
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
    val ocrPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> if (uri != null) vm.ocr(uri) }
    var schedOpen by remember { mutableStateOf(false) }
    var confirmPost by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var cropOpen by remember { mutableStateOf(false) }
    var collOpen by remember { mutableStateOf(false) }
    var tplEdit by remember { mutableStateOf<String?>(null) }
    var tplReset by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshBalance() }
    LaunchedEffect(vm.adTick) { if (vm.adTick > 0) ads.load(force = true) }

    val morning = vm.mode == CAT_MORNING
    val quote = vm.mode == CAT_QUOTE
    val locked = vm.paid   // ওয়েবের fcPaid: টাকা কাটা হলে ফর্ম লক

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        H1("🖼️ ফোটোকার্ড")
        HSmall("এখান থেকে শুধু Facebook পেইজে ফোটোকার্ড পোস্ট হবে, WordPress-এ কিছু যাবে না।")

        vm.loadError?.let { err ->
            Column(Modifier.padding(top = 10.dp)) {
                Text("⚠️ $err", color = C.Red, fontSize = 14.sp)
                AppBtn("🔄 আবার চেষ্টা করুন", Modifier.padding(top = 8.dp), color = C.Blue) { vm.load(force = true) }
            }
        }

        // ক্যাটাগরি বার (.fccats): ফটোকার্ড / বক্তব্য কার্ড / শুভ সকাল
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 10.dp).alpha(if (locked) 0.6f else 1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(CAT_PHOTO to "🖼️ ফটোকার্ড", CAT_QUOTE to "💬 বক্তব্য কার্ড", CAT_MORNING to "🌅 শুভ সকাল").forEach { (k, t) ->
                val on = vm.mode == k
                Text(
                    t, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                    color = if (on) Color.White else C.Text,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(if (on) PillBlue else Color.White)
                        .border(1.dp, if (on) PillBlue else C.Border, RoundedCornerShape(20.dp))
                        .clickable(enabled = !locked && !on) { vm.switchMode(k) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
        val hint = when (vm.mode) {
            CAT_MORNING -> "প্রতিদিন নির্ধারিত সময়ে আপনার Facebook পেইজে নিজে থেকে \"শুভ সকাল\" কার্ড পোস্ট হবে।"
            CAT_QUOTE -> "বক্তব্য কার্ড: বক্তব্যের ঘরে বক্তব্য লিখুন, বক্তার নাম ও পদবি দিন, আর ছবিতে বক্তার ছবি দিন।"
            else -> ""
        }
        if (hint.isNotEmpty()) HSmall(hint, Modifier.padding(top = 4.dp))

        if (morning) {
            MorningPanel(vm)   // শুভ সকাল ট্যাবে আলাদা সরল ফর্ম; বাকি ফোটোকার্ড অপশন লুকানো
        } else {
            // ── প্রিভিউ প্যানেল ──
            HPnl(Modifier.padding(top = 12.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(CardRender.aspect(vm.tplId))
                            .clip(RoundedCornerShape(8.dp)).background(Color(0xFFDDDDDD))
                            .border(1.dp, TplBorder, RoundedCornerShape(8.dp))
                            .pointerInput(vm.hasPhoto, vm.mode, locked, vm.tplId) {   // আঙুল টেনে ছবির অবস্থান ঠিক করা
                                if (vm.hasPhoto && !locked) {
                                    var ok = true
                                    detectDragGestures(onDragStart = { o ->
                                        val k = CardRender.W / size.width.toFloat()
                                        ok = !quote || o.x * k <= CardRender.photoBox(vm.tplId).first   // বক্তব্য কার্ডে শুধু বাঁ পাশের ছবির অংশে
                                    }) { change, drag ->
                                        if (ok) {
                                            change.consume()
                                            val k = CardRender.W / size.width.toFloat()
                                            vm.pan(drag.x * k, drag.y * k)
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val pv = vm.preview
                        if (pv != null) Image(pv.asImageBitmap(), contentDescription = "কার্ডের প্রিভিউ", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else CircularProgressIndicator(color = C.Red)
                    }
                }
                if (quote && vm.hasPhoto) HSmall("👆 বাঁ পাশের ছবির ওপর আঙুল/মাউস দিয়ে টেনে ছবির পজিশন ঠিক করুন।", Modifier.fillMaxWidth().padding(top = 6.dp), color = C.Blue, center = true)
                if (quote && vm.hasPhoto && !locked) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    HBtn("↔️ ছবি ফ্লিপ (ডান-বাম উল্টান)", color = Teal, padH = 12.dp, padV = 8.dp) { vm.flipPhoto() }
                }
                if (vm.hasPhoto && !locked) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    HBtn("✂️ ছবি ক্রপ করুন", color = Teal, padH = 12.dp, padV = 8.dp) { cropOpen = true }
                }
                if (!vm.hasPhoto) HSmall("ছবি আপলোড করলে আপনার ছবি ও টাইটেলসহ প্রিভিউ এখানে দেখাবে। যে টেমপ্লেট বেছে নেবেন সেটাই Facebook-এ পোস্ট হবে।", Modifier.fillMaxWidth().padding(top = 6.dp), center = true)
            }

            // ── টাইটেল / বক্তব্য ──
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Text(if (quote) "বক্তব্য" else "ফোটোকার্ড টাইটেল", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (quote) HBtn(if (vm.ocrBusy) "পড়া হচ্ছে..." else "🖼️ ছবি থেকে লেখা", color = Purple, padH = 10.dp, padV = 4.dp, size = 12.sp, radius = 6.dp, enabled = !locked && !vm.ocrBusy) {
                    ocrPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (vm.mode == CAT_PHOTO) HBtn(if (vm.fcBusy) "লেখা হচ্ছে..." else "✨ জেনারেট", color = Purple, padH = 10.dp, padV = 4.dp, size = 12.sp, radius = 6.dp, enabled = !locked && !vm.fcBusy) { vm.genTitle() }
            }
            HtmlField(
                vm.title, { vm.title = it }, Modifier.padding(top = 6.dp),
                placeholder = if (quote) "এখানে বক্তার বক্তব্যটা লিখুন" else "Facebook-এ ছবির ক্যাপশন হিসেবে যাবে",
                singleLine = false, minLines = 2, readOnly = locked, visual = CardTitleVT,
            )

            // ── ছবি থেকে পাওয়া লেখার বক্স (শুধু বক্তব্য কার্ড) ──
            if (quote && (vm.ocrMsg.isNotEmpty() || vm.ocrText.isNotEmpty())) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFFAFAFA)).border(1.dp, TplBorder, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    HSmall(vm.ocrMsg, Modifier.padding(bottom = 6.dp))
                    HtmlField(vm.ocrText, { vm.ocrText = it }, placeholder = "ছবির লেখা এখানে আসবে", singleLine = false, minLines = 5)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HBtn("📋 কপি", padH = 12.dp, padV = 8.dp) {
                            if (vm.ocrText.isNotBlank()) {
                                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("ocr", vm.ocrText))
                                vm.ocrMsg = "✅ কপি হয়েছে"
                            }
                        }
                        HBtn("✖ বন্ধ", color = C.Gray, padH = 12.dp, padV = 8.dp) { vm.ocrText = ""; vm.ocrMsg = "" }
                    }
                }
            }

            // ── বক্তার নাম ও পদবি (বক্তব্য কার্ড) ──
            if (quote) {
                FieldLabel("বক্তার নাম")
                HtmlField(vm.name, { vm.name = it }, placeholder = "যেমন: নাহিদ ইসলাম", readOnly = locked)
                FieldLabel("বক্তার পদবি")
                HtmlField(vm.role, { vm.role = it }, placeholder = "যেমন: আহ্বায়ক, এনসিপি", readOnly = locked)
            }

            // ── নিচের লেখা / ক্যাটাগরি / ছবির ট্যাগ (শুধু ফটোকার্ড) ──
            if (vm.mode == CAT_PHOTO) {
                FieldLabel("নিচের লেখা (খালি রাখলে দেখাবে না)")
                HtmlField(vm.cta, { vm.setCta(it) }, placeholder = "যেমন: এইমাত্র পাওয়া", readOnly = locked)
                HSmall("এটা বদলালে শুধু এই ফোটোকার্ড মেনুর কার্ডে বদলাবে। ✍️ নতুন পোস্টের কার্ডে সেটিংসের লেখাই (আগের মতো) থাকবে।", Modifier.padding(top = 4.dp))
                FieldLabel("ক্যাটাগরি লেখা (তারিখের পাশে)")
                HtmlField(vm.category, { vm.setCategory(it) }, placeholder = "যেমন: জাতীয়", readOnly = locked)
                HSmall("খালি রাখলে ক্যাটাগরি দেখাবে না, শুধু তারিখ থাকবে। এটা বদলালে শুধু এই ফোটোকার্ড মেনুর কার্ডে বদলাবে। ✍️ নতুন পোস্টের কার্ডে সেটিংসের ক্যাটাগরিই (আগের মতো) থাকবে।", Modifier.padding(top = 4.dp))
                FieldLabel("ছবির ট্যাগ (খালি রাখলে দেখাবে না)")
                HtmlField(vm.tag, { vm.setTag(it) }, placeholder = "যেমন: সংগৃহীত", readOnly = locked)
                HSmall("AI দিয়ে ছবি বানালে এখানে নিজে থেকে \"AI জেনারেটেড\" বসবে। এটা বদলালে শুধু এই ফোটোকার্ড মেনুর কার্ডে বদলাবে, ✍️ নতুন পোস্টের কার্ডে সেটিংসের ট্যাগই থাকবে।", Modifier.padding(top = 4.dp))
            }

            // ── টেমপ্লেট ──
            FieldLabel("ফোটোকার্ড টেমপ্লেট (যেটা পোস্ট হবে সেটা বেছে নিন)")
            val canEdit = vm.mode == CAT_PHOTO && vm.tplReady
            Row(
                Modifier.fillMaxWidth().padding(top = 0.dp).alpha(if (locked) 0.6f else 1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cardTpls(vm.mode).forEach { (id, name) ->
                    val on = vm.tplId == id
                    Column(Modifier.width(120.dp)) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(CardRender.aspect(id)).clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFDDDDDD))
                                .border(3.dp, if (on) C.Red else TplBorder, RoundedCornerShape(8.dp))
                                .clickable(enabled = !locked) { vm.tplId = id },
                        ) {
                            vm.thumbs[id]?.let { Image(it.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        HSmall(name + if (on) " ✔" else "", Modifier.fillMaxWidth().padding(top = 2.dp), color = Color(0xFF555555), center = true)
                        if (vm.mode == CAT_PHOTO) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            HBtn("✏️ এডিট", Modifier.weight(1f), color = C.Gray, padH = 4.dp, padV = 4.dp, size = 12.sp, enabled = canEdit && !locked) { tplEdit = id }
                            if (TplRegistry.isDef(id) && TplRegistry.store.dtpl.has(id)) HBtn("↺", color = C.Gray, padH = 10.dp, padV = 4.dp, size = 12.sp, enabled = canEdit && !locked) { tplReset = id }
                        }
                    }
                }
                if (vm.mode == CAT_PHOTO) {
                    val free = TplRegistry.canAddCustom()
                    Column(
                        Modifier.width(120.dp).aspectRatio(4f / 5f).clip(RoundedCornerShape(8.dp)).background(Color.White)
                            .drawBehind {
                                drawRoundRect(TplBorder, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))))
                            }
                            .clickable(enabled = canEdit && !locked) {
                                if (free) tplEdit = TplRegistry.nextCustomId()
                                else vm.fcOut = "⚠️ আরেকটা কাস্টম টেমপ্লেটের জন্য ৫০০ টাকা লাগে। ওয়েব অ্যাপ থেকে ব্যালেন্স দিয়ে কিনুন, তারপর এখানে ➕ চাপুন।"
                            }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text("➕", fontSize = 34.sp, color = C.Red, lineHeight = 36.sp)
                        if (free) Text("কাস্টম টেমপ্লেট\nনিজে বানান", fontSize = 14.sp, color = C.Red, textAlign = TextAlign.Center)
                        else {
                            Text("আরেকটা কাস্টম টেমপ্লেট", fontSize = 14.sp, color = C.Red, textAlign = TextAlign.Center)
                            Text("৫০০ টাকা", fontSize = 14.sp, color = C.Red, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("ব্যালেন্স থেকে কিনুন", fontSize = 11.sp, color = C.Red, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            // ── লিংক (বক্তব্য কার্ডে নেই) ──
            if (!quote) {
                FieldLabel("লিংক (ঐচ্ছিক)")
                HtmlField(vm.link, { vm.link = it }, placeholder = "https://...", readOnly = locked, keyboard = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                HSmall("লিংক দিলে পোস্টের কমেন্টে \"বিস্তারিত পড়ুন: লিংক\" যাবে। না দিলে কমেন্ট হবে না, শুধু ফোটোকার্ড পোস্ট হবে।", Modifier.padding(top = 4.dp))
            }

            // ── ছবি আপলোড ──
            FieldLabel("ছবি আপলোড")
            Box(Modifier.padding(vertical = 6.dp)) {
                HBtn("📁 ছবি বাছুন", color = FpickBlue, padH = 16.dp, padV = 10.dp, enabled = !locked) { pick() }
            }
            if (vm.canEditCollage) {
                Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    if (vm.collItems.size == 2) {
                        Text("দুইটা ছবির সাজানো (যেকোনো সময় বদলানো যাবে)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CollBtn("◀️▶️ পাশাপাশি", on = !vm.collVertical, enabled = !locked) { vm.setCollVertical(false) }
                            CollBtn("⬆️⬇️ উপর-নিচ", on = vm.collVertical, enabled = !locked) { vm.setCollVertical(true) }
                        }
                    }
                    Box(Modifier.padding(top = 8.dp)) {
                        HBtn("🧩 ছবি টেনে পজিশন ঠিক করুন", color = Teal, padH = 12.dp, padV = 8.dp, enabled = !locked) { collOpen = true }
                    }
                    HSmall("👆 ছবির ওপর আঙুল (বা মাউস) টেনে প্রতিটি ছবির পজিশন ঠিক করুন।", Modifier.padding(top = 4.dp), color = C.Blue)
                }
            }
            HSmall(vm.fileMsg, Modifier.padding(top = 4.dp))

            // ── ✨ AI দিয়ে ছবি ──
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                HBtn("✨ ছবি তৈরী করুন (AI)", Modifier.align(Alignment.Start), color = Purple, padH = 12.dp, padV = 8.dp, enabled = !locked) { vm.aiOpen = !vm.aiOpen }
                if (vm.aiOpen) Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("কেমন ছবি চান? (প্রম্পট লিখুন)", fontWeight = FontWeight.Bold)
                    HtmlField(vm.aiPrompt, { vm.aiPrompt = it }, Modifier.padding(top = 6.dp), placeholder = "যেমন: রোড এক্সিডেন্ট, একটা বাস ফুটপাতের ওপর উঠে গেছে", singleLine = false, minLines = 3)
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HBtn(if (vm.aiBusy) "⏳ বানাচ্ছি…" else "🎨 ছবি বানাও", Modifier.weight(1f), color = C.Green, padH = 6.dp, padV = 10.dp, size = 14.sp, enabled = !locked && !vm.aiBusy) { vm.aiImage() }
                        HBtn("বন্ধ করুন", Modifier.weight(1f), color = C.Gray, padH = 6.dp, padV = 10.dp, size = 14.sp) { vm.aiOpen = false }
                    }
                    HSmall(
                        vm.aiMsg.ifEmpty { "বাংলাদেশের প্রেক্ষাপটে বাস্তব ছবির ধাঁচে ছবি হবে (কার্টুন নয়)। ছবির ট্যাগে \"AI জেনারেটেড\" লেখা বসবে। ছবি পছন্দ না হলে আবার চাপুন।" },
                        Modifier.padding(top = 4.dp),
                    )
                }
            }

            // ── খরচ/লক বার্তা ──
            val cost = if (vm.used) "পোস্ট/সিডিউল হয়ে গেছে। নতুন কার্ডের জন্য \"ক্লিয়ার\" চাপুন।" else if (locked) "এডিট করতে হলে \"ক্লিয়ার\" চেপে নতুন করে শুরু করুন।" else ""
            if (cost.isNotEmpty()) HSmall(cost, Modifier.padding(top = 10.dp))

            // ── পোস্ট / সিডিউল / সেভ ──
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavBtn(if (vm.fcBusy) "⏳ অপেক্ষা করুন…" else "📘 এখনই Facebook-এ পোস্ট", Modifier.weight(1f), color = C.Green, enabled = !vm.fcBusy && !vm.used && vm.preview != null) {
                    vm.problem()?.let { vm.fcOut = "❌ $it"; vm.fcFbUrl = null } ?: run { vm.fcOut = null; confirmPost = true }
                }
                NavBtn("⏰ সিডিউল পোস্ট", Modifier.weight(1f), color = C.Blue, enabled = !vm.fcBusy && !vm.used && vm.preview != null) {
                    vm.problem()?.let { vm.fcOut = "❌ $it"; vm.fcFbUrl = null } ?: run { vm.fcOut = null; schedOpen = true }
                }
                NavBtn(if (working) "⏳ অপেক্ষা করুন…" else "💾 সেভ ইমেজ", Modifier.weight(1f), color = Orange, enabled = !working && vm.preview != null) {
                    working = true
                    scope.launch {
                        if (!vm.chargeSave()) { working = false; return@launch }
                        val uri = vm.save()
                        ads.load(force = true)   // বিজ্ঞাপন ট্যাবেও "পরেরটা" নতুন করে দেখাবে
                        vm.fcOut = if (uri != null) "✅ ছবি সেভ হয়েছে (ওয়াটারমার্ক ছাড়া)।" else "❌ সেভ করা যায়নি"
                        vm.fcFbUrl = null
                        working = false
                    }
                }
            }
            vm.fcOut?.let { msg ->
                Row(Modifier.padding(top = 12.dp)) {
                    Text(msg + if (vm.fcFbUrl != null) " " else "", fontSize = 16.sp, color = if (msg.startsWith("❌")) C.Red else C.Text)
                    vm.fcFbUrl?.let { url ->
                        Text("ফেসবুক পোস্ট দেখুন", fontSize = 16.sp, color = C.Blue, textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
                    }
                }
            }
            vm.notice?.let { Text(it, modifier = Modifier.padding(top = 10.dp), fontSize = 14.sp, color = if (it.startsWith("❌")) C.Red else C.Green) }
            HBtn("🧹 ক্লিয়ার (নতুন করে শুরু)", Modifier.fillMaxWidth().padding(top = 10.dp), color = C.Gray) {
                if (vm.hasPhoto || vm.title.isNotBlank()) confirmClear = true else vm.clearAll()
            }
        }

        // ───────── ডায়ালগ ─────────
        if (confirmPost) FbConfirmDialog(
            msg = "Facebook পেইজে এখনই ফোটোকার্ড পোস্ট করবেন? (ওয়াটারমার্ক ছাড়া যাবে)" + if (vm.link.isNotBlank()) "\nকমেন্টে লিংকও যাবে।" else "",
            onYes = { confirmPost = false; vm.postNow() }, onNo = { confirmPost = false },
        )
        if (confirmClear) AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text("সব মুছে নতুন করে শুরু করবেন?") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearAll() }) { Text("ঠিক আছে") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("বাতিল") } },
        )
        vm.ocrAsk?.let {
            AlertDialog(
                onDismissRequest = { vm.resolveOcr(false) },
                text = { Text("ঘরে আগে থেকে লেখা আছে। ছবি থেকে পাওয়া বক্তব্য, নাম ও পদবি দিয়ে বদলে দেবেন?") },
                confirmButton = { TextButton(onClick = { vm.resolveOcr(true) }) { Text("ঠিক আছে") } },
                dismissButton = { TextButton(onClick = { vm.resolveOcr(false) }) { Text("বাতিল") } },
            )
        }
        if (schedOpen) SchedulePickDialog(busy = vm.fcBusy, error = vm.fcOut?.takeIf { it.startsWith("❌") }, onConfirm = { at -> vm.schedule(at) { schedOpen = false } }, onDismiss = { schedOpen = false })

        if (cropOpen) {
            val cb = vm.photoForCrop()
            if (cb == null) cropOpen = false
            else CropDialog(cb, vm.cropRatio(), onApply = { l, t, r, b -> vm.applyCrop(l, t, r, b); cropOpen = false }, onCancel = { cropOpen = false })
        }
        if (collOpen) {
            if (!vm.canEditCollage) collOpen = false
            else CollageDialog(vm.collItems, vm.collVertical, onApply = { items, v -> vm.applyCollage(items, v); collOpen = false }, onCancel = { collOpen = false })
        }
        tplEdit?.let { id ->
            TplEditorDialog(np, id) { tplEdit = null; vm.tplChanged(np.tplId) }
        }
        tplReset?.let { id ->
            AlertDialog(
                onDismissRequest = { tplReset = null },
                text = { Text("\"" + (cardTpls(CAT_PHOTO).firstOrNull { it.first == id }?.second ?: id) + "\" টেমপ্লেটের সব এডিট মুছে আসল ডিফল্টে ফিরবেন?") },
                confirmButton = { TextButton(onClick = { tplReset = null; np.resetDefTpl(id) { e -> if (e != null) vm.fcOut = "❌ $e"; vm.tplChanged(null) } }) { Text("ঠিক আছে") } },
                dismissButton = { TextButton(onClick = { tplReset = null }) { Text("বাতিল") } },
            )
        }
        Box(Modifier.height(24.dp))
    }
}

/** ওয়েবের `#fcPick2 .btn.small`: বাছা থাকলে গাঢ় নীল (#0a3d91) + হালকা আউটলাইন */
@Composable
private fun CollBtn(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.then(if (on) Modifier.border(3.dp, Color(0xFF9BBCF0), RoundedCornerShape(8.dp)) else Modifier)) {
        HBtn(text, color = if (on) Color(0xFF0A3D91) else C.Red, padH = 12.dp, padV = 8.dp, enabled = enabled, onClick = onClick)
    }
}

/** ওয়েবের fcAsk(): ৪২০ চওড়া সাদা কার্ড, শিরোনাম "📘 Facebook-এ পোস্ট", সবুজ + ধূসর বাটন */
@Composable
private fun FbConfirmDialog(msg: String, onYes: () -> Unit, onNo: () -> Unit) {
    Dialog(onDismissRequest = onNo, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp),
        ) {
            Text("📘 Facebook-এ পোস্ট", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = C.Text, modifier = Modifier.padding(bottom = 10.dp))
            Text(msg, fontSize = 15.sp, lineHeight = 24.sp, color = C.Text, modifier = Modifier.padding(bottom = 18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HBtn("✅ হ্যাঁ, পোস্ট করুন", Modifier.weight(1f), color = C.Green, onClick = onYes)
                HBtn("বাতিল", Modifier.weight(1f), color = ConfirmGray, onClick = onNo)
            }
        }
    }
}

/** ওয়েবের `#fcMorn`: শুভ সকাল ট্যাবের সরল ফর্ম (সময়, সাইটের নাম, ব্যানার, চালু/বন্ধ, সেভ) */
@Composable
private fun MorningPanel(vm: PhotoCardViewModel) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    HPnl(Modifier.padding(top = 12.dp)) {
        Text("⏰ কখন পোস্ট হবে (বাংলাদেশ সময়)", fontWeight = FontWeight.Bold)
        Box(
            Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)
                .border(1.dp, C.Border, RoundedCornerShape(8.dp))
                .clickable {
                    val t = vm.mnTimeMin
                    TimePickerDialog(ctx, { _, h, m -> vm.setMnTime(h, m) }, t / 60, t % 60, false).show()
                }.padding(10.dp),
        ) { Text(MnCal.clock(vm.mnTimeMin), fontSize = 16.sp) }
        HSmall("সকাল ৬:৩০ থেকে ৮:৩০-এর মধ্যে সময় দিতে পারবেন। এই সময়ের পর পোস্ট হবে (সামান্য দেরি হতে পারে)।", Modifier.padding(top = 6.dp))

        FieldLabel("🏷️ সাইটের নাম")
        HtmlField(vm.mnSite, { vm.mnSite = it }, placeholder = "যেমন: শীর্ষ নিউজ")
        HSmall("কার্ডের ছবি ও সাদা অংশের ঠিক মাঝখানে বসবে।", Modifier.padding(top = 6.dp))

        FieldLabel("📢 ব্যানার")
        Box(Modifier.padding(top = 0.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)
                    .border(1.dp, C.Border, RoundedCornerShape(8.dp)).clickable { menu = true }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(vm.mnChoices.firstOrNull { it.first == vm.mnAd }?.second ?: "", fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("▾", fontSize = 14.sp, color = Color(0xFF555555))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                vm.mnChoices.forEach { (v, label) ->
                    DropdownMenuItem(text = { Text(label, fontSize = 16.sp) }, onClick = { vm.mnAd = v; menu = false })
                }
            }
        }
        HSmall("“অটো” বাছলে সেটিংসের বিজ্ঞাপনগুলো থেকে প্রতিদিন একটির পর একটি বসবে। চাইলে নির্দিষ্ট একটা বিজ্ঞাপন বা ব্যানার ছাড়াও বাছতে পারেন।", Modifier.padding(top = 6.dp))

        Box(Modifier.padding(top = 6.dp)) { CheckRow(vm.mnOn, "✅ এই কার্ড প্রতিদিন আমার Facebook পেইজে পোস্ট হবে", "") { vm.mnOn = it } }
        vm.mnBalance?.let { b ->
            val t = if (b < 0) " ⚠️ ব্যালেন্স মাইনাস (৳" + MnCal.n(b.toInt()) + ")। পোস্ট চলবে, কিন্তু মাইনাস ৭ দিনের বেশি থাকলে বন্ধ হবে। ড্যাশবোর্ড থেকে ব্যালেন্স যোগ করুন।"
            else if (b < 2) " ⚠️ ব্যালেন্স ২ টাকার কম। পোস্ট হবে, ব্যালেন্স মাইনাস হবে (৭ দিনের মধ্যে যোগ করুন)।" else ""
            if (t.isNotEmpty()) HSmall(t, Modifier.padding(top = 6.dp))
        }
        HBtn(if (vm.mnBusy) "⏳ সেভ হচ্ছে…" else "💾 সেভ করুন", Modifier.fillMaxWidth().padding(top = 12.dp), color = C.Green, enabled = !vm.mnBusy) { vm.saveMorning() }
        vm.mnMsg?.let { Text(it, modifier = Modifier.padding(top = 8.dp), fontSize = 16.sp, color = C.Text) }
    }
}
