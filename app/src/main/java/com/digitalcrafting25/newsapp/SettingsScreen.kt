package com.digitalcrafting25.newsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class SetTab(val label: String) {
    SITE("🏷️ সাইট"),
    ADS("📢 বিজ্ঞাপন"),
    CONN("🔑 সংযোগ"),
    AI("🤖 AI কী"),
    UPDATE("🔄 আপডেট"),
}

private val Amber = Color(0xFFB26A00)

/** "হ্যাঁ/না" নিশ্চিতকরণ (ওয়েবের confirm()) */
private class Ask(val text: String, val onYes: () -> Unit)

@Composable
fun SettingsScreen(vm: SettingsViewModel, site: SiteViewModel, ads: AdsViewModel, uv: UpdateViewModel) {
    var tab by rememberSaveable { mutableStateOf(SetTab.SITE.name) }
    var ask by remember { mutableStateOf<Ask?>(null) }

    LaunchedEffect(Unit) {
        vm.loadCreds()
        vm.loadAi()
    }

    // অ্যাডমিন না হলে AI কী ট্যাবই দেখায় না (ওয়েবে কার্ড লুকানো থাকে)
    val tabs = SetTab.entries.filter { it != SetTab.AI || vm.aiOwner == true }
    val cur = tabs.firstOrNull { it.name == tab } ?: SetTab.SITE

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
            H1("⚙️ সেটিংস")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEach { t ->
                    FilterChip(selected = t == cur, onClick = { tab = t.name }, label = { Text(t.label) })
                }
            }
        }
        vm.notice?.let { n ->
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(10.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(n, modifier = Modifier.weight(1f), fontSize = 14.sp)
                TextButton(onClick = { vm.notice = null }) { Text("✕", color = C.Gray) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (cur) {
                SetTab.SITE -> SiteTab(site)
                SetTab.ADS -> AdsTab(ads)
                SetTab.CONN -> ConnTab(vm) { ask = it }
                SetTab.AI -> AiTab(vm) { ask = it }
                SetTab.UPDATE -> UpdateScreen(uv)
            }
        }
    }

    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { ask = null },
            text = { Text(a.text) },
            confirmButton = { TextButton(onClick = { ask = null; a.onYes() }) { Text("হ্যাঁ", color = C.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("না") } },
        )
    }
}

// ───────────────────────── ছোট সাহায্যকারী ─────────────────────────

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Text(
        text,
        color = if (ok) C.Green else C.Red,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth()
            .background(if (ok) Color(0xFFE8F5E9) else Color(0xFFFDECEA), RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}

@Composable
private fun Small(text: String, modifier: Modifier = Modifier, color: Color = C.Muted) =
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp, modifier = modifier)

/** ওয়েবের <details class="guide"> */
@Composable
private fun Guide(title: String, lines: List<String>) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            (if (open) "▾ " else "▸ ") + title,
            color = C.Blue, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 6.dp),
        )
        if (open) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFFF7F8FA), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                lines.forEach { l -> Text(l, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF333333)) }
            }
        }
    }
}

// ───────────────────────── সংযোগ: WordPress ও Facebook ─────────────────────────

