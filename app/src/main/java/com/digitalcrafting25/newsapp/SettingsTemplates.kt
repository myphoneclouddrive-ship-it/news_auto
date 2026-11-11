package com.digitalcrafting25.newsapp

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * ওয়েবের সেটিংসের "🖼️ ফোটোকার্ড টেমপ্লেট" কার্ড (`#tplGrid` + `#tplModal` + `#cbBuy`)।
 *
 *  • গ্রিড: প্রতিটা টেমপ্লেট নমুনা শিরোনাম, সেটিংসের লোগো/তথ্য ও আজকের তারিখসহ; বাছাইটায় ✔ ও লাল বর্ডার।
 *    শেষে ➕ কাস্টম টেমপ্লেট (১টা ফ্রি; এরপর ব্যালেন্স থেকে ৫০০ টাকায় কিনতে হয়)।
 *  • ডিফল্ট টেমপ্লেটে চাপলে বড় ভিউ (মোডাল): লোগো মোড, সাইজ, রঙ, ↺ ডিফল্ট, ✏️ লেআউট এডিট, ✔ বেছে নিন।
 *  • কাস্টম টেমপ্লেটে চাপলে সরাসরি লেআউট এডিটর।
 *  • বদল সেটিংসের নিচের "💾 সেটিংস সেভ করুন" দিয়ে সেভ হয় (ওয়েবের মতোই)।
 */

private val CB_PRICE: Int get() = Rates.get("cb")   // কাস্টম টেমপ্লেট স্লটের দাম (অ্যাডমিন অ্যাপের 💰 রেট)
private val TplBorder = Color(0xFFCCCCCC)
private val CustomRe = Regex("^custom\\d*$")

private val ModeOptions = listOf(
    "auto" to "স্বয়ংক্রিয় (যেটা আছে)",
    "wide" to "লম্বা/আড়াআড়ি লোগো",
    "icon" to "স্কোয়ার/রাউন্ড আইকন",
    "text" to "সাইটের নাম (লেখা)",
    "none" to "কিছুই না",
)

private val Palette = listOf(
    "#ffffff", "#000000", "#ffd400", "#ffe600", "#d50000", "#c62828", "#b30012", "#8e000d",
    "#1a3ea8", "#0d3b8e", "#08224f", "#1e1e6e", "#1c2d52", "#6a1bb1", "#2e7d32", "#f2f2f2",
    "#e2e2e2", "#dddddd", "#333333", "#222222",
)

private fun parseHex(h: String): Color = runCatching { Color(android.graphics.Color.parseColor(h)) }.getOrDefault(Color.White)

private fun copyJson(o: JSONObject?): JSONObject = if (o == null) JSONObject() else JSONObject(o.toString())

private fun tplName(id: String): String =
    cardTpls("photo").firstOrNull { it.first == id }?.second ?: id

