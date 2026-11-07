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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * ভিডিও এডিট থেকে রেন্ডার শেষে এই পেইজ (HTML `#sv`, "২ ভিডিও" ধাপ):
 * ওপরে ধাপ-বার, রেন্ডার করা ভিডিওর প্রিভিউ (শুধু দেখা), ভিডিও টাইটেল,
 * প্রতি ভিডিওর নিচে শেয়ার বাটন (ফেইসবুক ভিডিও → Facebook অ্যাপ, ইউটিউব ভিডিও → YouTube অ্যাপ, বা যেকোনো অ্যাপ), 💾 সেভ, ← ব্যাক (এডিটে), ↩️ নতুন পোস্টে ফিরুন।
 * ফেইসবুক ভিডিও: "📤 Facebook পেইজে আপলোড" (step61) — Worker-এর /vid-fb-* রুট দিয়ে টুকরো টুকরো আপলোড (`VideoUpload`), টোকেন Worker-এই থাকে।
 * ইউটিউব ভিডিও: \"📤 YouTube চ্যানেলে আপলোড\" (step62) — অ্যাপ থেকে সরাসরি YouTube Data API-তে (`YouTube.upload`), তারপর \"🌐 WordPress-এ পোস্ট\" (Worker `/vid-wp`)। শেয়ার বাটনও আছে (`VideoShare`)।
 */
