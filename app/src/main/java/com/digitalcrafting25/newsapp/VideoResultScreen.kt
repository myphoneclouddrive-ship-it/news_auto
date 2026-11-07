package com.digitalcrafting25.newsapp

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * ভিডিও এডিট থেকে রেন্ডার শেষে এই পেইজ (HTML `#sv`, "২ ভিডিও" ধাপ):
 * ওপরে ধাপ-বার, রেন্ডার করা ভিডিওর প্রিভিউ (শুধু দেখা), ভিডিও টাইটেল,
 * 🚀 পাবলিশ, 💾 সেভ, ← ব্যাক (এডিটে), ↩️ নতুন পোস্টে ফিরুন।
 * সরাসরি Facebook/YouTube/WordPress আপলোড এখনো যোগ হয়নি — বাটন সেটা সততার সাথে জানায়।
 */
@Composable
fun VideoResultScreen(vm: EditorViewModel, outputs: List<RenderOutput>, onBack: () -> Unit, onNewPost: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    var msgOk by remember { mutableStateOf(false) }
    BackHandler { onBack() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
    ) {
        // ধাপ-বার (HTML #stepbar): ১ ভিডিও এডিট (সবুজ, হয়ে গেছে) · ২ ভিডিও (লাল, চলতি)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepCell("১ ভিডিও এডিট", C.Green, false, Modifier.weight(1f))
            StepCell("২ ভিডিও", C.Red, true, Modifier.weight(1f))
        }

        // রেন্ডার করা ভিডিও (শুধু দেখা যাবে); ফেইসবুক + ইউটিউব দুটো হলে দুটোই, ফেইসবুক আগে (HTML-এর মতো)
        val ordered = outputs.sortedBy { if (it.mode == "fb") 0 else 1 }
        ordered.forEachIndexed { i, o ->
            Column(
                Modifier.padding(top = if (i == 0) 0.dp else 10.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xFFFAFAFA))
                    .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(10.dp))
                    .padding(10.dp),
            ) {
                Text(
                    if (ordered.size > 1) "🎬 ${o.label} ভিডিও · ${o.w}×${o.h} (শুধু দেখা যাবে)" else "🎬 রেন্ডার করা ভিডিও (শুধু দেখা যাবে)",
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
            }
        }

        FieldLabel("ভিডিও টাইটেল")
        OutlinedTextField(
            value = vm.videoTitle,
            onValueChange = { vm.videoTitle = it },
            placeholder = { Text("ভিডিওর টাইটেল লিখুন") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        if (msg.isNotEmpty()) {
            Text(
                msg, color = if (msgOk) C.Green else C.Red, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        AppBtn("🚀 পাবলিশ করুন (Facebook+Youtube+WordPress)", Modifier.padding(top = 12.dp), color = C.Green) {
            msgOk = false
            msg = "সরাসরি আপলোড এখনো অ্যাপে যোগ হয়নি। ভিডিও ফোনে (Movies/NewsPublisher) সেভ আছে।"
        }
        AppBtn("💾 সেভ করুন", Modifier.padding(top = 8.dp), color = C.Blue) {
            msgOk = true
            msg = if (outputs.size > 1) "✅ ${outputs.size}টি ভিডিও (ফেইসবুক, ইউটিউব) ফোনে সেভ করা আছে: Movies/NewsPublisher" else "✅ ভিডিও ফোনে সেভ করা আছে: Movies/NewsPublisher"
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("← ব্যাক", Modifier.weight(1f), color = C.Gray) { onBack() }
            AppBtn("↩️ নতুন পোস্টে ফিরুন", Modifier.weight(1f), color = C.Gray) { onNewPost() }
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
