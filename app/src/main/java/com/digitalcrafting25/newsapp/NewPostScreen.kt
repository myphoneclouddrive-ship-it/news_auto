package com.digitalcrafting25.newsapp

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.TextFieldValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** সিডিউলের সময় বাছাই: দ্রুত চিপ (৩০ মিনিট, ১ ঘণ্টা, ৩ ঘণ্টা, কাল সকাল ৮টা) + নিজে তারিখ/সময় বাছা (ওয়েবের schDrawer) */
@Composable
fun SchedulePickDialog(busy: Boolean, error: String? = null, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var at by remember { mutableLongStateOf(System.currentTimeMillis() + 3600_000L) }
    val fmt = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale("bn", "BD")) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("⏰ সিডিউল পোস্ট") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("সময় হলে Worker নিজেই পোস্ট করে দেবে। অ্যাপ বা ফোন বন্ধ থাকলেও চলবে।", fontSize = 13.sp, color = C.Label)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("৩০ মিনিট" to 30, "১ ঘণ্টা" to 60, "৩ ঘণ্টা" to 180).forEach { (l, m) ->
                        FilterChip(selected = false, onClick = { at = System.currentTimeMillis() + m * 60_000L }, label = { Text(l) })
                    }
                    FilterChip(selected = false, onClick = {
                        val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 8); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
                        at = c.timeInMillis
                    }, label = { Text("কাল সকাল ৮টা") })
                }
                AppBtn("📅 " + fmt.format(Date(at)) + " — বদলান", color = C.Blue) { pickDateTime(ctx, at) { at = it } }
                if (at < System.currentTimeMillis() + 60_000L) Text("⚠️ অন্তত ১ মিনিট পরের সময় দিন", color = C.Red, fontSize = 13.sp)
                error?.let { Text(if (it.startsWith("❌") || it.startsWith("⚠️")) it else "⚠️ $it", color = C.Red, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(at) }, enabled = !busy) { Text(if (busy) "অপেক্ষা করুন…" else "✅ সিডিউল করুন") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("বাতিল") } },
    )
}

@Composable
private fun StepBar(step: Int) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("১ খবর", "২ লেখা", "৩ কার্ড", "৪ কনফার্ম", "✔ শেষ").forEachIndexed { i, l ->
            val on = step == i + 1
            Text(
                l, Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (on) C.Red else if (step > i + 1) Color(0xFFFFCDD2) else Color(0xFFE6E6E6)).padding(vertical = 6.dp),
                textAlign = TextAlign.Center, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (on) Color.White else C.Text,
            )
        }
    }
}

@Composable
private fun BodyField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, minLines: Int, placeholder: String = "") {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = (minLines * 24).dp),
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = Color(0xFF999999)) },
        shape = RoundedCornerShape(8.dp),
    )
}

/** B / I / উপশিরোনাম / সাধারণ লেখা / তালিকা / আনডু */
@Composable
private fun FormatBar(vm: NewPostViewModel, manual: Boolean) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppBtn("B", color = C.Gray, small = true) { vm.fmtWrap("**", manual) }
        AppBtn("I", color = C.Gray, small = true) { vm.fmtWrap("*", manual) }
        AppBtn("উপশিরোনাম", color = C.Gray, small = true) { vm.fmtLine("## ", manual) }
        AppBtn("সাধারণ লেখা", color = C.Gray, small = true) { vm.fmtLine("", manual) }
        AppBtn("• তালিকা", color = C.Gray, small = true) { vm.fmtLine("• ", manual) }
        if (!manual) AppBtn("↶ আনডু", color = C.Gray, small = true) { vm.doUndo() }
    }
}

