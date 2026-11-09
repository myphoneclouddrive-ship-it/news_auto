package com.digitalcrafting25.newsapp

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * ভিডিও এডিট থেকে রেন্ডার শেষে এই পেইজ (HTML `#sv`, "২ ভিডিও" ধাপ):
 * ওপরে ধাপ-বার, রেন্ডার করা ভিডিওর প্রিভিউ, ভিডিও টাইটেল (আগে থেকে বসানো), একটাই "🚀 পাবলিশ করুন (Facebook+YouTube+WordPress)" বাটন,
 * 💾 সেভ, ← ব্যাক (এডিটে), ↩️ নতুন পোস্টে ফিরুন।
 * পাবলিশ: `VideoPublishState` — ফেইসবুক → ইউটিউব → ওয়ার্ডপ্রেস একটার পর একটা; যা হয়ে গেছে আবার যায় না।
 * রেন্ডার ও পাবলিশের অবস্থা `LastRender`-এ মনে রাখা হয়, তাই অ্যাপ বন্ধ করে খুললেও এই পেইজ ফিরে আসে।
 */
@Composable
fun VideoResultScreen(vm: EditorViewModel, outputs: List<RenderOutput>, onBack: () -> Unit, onNewPost: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    var msgOk by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pub = vm.publish
    val uriHandler = LocalUriHandler.current
    val login = rememberYtLogin()
    var dupAsk by remember { mutableStateOf(false) }   // সব জায়গায় আগেই পাবলিশ হয়ে গেলে ডুপ্লিকেটের সতর্কতা
    // ওপরের ট্যাব: YT preview / FB preview। যে ভার্সন আগে রেন্ডার হয়নি, ট্যাব চাপলে সেটা রেন্ডার করে তারপর দেখায়
    val outs = remember { mutableStateMapOf<String, RenderOutput>().apply { outputs.forEach { put(it.mode, it) } } }
    var sel by remember { mutableStateOf(if (outs.containsKey("fb") || !outs.containsKey("yt")) "fb" else "yt") }
    val status by RenderBus.status.collectAsState()
    var pending by remember { mutableStateOf<String?>(null) }     // এখন যে ভার্সন রেন্ডার চলছে
    var sawRun by remember { mutableStateOf(false) }
    LaunchedEffect(status, pending) {
        if (pending == null) return@LaunchedEffect
        when (val st = status) {
            is RenderStatus.Running -> sawRun = true
            is RenderStatus.Done -> if (sawRun) {
                st.outputs.forEach { outs[it.mode] = it }
                sel = pending ?: sel
                pending = null; sawRun = false
            }
            is RenderStatus.Failed -> if (sawRun) { msgOk = false; msg = "❌ " + st.message; pending = null; sawRun = false }
            is RenderStatus.Idle -> if (sawRun) { msgOk = false; msg = "রেন্ডার বাতিল হয়েছে"; pending = null; sawRun = false }
        }
    }
    val rendering = pending != null
    var saving by remember { mutableStateOf(false) }
    val savedModes = remember { mutableStateListOf<String>() }   // যে ভার্সন গ্যালারিতে সেভ হয়ে গেছে (ডুপ্লিকেট সেভ ঠেকাতে)
    BackHandler(enabled = !pub.busy && !rendering) { onBack() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
    ) {
        // ধাপ-বার (HTML #stepbar): ১ ভিডিও এডিট (সবুজ, হয়ে গেছে) · ২ ভিডিও (লাল, চলতি)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepCell("১ ভিডিও এডিট", C.Green, false, Modifier.weight(1f))
            StepCell("২ ভিডিও", C.Red, true, Modifier.weight(1f))
        }

        // ওপরের অপশন: YT preview / FB preview — চাপলে ওই ভার্সনের ভিডিও দেখায়; রেন্ডার না থাকলে ইউটিউব/ফেইসবুকের জন্য রেন্ডার করে দেখায়
        fun pick(mode: String) {
            if (rendering || pub.busy) return
            sel = mode
            if (outs[mode] != null) return
            vm.loadEditForRender()   // অ্যাপ নতুন করে খোলার পর টাইমলাইন ফাঁকা, তাই আগে এডিট ফেরানো
            val m = vm.startRender(fb = mode == "fb", yt = mode == "yt", merge = true)
            if (m != null) { msgOk = false; msg = "❌ $m" } else { msg = ""; pending = mode; sawRun = false }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn(
                "▶️ YT preview" + if (outs["yt"] == null) " (রেন্ডার)" else "", Modifier.weight(1f),
                color = if (sel == "yt") C.Red else C.Gray, small = true, enabled = !rendering && !pub.busy,
            ) { pick("yt") }
            AppBtn(
                "📘 FB preview" + if (outs["fb"] == null) " (রেন্ডার)" else "", Modifier.weight(1f),
                color = if (sel == "fb") C.Blue else C.Gray, small = true, enabled = !rendering && !pub.busy,
            ) { pick("fb") }
        }
        val runSt = status as? RenderStatus.Running
        if (rendering) {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Text(
                    "⏳ " + (if (pending == "yt") "ইউটিউব" else "ফেইসবুক") + " ভিডিও রেন্ডার হচ্ছে… " + (runSt?.progress ?: 0) + "%",
                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                )
                LinearProgressIndicator(progress = { (runSt?.progress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                AppBtn("⏹ বাতিল", Modifier.padding(top = 8.dp), color = C.Gray, small = true) { vm.cancelRender() }
            }
        }

        // রেন্ডার করা ভিডিও (শুধু দেখা যাবে): বাছা ট্যাবের ভিডিওটাই
        val ordered = if (rendering) emptyList() else listOfNotNull(outs[sel])
        ordered.forEachIndexed { i, o ->
            Column(
                Modifier.padding(top = if (i == 0) 0.dp else 10.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xFFFAFAFA))
                    .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(10.dp))
                    .padding(10.dp),
            ) {
                Text(
                    "🎬 ${o.label} প্রিভিউ · ${o.w}×${o.h} (শুধু দেখা যাবে)",
                    fontWeight = FontWeight.Bold,
                )
                val uri = o.uri
                Box(
                    Modifier.padding(top = 8.dp).fillMaxWidth().height(230.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { c ->
                            VideoView(c).also { v ->
                                val mc = MediaController(c)
                                mc.setAnchorView(v)
                                v.setMediaController(mc)
                                v.setVideoURI(uri)
                                v.setOnPreparedListener { it.seekTo(1) }   // প্রথম ফ্রেম দেখায়, নিজে চলে না
                            }
                        },
                        update = { v -> if (v.tag != uri) { v.tag = uri; v.setVideoURI(uri) } },
                        modifier = Modifier.fillMaxWidth().height(230.dp),
                    )
                }
                // শেয়ার: ফেইসবুক ভিডিও → Facebook অ্যাপ, ইউটিউব ভিডিও → YouTube অ্যাপ; দুটোর পাশেই যেকোনো অ্যাপে পাঠানো
                val isFb = o.mode == "fb"
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppBtn(
                        if (isFb) "📘 Facebook-এ পাঠান" else "▶️ YouTube-এ পাঠান", Modifier.weight(1f), color = C.Green, small = true,
                    ) {
                        val r = VideoShare.share(ctx, uri, vm.videoTitle, if (isFb) VideoShare.FACEBOOK else VideoShare.YOUTUBE, if (isFb) "Facebook" else "YouTube")
                        msgOk = !r.startsWith("❌"); msg = r
                    }
                    AppBtn("↗️ অন্য অ্যাপে", Modifier.weight(1f), color = C.Gray, small = true) {
                        val r = VideoShare.share(ctx, uri, vm.videoTitle)
                        msgOk = !r.startsWith("❌"); msg = r
                    }
                }
            }
        }

        FieldLabel("ভিডিও টাইটেল")
        OutlinedTextField(
            value = vm.videoTitle,
            onValueChange = { vm.videoTitle = it; vm.persistTitleSoon() },
            placeholder = { Text("ভিডিওর টাইটেল লিখুন") },
            singleLine = false,
            maxLines = 3,
            enabled = !pub.busy,
            modifier = Modifier.fillMaxWidth(),
        )

        // কোথায় কী অবস্থা: প্রতি প্ল্যাটফর্মের সারি (অপেক্ষায়/আপলোড %/✅/❌)
        listOf("fb" to "Facebook", "yt" to "YouTube", "wp" to "WordPress").forEach { (k, name) ->
            val r = pub.rows[k] ?: return@forEach
            val col = when (r.state) { 1 -> C.Green; 2 -> C.Red; 3 -> Color(0xFFB45309); else -> C.Muted }
            Column(
                Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFAFAFA)).border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(8.dp)).padding(10.dp),
            ) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (r.state == 0) {
                    if (r.pct < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    else LinearProgressIndicator(progress = { r.pct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                Text(r.text, color = col, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if (r.link.isNotBlank()) Text(
                    "🔗 খুলুন", color = C.Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp).clickable { runCatching { uriHandler.openUri(r.link) } },
                )
            }
        }

        // একটাই পাবলিশ বাটন: যে যে ভিডিও রেন্ডার করা আছে সেগুলোর নাম বাটনে দেখায়
        fun doPublish() {
            val title = vm.videoTitle.trim()
            if (title.isEmpty()) { pub.say("❌ আগে ভিডিও টাইটেল লিখুন", 3); return }
            val snap = outs.toMap()
            scope.launch {
                val need = pub.prepare(snap, title, vm.targets) ?: return@launch
                if (need.allDone) { dupAsk = true; return@launch }
                var tok: String? = null
                if (need.yt) {   // Google পপআপ ক্লিকের সাথে সাথেই খোলে, তাই আপলোডের আগে লগইন
                    try {
                        tok = login.token()
                        YouTube.setConnected(ctx, true)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        pub.say("⚠️ " + (e.message ?: "YouTube লগইন হয়নি") + "\nসেটিংসের YouTube অংশ ঠিক করে আবার চেষ্টা করুন। কিছুই পাবলিশ হয়নি।", 3)
                        return@launch
                    }
                }
                pub.run(need, tok, title, snap)
            }
        }
        if (dupAsk) AlertDialog(
            onDismissRequest = { dupAsk = false },
            title = { Text("আবার পাবলিশ?") },
            text = { Text("সব জায়গায় আগেই পাবলিশ হয়ে গেছে। আবার করলে ডুপ্লিকেট পোস্ট হবে। তবুও করবেন?") },
            confirmButton = { TextButton(onClick = { dupAsk = false; pub.forgetDone(); doPublish() }) { Text("হ্যাঁ, আবার") } },
            dismissButton = { TextButton(onClick = { dupAsk = false }) { Text("না") } },
        )
        // পাবলিশ হবে শুধু: চিপ — ✕ চাপলে বাদ, + চাপলে আবার যোগ (YouTube বাদ দিলে WordPress-ও বাদ)
        val tg = vm.targets
        val hasFb = outs["fb"] != null
        val hasYt = outs["yt"] != null
        if (hasFb || hasYt) {
            Text("🚀 পাবলিশ হবে শুধু:", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                @Composable
                fun Chip(k: String, name: String, on: Boolean, c: Color, mod: Modifier) {
                    AppBtn(
                        if (on) "$name ✕" else "+ $name", mod, color = if (on) c else C.Gray, small = true, enabled = !pub.busy && !rendering,
                    ) { vm.setTarget(k, !on, hasYt) }
                }
                if (hasFb) Chip("fb", "Facebook", tg.fb, C.Blue, Modifier.weight(1f))
                if (hasYt) Chip("yt", "YouTube", tg.yt, C.Red, Modifier.weight(1f))
                if (hasYt) Chip("wp", "WordPress", tg.wp && tg.yt, Color(0xFF21759B), Modifier.weight(1f))
            }
            val no = listOfNotNull(if (!hasFb) "Facebook" else null, if (!hasYt) "YouTube, WordPress" else null)
            if (no.isNotEmpty()) Text("রেন্ডারে টিক দেওয়া হয়নি, তাই যোগ করা যাবে না: " + no.joinToString(", "), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        val canPub = !rendering && !pub.busy && pub.names(outs, tg).isNotEmpty()
        AppBtn(
            if (pub.busy) "⏳ পাবলিশ হচ্ছে…" else pub.label(outs, tg),
            Modifier.padding(top = 8.dp), color = C.Green, enabled = canPub,
        ) { doPublish() }

        if (msg.isNotEmpty()) {
            Text(
                msg, color = if (msgOk) C.Green else C.Red, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (pub.msg.isNotEmpty()) {
            Text(
                pub.msg,
                color = when (pub.msgKind) { 1 -> C.Green; 2 -> Color(0xFFB45309); 3 -> C.Red; else -> C.Muted },
                fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (pub.busy) Text("শেষ না হওয়া পর্যন্ত অ্যাপ বন্ধ করবেন না।", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))

        Text(
            "পাবলিশ বাটনে চাপলে রেন্ডার করা ভিডিও একে একে Facebook পেইজ, YouTube চ্যানেল ও WordPress-এ যাবে (সেটিংস → 🔑 সংযোগে দেওয়া থাকতে হবে; যেটার সংযোগ নেই সেটা বাদ যাবে)। কোনোটা না হলে আবার চাপলে শুধু বাকিগুলো চেষ্টা হবে। রেন্ডার ও টাইটেল মনে থাকে: অ্যাপ বন্ধ করে খুললেও এই পেইজ ফিরে আসবে।",
            color = C.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp),
        )
        // HTML-এর মতো: রেন্ডারের পর নিজে সেভ হয় না; এই বাটনে চাপলে তবেই ফোনের গ্যালারিতে (Movies/NewsPublisher) যায়
        fun saveRun(only: String?) {
            if (saving || rendering || pub.busy) return
            val base = vm.videoTitle.trim().replace(Regex("[\\\\/:*?\"<>|]+"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "video" }
            val files = outs.values.filter { only == null || it.mode == only }.sortedBy { if (it.mode == "fb") 0 else 1 }
            if (files.isEmpty()) { msgOk = false; msg = "❌ এই ভিডিওটা রেন্ডার করা নেই"; return }
            saving = true; msgOk = true; msg = "⏳ গ্যালারিতে সেভ হচ্ছে…"
            scope.launch {
                try {
                    val done = ArrayList<String>()
                    for (o in files) {
                        if (savedModes.contains(o.mode)) { done += o.label; continue }
                        val nm = base + (if (outs.size > 1) (if (o.mode == "fb") " - facebook" else " - youtube") else "") + ".mp4"
                        withContext(Dispatchers.IO) { VideoShare.saveToGallery(ctx, o.uri, nm) }
                        savedModes.add(o.mode)
                        done += o.label
                    }
                    msgOk = true
                    msg = "✅ " + done.joinToString(" ও ") + " ভিডিও ফোনে সেভ হয়েছে: Movies/NewsPublisher"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    msgOk = false; msg = "❌ সেভ করা যায়নি: " + (e.message ?: "অজানা সমস্যা")
                } finally { saving = false }
            }
        }
        AppBtn("💾 সেভ করুন", Modifier.padding(top = 8.dp), color = C.Blue, enabled = !saving && !rendering && !pub.busy) { saveRun(null) }
        if (outs.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn("💾 শুধু Facebook", Modifier.weight(1f), color = C.Blue, small = true, enabled = !saving && !rendering && !pub.busy) { saveRun("fb") }
                AppBtn("💾 শুধু YouTube", Modifier.weight(1f), color = C.Red, small = true, enabled = !saving && !rendering && !pub.busy) { saveRun("yt") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("← ব্যাক", Modifier.weight(1f), color = C.Gray, enabled = !pub.busy && !rendering) { onBack() }
            AppBtn("↩️ নতুন পোস্টে ফিরুন", Modifier.weight(1f), color = C.Gray, enabled = !pub.busy && !rendering) { onNewPost() }
        }
    }
}

@Composable
private fun StepCell(text: String, color: Color, bold: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text, color = color, fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Box(Modifier.fillMaxWidth().height(3.dp).background(color))
    }
}
