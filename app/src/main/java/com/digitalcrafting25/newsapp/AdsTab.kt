package com.digitalcrafting25.newsapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun AdPreview(ad: Ad, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(null, ad) {
        value = withContext(Dispatchers.Default) { runCatching { AdRender.render(ad).asImageBitmap() }.getOrNull() }
    }
    Box(modifier.fillMaxWidth().background(Color(0xFFEEEEEE), RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))) {
        val b = bmp
        if (b != null && ad.ok) Image(b, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        else Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) { Text("এখনও কোনো ব্যানার নেই", color = Color(0xFF999999), fontSize = 12.sp) }
    }
}

private val Presets = listOf("#c2185b", "#8e0e4b", "#c62828", "#e65100", "#1565c0", "#00695c", "#2e7d32", "#4527a0", "#212121", "#ffffff", "#ffd400")

private fun parseHex(s: String): Color? = runCatching { Color(android.graphics.Color.parseColor(s.trim())) }.getOrNull()

@Composable
private fun ColorRow(label: String, value: String, onChange: (String) -> Unit) {
    FieldLabel(label)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(parseHex(value) ?: Color.LightGray).border(1.dp, C.Border, RoundedCornerShape(8.dp)))
        Box(Modifier.weight(1f)) { AppField(value, { onChange(it.take(9)) }, placeholder = "#rrggbb") }
    }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Presets.forEach { p ->
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(parseHex(p) ?: Color.Gray)
                    .border(1.dp, C.Border, CircleShape).clickable { onChange(p) },
            )
        }
    }
}

@Composable
fun AdsTab(vm: AdsViewModel) {
    LaunchedEffect(Unit) { vm.load() }
    val pickBanner = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::pickBanner) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::pickLogo) }
    val imgOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    var delIdx by remember { mutableStateOf(-1) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        vm.notice?.let { n ->
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n, modifier = Modifier.weight(1f), fontSize = 14.sp)
                TextButton(onClick = { vm.notice = null }) { Text("✕", color = C.Gray) }
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
            InfoCard {
                Text("📢 বিজ্ঞাপন সেকশন", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "ফোটোকার্ডের একদম নিচে বিজ্ঞাপন ব্যানার বসবে। সর্বোচ্চ ১০টি বিজ্ঞাপন সেভ করে রাখা যায়। প্রতিটি নতুন কার্ড পাবলিশের সময় এই বিজ্ঞাপনগুলো থেকে একটির পর একটি অটো বসবে (একটা কার্ডে একটাই বিজ্ঞাপন)।",
                    color = C.Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                val nx = vm.next()
                if (vm.ads.isEmpty()) Text("এখনও কোনো বিজ্ঞাপন সেভ করা নেই।", color = C.Muted, fontSize = 13.sp)
                vm.ads.forEachIndexed { i, a ->
                    val editing = i == vm.editIdx
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp)
                            .border(1.dp, if (editing) C.Red else C.Line, RoundedCornerShape(10.dp)).padding(10.dp),
                    ) {
                        Text(
                            "বিজ্ঞাপন ${Bn.num(i + 1)}" + (if (!a.ok) " (ফাঁকা)" else "") + (if (a.ok && nx?.id == a.id) " — পরের কার্ডে বসবে" else ""),
                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        AdPreview(a)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppBtn(if (editing) "✏️ সম্পাদনা চলছে" else "✏️ সম্পাদনা", color = C.Gray, small = true, onClick = { vm.startEdit(i) })
                            AppBtn("🗑️ মুছুন", color = C.Gray, small = true, onClick = { delIdx = i })
                        }
                    }
                }
                AppBtn("➕ নতুন বিজ্ঞাপন", color = C.Green, enabled = vm.ads.size < AD_MAX, onClick = { vm.newAd() })
                Text(
                    "📌 সেভ করা: ${Bn.num(vm.ads.size)}/${Bn.num(AD_MAX)}টি · পরের কার্ডে বসবে: " +
                        (nx?.let { n -> "বিজ্ঞাপন ${Bn.num(vm.ads.indexOfFirst { it.id == n.id } + 1)}" } ?: "কোনোটা না"),
                    color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }

            vm.current?.let { a ->
                InfoCard {
                    Text("✏️ বিজ্ঞাপন ${Bn.num(vm.editIdx + 1)} সম্পাদনা", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = a.mode == "upload", onClick = { vm.setMode("upload") }, label = { Text("📁 ব্যানার আপলোড") })
                        FilterChip(selected = a.mode == "build", onClick = { vm.setMode("build") }, label = { Text("🛠️ অ্যাপে বানান") })
                    }
                    if (a.mode == "upload") {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppBtn(if (a.img.isBlank()) "📁 ছবি বাছুন" else "📁 বদলান", color = C.Blue, small = true, onClick = { pickBanner.launch(imgOnly) })
                            if (a.img.isNotBlank()) AppBtn("✕ ব্যানার সরান", color = C.Gray, small = true, onClick = { vm.clearImg() })
                        }
                        Text("ছবির মাপ ঠিক ১০৮০×১২০ না হলে নেওয়া হবে না।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        FieldLabel("বড় লেখা (সাদা)")
                        AppField(a.t1, vm::setT1, placeholder = "পেমেন্ট মানেই বিকাশ")
                        FieldLabel("হাইলাইট লেখা (হলুদ)")
                        AppField(a.t2, vm::setT2, placeholder = "৮,০০০")
                        FieldLabel("ডানের ছোট লেখা (দুই লাইন করতে | দিন)")
                        AppField(a.t3, vm::setT3, placeholder = "টাকার|বেশি অফার*")
                        ColorRow("ব্যাকগ্রাউন্ড ১", a.bg1, vm::setBg1)
                        ColorRow("ব্যাকগ্রাউন্ড ২", a.bg2, vm::setBg2)
                        ColorRow("লেখার রঙ", a.c1, vm::setC1)
                        ColorRow("হাইলাইটের রঙ", a.c2, vm::setC2)
                        FieldLabel("ডান পাশের লোগো (ঐচ্ছিক, যেকোনো মাপ)")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppBtn(if (a.logo.isBlank()) "📁 লোগো বাছুন" else "📁 বদলান", color = C.Blue, small = true, onClick = { pickLogo.launch(imgOnly) })
                            if (a.logo.isNotBlank()) AppBtn("✕ লোগো সরান", color = C.Gray, small = true, onClick = { vm.clearLogo() })
                        }
                    }
                    Text("প্রিভিউ:", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    AdPreview(a)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppBtn("✔ হয়েছে", color = C.Green, small = true, onClick = { vm.done() })
                        AppBtn("🗑️ মুছুন", color = C.Gray, small = true, onClick = { delIdx = vm.editIdx })
                    }
                }
            }

            if (vm.dirty) Text("⚠️ বদল সেভ করা হয়নি", color = C.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            AppBtn(if (vm.busy) "অপেক্ষা করুন..." else "💾 বিজ্ঞাপন সেভ করুন", color = C.Green, enabled = !vm.busy && vm.dirty, onClick = { vm.save() })
            Spacer(Modifier.height(20.dp))
        }
    }

    if (delIdx >= 0) {
        AlertDialog(
            onDismissRequest = { delIdx = -1 },
            text = { Text("বিজ্ঞাপন ${Bn.num(delIdx + 1)} মুছে ফেলবেন?") },
            confirmButton = { TextButton(onClick = { vm.remove(delIdx); delIdx = -1 }) { Text("হ্যাঁ", color = C.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { delIdx = -1 }) { Text("না") } },
        )
    }
}
