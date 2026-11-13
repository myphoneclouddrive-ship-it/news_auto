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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.withStyle
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Surface
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** সিডিউলের সময় বাছাই: দ্রুত চিপ (৩০ মিনিট, ১ ঘণ্টা, ৩ ঘণ্টা, কাল সকাল ৮টা) + নিজে তারিখ/সময় বাছা (ওয়েবের schDrawer) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SchedulePickDialog(busy: Boolean, error: String? = null, initialAt: Long? = null, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var at by remember { mutableLongStateOf(initialAt?.takeIf { it > System.currentTimeMillis() + 60_000L } ?: (System.currentTimeMillis() + 3600_000L)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // ওয়েবের #schDrawer: ডান পাশে সাদা প্যানেল (৩৪০ বা ৯২% চওড়া), প্যাডিং ১৬
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.fillMaxHeight().fillMaxWidth(0.92f).widthIn(max = 340.dp).background(Color.White)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("⏰ সিডিউল মেনু", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFEEEEEE))
                            .clickable(enabled = !busy) { onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", fontSize = 16.sp) }
                }
                Text("কখন পোস্ট হবে?", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("৩০ মিনিট পরে" to 30, "১ ঘণ্টা পরে" to 60, "৩ ঘণ্টা পরে" to 180, "আগামীকাল সকাল ৮টা" to -1).forEach { (l, m) ->
                        Text(
                            l, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(14.dp))
                                .clickable {
                                    at = if (m == -1) java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 8); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
                                    else System.currentTimeMillis() + m * 60_000L
                                }.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                FieldLabel("অথবা নিজে সময় দিন")
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, C.Border, RoundedCornerShape(8.dp))
                        .clickable { pickDateTime(ctx, at) { at = it } }.padding(10.dp),
                ) { Text(Bn.dateTime(at), fontSize = 16.sp) }
                val left = if (at < now + 60_000L) "⚠️ অন্তত ১ মিনিট পরের সময় দিন" else Bn.dateTime(at) + " • " + leftText(at - now) + " পরে"
                Text(
                    left, color = Color(0xFF0D47A1), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFE3F2FD)).padding(10.dp),
                )
                error?.let { Text(if (it.startsWith("❌") || it.startsWith("⚠️")) it else "⚠️ $it", color = C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                HBtn(if (busy) "অপেক্ষা করুন…" else "✅ সিডিউল নিশ্চিত করুন", Modifier.fillMaxWidth().padding(top = 10.dp), color = C.Green, enabled = !busy) { onConfirm(at) }
                HSmall("নিশ্চিত করলে পোস্টটি \"সিডিউল\" ট্যাবে চলে যাবে এবং বাকি সময় সেখানে দেখাবে।", Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun StepBar(step: Int) {
    // ওয়েবের #stepbar: নিচে ৩px রেখা — চলমান লাল, শেষ হওয়া সবুজ, বাকি ধূসর
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("১ খবর", "২ লেখা", "৩ কার্ড", "৪ কনফার্ম", "✔ শেষ").forEachIndexed { i, l ->
            val on = step == i + 1
            val done = step > i + 1
            val col = if (on) C.Red else if (done) C.Green else Color(0xFF888888)
            Column(Modifier.weight(1f)) {
                Text(
                    l, Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 2.dp),
                    textAlign = TextAlign.Center, fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = col,
                )
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) C.Red else if (done) C.Green else C.NavLine))
            }
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
        visualTransformation = RichVT,
    )
}

/**
 * ওয়েবের রিচ এডিটরের মতো দেখানো: **বোল্ড** / *ইটালিক* / "## উপশিরোনাম"-এর চিহ্ন লুকিয়ে লেখাটাই বোল্ড/ইটালিক/বড় দেখায়।
 * ভেতরের লেখা বদলায় না (সেভ ও পাবলিশ আগের মতো), শুধু পর্দায় আঁকা বদলায়। জোড়া না মিললে চিহ্ন দেখা যায়।
 */
