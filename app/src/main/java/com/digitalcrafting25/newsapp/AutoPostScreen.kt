package com.digitalcrafting25.newsapp

import android.app.TimePickerDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 🌅 অটো "শুভ সকাল" পোস্ট — ওয়েবের autoCard (সেটিংসের একটা ট্যাব) */
@Composable
fun AutoPostTab(vm: AutoPostViewModel) {
    val ctx = LocalContext.current
    var confirmNow by remember { mutableStateOf(false) }
    val busy = vm.busy != null

    LaunchedEffect(Unit) { vm.load() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard {
            Text("🌅 অটো \"শুভ সকাল\" পোস্ট", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "প্রতিদিন নির্ধারিত সময়ে নিজে থেকে ফোটোকার্ড বানিয়ে Facebook পেইজে পোস্ট হবে। রংপুরের আবহাওয়া অনুযায়ী ছবি বসবে (বৃষ্টিতে বৃষ্টির, শীতে শীতের ছবি)। রাশি AI বাছাই করবে, আর ক্যাপশন নিজে তৈরি হবে। Facebook সংযোগ \"🔑 সংযোগ\" ট্যাবে সেভ করা থাকতে হবে।",
                color = C.Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp),
            )
            when {
                vm.busy == "load" && vm.status == null -> Note("লোড হচ্ছে...", C.Muted)
                vm.missing -> Box2(
                    "⚠️ Worker-এ অটো-পোস্টের রুট (/auto-get, /auto-save, /auto-preview, /auto-now) পাওয়া যায়নি। " +
                        "যে Worker ফাইল ডেপ্লয় আছে তাতে এই রুটগুলো যোগ করতে হবে।",
                )
                vm.loadErr != null -> Box2("⚠️ ${vm.loadErr}")
                vm.status != null -> vm.status!!.let { (ok, t) -> Box2(t, ok) }
            }
        }

        if (!vm.missing && vm.loadErr == null && vm.status != null) {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = vm.on, onCheckedChange = { vm.on = it })
                    Text("প্রতিদিন অটো পোস্ট চালু", fontWeight = FontWeight.Bold)
                }

                FieldLabel("পোস্টের সময় (বাংলাদেশ সময়)")
                OutlinedButton(onClick = {
                    val (h, m) = parseHm(vm.time)
                    TimePickerDialog(ctx, { _, hh, mm -> vm.time = "%02d:%02d".format(hh, mm) }, h, m, false).show()
                }) { Text("🕒 ${vm.time}", fontSize = 16.sp) }

                FieldLabel("পরিষ্কার দিনের ছবি")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AUTO_TPLS.forEach { (id, label) ->
                        FilterChip(selected = vm.tpl == id, onClick = { vm.tpl = id }, label = { Text(label) })
                    }
                }

                FieldLabel("কার্ডের নিচে সাইটের নাম")
                AppField(
                    vm.siteUrl, { vm.siteUrl = it }, placeholder = "sirsonews24.com",
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )

                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = vm.rashi, onCheckedChange = { vm.rashi = it })
                    Text("কার্ডে ৩টি রাশি (AI বাছাই) দেখান", fontSize = 14.sp)
                }

                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppBtn("💾 সেভ", color = C.Green, small = true, enabled = !busy, onClick = { vm.save() })
                    AppBtn("👁️ প্রিভিউ", color = C.Gray, small = true, enabled = !busy, onClick = { vm.preview() })
                    AppBtn("🚀 এখনই পোস্ট", color = C.Gray, small = true, enabled = !busy, onClick = { confirmNow = true })
                }
                Text("প্রিভিউতে পোস্ট হয় না।", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

                vm.msg?.let { (ok, t) ->
                    Text(
                        t, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                        color = when (ok) { true -> C.Green; false -> C.Red; null -> C.Label },
                    )
                }
                vm.previewBmp?.let { bmp ->
                    Image(
                        bmp.asImageBitmap(), null,
                        Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                    if (vm.previewTitle.isNotBlank()) {
                        Text(vm.previewTitle, color = Color(0xFF444444), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        } else if (vm.busy != "load") {
            AppBtn("🔄 আবার চেষ্টা করুন", color = C.Gray, onClick = { vm.load(force = true) })
        }
    }

    if (confirmNow) {
        AlertDialog(
            onDismissRequest = { confirmNow = false },
            text = { Text("এখনই আজকের শুভ সকাল কার্ড Facebook-এ পোস্ট হবে। চালাবেন?") },
            confirmButton = { TextButton(onClick = { confirmNow = false; vm.postNow() }) { Text("হ্যাঁ", color = C.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmNow = false }) { Text("না") } },
        )
    }
    vm.askAgain?.let { t ->
        AlertDialog(
            onDismissRequest = { vm.dismissAgain() },
            text = { Text("$t\n\nআবার পোস্ট করবেন?") },
            confirmButton = { TextButton(onClick = { vm.postNow(again = true) }) { Text("হ্যাঁ", color = C.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { vm.dismissAgain() }) { Text("না") } },
        )
    }
}

private fun parseHm(s: String): Pair<Int, Int> {
    val p = s.split(":")
    val h = p.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 6
    val m = p.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30
    return h to m
}

@Composable
private fun Note(text: String, color: Color) =
    Text(text, color = color, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Box2(text: String, ok: Boolean = false) {
    Text(
        text,
        color = if (ok) C.Green else C.Red,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
            .background(if (ok) Color(0xFFE8F5E9) else Color(0xFFFDECEA), RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}
