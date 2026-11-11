package com.digitalcrafting25.newsapp

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Calendar

private val Orange = Color(0xFFEF6C00)

/** ওয়েবের leftText(): বাকি সময় বাংলায় */
fun leftText(ms: Long): String {
    if (ms <= 0) return "এখনই"
    val t = ms / 1000
    val d = t / 86400
    val h = t % 86400 / 3600
    val m = t % 3600 / 60
    val s = t % 60
    return listOfNotNull(
        if (d > 0) "${Bn.num(d)} দিন" else null,
        if (h > 0 || d > 0) "${Bn.num(h)} ঘণ্টা" else null,
        "${Bn.num(m)} মিনিট",
        if (d == 0L) "${Bn.num(s)} সেকেন্ড" else null,
    ).joinToString(" ")
}

internal fun pickDateTime(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(
        ctx,
        { _, y, mo, d ->
            TimePickerDialog(
                ctx,
                { _, h, mi ->
                    val r = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }
                    onPicked(r.timeInMillis)
                },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false,
            ).show()
        },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH),
    ).show()
}

@Composable
fun ScheduleScreen(vm: ScheduleViewModel, hasDraft: () -> Boolean = { false }, onEdit: (SchedItem) -> Unit = {}) {
    val st by vm.state.collectAsStateWithLifecycle()
    val cur by rememberUpdatedState(st)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val dueSeen = remember { mutableSetOf<String>() }
    var confirmNow by remember { mutableStateOf<SchedItem?>(null) }
    var confirmEdit by remember { mutableStateOf<SchedItem?>(null) }

    LaunchedEffect(Unit) { vm.load() }
    // প্রতি সেকেন্ডে কাউন্টডাউন; কোনো পোস্টের সময় পেরোলে ১৫ সেকেন্ড পর একবার, নইলে ৫ মিনিটে একবার সার্ভার থেকে হালনাগাদ
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            tick++
            val s = cur
            if (s.items.isNotEmpty()) {
                val n = now + s.offset
                val fresh = s.items.filter { it.status == "pending" && it.at + 15000 <= n && it.id !in dueSeen }
                if (fresh.isNotEmpty()) {
                    fresh.forEach { dueSeen.add(it.id) }
                    tick = 0
                    vm.load()
                } else if (tick % 300 == 0) vm.load()
            }
        }
    }

    val active = st.items.count { it.status == "pending" || it.status == "running" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        H1("⏰ সিডিউল পোস্ট")
        Text("📌 সিডিউল করা: ${Bn.num(active)}/${Bn.num(st.max)}টি (একজন সর্বোচ্চ ${Bn.num(st.max)}টি)", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Text(
            "সময় হলে Worker নিজেই পোস্ট করে দেবে। অ্যাপ বা ফোন বন্ধ থাকলেও চলবে।",
            fontSize = 13.sp, color = C.Label, modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(6.dp))
        when {
            st.error != null && st.items.isEmpty() -> Text("⚠️ ${st.error}", color = C.Red, modifier = Modifier.padding(top = 8.dp))
            st.loading && !st.loaded -> Text("লোড হচ্ছে...", modifier = Modifier.padding(top = 8.dp))
            st.loaded && st.items.isEmpty() -> Text("কোনো সিডিউল করা পোস্ট নেই।", modifier = Modifier.padding(top = 8.dp))
        }
        if (st.error != null && st.items.isNotEmpty()) {
            Text("⚠️ হালনাগাদ করা যায়নি: ${st.error}", color = C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }

        val nowMs = now + st.offset
        st.items.forEach { item ->
            ScheduleCard(
                item = item, nowMs = nowMs, busy = item.id in st.busy,
                onPostNow = { confirmNow = item },
                onCancel = { vm.cancel(item) },
                onReschedule = { at -> vm.reschedule(item, at) },
                // নতুন পোস্টে লেখা থাকলে আগে জিজ্ঞেস; নইলে সরাসরি ধাপ ২-এ এডিট
                onEdit = { if (hasDraft()) confirmEdit = item else onEdit(item) },
            )
        }

        AppBtn(
            if (st.refreshing || st.loading) "অপেক্ষা করুন..." else "🔄 রিফ্রেশ", color = C.Gray,
            enabled = !st.refreshing && !st.loading, modifier = Modifier.padding(top = 10.dp),
        ) { vm.refresh() }
        Spacer(Modifier.height(16.dp))
    }

    confirmNow?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmNow = null },
            containerColor = Color.White,
            text = { Text("এই পোস্টটি এখনই পোস্ট হয়ে যাবে এবং সিডিউল থেকে উঠে যাবে। করবেন?") },
            confirmButton = { TextButton(onClick = { confirmNow = null; vm.postNow(item) }) { Text("হ্যাঁ, পোস্ট করুন", color = C.Green, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmNow = null }) { Text("বাতিল", color = C.Gray) } },
        )
    }
    confirmEdit?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmEdit = null },
            containerColor = Color.White,
            text = { Text("নতুন পোস্টের পাতায় যে লেখা/ছবি আছে, এডিট শুরু করলে সেটা মুছে যাবে। এডিট করবেন?") },
            confirmButton = { TextButton(onClick = { confirmEdit = null; onEdit(item) }) { Text("হ্যাঁ, এডিট করুন", color = C.Green, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmEdit = null }) { Text("বাতিল", color = C.Gray) } },
        )
    }
    st.notice?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.dismissNotice() },
            containerColor = Color.White,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.dismissNotice() }) { Text("ঠিক আছে", fontWeight = FontWeight.Bold) } },
        )
    }
}