private object RichVT : VisualTransformation {
    private val boldRe = Regex("\\*\\*(.+?)\\*\\*")
    private val italRe = Regex("(?<!\\*)\\*(?!\\*)([^*\\n]+?)(?<!\\*)\\*(?!\\*)")
    private val linkRe = Regex("\\[([^\\]\\n]+)\\]\\((https?://[^)\\s\"]+)\\)")

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        val n = src.length
        val hidden = BooleanArray(n)
        val bold = BooleanArray(n)
        val ital = BooleanArray(n)
        val head = BooleanArray(n)
        val link = BooleanArray(n)
        val quote = BooleanArray(n)
        var ls = 0
        while (ls <= n) {
            var le = src.indexOf('\n', ls); if (le < 0) le = n
            var from = ls
            if (src.startsWith(">", ls)) for (i in ls until le) quote[i] = true
            if (src.startsWith("## ", ls)) {
                for (i in ls until ls + 3) hidden[i] = true
                from = ls + 3
                for (i in from until le) head[i] = true
            }
            if (le > from) {
                val line = src.substring(from, le)
                boldRe.findAll(line).forEach { m ->
                    val a = from + m.range.first; val b = from + m.range.last
                    hidden[a] = true; hidden[a + 1] = true; hidden[b] = true; hidden[b - 1] = true
                    for (i in a + 2..b - 2) bold[i] = true
                }
                italRe.findAll(line).forEach { m ->
                    val a = from + m.range.first; val b = from + m.range.last
                    hidden[a] = true; hidden[b] = true
                    for (i in a + 1 until b) ital[i] = true
                }
                linkRe.findAll(line).forEach { m ->   // [লেখা](url): শুধু লেখাটা নীল-আন্ডারলাইন, বাকি চিহ্ন লুকানো
                    val a = from + m.range.first; val b = from + m.range.last
                    val te = a + 1 + m.groupValues[1].length
                    hidden[a] = true
                    for (i in te..b) hidden[i] = true
                    for (i in a + 1 until te) link[i] = true
                }
            }
            ls = le + 1
        }
        val sb = StringBuilder()
        val o2t = IntArray(n + 1)
        val t2o = ArrayList<Int>(n + 1)
        for (i in 0 until n) {
            o2t[i] = sb.length
            if (!hidden[i]) { sb.append(src[i]); t2o.add(i) }
        }
        o2t[n] = sb.length
        t2o.add(n)
        val out = buildAnnotatedString {
            append(sb.toString())
            fun key(oi: Int) = (if (bold[oi] || head[oi]) 1 else 0) or (if (ital[oi]) 2 else 0) or (if (head[oi]) 4 else 0) or
                (if (link[oi]) 8 else 0) or (if (quote[oi]) 16 else 0)
            var j = 0
            while (j < sb.length) {
                val kk = key(t2o[j])
                var k = j + 1
                while (k < sb.length && key(t2o[k]) == kk) k++
                if (kk != 0) addStyle(
                    SpanStyle(
                        fontWeight = if ((kk and 1) != 0) FontWeight.Bold else null,
                        fontStyle = if ((kk and 2) != 0) FontStyle.Italic else null,
                        fontSize = if ((kk and 4) != 0) 20.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                        color = if ((kk and 8) != 0) Color(0xFF1A73E8) else if ((kk and 16) != 0) Color(0xFF555555) else Color.Unspecified,
                        textDecoration = if ((kk and 8) != 0) TextDecoration.Underline else null,
                    ), j, k,
                )
                j = k
            }
        }
        val map = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = o2t[offset.coerceIn(0, n)]
            override fun transformedToOriginal(offset: Int): Int = t2o[offset.coerceIn(0, t2o.size - 1)]
        }
        return TransformedText(out, map)
    }
}