@Composable
fun VideoResultScreen(vm: EditorViewModel, outputs: List<RenderOutput>, onBack: () -> Unit, onNewPost: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    var msgOk by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // ফেইসবুক সরাসরি আপলোড: mode -> অগ্রগতি (০..১০০, -১ = চলছে না) ও সফল লিংক
    var upBusy by remember { mutableStateOf(false) }
    var upPct by remember { mutableIntStateOf(0) }
    val upLinks = remember { mutableStateMapOf<String, String>() }
    val login = rememberYtLogin()
    var ytId by remember { mutableStateOf("") }          // আপলোড হওয়া ইউটিউব ভিডিওর আইডি (WordPress পোস্টে লাগে)
    var wpBusy by remember { mutableStateOf(false) }
    var wpLink by remember { mutableStateOf("") }
    BackHandler(enabled = !upBusy && !wpBusy) { onBack() }

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
                if (isFb) {
                    val done = upLinks[o.mode]
                    if (done != null) {
                        Text("✅ Facebook-এ আপলোড হয়েছে: $done", color = C.Green, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        if (upBusy) {
                            LinearProgressIndicator(progress = { upPct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Text("Facebook-এ আপলোড হচ্ছে… $upPct% (শেষ না হওয়া পর্যন্ত অ্যাপ বন্ধ করবেন না)", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
                        }
                        AppBtn("📤 Facebook পেইজে আপলোড", Modifier.padding(top = 8.dp), color = C.Blue, enabled = !upBusy) {
                            val title = vm.videoTitle.trim()
                            if (title.isEmpty()) { msgOk = false; msg = "❌ আগে ভিডিও টাইটেল লিখুন"; return@AppBtn }
                            upBusy = true; upPct = 0; msgOk = true; msg = ""
                            scope.launch {
                                try {
                                    val r = VideoUpload.toFacebook(ctx, uri, title, title) { upPct = it }
                                    upLinks[o.mode] = r.link.ifBlank { "https://www.facebook.com/" + r.id }
                                    msgOk = true; msg = "✅ ভিডিও Facebook পেইজে পোস্ট হয়েছে। আবার আপলোড করবেন না (ডুপ্লিকেট হবে)।"
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    msgOk = false; msg = "❌ " + (e.message ?: "আপলোড হয়নি") + "\nআধা আপলোড হলে Facebook পেইজে পোস্ট হয় না; ঠিক করে আবার চেষ্টা করুন।"
                                } finally { upBusy = false }
                            }
                        }
                    }
                } else {
                    val done = upLinks[o.mode]
                    if (done != null) {
                        Text("✅ YouTube-এ আপলোড হয়েছে: $done", color = C.Green, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        if (wpLink.isNotEmpty()) {
                            Text("✅ WordPress-এ পোস্ট হয়েছে: $wpLink", color = C.Green, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        } else {
                            AppBtn("🌐 WordPress-এ পোস্ট (YouTube iframe সহ)", Modifier.padding(top = 8.dp), color = C.Blue, enabled = !wpBusy && !upBusy) {
                                val title = vm.videoTitle.trim()
                                wpBusy = true; msg = ""
                                scope.launch {
                                    try {
                                        val body = JSONObject().put("title", title).put("youtubeId", ytId).put("youtubeUrl", "https://www.youtube.com/watch?v=$ytId")
                                            .put("thumb", "https://i.ytimg.com/vi/$ytId/maxresdefault.jpg").put("facebook", upLinks["fb"].orEmpty())
                                        val r = Worker.post(ctx, "/vid-wp", body, 120000)
                                        wpLink = r.optString("link").ifBlank { "হয়েছে" }
                                        val w = r.optString("warn")
                                        msgOk = w.isBlank(); msg = if (w.isBlank()) "✅ WordPress-এ পোস্ট হয়েছে (YouTube iframe সহ)" else "✅ WordPress-এ পোস্ট হয়েছে\n⚠️ $w"
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        msgOk = false; msg = "❌ " + (e.message ?: "WordPress-এ পোস্ট হয়নি")
                                    } finally { wpBusy = false }
                                }
                            }
                        }
                    } else {
                        if (upBusy) {
                            LinearProgressIndicator(progress = { upPct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Text("YouTube-এ আপলোড হচ্ছে… $upPct% (শেষ না হওয়া পর্যন্ত অ্যাপ বন্ধ করবেন না)", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
                        }
                        AppBtn("📤 YouTube চ্যানেলে আপলোড", Modifier.padding(top = 8.dp), color = C.Red, enabled = !upBusy) {
                            val title = vm.videoTitle.trim()
                            if (title.isEmpty()) { msgOk = false; msg = "❌ আগে ভিডিও টাইটেল লিখুন"; return@AppBtn }
                            upBusy = true; upPct = 0; msgOk = true; msg = ""
                            scope.launch {
                                try {
                                    val tok = login.token()   // Google পপআপ লাগলে এখানেই আসে, তাই আপলোডের আগে
                                    YouTube.setConnected(ctx, true)
                                    val r = YouTube.upload(ctx, uri, title, title, tok) { upPct = it }
                                    ytId = r.id; upLinks[o.mode] = r.link
                                    msgOk = r.privacy.isBlank() || r.privacy == "public"
                                    msg = if (msgOk) "✅ ভিডিও YouTube-এ পোস্ট হয়েছে। আবার আপলোড করবেন না (ডুপ্লিকেট হবে)।"
                                    else "✅ আপলোড হয়েছে, কিন্তু YouTube ভিডিওটি \"${r.privacy}\" রেখেছে (নতুন API প্রজেক্টের অডিট না হলে এমন হয়)। YouTube Studio থেকে Public করুন, নইলে WordPress-এ চলবে না।"
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    msgOk = false; msg = "❌ " + (e.message ?: "আপলোড হয়নি")
                                } finally { upBusy = false }
                            }
                        }
                    }
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

        Text(
            "ফেইসবুক ভিডিও সরাসরি পেইজে আপলোড হয় (সেটিংস → 🔑 সংযোগে Facebook দেওয়া থাকতে হবে)। ইউটিউব ভিডিও সরাসরি চ্যানেলে আপলোড হয় (সেটিংস → 🔑 সংযোগ → YouTube-এ লগইন টেস্ট করে নিন); আপলোডের পর WordPress-এ iframe সহ পোস্টও করা যায়। \"পাঠান\" বাটনে ভিডিও সংযুক্ত হয়ে ওই অ্যাপ খোলে; টাইটেল কপি হয়ে থাকে।",
            color = C.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp),
        )
        AppBtn("💾 সেভ করুন", Modifier.padding(top = 8.dp), color = C.Blue) {
            msgOk = true
            msg = if (outputs.size > 1) "✅ ${outputs.size}টি ভিডিও (ফেইসবুক, ইউটিউব) ফোনে সেভ করা আছে: Movies/NewsPublisher" else "✅ ভিডিও ফোনে সেভ করা আছে: Movies/NewsPublisher"
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("← ব্যাক", Modifier.weight(1f), color = C.Gray, enabled = !upBusy) { onBack() }
            AppBtn("↩️ নতুন পোস্টে ফিরুন", Modifier.weight(1f), color = C.Gray, enabled = !upBusy) { onNewPost() }
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