@Composable
private fun ScheduleCard(
    item: SchedItem,
    nowMs: Long,
    busy: Boolean,
    onPostNow: () -> Unit,
    onCancel: () -> Unit,
    onReschedule: (Long) -> Unit,
    onEdit: () -> Unit,
) {
    val ctx = LocalContext.current
    val failed = item.status == "failed"
    // চলমান দেখাচ্ছে অথচ ৪ মিনিটের বেশি কিছু ঘটেনি: আটকে গেছে
    val stuck = item.status == "running" && nowMs - item.ra > 240000
    val running = item.status == "running" || item.at <= nowMs
    var editing by remember(item.id) { mutableStateOf(false) }
    var askDel by remember(item.id) { mutableStateOf(false) }
    var picked by remember(item.id, item.at) { mutableLongStateOf(item.at) }

    val runClass = item.status == "running" || item.at <= nowMs   // ওয়েবের .sch-item.run
    val cdText: String = when {
        failed -> "⚠️ পোস্ট হয়নি: " + item.error.ifBlank { "কারণ অজানা" }
        stuck -> "⚠️ ফেইসবুক পেজে পোস্টটি গেছে কিনা দেখুন। পোস্ট হলে মুছে ফেলুন।"
        item.status != "running" && item.tries > 0 ->
            "🔁 আবার চেষ্টা হবে (${Bn.num(item.tries)} বার হয়েছে)" +
                (if (item.rt > nowMs) " • পরের চেষ্টা ${leftText(item.rt - nowMs)} পরে" else "") +
                (if (item.error.isNotBlank()) " — ${item.error}" else "")
        item.status == "running" || item.at - nowMs <= 0 ->
            "⏳ পোস্ট হচ্ছে..." + (if (item.tries > 1) " (চেষ্টা ${Bn.num(item.tries)})" else "") +
                (if (item.error.isNotBlank()) " — আগের ত্রুটি: ${item.error}" else "")
        else -> "⏱ ${leftText(item.at - nowMs)} বাকি"
    }
    // .sch-cd: নীল ২২px বোল্ড; চলমান হলে কমলা; ফেইল হলে লাল ১৪px সাধারণ
    val cdColor = if (failed) C.Red else if (runClass) Orange else C.Blue

    Column(
        Modifier.padding(top = 10.dp).fillMaxWidth().shadow(1.dp, RoundedCornerShape(12.dp)).background(Color.White, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text((if (item.kind == "card") "🖼️ " else "") + item.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("🗓 " + Bn.dateTime(item.at), fontSize = 13.sp, color = C.Label, modifier = Modifier.padding(top = 2.dp))
        Text(
            cdText, color = cdColor,
            fontSize = if (failed) 14.sp else 20.sp, fontWeight = if (failed) FontWeight.Normal else FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (item.status != "running" || stuck) {
            if (failed || stuck) {
                HBtn("🗑️ মুছে ফেলুন", Modifier.fillMaxWidth().padding(top = 6.dp), color = C.Gray, padH = 8.dp, padV = 7.dp, size = 13.sp, enabled = !busy, onClick = onCancel)
            } else {
                // তিনটা বাটন পাশাপাশি: এখনই পোস্ট | সময় বদলান | এডিট করুন (ফোটোকার্ডে এডিট নেই, তাই সেখানে মুছুন)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.status == "pending") {
                        HBtn(
                            if (busy) "অপেক্ষা..." else "🚀 এখনই পোস্ট", Modifier.weight(1f),
                            color = C.Green, padH = 4.dp, padV = 7.dp, size = 12.sp, enabled = !busy, onClick = onPostNow,
                        )
                    }
                    HBtn("⏰ সময় বদলান", Modifier.weight(1f), color = C.Gray, padH = 4.dp, padV = 7.dp, size = 12.sp, enabled = !busy) {
                        editing = !editing; if (editing) picked = item.at
                    }
                    if (item.status == "pending") {
                        if (item.kind == "card") HBtn("🗑️ মুছুন", Modifier.weight(1f), color = C.Red, padH = 4.dp, padV = 7.dp, size = 12.sp, enabled = !busy) { askDel = true }
                        else HBtn("✏️ এডিট করুন", Modifier.weight(1f), color = C.Blue, padH = 4.dp, padV = 7.dp, size = 12.sp, enabled = !busy, onClick = onEdit)
                    }
                }
                if (editing) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)
                                .border(1.dp, C.Border, RoundedCornerShape(8.dp))
                                .clickable { pickDateTime(ctx, picked) { picked = it } }.padding(10.dp),
                        ) { Text(Bn.dateTime(picked), fontSize = 16.sp) }
                        val tooSoon = picked < nowMs + 60000
                        HSmall(
                            if (tooSoon) "⚠️ অন্তত ১ মিনিট পরের সময় দিন" else Bn.dateTime(picked) + " • " + leftText(picked - nowMs) + " পরে",
                            Modifier.padding(top = 2.dp),
                        )
                        HBtn(
                            if (busy) "অপেক্ষা করুন..." else "✅ নতুন সময় সংরক্ষণ", Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = C.Green, padH = 8.dp, padV = 8.dp, size = 14.sp, enabled = !busy && !tooSoon,
                        ) { onReschedule(picked); editing = false }
                    }
                }
            }
        }
    }
    if (askDel) AlertDialog(
        onDismissRequest = { askDel = false },
        containerColor = Color.White,
        text = { Text("এই সিডিউল পোস্টটি মুছে ফেলবেন? এটা আর পোস্ট হবে না।") },
        confirmButton = { TextButton(onClick = { askDel = false; onCancel() }) { Text("হ্যাঁ, মুছুন", color = C.Red, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { askDel = false }) { Text("বাতিল", color = C.Gray) } },
    )
}