/** ওয়েবের ctRender: কার্ড শিরোনামের ৮৫ অক্ষরের পরের অংশ লাল দেখায় */
object CardTitleVT : VisualTransformation {
    private const val MAX = 85
    override fun filter(text: AnnotatedString): TransformedText {
        val t = text.text
        val over = t.codePointCount(0, t.length) > MAX
        val cut = if (over) t.offsetByCodePoints(0, MAX) else t.length
        val out = buildAnnotatedString {
            append(t)
            if (over) addStyle(SpanStyle(color = Color(0xFFE53935)), cut, t.length)
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

/** ওয়েবের .chips বাটন: ১px #ccc বর্ডার, ১৪ ব্যাসার্ধ, ১৩px লেখা; বাছা থাকলে সবুজ (ক্যাটাগরি) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistChips(items: List<String>, isOn: (String) -> Boolean = { false }, onClick: (String) -> Unit) {
    if (items.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { v ->
            val on = isOn(v)
            Text(
                v, fontSize = 13.sp, color = if (on) Color.White else C.Text,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    .background(if (on) C.Green else Color.White)
                    .border(1.dp, if (on) C.Green else Color(0xFFCCCCCC), RoundedCornerShape(14.dp))
                    .clickable { onClick(v) }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
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
fun NewPostScreen(vm: NewPostViewModel, ads: AdsViewModel, onOpenVideo: () -> Unit, onScheduled: () -> Unit, onDelete: () -> Unit = {}, header: @Composable () -> Unit = {}) {
    LaunchedEffect(Unit) { vm.load(force = true) }   // সাইট ট্যাবে লোগো/নাম বদলালে এখানে নতুনটা আসে
    LaunchedEffect(vm.adTick) { if (vm.adTick > 0) ads.load(force = true) }
    LaunchedEffect(vm.cardTitle, vm.title, vm.category, vm.tag, vm.tplId, vm.photoVer, vm.useAd, vm.nextAdId, vm.step) { if (vm.step in 3..4) vm.refresh() }

    // ওয়েবের queueSave: লেখা/ছবি/বাছাই বদলালে ৪০০ms পর খসড়া সেভ
    LaunchedEffect(vm.src, vm.notes, vm.title, vm.cardTitle, vm.body.text, vm.aiPrompt, vm.imgUrl, vm.imgMode, vm.webNews, vm.category, vm.tag, vm.tplId, vm.photoVer, vm.hasPhoto, vm.refBmp, vm.step) { vm.queueSave() }

    var warn by remember { mutableStateOf(false) }
    var sched by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRetry by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // সিডিউল এডিট চললে: ধাপ ১ নেই, ক্লিয়ারের জায়গায় লাল ডিলিট বাটন (সিডিউল থেকেই মুছে যায়)
    val editing = vm.editItem != null
    val clearLabel = if (editing) "🗑️ ডিলিট" else "🗑️ ক্লিয়ার"
    val clearColor = if (editing) C.Red else C.Gray
    val onClearClick = { if (editing) confirmDelete = true else confirmClear = true }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        H1("✍️ নতুন পোস্ট")
        StepBar(vm.step)
        vm.balance?.let { Text("💰 ব্যালেন্স: ৳ " + PostUtil.bn(it.toInt()), fontSize = 13.sp, color = C.Label, modifier = Modifier.padding(bottom = 6.dp)) }

        if (vm.busy && !vm.rewriting) {
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

        // গেস্ট AI-র অদৃশ্য রানার: কিছু আঁকে না, শুধু প্রসেসিং প্যানেলের বার/লগ চালায়
        vm.guestJob?.let { job ->
            GuestHiddenRunner(job, onSent = { vm.guestSent() }, onPartial = { vm.guestPartial(it) },
                onDone = { t, c, b -> vm.guestDone(t, c, b) }, onFail = { vm.guestFailed() }, onStage = { vm.guestStage(it) })
        }
        if (vm.rewriting) ProcPanel(vm) else when (vm.step) {
            1 -> Step1(vm, onOpenVideo, onClear = { vm.clearAll() }, header = header)
            2 -> Step2(vm, onNext = { if (vm.title.isBlank() || vm.body.text.isBlank()) vm.error = "শিরোনাম ও বডি লিখুন" else warn = true },
                onRetry = { if (vm.retryOk()) confirmRetry = true }, onClear = onClearClick, clearLabel = clearLabel, clearColor = clearColor,
                onBack = { if (editing) { vm.clearAll(); onScheduled() } else vm.back() })   // এডিটে ব্যাক = কিছু না বদলে সিডিউলে ফেরা
            3 -> Step3(vm, onClear = onClearClick, clearLabel = clearLabel, clearColor = clearColor)
            4 -> Step4(vm, onSchedule = { sched = true }, onClear = onClearClick, clearLabel = clearLabel, clearColor = clearColor)
            else -> Step5(vm)
        }
        Box(Modifier.height(24.dp))
    }

    // ওয়েবের alert()-এর মতো পপআপ
    vm.popup?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.popup = null },
            text = { Text(msg, fontSize = 15.sp, lineHeight = 24.sp) },
            confirmButton = { TextButton(onClick = { vm.popup = null }) { Text("ঠিক আছে") } },
        )
    }
    // AI-র ওয়ার্নিং (ওয়েবের #aiWarn): টিক না দিলে নেক্সট চালু হয় না
    if (warn) AiWarnDialog(onRead = { warn = false }, onGo = { warn = false; vm.toStep3() })
    if (sched) SchedulePickDialog(busy = vm.busy, error = vm.error, initialAt = vm.editItem?.at, onConfirm = { at -> vm.schedule(at) { sched = false; onScheduled() } }, onDismiss = { sched = false })
    if (confirmRetry) AlertDialog(
        onDismissRequest = { confirmRetry = false },
        text = { Text("আবার নতুন করে লেখাবেন? এখনকার শিরোনাম ও বডি বদলে যাবে।") },
        confirmButton = { TextButton(onClick = { confirmRetry = false; vm.rewriteAgain() }) { Text("হ্যাঁ") } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text("না") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Color.White,
        text = { Text("এই সিডিউল পোস্টটি মুছে ফেলবেন? এটা আর পোস্ট হবে না।") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("হ্যাঁ, মুছুন", color = C.Red, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("বাতিল", color = C.Gray) } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        text = { Text("সব মুছে নতুন করে শুরু করবেন?") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearAll() }) { Text("হ্যাঁ, মুছুন") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("না") } },
    )
}

@Composable
private fun AiWarnDialog(onRead: () -> Unit, onGo: () -> Unit) {
    var ok by remember { mutableStateOf(false) }
    val bd = SpanStyle(fontWeight = FontWeight.Bold)
    Dialog(onDismissRequest = onRead) {
        Surface(shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚠️", fontSize = 26.sp)
                    Text("সতর্কতা: এটি AI-র লেখা", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C))
                }
                Column(
                    Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFF8E1))
                        .border(1.dp, Color(0xFFFFE082), RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(buildAnnotatedString {
                        append("এই নিউজটি "); withStyle(bd) { append("AI দিয়ে তৈরি") }
                        append("। মূল পোস্ট থেকে তথ্য নিয়ে লেখা হয়েছে। পোস্ট করার আগে নিচের শর্তগুলো মনোযোগ দিয়ে পড়ুন।")
                    }, fontSize = 15.sp, lineHeight = 27.sp)
                    listOf(
                        "পুরো আর্টিকেল নিজে পড়ুন।" to " AI মাঝে মাঝে ভুল করতে পারে, তাই নাম, তারিখ, সংখ্যা ও তথ্য ঠিক আছে কি না যাচাই করে নিন।",
                        "সংবেদনশীল বিষয়ে সতর্ক থাকুন।" to " কোনো তথ্য রাষ্ট্র, সরকার কিংবা ধর্মীয় অনুভূতিতে আঘাত হানে এমন হলে তা অবশ্যই নিজ দায়িত্বে এডিট বা ডিলিট করুন।",
                        "দায় আপনার।" to " ভুল বা আপত্তিকর তথ্য প্রকাশিত হলে তার দায় AI বা অ্যাপ কর্তৃপক্ষ নেবে না।",
                    ).forEachIndexed { i, (h, t) ->
                        Text(buildAnnotatedString {
                            append(PostUtil.bn(i + 1) + ". "); withStyle(bd) { append(h) }; append(t)
                        }, fontSize = 15.sp, lineHeight = 27.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Row(
                    Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp)).clickable { ok = !ok }.padding(10.dp),
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(checked = ok, onCheckedChange = { ok = it }, modifier = Modifier.width(22.dp).height(22.dp))
                    Text("হ্যাঁ, আমি পড়েছি, সব শর্ত মেনে নিচ্ছি এবং স্বীকার করছি তথ্য ঠিক আছে।", fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 24.sp)
                }
                Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NavBtn("আবার পড়ুন", Modifier.weight(1f), color = C.Gray, onClick = onRead)
                    NavBtn("নেক্সট →", Modifier.weight(1f), color = C.Green, enabled = ok, onClick = onGo)
                }
            }
        }
    }
}

/** ওয়েবের #sp: স্পিনার, প্রগ্রেস বার, % / সময়, আর AI-র চলমান আউটপুটের শেষ ৩ লাইন */
@Composable
private fun ProcPanel(vm: NewPostViewModel) {
    val pct by androidx.compose.animation.core.animateFloatAsState(vm.procPct.coerceIn(0f, 100f), androidx.compose.animation.core.tween(500), label = "proc")
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = C.Red, trackColor = Color(0xFFEEEEEE), strokeWidth = 5.dp, modifier = Modifier.padding(top = 20.dp, bottom = 14.dp).width(46.dp).height(46.dp))
        Text("⏳ প্রসেসিং হচ্ছে...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (vm.procMode > 0) Text("ধাপ-" + PostUtil.bn(vm.procMode), fontSize = 12.sp, color = C.Label)
        Text("AI খবরটা পড়ে নতুন করে লিখছে। একটু অপেক্ষা করুন।", fontSize = 13.sp, color = C.Label, textAlign = TextAlign.Center)
        Box(Modifier.padding(top = 16.dp, bottom = 6.dp).widthIn(max = 420.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE5E5E5))) {
            Box(Modifier.fillMaxWidth(pct / 100f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(C.Red))
        }
        Row(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(PostUtil.bn(Math.round(pct)) + "%", fontSize = 12.sp, color = C.Label)
            Text(PostUtil.bn(vm.elapsed) + " সেকেন্ড", fontSize = 12.sp, color = C.Label)
        }
        Column(Modifier.padding(top = 12.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(min = 60.dp)) {
            val lines = vm.procLines
            if (lines.isEmpty()) Text("…", fontSize = 13.sp, color = C.Text.copy(alpha = 0.5f))
            lines.forEachIndexed { i, m ->
                val last = i == lines.lastIndex
                Text(m, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 2.dp),
                    fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                    color = C.Text.copy(alpha = if (last) 1f else (0.55f - (lines.size - 2 - i) * 0.15f).coerceIn(0.2f, 1f)))
            }
        }
    }
}

// ───────── ধাপ ১: খবর (ওয়েবের .sec অ্যাকর্ডিয়ন, হুবহু) ─────────
private val SecBorder = Color(0xFFDDDDDD)

/** ওয়েবের .sec: সাদা বক্স, ১px #ddd বর্ডার, ১০ ব্যাসার্ধ; খোলা থাকলে হেডার #fdeeee/#b71c1c; ডানে ▾ (বন্ধে ঘোরানো ▸) */
@Composable
private fun Sec(title: String, open: Boolean, onHeader: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(top = 10.dp).fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)).background(Color.White)
            .border(1.dp, SecBorder, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(if (open) Color(0xFFFDEEEE) else Color.White)
                .clickable(onClick = onHeader).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (open) Color(0xFFB71C1C) else C.Text)
            Text(if (open) "▾" else "▸", color = Color(0xFF888888), fontSize = 14.sp)
        }
        if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), content = content)
    }
}

