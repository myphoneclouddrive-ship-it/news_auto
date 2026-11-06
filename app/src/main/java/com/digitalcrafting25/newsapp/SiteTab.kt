package com.digitalcrafting25.newsapp

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "data:image/png;base64,..." থেকে ছবি */
@Composable
private fun DataImage(url: String, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    val bmp by produceState<ImageBitmap?>(null, url) {
        value = if (url.isBlank()) null else withContext(Dispatchers.Default) {
            runCatching {
                val b = Base64.decode(url.substringAfter("base64,"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier) { bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = scale) } }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, color = C.Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = modifier)

@Composable
fun SiteTab(vm: SiteViewModel) {
    LaunchedEffect(Unit) { vm.load() }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::pickLogo) }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::pickIcon) }
    val imgOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        vm.notice?.let { n ->
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n, modifier = Modifier.weight(1f), fontSize = 14.sp)
                androidx.compose.material3.TextButton(onClick = { vm.notice = null }) { Text("✕", color = C.Gray) }
            }
        }
        vm.loadError?.let { e ->
            InfoCard {
                Text("⚠️ $e", color = C.Red)
                Spacer(Modifier.height(8.dp))
                AppBtn("আবার চেষ্টা করুন", color = C.Gray, small = true, onClick = { vm.load(force = true) })
            }
        }
        if (!vm.loaded && vm.loadError == null) InfoCard { Text("লোড হচ্ছে...", color = C.Muted) }

        if (vm.loaded) {
            // ── লোগো ও আইকন ──
            InfoCard {
                Text("🏷️ লোগো ও সাইটের তথ্য", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Hint("লোগো আর শিরোনাম দেখে অ্যাপ নিজে জায়গা ও আকার ঠিক করবে। স্বচ্ছ ব্যাকগ্রাউন্ডের PNG সবচেয়ে ভালো।", Modifier.padding(top = 4.dp))

                FieldLabel("লম্বা/আড়াআড়ি লোগো")
                if (vm.logo.isNotBlank()) {
                    DataImage(vm.logo, Modifier.fillMaxWidth().height(90.dp).background(Color(0xFFE9E9E9), RoundedCornerShape(8.dp)).padding(6.dp))
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppBtn(if (vm.logo.isBlank()) "📁 লোগো বাছুন" else "📁 বদলান", color = C.Blue, small = true, onClick = { pickLogo.launch(imgOnly) })
                    if (vm.logo.isNotBlank()) AppBtn("✕ লোগো সরান", color = C.Gray, small = true, onClick = { vm.clearLogo() })
                }

                FieldLabel("স্কোয়ার/রাউন্ড আইকন")
                if (vm.icon.isNotBlank()) {
                    val shape = if (vm.iconShape == "round") CircleShape else RoundedCornerShape(6.dp)
                    DataImage(vm.icon, Modifier.size(84.dp).clip(shape).background(Color(0xFFE9E9E9)), ContentScale.Crop)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppBtn(if (vm.icon.isBlank()) "📁 আইকন বাছুন" else "📁 বদলান", color = C.Blue, small = true, onClick = { pickIcon.launch(imgOnly) })
                    if (vm.icon.isNotBlank()) AppBtn("✕ আইকন সরান", color = C.Gray, small = true, onClick = { vm.clearIcon() })
                }

                FieldLabel("আইকনের আকৃতি")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.iconShape == "round", onClick = { vm.iconShape = "round" }, label = { Text("গোল (রাউন্ড)") })
                    FilterChip(selected = vm.iconShape == "square", onClick = { vm.iconShape = "square" }, label = { Text("স্কোয়ার") })
                }
                Hint("কোন টেমপ্লেটে লোগো, আইকন বা সাইটের নাম বসবে আর সাইজ কত হবে, সেটা ফোটোকার্ড অংশে ঠিক করা যাবে (পরের ধাপে)।", Modifier.padding(top = 6.dp))

                FieldLabel("সাইটের নাম (কার্ডের ছবি ও সাদা অংশের মাঝখানে বসবে; লোগো না থাকলে নিচেও এটাই)")
                AppField(vm.siteName, { vm.siteName = it })
                FieldLabel("সাইটের ট্যাগলাইন (ভিডিওর নাম/পদবির বারে পদবির জায়গায় বসবে)")
                AppField(vm.siteTagline, { vm.siteTagline = it }, placeholder = "যেমন: সত্য প্রকাশে আমরা সবসময়")
                FieldLabel("ওয়েবসাইট (যেমন prothomalo.com)")
                AppField(vm.siteUrl, { vm.siteUrl = it }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
                FieldLabel("ক্যাটাগরি লেখা (তারিখের পাশে)")
                AppField(vm.category, { vm.category = it })
                FieldLabel("ছবির ট্যাগ (খালি রাখলে দেখাবে না)")
                AppField(vm.photoTag, { vm.photoTag = it })
                FieldLabel("নিচের লেখা (খালি রাখলে দেখাবে না)")
                AppField(vm.ctaText, { vm.ctaText = it })
            }

            // ── ফিচার ইমেজ ──
            InfoCard {
                Text("🖼️ ওয়ার্ডপ্রেস ফিচার ইমেজ", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Hint("নতুন পোস্টে যে ছবি বেছে ফোটোকার্ড বানাবেন, সেই ছবিটাই ওয়ার্ডপ্রেস পোস্টের ফিচার ইমেজ হয় (ফোটোকার্ড নয়, শুধু ছবিটা)। এখানে তার মাপ ঠিক করুন।", Modifier.padding(top = 4.dp))
                FieldLabel("মাপ")
                FI_SIZES.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.fiSize = v }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (vm.fiSize == v) "◉" else "○", color = if (vm.fiSize == v) C.Red else C.Muted, modifier = Modifier.padding(end = 10.dp))
                        Text(label, fontWeight = if (vm.fiSize == v) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                if (vm.fiSize == "custom") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            FieldLabel("প্রস্থ (px)")
                            AppField(vm.fiW, { vm.fiW = it.filter(Char::isDigit).take(4) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        Column(Modifier.weight(1f)) {
                            FieldLabel("উচ্চতা (px)")
                            AppField(vm.fiH, { vm.fiH = it.filter(Char::isDigit).take(4) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                }
                if (vm.fiSize != "original") {
                    FieldLabel("ছবির অনুপাত না মিললে")
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.fiFit = "stretch" }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (vm.fiFit == "stretch") "◉" else "○", color = if (vm.fiFit == "stretch") C.Red else C.Muted, modifier = Modifier.padding(end = 10.dp))
                        Text("ক্রপ ছাড়া: ছবি চ্যাপ্টা বা লম্বা হয়ে পুরো মাপে বসবে", modifier = Modifier.weight(1f))
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.fiFit = "cover" }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (vm.fiFit == "cover") "◉" else "○", color = if (vm.fiFit == "cover") C.Red else C.Muted, modifier = Modifier.padding(end = 10.dp))
                        Text("ক্রপ করে পুরো মাপ ভরবে (ছবি বিকৃত হবে না)", modifier = Modifier.weight(1f))
                    }
                }
                Hint(
                    if (vm.fiSize == "original") "ফিচার ইমেজ: আসল ছবি, ক্রপ ছাড়া (বেছে নেওয়া ছবির মাপেই, সর্বোচ্চ ১৬০০ px)"
                    else "ফিচার ইমেজ: " + (if (vm.fiSize == "custom") "${vm.fiW}×${vm.fiH}" else vm.fiSize.replace("x", "×")) + " px" +
                        if (vm.fiFit == "cover") " (দরকারে ক্রপ হবে)" else " (ক্রপ ছাড়া, ছবি চ্যাপ্টা/লম্বা হতে পারে)",
                    Modifier.padding(top = 6.dp),
                )
            }

            AppBtn(if (vm.busy) "অপেক্ষা করুন..." else "💾 সেটিংস সেভ করুন", color = C.Green, enabled = !vm.busy, onClick = { vm.save() })
            Spacer(Modifier.height(20.dp))
        }
    }
}
