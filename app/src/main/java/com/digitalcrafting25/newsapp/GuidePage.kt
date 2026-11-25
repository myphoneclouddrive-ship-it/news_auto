package com.digitalcrafting25.newsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** গাইডের একটা ধাপ: শিরোনাম, লেখা, কপি করার মতো কোড/মান, ক্লিক করে খোলার লিংক, সতর্কতা */
internal class GStep(
    val title: String,
    val lines: List<String> = emptyList(),
    val code: List<String> = emptyList(),
    val links: List<Pair<String, String>> = emptyList(),
    val note: String? = null,
)

/** একটা পূর্ণ গাইড-পেইজ */
internal class GDoc(
    val title: String,
    val intro: String = "",
    val needs: List<String> = emptyList(),
    val steps: List<GStep> = emptyList(),
    val plain: List<String> = emptyList(),
    val problems: List<Pair<String, String>> = emptyList(),
    val done: String? = null,
)

private val BnDigits = "০১২৩৪৫৬৭৮৯"
internal fun bn(n: Int): String = n.toString().map { BnDigits[it - '0'] }.joinToString("")

private val WarnBg = Color(0xFFFFF4E0)
private val WarnFg = Color(0xFF8A5300)
private val InfoBg = Color(0xFFEAF2FD)
private val OkBg = Color(0xFFE8F5E9)

/** সেটিংসে ছোট লিংক-বাটন। চাপলে নিচে না খুলে আলাদা ফুল পেইজে গাইড খোলে। ফোনের Back চাপলে ফিরে আসে। */
@Composable
internal fun GuideLink(label: String, doc: GDoc) {
    var open by rememberSaveable(doc.title) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp)
            .background(InfoBg, RoundedCornerShape(8.dp))
            .clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = C.Blue, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("›", color = C.Blue, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = C.Bg) { GuidePage(doc) { open = false } }
        }
    }
}

@Composable
private fun GuidePage(doc: GDoc, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "← পেছনে", color = C.Blue, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.clickable { onClose() }.padding(10.dp),
            )
            Text(doc.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(doc.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (doc.intro.isNotBlank()) Text(doc.intro, fontSize = 14.sp, lineHeight = 21.sp, color = Color(0xFF333333))

            if (doc.needs.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(OkBg, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("✅ শুরুর আগে যা যা লাগবে", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.Green)
                    doc.needs.forEach { Text("• $it", fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF333333)) }
                }
            }

            doc.steps.forEachIndexed { i, st ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).background(C.Red, CircleShape), contentAlignment = Alignment.Center) {
                                Text(bn(i + 1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Text(st.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        }
                        st.lines.forEach { Text(it, fontSize = 14.sp, lineHeight = 21.sp, color = Color(0xFF333333)) }
                        if (st.code.isNotEmpty()) {
                            SelectionContainer {
                                Column(
                                    Modifier.fillMaxWidth().background(Color(0xFFF1F3F5), RoundedCornerShape(8.dp)).padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    st.code.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp) }
                                }
                            }
                            Text("(উপরের লেখায় চেপে ধরে কপি করতে পারবেন)", fontSize = 11.sp, color = C.Muted)
                        }
                        st.links.forEach { (label, url) ->
                            Row(
                                Modifier.fillMaxWidth().background(InfoBg, RoundedCornerShape(8.dp))
                                    .clickable { try { uri.openUri(url) } catch (e: Exception) {} }
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("🔗 $label", color = C.Blue, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text("↗", color = C.Blue, fontWeight = FontWeight.Bold)
                            }
                        }
                        st.note?.let {
                            Text(
                                "⚠️ $it", fontSize = 13.sp, lineHeight = 19.sp, color = WarnFg,
                                modifier = Modifier.fillMaxWidth().background(WarnBg, RoundedCornerShape(8.dp)).padding(10.dp),
                            )
                        }
                    }
                }
            }

            doc.plain.forEach { Text(it, fontSize = 14.sp, lineHeight = 21.sp, color = Color(0xFF333333)) }

            if (doc.problems.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(WarnBg, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("🛠️ সমস্যা হলে", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = WarnFg)
                    doc.problems.forEach { (q, a) ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("❓ $q", fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 19.sp)
                            Text(a, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF333333))
                        }
                    }
                }
            }
            doc.done?.let { Text("🎉 $it", fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = C.Green) }

            Text(
                "← পেছনে যেতে উপরের বাটন বা ফোনের Back চাপুন", fontSize = 12.sp, color = C.Muted,
                modifier = Modifier.fillMaxWidth().clickable { onClose() }.padding(vertical = 16.dp),
            )
        }
    }
}