/** ওয়েবের textarea: ১px #ccc বর্ডার, ৮ ব্যাসার্ধ, প্যাডিং ১০, ওপরে ৮ ফাঁক */
@Composable
private fun SecArea(value: String, onChange: (String) -> Unit, placeholder: String, minHeight: Int) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = minHeight.dp),
        placeholder = { Text(placeholder, color = Color(0xFF757575), fontSize = 16.sp) },
        shape = RoundedCornerShape(8.dp), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Red, unfocusedBorderColor = C.Border,
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
        ),
    )
}

/** ওয়েবের .chk: ছোট চেকবক্স + হালকা (non-bold) লেখা */
@Composable
fun CheckRow(checked: Boolean, label: String, hint: String, onChange: (Boolean) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.padding(top = 2.dp).width(18.dp).height(18.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (checked) Color(0xFF1A73E8) else Color.White)
                    .border(1.5.dp, if (checked) Color(0xFF1A73E8) else Color(0xFF767676), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) { if (checked) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Text(label, fontSize = 16.sp, color = C.Text)
        }
        if (hint.isNotEmpty()) Text(hint, fontSize = 12.sp, color = C.Text.copy(alpha = 0.7f), modifier = Modifier.padding(start = 26.dp, top = 2.dp))
    }
}

/** ওয়েবের .navrow .btn: প্যাডিং ১০/৬, ফন্ট ১৪ */
@Composable
fun NavBtn(text: String, modifier: Modifier, color: Color = C.Red, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = color, contentColor = Color.White,
            disabledContainerColor = color.copy(alpha = 0.5f), disabledContentColor = Color.White.copy(alpha = 0.9f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp),
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center) }
}