@Composable
internal fun TplSettingsBody(np: NewPostViewModel, dash: DashboardViewModel) {
    val ver = np.tplVer   // টেমপ্লেট/স্লট বদলালে গ্রিড নতুন করে আঁকার জন্য
    var thumbs by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var big by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<String?>(null) }
    var buy by remember { mutableStateOf(false) }
    var topup by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    LaunchedEffect(Unit) { np.load() }
    LaunchedEffect(ver, np.loaded) {
        if (np.loaded) thumbs = runCatching { np.settingsThumbs(0.25f) }.getOrDefault(thumbs)
    }

    Small("একটা বেছে নিন। প্রিভিউতে আপনার লোগো, তথ্য ও আজকের তারিখ বসানো দেখাবে। পছন্দ না হলে শেষের ➕ কাস্টম টেমপ্লেট চেপে নিজের মতো করে বানিয়ে নিন।")

    if (!np.loaded) {
        Text(np.error ?: "লোড হচ্ছে...", fontSize = 13.sp, color = if (np.error != null) C.Red else C.Muted, modifier = Modifier.padding(top = 10.dp))
        return
    }

    val ids = cardTpls("photo").map { it.first }
    val current = TplRegistry.store.template.ifBlank { "dark" }
    val free = TplRegistry.canAddCustom()
    // গ্রিড: প্রতি সারিতে ৩টা (শেষে ➕ ঘর)
    val cells: List<String> = ids + "__add__"
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                row.forEach { id ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (id == "__add__") {
                            Column(
                                Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(8.dp)).background(Color.White)
                                    .drawBehind {
                                        drawRoundRect(TplBorder, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))))
                                    }
                                    .clickable { msg = null; if (free) edit = TplRegistry.nextCustomId() else buy = true }
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Text("➕", fontSize = 30.sp, color = C.Red)
                                if (free) Text("কাস্টম টেমপ্লেট\nনিজে বানান", fontSize = 12.sp, color = C.Red, textAlign = TextAlign.Center)
                                else {
                                    Text("আরেকটা কাস্টম টেমপ্লেট", fontSize = 12.sp, color = C.Red, textAlign = TextAlign.Center)
                                    Text(Bn.num(CB_PRICE) + " টাকা", fontSize = 12.sp, color = C.Red, fontWeight = FontWeight.Bold)
                                    Text("ব্যালেন্স থেকে কিনুন", fontSize = 10.sp, color = C.Red, textAlign = TextAlign.Center)
                                }
                            }
                        } else {
                            val on = current == id
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(CardRender.aspect(id)).clip(RoundedCornerShape(8.dp)).background(Color(0xFFDDDDDD))
                                    .border(BorderStroke(if (on) 3.dp else 1.dp, if (on) C.Red else TplBorder), RoundedCornerShape(8.dp))
                                    .clickable { msg = null; if (CustomRe.matches(id)) edit = id else big = id },
                            ) {
                                thumbs[id]?.let { Image(it.asImageBitmap(), contentDescription = tplName(id), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                            Text(
                                tplName(id) + if (on) " ✔" else "", fontSize = 12.sp, textAlign = TextAlign.Center,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) C.Red else Color(0xFF555555),
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    msg?.let { (ok, t) -> Text(t, fontSize = 13.sp, color = if (ok) C.Green else C.Red, modifier = Modifier.padding(top = 8.dp)) }
    if (np.tplDirty) Text("⚠️ টেমপ্লেটের বদল সেভ করা হয়নি। নিচের \"সেটিংস সেভ করুন\" চাপুন।", fontSize = 13.sp, color = C.Red, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

    big?.let { id ->
        TplBigDialog(np, id, onClose = { big = null }, onEdit = { big = null; edit = id })
    }
    edit?.let { id -> TplEditorDialog(np, id) { edit = null } }
    if (buy) CbBuyDialog(
        np, onClose = { buy = false },
        onTopup = { buy = false; topup = true },
        onBought = { buy = false; msg = true to "✅ কেনা হয়েছে। এখন ➕ কার্ড থেকে নতুন কাস্টম টেমপ্লেট বানাতে পারবেন।" },
    )
    if (topup) TopupDialog(dash, np.balance, onClose = { topup = false })
}

/** ওয়েবের `#tplModal`: টেমপ্লেট বড় করে দেখা ও সাজানো */
@Composable
private fun TplBigDialog(np: NewPostViewModel, id: String, onClose: () -> Unit, onEdit: () -> Unit) {
    val st = TplRegistry.store
    var ts by remember(id) { mutableStateOf(copyJson(st.tpls.optJSONObject(id))) }
    var ver by remember(id) { mutableIntStateOf(0) }          // ts বদলালে প্রিভিউ আবার আঁকার জন্য
    var changed by remember(id) { mutableStateOf(false) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val layoutEdited = st.dtpl.has(id)
    val picked = st.template.ifBlank { "dark" } == id

    fun touch() { changed = true; ver++ }
    fun mode() = ts.optString("mode", "auto").ifBlank { "auto" }
    var size by remember(id) { mutableStateOf(ts.optInt("size", 100).let { if (it <= 0) 100 else it }.toFloat()) }

    LaunchedEffect(id, ver) {
        delay(30)
        bmp = runCatching { np.settingsCard(id, 0.75f, copyJson(ts)) }.getOrNull() ?: bmp
    }

    fun commit(pick: Boolean) {
        if (changed || pick) np.stageTplSettings(id, copyJson(ts), pick)
        onClose()
    }

    Dialog(
        onDismissRequest = { commit(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111111)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                Text(tplName(id) + if (picked) " (বর্তমানে বাছাই করা)" else "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().aspectRatio(CardRender.aspect(id)).clip(RoundedCornerShape(8.dp)).background(Color(0xFF333333))) {
                        bmp?.let { Image(it.asImageBitmap(), contentDescription = "টেমপ্লেট", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    }
                }
                Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(10.dp), color = Color.White) {
                    Column(Modifier.padding(12.dp)) {
                        Text("এই টেমপ্লেটে কী বসবে", fontWeight = FontWeight.Bold)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ModeOptions.forEach { (k, l) ->
                                FilterChip(selected = mode() == k, onClick = { ts.put("mode", k); touch() }, label = { Text(l, fontSize = 13.sp) })
                            }
                        }
                        Text("লোগোর সাইজ: " + size.toInt() + "%", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                        Slider(
                            value = size, onValueChange = { size = (Math.round(it / 5f) * 5).toFloat() },
                            valueRange = 40f..220f, steps = 35,
                            onValueChangeFinished = { ts.put("size", size.toInt()); touch() },
                        )
                        Text("🎨 রঙ", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
                        val defs = TplColors.DEFS[id].orEmpty()
                        val cs = ts.optJSONObject("c")
                        defs.forEach { (k, label, dflt) ->
                            val cur = cs?.optString(k).orEmpty().ifBlank { dflt }
                            TplColorField(label, cur) { hex ->
                                val c = ts.optJSONObject("c") ?: JSONObject().also { ts.put("c", it) }
                                c.put(k, hex); touch()
                            }
                        }
                        if (layoutEdited) Text("✏️ এই টেমপ্লেটের লেআউট এডিটরে বদলানো হয়েছে, তাই রঙ ও বাকি সবকিছু \"লেআউট এডিট করুন\" থেকে বদলান।", fontSize = 12.sp, color = C.Label, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppBtn("↺ ডিফল্ট রঙ ও সাইজ", color = C.Gray, small = true) {
                                if (layoutEdited) confirmReset = true
                                else { ts = JSONObject(); size = 100f; touch() }
                            }
                            if (TplRegistry.isDef(id)) AppBtn("✏️ লেআউট এডিট করুন", color = C.Blue, small = true) {
                                if (changed) np.stageTplSettings(id, copyJson(ts), false)
                                onEdit()
                            }
                        }
                        Small("বদল সেভ করতে সেটিংস পেইজের নিচের \"সেটিংস সেভ করুন\" চাপুন।", Modifier.padding(top = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!picked) AppBtn("✔ এটি বেছে নিন", Modifier.weight(1f), color = C.Green) { commit(true) }
                    AppBtn("✕ বন্ধ করুন", Modifier.weight(1f), color = C.Gray) { commit(false) }
                }
            }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        text = { Text("এডিটরে বদলানো লেআউটও মুছে আসল ডিফল্টে ফিরবেন?") },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                np.stageTplSettings(id, null, false, dropLayout = true)
                onClose()
            }) { Text("হ্যাঁ", color = C.Red, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("না") } },
    )
}

/** ওয়েবের `<input type=color>`-এর বদলে: রঙের নমুনা + #rrggbb ঘর + তৈরি প্যালেট */
@Composable
private fun TplColorField(label: String, hex: String, onChange: (String) -> Unit) {
    var t by remember(hex) { mutableStateOf(hex) }
    Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(parseHex(hex)).border(1.dp, C.Border, RoundedCornerShape(6.dp)))
        OutlinedTextField(
            value = t,
            onValueChange = { v ->
                t = v
                val x = v.trim()
                if (Regex("^#[0-9a-fA-F]{6}$").matches(x)) onChange(x.lowercase())
            },
            singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), textStyle = TextStyle(fontSize = 16.sp),
        )
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Palette.forEach { c ->
            val on = c.equals(hex, true)
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(parseHex(c))
                    .border(if (on) 3.dp else 1.dp, if (on) C.Red else C.Border, RoundedCornerShape(6.dp))
                    .clickable { t = c; onChange(c) },
            )
        }
    }
}

/** ওয়েবের `#cbBuy`: ব্যালেন্স থেকে ৫০০ টাকায় আরেকটা কাস্টম টেমপ্লেট কেনা (কনফার্মেশন + লগইন পাসওয়ার্ড) */
@Composable
private fun CbBuyDialog(np: NewPostViewModel, onClose: () -> Unit, onTopup: () -> Unit, onBought: () -> Unit) {
    val bal = np.balance ?: 0.0
    val ok = bal >= CB_PRICE
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        containerColor = Color.White,
        confirmButton = {},
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("🛒 আরেকটা কাস্টম টেমপ্লেট কিনুন", fontWeight = FontWeight.Bold)
                Text("দাম: ৳ " + Bn.num(CB_PRICE) + " (ব্যালেন্স থেকে কাটা হবে, ফেরতযোগ্য নয়)", fontSize = 13.sp, color = C.Label, modifier = Modifier.padding(top = 6.dp))
                Text("বর্তমান ব্যালেন্স: ৳ " + Bn.num(bal), modifier = Modifier.padding(top = 8.dp))
                Text("কেনার পর ব্যালেন্স: " + if (ok) "৳ " + Bn.num(bal - CB_PRICE) else "—", modifier = Modifier.padding(top = 2.dp))
                if (!ok) {
                    Text("ব্যালেন্স কম আছে। আগে ব্যালেন্স যোগ করুন।", color = C.Red, modifier = Modifier.padding(top = 8.dp))
                    AppBtn("➕ ব্যালেন্স যোগ করুন", Modifier.padding(top = 6.dp), color = C.Blue, small = true, onClick = onTopup)
                }
                Text("আপনার লগইন পাসওয়ার্ড", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                err?.let { Text("⚠️ $it", color = C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                AppBtn(if (busy) "অপেক্ষা করুন..." else "✔ কিনুন", Modifier.padding(top = 12.dp), color = C.Green, enabled = ok && !busy) {
                    if (pw.isBlank()) err = "আপনার লগইন পাসওয়ার্ড দিন" else { err = null; confirm = true }
                }
                AppBtn("✕ বন্ধ করুন", Modifier.padding(top = 8.dp), color = C.Gray, enabled = !busy, onClick = onClose)
            }
        },
    )

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Text("ব্যালেন্স থেকে ${Bn.num(CB_PRICE)} টাকা কেটে আরেকটা কাস্টম টেমপ্লেট কিনবেন? এটা ফেরতযোগ্য নয়।") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false; busy = true; err = null
                np.buyCustomSlot(pw) { e -> busy = false; if (e == null) { pw = ""; onBought() } else err = e }
            }) { Text("হ্যাঁ", color = C.Red, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("না") } },
    )
}