@Composable
fun NewPostScreen(vm: NewPostViewModel, ads: AdsViewModel, onOpenVideo: () -> Unit, onScheduled: () -> Unit) {
    LaunchedEffect(Unit) { vm.load(force = true) }   // সাইট ট্যাবে লোগো/নাম বদলালে এখানে নতুনটা আসে
    LaunchedEffect(vm.adTick) { if (vm.adTick > 0) ads.load(force = true) }
    LaunchedEffect(vm.cardTitle, vm.title, vm.category, vm.tag, vm.tplId, vm.photoVer, vm.fx, vm.fy, vm.useAd, vm.nextAdId, vm.step) { if (vm.step in 3..4) vm.refresh() }

    var warn by remember { mutableStateOf(false) }
    var sched by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        H1("✍️ নতুন পোস্ট")
        StepBar(vm.step)
        vm.balance?.let { Text("💰 ব্যালেন্স: ৳ " + PostUtil.bn(it.toInt()), fontSize = 13.sp, color = C.Label, modifier = Modifier.padding(bottom = 6.dp)) }

        if (vm.busy) {
            InfoCard(Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = C.Red, modifier = Modifier.width(28.dp).height(28.dp), strokeWidth = 3.dp)
                    Column {
                        Text(vm.busyMsg, fontSize = 14.sp)
                        Text(PostUtil.bn(vm.elapsed) + " সেকেন্ড", fontSize = 12.sp, color = C.Muted)
                    }
                }
            }
        }
        vm.error?.let { Text("⚠️ $it", color = C.Red, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        vm.notice?.let { Text(it, color = if (it.startsWith("⚠️")) Color(0xFFB26A00) else C.Green, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }

        when (vm.step) {
            1 -> Step1(vm, onOpenVideo, onClear = { confirmClear = true })
            2 -> Step2(vm, onNext = { if (vm.title.isBlank() || vm.body.text.isBlank()) vm.error = "শিরোনাম ও বডি লিখুন" else warn = true }, onClear = { confirmClear = true })
            3 -> Step3(vm, onClear = { confirmClear = true })
            4 -> Step4(vm, onSchedule = { sched = true }, onClear = { confirmClear = true })
            else -> Step5(vm)
        }
        Box(Modifier.height(24.dp))
    }

    // AI-র ওয়ার্নিং: টিক না দিলে নেক্সট চালু হয় না
    if (warn) {
        var ok by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { warn = false },
            title = { Text("⚠️ পাবলিশের আগে পড়ুন") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI-র লেখা ভুল বা অসম্পূর্ণ হতে পারে। নাম, সংখ্যা, তারিখ, স্থান ও উদ্ধৃতি মূল খবরের সাথে মিলিয়ে দেখুন। পাবলিশের দায় আপনার।", fontSize = 14.sp)
                    Row(Modifier.clickable { ok = !ok }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ok, onCheckedChange = { ok = it })
                        Text("আমি পুরো লেখা পড়ে দেখেছি", fontSize = 14.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { warn = false; vm.toStep3() }, enabled = ok) { Text("নেক্সট →") } },
            dismissButton = { TextButton(onClick = { warn = false }) { Text("আবার পড়ি") } },
        )
    }
    if (sched) SchedulePickDialog(busy = vm.busy, error = vm.error, onConfirm = { at -> vm.schedule(at) { sched = false; onScheduled() } }, onDismiss = { sched = false })
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("🗑️ ক্লিয়ার") },
        text = { Text("সব লেখা ও ছবি মুছে নতুন করে শুরু করবেন?") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearAll() }) { Text("হ্যাঁ, মুছুন") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("না") } },
    )
}