@Composable
private fun Step1(vm: NewPostViewModel, onOpenVideo: () -> Unit, onClear: () -> Unit, header: @Composable () -> Unit = {}) {
    fun setSec(m: String) { vm.sec = m; vm.showManual = m == "manual" }
    val hasText = vm.title.isNotBlank() && vm.body.text.isNotBlank()

    Sec("১. খবরের লিংক অথবা পুরো খবর পেস্ট করুন", vm.sec == "src", { setSec("src") }) {
        SecArea(vm.src, { vm.src = it }, "https://... লিংক দিলে খবর নিজে থেকে আসবে, অথবা খবরের টেক্সট পেস্ট করুন", 170)
        CheckRow(vm.useOrig, "🖼️ লিংকের খবরের ছবিই সরাসরি ব্যবহার করুন (কার্টুন বানাবে না)", "") { vm.setUseOrig(it) }
    }
    Sec("📍 মাঠ পর্যায়ের তথ্য ও কারণ (ঐচ্ছিক)", vm.sec == "notes", { setSec("notes") }) {
        SecArea(vm.notes, { vm.notes = it }, "নিজে ঘটনাস্থলে গিয়ে যা জেনেছেন, এলোমেলো থাকলেও লিখুন: কে, কোথায়, কী হয়েছে, কেন হয়েছে, স্থানীয়দের কথা, সংখ্যা... AI সাজিয়ে ছোট করে খবর লিখে দেবে। শুধু এই ঘরে লিখলেও চলবে (লিংক বা খবর না দিলেও)।", 125)
        CheckRow(vm.webNews, "🌐 অনলাইনে সাম্প্রতিক খবর থেকে তথ্য নিবে",
            "টিক দিলে নোট থেকে শিরোনাম বানিয়ে Gemini দিয়ে গত ৩ ঘণ্টার মধ্যে প্রকাশিত বাংলাদেশি নিউজ সাইটের লিংক খোঁজা হবে। শিরোনামের সঙ্গে মিললে সেই লিংকের পুরো খবর পড়ে আপনার তথ্যের সঙ্গে মিলিয়ে নতুন করে লেখা হবে। না পেলে শুধু আপনার তথ্য দিয়েই লেখা হবে।") { vm.webNews = it }
    }
    Sec("✍️ নিজে পোস্ট ক্রিয়েট করুন", vm.sec == "manual", { setSec("manual") }) {
        FieldLabel("শিরোনাম")
        AppField(vm.mTitle, { vm.mTitle = it.replace(Regex("\\s*\\n+\\s*"), " ") }, placeholder = "পোস্টের শিরোনাম লিখুন")   // ওয়েবের <input>-এর মতো এক লাইন
        FieldLabel("পোস্ট (এখানে পেস্ট করে এডিট করুন)")
        Text("যে লেখা বোল্ড বা উপশিরোনাম হবে, সেটা সিলেক্ট করে নিচের বাটনে চাপুন।", fontSize = 12.sp, color = C.Label)
        FormatBar(vm, manual = true)
        BodyField(vm.mBody, { vm.mBody = it }, 10, "এখানে আপনার পোস্টটা পেস্ট করুন...")
    }
    Sec("🎬 ভিডিও পোস্ট করুন", vm.sec == "video", { setSec("video") }) {
        header()   // অসমাপ্ত ভিডিও এডিট — "ভিডিও বাছুন"-এর ঠিক ওপরে
        AppBtn("📁 ভিডিও বাছুন", Modifier.padding(top = 8.dp), color = C.Gray, onClick = onOpenVideo)
        Text("ভিডিও বাছলে গ্যালারি খুলবে, ভিডিও বেছে নিলে সেটা নিয়ে ভিডিও এডিটরে চলে যাবে। এই অবস্থায় কোনো টাকা কাটবে না।", fontSize = 12.sp, color = C.Label, modifier = Modifier.padding(top = 6.dp))
    }

    if (vm.sec == "manual") {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavBtn("✅ পোস্ট করুন", Modifier.weight(0.78f), color = C.Green, enabled = !vm.busy && !vm.mpLow) { vm.manualPost() }
            NavBtn("🗑️ ক্লিয়ার", Modifier.weight(0.22f), color = C.Gray, onClick = onClear)
        }
        Text(if (vm.mpLow) "⚠️ ব্যালেন্স কম (কমপক্ষে ৳${PostUtil.bn(PostUtil.MP_COST)} লাগবে)। ড্যাশবোর্ড থেকে ব্যালেন্স যোগ করুন।" else "এই অবস্থায় ৳${PostUtil.bn(PostUtil.MP_COST)} কাটে (AI ছাড়া)।", fontSize = 12.sp, color = C.Label, modifier = Modifier.padding(top = 6.dp))
    } else if (vm.sec != "video") {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavBtn(if (vm.busy) "⏳ প্রসেসিং হচ্ছে..." else "AI দিয়ে নতুন করে লিখুন", Modifier.weight(if (hasText) 0.39f else 0.78f), enabled = !vm.busy && !vm.rwLow) { vm.startGuest() }
            if (hasText) NavBtn("নেক্সট →", Modifier.weight(0.39f), color = C.Green) { vm.step = 2; vm.saveDraft() }
            NavBtn("🗑️ ক্লিয়ার", Modifier.weight(0.22f), color = C.Gray, onClick = onClear)
        }
        Text(if (vm.rwLow) "⚠️ ব্যালেন্স কম (কমপক্ষে ৳${PostUtil.bn(PostUtil.RW_COST)} লাগবে)। ড্যাশবোর্ড থেকে ব্যালেন্স যোগ করুন।" else "AI দিয়ে লিখলে সফল হওয়ার পর ৳${PostUtil.bn(PostUtil.RW_COST)} কাটে।", fontSize = 12.sp, color = C.Label, modifier = Modifier.padding(top = 6.dp))
    }
}