@Composable
private fun ConnTab(vm: SettingsViewModel, ask: (Ask) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard {
            Text("🔑 WordPress ও Facebook সংযোগ", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Small("এখানে নিজের সাইট ও পেইজের তথ্য দিন। তথ্যগুলো Worker-এ এনক্রিপ্ট করে থাকে, অ্যাপে আর ফেরত আসে না। এই সংযোগ শুধু আপনার লগইন অ্যাকাউন্টের।", Modifier.padding(top = 4.dp))
            vm.credsErr?.let { Spacer(Modifier.height(8.dp)); StatusLine(false, "⚠️ $it") }
            if (!vm.kvOk) { Spacer(Modifier.height(8.dp)); StatusLine(false, "⚠️ Worker-এ KV (SCHEDULE) বাইন্ডিং নেই, তাই এখান থেকে সেভ হবে না") }
        }

        // ── WordPress ──
        InfoCard {
            Text("🌐 WordPress", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            StatusLine(
                vm.wpOk(),
                if (vm.wpOk()) "✅ সেট করা আছে" + (if (vm.credEnv(listOf("WP_URL", "WP_USER", "WP_APP_PASS"))) " (কিছু তথ্য Worker ভ্যারিয়েবল থেকে)" else "")
                else "❌ সম্পূর্ণ সেট করা নেই",
            )
            FieldLabel("সাইটের URL")
            AppField(vm.wpUrl, { vm.wpUrl = it }, placeholder = "https://আপনারসাইট.com", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
            FieldLabel("ইউজারনেম")
            AppField(vm.wpUser, { vm.wpUser = it }, placeholder = "WordPress লগইন ইউজারনেম")
            FieldLabel("অ্যাপ্লিকেশন পাসওয়ার্ড")
            AppField(
                vm.wpPass, { vm.wpPass = it },
                placeholder = vm.secretHint("WP_APP_PASS")?.let { "$it (বদলাতে চাইলে নতুনটা বসান)" } ?: "xxxx xxxx xxxx xxxx xxxx xxxx",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                visual = PasswordVisualTransformation(),
            )
            Guide(
                "📖 এগুলো কোথায় পাবেন (ধাপে ধাপে)",
                listOf(
                    "১. আপনার WordPress ড্যাশবোর্ডে (আপনারসাইট.com/wp-admin) লগইন করুন।",
                    "২. বাঁ মেনু থেকে Users → Profile (নিজের ইউজার) খুলুন।",
                    "৩. একদম নিচে Application Passwords অংশে যেকোনো নাম দিন (যেমন News App), তারপর Add New Application Password চাপুন।",
                    "৪. যে পাসওয়ার্ডটি দেখাবে (স্পেসসহ ২৪ অক্ষর) সঙ্গে সঙ্গে কপি করে উপরের ঘরে বসান। এটা দ্বিতীয়বার দেখাবে না।",
                    "৫. ইউজারনেম হলো ওই প্রোফাইলের Username ঘরের লেখা, এটা আপনার লগইন পাসওয়ার্ড নয়।",
                    "৬. সাইটের URL https:// দিয়ে লিখুন, শেষে / লাগবে না।",
                    "সমস্যা হলে: Application Passwords অংশ না দেখালে সাইটে HTTPS চালু আছে কিনা দেখুন; কিছু সিকিউরিটি প্লাগিনও এটা বন্ধ রাখে। ইউজারের রোল Author, Editor বা Administrator হতে হবে। \"টেস্ট করুন\" চাপলে ভুল কোথায় তা বলে দেবে।",
                ),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn("💾 সেভ", color = C.Green, small = true, enabled = vm.busy == null, onClick = { vm.saveCreds() })
                AppBtn("🔌 টেস্ট করুন", color = C.Gray, small = true, enabled = vm.busy == null, onClick = { vm.testCred("wp") })
                AppBtn("🗑️ মুছুন", color = C.Gray, small = true, enabled = vm.busy == null, onClick = {
                    ask(Ask("WordPress-এর সেভ করা তথ্য মুছে ফেলবেন?") { vm.clearCreds("wp") })
                })
            }
            vm.wpMsg?.let { (ok, t) -> Text(t, color = if (ok) C.Green else C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }

        // ── Facebook ──
        InfoCard {
            Text("📘 Facebook পেইজ", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            StatusLine(
                vm.fbOk(),
                if (vm.fbOk()) "✅ সেট করা আছে" + (if (vm.credEnv(listOf("FB_PAGE_ID", "FB_TOKEN"))) " (কিছু তথ্য Worker ভ্যারিয়েবল থেকে)" else "")
                else "❌ সম্পূর্ণ সেট করা নেই",
            )
            FieldLabel("Page ID")
            AppField(vm.fbPage, { vm.fbPage = it }, placeholder = "শুধু সংখ্যা, যেমন 1234567890", keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            FieldLabel("Page Access Token")
            AppField(
                vm.fbToken, { vm.fbToken = it },
                placeholder = vm.secretHint("FB_TOKEN")?.let { "$it (বদলাতে চাইলে নতুনটা বসান)" } ?: "EAAB...",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                visual = PasswordVisualTransformation(),
            )
            Guide(
                "📖 Page ID ও টোকেন কীভাবে পাবেন (সংক্ষেপে)",
                listOf(
                    "১. developers.facebook.com-এ লগইন করুন (অ্যাকাউন্টটি ওই পেইজের অ্যাডমিন হতে হবে)।",
                    "২. My Apps → Create App। Use cases-এ Manage everything on your Page বেছে অ্যাপ বানান।",
                    "৩. Tools → Graph API Explorer খুলে ডানে আপনার অ্যাপটি বেছে নিন।",
                    "৪. শুধু এই চারটা পারমিশন দিন: pages_show_list, pages_read_engagement, pages_manage_posts, pages_manage_engagement।",
                    "৫. Generate Access Token চেপে পপআপে আপনার পেইজটি টিক দিয়ে অনুমতি দিন (পেইজ টিক না দিলে me/accounts ফাঁকা আসবে)।",
                    "৬. টোকেন স্থায়ী করতে Access Token Debugger-এ গিয়ে Extend Access Token করুন, তারপর Graph API Explorer-এ ওই লম্বা টোকেন বসিয়ে me/accounts লিখে Submit চাপুন।",
                    "৭. ফলাফলে আপনার পেইজের access_token হলো Page Access Token, আর id হলো Page ID। দুটো উপরের ঘরে বসিয়ে সেভ করুন।",
                    "মনে রাখুন: অ্যাপ Live মোডে না থাকলে পোস্ট সবাই দেখতে পায় না (App Settings → Basic-এ Privacy Policy URL দিয়ে Live করুন)। পাসওয়ার্ড বদলালে বা পেইজের অ্যাডমিন থেকে সরে গেলে টোকেন বাতিল হয়। টোকেন কারও সাথে শেয়ার করবেন না।",
                ),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn("💾 সেভ", color = C.Green, small = true, enabled = vm.busy == null, onClick = { vm.saveCreds() })
                AppBtn("🔌 টেস্ট করুন", color = C.Gray, small = true, enabled = vm.busy == null, onClick = { vm.testCred("fb") })
                AppBtn("🗑️ মুছুন", color = C.Gray, small = true, enabled = vm.busy == null, onClick = {
                    ask(Ask("Facebook-এর সেভ করা তথ্য মুছে ফেলবেন?") { vm.clearCreds("fb") })
                })
            }
            vm.fbMsg?.let { (ok, t) -> Text(t, color = if (ok) C.Green else C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }

    }
}

// ───────────────────────── AI কী ─────────────────────────

private fun statLine(c: KeyCheck?): Pair<Color, String> = when (c?.state) {
    null -> C.Muted to "চেক করা হয়নি"
    "checking" -> C.Muted to "⏳ চেক হচ্ছে..."
    "active" -> C.Green to "✅ সক্রিয় (কাজ করছে)"
    "quota" -> Amber to ("⚠️ কী ঠিক আছে, কিন্তু এখন কোটা/ব্যালেন্স/লিমিট শেষ" + if (c.msg.isNotBlank()) " — ${c.msg}" else "")
    "invalid" -> C.Red to ("❌ কাজ করছে না" + if (c.msg.isNotBlank()) ": ${c.msg}" else "")
    else -> Amber to ("⚠️ চেক করা যায়নি" + if (c.msg.isNotBlank()) ": ${c.msg}" else "")
}

@Composable
private fun AiTab(vm: SettingsViewModel, ask: (Ask) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard {
            Text("🤖 AI কী (সবার জন্য)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Small("এখানে দেওয়া Gemini/Groq/Cloudflare/Claude কী অ্যাপের সব ইউজারের জন্য কাজ করবে। শুধু অ্যাডমিন এটা দেখতে ও বদলাতে পারে।", Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            if (vm.aiStatus.isBlank()) Small("লোড হচ্ছে...") else StatusLine(vm.aiStatusOk, vm.aiStatus)
        }

        AiProv.entries.forEach { p ->
            InfoCard {
                Text(p.title, fontWeight = FontWeight.Bold)
                if (p == AiProv.GEMINI && vm.geminiModels.isNotBlank()) Small(vm.geminiModels, Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                val list = vm.aiKeys[p.id].orEmpty()
                if (list.isEmpty()) Small("কোনো ${p.label} কী নেই। নিচে কী বসিয়ে Add চাপুন।")
                list.forEachIndexed { i, k ->
                    val chk = vm.aiChecks["${p.id}:${k.id}"]
                    val (col, txt) = if (k.cooling && chk == null) C.Muted to "" else statLine(chk)
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${Bn.num(i + 1)}.", color = C.Muted, modifier = Modifier.padding(end = 6.dp))
                            Text(k.hint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Small(if (k.fromEnv) "  Worker সিক্রেট" else "  অ্যাপ", color = C.Muted)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.checkKey(p, k.id) }, enabled = chk?.state != "checking") { Text("✅") }
                            TextButton(
                                onClick = { ask(Ask("এই ${p.label} কী (${k.hint}) মুছে ফেলবেন?") { vm.deleteKey(p, k.id) }) },
                                enabled = !k.fromEnv && vm.busy == null,
                            ) { Text("🗑️") }
                        }
                        if (txt.isNotBlank()) Text(txt, color = col, fontSize = 12.sp, lineHeight = 17.sp)
                        if (k.usage.isNotBlank()) Small(k.usage, Modifier.padding(top = 2.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                AppField(
                    vm.aiInputs[p.id].orEmpty(), { vm.aiInputs[p.id] = it },
                    placeholder = p.placeholder, singleLine = false,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppBtn("➕ Add", color = C.Green, small = true, enabled = vm.busy == null, onClick = { vm.addKeys(p) })
                    AppBtn("✅ সব কী চেক করুন", color = C.Gray, small = true, enabled = vm.busy == null && list.isNotEmpty(), onClick = { vm.checkAll(p) })
                }
                if (p == AiProv.GROQ) {
                    FieldLabel("Groq মডেল (ঐচ্ছিক, ফাঁকা থাকলে llama-3.3-70b-versatile)")
                    AppField(vm.groqModel, { vm.groqModel = it })
                }
                if (p == AiProv.CF) {
                    FieldLabel("Cloudflare মডেল (ঐচ্ছিক, ফাঁকা থাকলে @cf/meta/llama-3.3-70b-instruct-fp8-fast)")
                    AppField(vm.cfModel, { vm.cfModel = it })
                }
            }
        }

        InfoCard {
            Guide(
                "📖 কী কোথায় পাবেন",
                listOf(
                    "Gemini: aistudio.google.com-এ লগইন করে Get API key → Create API key। কয়েকটা কী দিলে একটা ফেইল করলে পরেরটা চলবে।",
                    "Claude: console.anthropic.com → API Keys → Create Key। এটার জন্য কনসোলে ব্যালেন্স লাগে।",
                    "Groq: console.groq.com → API Keys → Create API Key (ফ্রি টিয়ার আছে, সীমা কম)।",
                    "Cloudflare: dash.cloudflare.com → AI → Workers AI থেকে Account ID নিন, আর My Profile → API Tokens থেকে Workers AI পারমিশনসহ টোকেন বানান। দুটো এক লাইনে লিখুন: ACCOUNT_ID:TOKEN",
                    "ক্রম: খবর নতুন করে লেখার সময় আগে Gemini, সব কী ব্যর্থ হলে Groq, তারপর Cloudflare, সবশেষে Claude। ছবি দেখার কাজ শুধু Gemini/Claude দিয়ে হয়।",
                    "Groq/Cloudflare-এর বাংলা লেখার মান Gemini-র চেয়ে কম হতে পারে, তাই পাবলিশের আগে পড়ে দেখুন।",
                    "কী কারও সাথে শেয়ার করবেন না। সেভ করার পর এখানে শুধু শেষ ৪ অক্ষর দেখাবে।",
                ),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppBtn("💾 মডেল সেভ", color = C.Green, small = true, enabled = vm.busy == null, onClick = { vm.saveAiModels() })
                AppBtn("🗑️ অ্যাপের সব AI কী মুছুন", color = C.Gray, small = true, enabled = vm.busy == null, onClick = {
                    ask(Ask("অ্যাপে সেভ করা সব AI কী (Gemini, Groq, Cloudflare, Claude, মডেল) মুছে ফেলবেন? একটা একটা করে মুছতে কী-এর পাশের 🗑️ চাপুন। (Worker সিক্রেট থাকলে সেটাই চলবে)") { vm.clearAllAi() })
                })
            }
        }
    }
}