// ───────── ধাপ ১: খবর ─────────
@Composable
private fun Step1(vm: NewPostViewModel, onOpenVideo: () -> Unit, onClear: () -> Unit) {
    FieldLabel("১. খবরের লিংক অথবা পুরো খবর পেস্ট করুন")
    AppField(vm.src, { vm.src = it }, placeholder = "https://... লিংক দিলে খবর নিজে থেকে আসবে, অথবা খবরের টেক্সট পেস্ট করুন", singleLine = false, modifier = Modifier.heightIn(min = 170.dp))
    CheckRow(vm.useOrig, "🖼️ লিংকের খবরের ছবিই সরাসরি ব্যবহার করুন (কার্টুন বানাবে না)", "") { vm.setUseOrig(it) }

    FieldLabel("📍 মাঠ পর্যায়ের তথ্য ও কারণ (ঐচ্ছিক)")
    AppField(vm.notes, { vm.notes = it }, placeholder = "নিজে ঘটনাস্থলে গিয়ে যা জেনেছেন, এলোমেলো থাকলেও লিখুন: কে, কোথায়, কী, কেন…", singleLine = false, modifier = Modifier.heightIn(min = 120.dp))
    CheckRow(vm.webNews, "🌐 অনলাইনে সাম্প্রতিক খবর থেকে তথ্য নিবে",
        "টিক দিলে নোট থেকে শিরোনাম বানিয়ে গত ৩ ঘণ্টার বাংলাদেশি নিউজ সাইটের লিংক খোঁজা হবে। শিরোনামের সঙ্গে মিললে সেই খবর পড়ে আপনার তথ্যের সাথে মিলিয়ে লেখা হবে। না পেলে শুধু আপনার তথ্য দিয়েই লেখা হবে।") { vm.webNews = it }

    Text("AI দিয়ে লিখলে সফল হওয়ার পর ৳${PostUtil.bn(PostUtil.RW_COST)} কাটে।", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 8.dp))
    AppBtn(if (vm.busy) "⏳ প্রসেসিং হচ্ছে..." else "AI দিয়ে নতুন করে লিখুন", Modifier.padding(top = 6.dp), enabled = !vm.busy) { vm.rewrite() }

    AppBtn(if (vm.showManual) "▲ নিজে পোস্ট ক্রিয়েট বন্ধ করুন" else "✍️ নিজে পোস্ট ক্রিয়েট করুন", Modifier.padding(top = 8.dp), color = C.Blue) { vm.showManual = !vm.showManual }
    if (vm.showManual) {
        InfoCard(Modifier.padding(top = 8.dp)) {
            FieldLabel("শিরোনাম")
            AppField(vm.mTitle, { vm.mTitle = it }, singleLine = false)
            FieldLabel("পোস্ট (এখানে পেস্ট করে এডিট করুন)")
            Text("যে লেখা বোল্ড বা উপশিরোনাম হবে, সেটা সিলেক্ট করে নিচের বাটনে চাপুন।", fontSize = 12.sp, color = C.Muted)
            FormatBar(vm, manual = true)
            BodyField(vm.mBody, { vm.mBody = it }, 8)
            Text("এই অবস্থায় ৳৫ কাটে (AI ছাড়া)।", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 6.dp))
            AppBtn("✅ পোস্ট করুন", Modifier.padding(top = 6.dp), color = C.Green, enabled = !vm.busy) { vm.manualPost() }
        }
    }
    AppBtn("🎬 ভিডিও পোস্ট করুন", Modifier.padding(top = 8.dp), color = C.Gray, onClick = onOpenVideo)
    AppBtn("🗑️ ক্লিয়ার", Modifier.padding(top = 8.dp), color = C.Gray, onClick = onClear)
}

@Composable
private fun CheckRow(checked: Boolean, label: String, hint: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 8.dp).clickable { onChange(!checked) }, verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.padding(top = 10.dp)) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (hint.isNotEmpty()) Text(hint, color = C.Muted, fontSize = 12.sp)
        }
    }
}

// ───────── ধাপ ২: লেখা ─────────
@Composable
private fun Step2(vm: NewPostViewModel, onNext: () -> Unit, onClear: () -> Unit) {
    FieldLabel("২. শিরোনাম (এডিট করুন)")
    AppField(vm.title, { vm.title = it; vm.saveDraft() }, singleLine = false)
    FieldLabel("বডি (পোস্ট প্রিভিউ, এখানেই এডিট করুন)")
    Text("চিহ্ন: **বোল্ড**, *ইটালিক*, লাইনের শুরুতে ## = উপশিরোনাম, • = তালিকা।", fontSize = 12.sp, color = C.Muted)
    FormatBar(vm, manual = false)
    BodyField(vm.body, { vm.editBody(it) }, 14)
    AppBtn("নেক্সট →", Modifier.padding(top = 12.dp), color = C.Green, onClick = onNext)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppBtn("← ব্যাক", Modifier.weight(1f), color = C.Gray) { vm.back() }
        AppBtn("🔁 আবার লিখুন", Modifier.weight(1f), color = C.Gray, enabled = !vm.busy) { vm.rewriteAgain() }
    }
    AppBtn("🗑️ ক্লিয়ার", Modifier.padding(top = 8.dp), color = C.Gray, onClick = onClear)
}