// ───────── ধাপ ২: লেখা ─────────
@Composable
private fun Step2(vm: NewPostViewModel, onNext: () -> Unit, onRetry: () -> Unit, onClear: () -> Unit, clearLabel: String, clearColor: Color, onBack: () -> Unit) {
    FieldLabel("২. শিরোনাম (এডিট করুন)")
    AppField(vm.title, { vm.title = it; vm.saveDraft() }, singleLine = false)
    FieldLabel("বডি (পোস্ট প্রিভিউ, এখানেই এডিট করুন)")
    FormatBar(vm, manual = false)
    BodyField(vm.body, { vm.editBody(it) }, 14)
    AppBtn("নেক্সট →", Modifier.padding(top = 10.dp), onClick = onNext)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NavBtn("← ব্যাক", Modifier.weight(1f), color = C.Gray, onClick = onBack)
        NavBtn("🔁 আবার লিখুন", Modifier.weight(1f), color = C.Gray, enabled = !vm.busy && !vm.rwLow && vm.editItem == null, onClick = onRetry)
        NavBtn(clearLabel, Modifier.weight(1f), color = clearColor, onClick = onClear)
    }
}

// ───────── ধাপ ৩: কার্ড ─────────
@Composable
private fun Step3(vm: NewPostViewModel, onClear: () -> Unit, clearLabel: String, clearColor: Color) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris -> vm.addPhotos(uris) }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    var cropOpen by remember { mutableStateOf(false) }
    var collOpen by remember { mutableStateOf(false) }
    var tplEdit by remember { mutableStateOf(false) }

    // প্রিভিউ (আঙুল টেনে ছবির অবস্থান)
    Box(
        Modifier.fillMaxWidth().aspectRatio(CardRender.aspect(vm.tplId))
            .clip(RoundedCornerShape(10.dp)).background(Color(0xFFDDDDDD))
            .pointerInput(vm.hasPhoto) {
                if (vm.hasPhoto) detectDragGestures(onDragEnd = { vm.panEnd() }, onDragCancel = { vm.panEnd() }) { change, drag ->
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
    if (vm.hasPhoto) Text(if (vm.canEditCollage) "👆 কার্ডের ওপর আঙুল টেনে পুরো কোলাজের পজিশন ঠিক করুন; প্রতিটি ছবি আলাদা সরাতে 🧩 কোলাজ এডিট চাপুন।" else "👆 ছবির ওপর আঙুল টেনে ছবির পজিশন ঠিক করুন, বা ✂️ ক্রপ দিয়ে অংশ কেটে নিন।", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
    if (vm.collItems.size == 2) {
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !vm.collVertical, onClick = { vm.setCollVertical(false) }, label = { Text("◀️▶️ পাশাপাশি") })
            FilterChip(selected = vm.collVertical, onClick = { vm.setCollVertical(true) }, label = { Text("⬆️⬇️ উপর-নিচ") })
        }
    }
    if (vm.hasPhoto) {
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("↔️ ফ্লিপ", color = C.Gray, small = true) { vm.flipPhoto() }
            AppBtn("✂️ ক্রপ", color = C.Blue, small = true) { cropOpen = true }
            AppBtn("✖ ছবি সরান", color = C.Gray, small = true) { vm.clearPhoto() }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.canEditCollage) AppBtn("🧩 কোলাজ এডিট (প্রতিটি ছবি আলাদা)", color = C.Blue, small = true) { collOpen = true }
            if (vm.canUndoCrop) AppBtn("↩️ ক্রপ ফেরত", color = C.Gray, small = true) { vm.undoCrop() }
        }
    }

    FieldLabel("ফোটোকার্ডের শিরোনাম")
    AppField(vm.cardTitle, { vm.cardTitle = it.replace(Regex("\\s*\\n+\\s*"), " "); vm.saveDraft() }, singleLine = false, visual = CardTitleVT)

    H2("🎨 ফোটোকার্ড টেমপ্লেট (যেটা পোস্ট হবে সেটা বেছে নিন)")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TplRegistry.newPostTpls().forEach { (id, name) ->
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

    AppBtn("✏️ এই টেমপ্লেট এডিট করুন", Modifier.padding(top = 8.dp), color = C.Blue, small = true) { tplEdit = true }
    if (tplEdit) TplEditorDialog(vm, vm.tplId) { tplEdit = false }

    FieldLabel("ক্যাটাগরি (সর্বোচ্চ ২টি, কমা দিয়ে আলাদা। WordPress-এ দুটোই যাবে, কার্ডে তারিখের পাশে প্রথমটা দেখাবে)")
    AppField(vm.category, { vm.category = it; vm.saveDraft() }, placeholder = "যেমন: বাংলাদেশ, রাজনীতি")
    val curCats = PostUtil.catParts(vm.category)
    HistChips(vm.catChips, isOn = { it in curCats }) { vm.toggleCat(it) }
    FieldLabel("ছবির ট্যাগ (খালি রাখলে দেখাবে না)")
    AppField(vm.tag, { vm.tag = it; vm.saveDraft() }, placeholder = "যেমন: সংগৃহীত")
    HistChips(vm.tagChips) { vm.pickTag(it) }

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


    if (cropOpen) {
        val cb = vm.photoForCrop()
        if (cb == null) cropOpen = false
        else CropDialog(cb, vm.cropRatio(), onApply = { l, t, r, b -> vm.applyCrop(l, t, r, b); cropOpen = false }, onCancel = { cropOpen = false })
    }
    if (collOpen) {
        if (!vm.canEditCollage) collOpen = false
        else CollageDialog(vm.collItems, vm.collVertical, onApply = { items, v -> vm.applyCollage(items, v); collOpen = false }, onCancel = { collOpen = false })
    }


    AppBtn("৩. পাবলিশ করুন (WordPress + Facebook)", Modifier.padding(top = 10.dp), color = C.Green) { vm.toStep4() }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NavBtn("← ব্যাক", Modifier.weight(1f), color = C.Gray) { vm.back() }
        NavBtn("🔁 ছবি রিট্রাই", Modifier.weight(1f), color = C.Gray, enabled = !vm.busy) {
            when (vm.imgMode) { "ai" -> vm.makeCartoon(); "link" -> vm.fetchLink(); else -> pick() }
        }
        NavBtn(clearLabel, Modifier.weight(1f), color = clearColor, onClick = onClear)
    }
}