// ───────── ধাপ ৩: কার্ড ─────────
@Composable
private fun Step3(vm: NewPostViewModel, onClear: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris -> vm.addPhotos(uris) }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    FieldLabel("ফোটোকার্ডের শিরোনাম")
    AppField(vm.cardTitle, { vm.cardTitle = it; vm.saveDraft() }, singleLine = false)

    // প্রিভিউ (আঙুল টেনে ছবির অবস্থান)
    Box(
        Modifier.padding(top = 10.dp).fillMaxWidth().aspectRatio(CardRender.aspect(vm.tplId))
            .clip(RoundedCornerShape(10.dp)).background(Color(0xFFDDDDDD))
            .pointerInput(vm.hasPhoto) {
                if (vm.hasPhoto) detectDragGestures { change, drag ->
                    change.consume()
                    val k = CardRender.W / size.width.toFloat()
                    vm.pan(drag.x * k, drag.y * k)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val pv = vm.preview
        if (pv != null) Image(pv.asImageBitmap(), contentDescription = "কার্ডের প্রিভিউ", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else CircularProgressIndicator(color = C.Red)
    }
    if (vm.hasPhoto) Text("👆 ছবির ওপর আঙুল টেনে ছবির পজিশন ঠিক করুন।", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))

    H2("🎨 ফোটোকার্ড টেমপ্লেট (যেটা পোস্ট হবে সেটা বেছে নিন)")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        cardTpls("photo").forEach { (id, name) ->
            val on = vm.tplId == id
            Column(Modifier.width(96.dp).clickable { vm.tplId = id; vm.saveDraft() }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.width(96.dp).aspectRatio(CardRender.aspect(id)).clip(RoundedCornerShape(8.dp)).background(Color(0xFFDDDDDD))
                        .border(BorderStroke(if (on) 3.dp else 1.dp, if (on) C.Red else C.Border), RoundedCornerShape(8.dp)),
                ) { vm.thumbs[id]?.let { Image(it.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
                Text(name + if (on) " ✔" else "", fontSize = 12.sp, textAlign = TextAlign.Center, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) C.Red else C.Text, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    FieldLabel("🏷️ ক্যাটাগরি (সর্বোচ্চ ২টি, কমা দিয়ে আলাদা)")
    AppField(vm.category, { vm.category = it; vm.saveDraft() }, placeholder = "যেমন: বাংলাদেশ, রাজনীতি")
    Text("WordPress-এ দুটোই যাবে, কার্ডে তারিখের পাশে প্রথমটা দেখাবে।", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
    FieldLabel("📌 ছবির ট্যাগ (খালি রাখলে দেখাবে না)")
    AppField(vm.tag, { vm.tag = it; vm.saveDraft() }, placeholder = "যেমন: সংগৃহীত")

    if (vm.nextAdId.isNotEmpty()) {
        CheckRow(vm.useAd, "📢 কার্ডে বিজ্ঞাপন বসান", "এই কার্ডে বসবে: ${vm.nextAdLabel}। পাবলিশ/সিডিউলের পর পরের কার্ডে পরের বিজ্ঞাপন আসবে।") { vm.useAd = it }
    }

    // ছবি
    H2("🖼️ ছবি")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = vm.imgMode == "upload", onClick = { vm.imgMode = "upload" }, label = { Text("📁 আপলোড") })
        FilterChip(selected = vm.imgMode == "link", onClick = { vm.imgMode = "link" }, label = { Text("🔗 ইমেজ লিংক") })
        FilterChip(selected = vm.imgMode == "ai", onClick = { vm.imgMode = "ai" }, label = { Text("🤖 AI কার্টুন") })
    }
    when (vm.imgMode) {
        "upload" -> {
            AppBtn(if (vm.hasPhoto) "📷 ছবি বদলান" else "📷 ছবি আনুন", Modifier.padding(top = 8.dp), color = C.Blue) { pick() }
            Text("১টি ছবি দিলে সরাসরি যোগ হবে। একাধিক (সর্বোচ্চ ৬টি) একসাথে বাছলে সব মিলিয়ে একটা কোলাজ ছবি হবে।", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !vm.collVertical, onClick = { vm.setCollVertical(false) }, label = { Text("◀️▶️ পাশাপাশি") })
                FilterChip(selected = vm.collVertical, onClick = { vm.setCollVertical(true) }, label = { Text("⬆️⬇️ উপর-নিচ") })
            }
            Text("দুইটা ছবির সাজানো (যেকোনো সময় বদলানো যাবে)", fontSize = 12.sp, color = C.Muted)
        }
        "link" -> {
            FieldLabel("ইমেজ লিংক")
            AppField(vm.imgUrl, { vm.imgUrl = it }, placeholder = "https://.../photo.jpg", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
            AppBtn("🔗 ছবি আনুন", Modifier.padding(top = 8.dp), color = C.Blue, enabled = !vm.busy) { vm.fetchLink() }
        }
        else -> {
            FieldLabel("ছবির প্রম্পট (বাংলায়, ঐচ্ছিক)")
            AppField(vm.aiPrompt, { vm.onPromptEdit(it) }, placeholder = "খালি রাখলে শিরোনাম দেখে আঁকা হবে", singleLine = false)
            Text("বাটনে চাপলেই শিরোনাম দেখে কার্টুনিস্টের আঁকা স্টাইলে ছবি হবে এবং ছবিতে ✓ AI Generated চিহ্ন বসে যাবে।", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
            AppBtn("🤖 AI কার্টুন ছবি বানান", Modifier.padding(top = 8.dp), enabled = !vm.busy) { vm.makeCartoon() }
        }
    }
    if (vm.imgMsg.isNotEmpty()) Text(vm.imgMsg, fontSize = 13.sp, color = if (vm.imgMsg.startsWith("⚠️")) C.Red else C.Green, modifier = Modifier.padding(top = 6.dp))

    // লিংকের খবরের রেফারেন্স ছবি
    vm.refBmp?.let { rb ->
        InfoCard(Modifier.padding(top = 10.dp)) {
            Text("রেফারেন্স ছবি (লিংকের খবর থেকে, পাবলিশ হবে না)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Image(rb.asImageBitmap(), contentDescription = "রেফারেন্স ছবি", modifier = Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = 180.dp), contentScale = ContentScale.Fit)
            Text("টিক না দিলে এই ছবি দেখে কার্টুন বানানো হয়। অন্যের ছবি ব্যবহারে কপিরাইটের ঝুঁকি থাকে, অনুমতি বা সূত্র দেখে নিন।", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn("✔ এই ছবিই সরাসরি ব্যবহার করুন", Modifier.weight(1f), color = C.Green, small = true) { vm.useRefNow() }
                AppBtn("✕ বাদ দিন", color = C.Gray, small = true) { vm.dropRef() }
            }
        }
    }
    if (vm.hasPhoto) {
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("↔️ ফ্লিপ", color = C.Gray, small = true) { vm.flipPhoto() }
            AppBtn("✖ ছবি সরান", color = C.Gray, small = true) { vm.clearPhoto() }
        }
    }

    AppBtn("নেক্সট →", Modifier.padding(top = 14.dp), color = C.Green) { vm.toStep4() }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppBtn("← ব্যাক", Modifier.weight(1f), color = C.Gray) { vm.back() }
        AppBtn("🔁 ছবি রিট্রাই", Modifier.weight(1f), color = C.Gray, enabled = !vm.busy) {
            when (vm.imgMode) { "ai" -> vm.makeCartoon(); "link" -> vm.fetchLink(); else -> pick() }
        }
    }
    AppBtn("🗑️ ক্লিয়ার", Modifier.padding(top = 8.dp), color = C.Gray, onClick = onClear)
}

// ───────── ধাপ ৪: কনফার্ম ─────────
@Composable
private fun Step4(vm: NewPostViewModel, onSchedule: () -> Unit, onClear: () -> Unit) {
    H2("🔍 শেষবার দেখে নিন")
    vm.preview?.let { Image(it.asImageBitmap(), contentDescription = "কার্ড", modifier = Modifier.fillMaxWidth().aspectRatio(CardRender.aspect(vm.tplId)).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit) }
    Text(vm.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
    Text("কনফার্ম করলে WordPress-এ পোস্ট হবে আর Facebook-এ ফোটোকার্ডসহ শেয়ার হবে।", fontSize = 13.sp, color = C.Label, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    AppBtn(if (vm.busy) "⏳ অপেক্ষা করুন…" else "✅ কনফার্ম ও পাবলিশ করুন", color = C.Green, enabled = !vm.busy) { vm.publish() }
    AppBtn("⏰ সিডিউল পোস্ট", Modifier.padding(top = 8.dp), color = C.Blue, enabled = !vm.busy, onClick = onSchedule)
    AppBtn("← ব্যাক", Modifier.padding(top = 8.dp), color = C.Gray) { vm.back() }
    AppBtn("🗑️ ক্লিয়ার", Modifier.padding(top = 8.dp), color = C.Gray, onClick = onClear)
    Text("⚠️ পাবলিশ ব্যর্থ হলে \"কনফার্ম\" আবার চাপুন। ফেসবুক ত্রুটির বার্তা এলে আবার পাবলিশ করবেন না (ডুপ্লিকেট হবে)।", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 8.dp))
}

// ───────── ধাপ ৫: শেষ ─────────
@Composable
private fun Step5(vm: NewPostViewModel) {
    val ctx: Context = LocalContext.current
    H2("🎉 পোস্ট সম্পন্ন")
    InfoCard {
        Text("✅ WordPress-এ পাবলিশ হয়েছে:", fontWeight = FontWeight.Bold)
        Text(vm.pubLink, color = C.Blue, fontSize = 14.sp, modifier = Modifier.clickable {
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(vm.pubLink))) }
        })
        vm.pubNotes.forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp), color = if (it.startsWith("⚠️")) Color(0xFFB26A00) else C.Text) }
    }
    AppBtn("➕ নতুন পোস্ট শুরু করুন", Modifier.padding(top = 14.dp), color = C.Green) { vm.clearAll() }
}