// ───────── ধাপ ৪: কনফার্ম ─────────
@Composable
private fun Step4(vm: NewPostViewModel, onSchedule: () -> Unit, onClear: () -> Unit, clearLabel: String, clearColor: Color) {
    H2("🔍 শেষবার দেখে নিন")
    vm.preview?.let { Image(it.asImageBitmap(), contentDescription = "কার্ড", modifier = Modifier.fillMaxWidth().aspectRatio(CardRender.aspect(vm.tplId)).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit) }
    Text(vm.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
    Text("কনফার্ম করলে WordPress-এ পোস্ট হবে আর Facebook-এ ফোটোকার্ডসহ শেয়ার হবে।", fontSize = 13.sp, color = C.Label, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    AppBtn(if (vm.busy) "⏳ অপেক্ষা করুন…" else "✅ কনফার্ম ও পাবলিশ করুন", color = C.Green, enabled = !vm.busy) { vm.publish() }
    AppBtn(if (vm.editItem != null) "⏰ সিডিউল আপডেট করুন" else "⏰ সিডিউল পোস্ট", Modifier.padding(top = 10.dp), color = C.Blue, enabled = !vm.busy, onClick = onSchedule)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NavBtn("← ব্যাক", Modifier.weight(1f), color = C.Gray) { vm.back() }
        NavBtn(clearLabel, Modifier.weight(1f), color = clearColor, onClick = onClear)
    }
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
