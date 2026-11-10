package com.digitalcrafting25.newsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 💰 রেট (শুধু অ্যাডমিন অ্যাপ): সব কাজের দাম এক জায়গায় কম-বেশি করা যায়; সেভ করলে Worker-এ যায় ও ইউজারের অ্যাপে নতুন রেট দেখায় */
@Composable
fun AdminRatesScreen(vm: RatesViewModel) {
    LaunchedEffect(Unit) { if (!vm.loaded && !vm.loading) vm.load() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        H1("💰 রেট")
        Small("কোন কাজে ইউজারের ব্যালেন্স থেকে কত টাকা কাটবে তা এখান থেকে কম-বেশি করুন। ➖ ➕ চেপে ১ টাকা করে বা সরাসরি সংখ্যা লিখে বদলান, তারপর নিচে \"সেভ করুন\" চাপুন। ০ দিলে কাজটা ফ্রি হবে।")
        vm.error?.let { StatusLine(false, "⚠️ $it") }
        vm.msg?.let { StatusLine(true, it) }
        if (vm.loading) Text("লোড হচ্ছে...", fontSize = 14.sp, color = C.Muted)

        var group = ""
        Rates.DEFS.forEach { d ->
            if (d.group != group) { group = d.group; H2(d.group) }
            RateRow(vm, d)
        }

        AppBtn(
            if (vm.saving) "সেভ হচ্ছে..." else if (vm.dirty) "💾 সেভ করুন" else "✓ কোনো বদল নেই",
            Modifier.fillMaxWidth().padding(top = 6.dp),
            enabled = vm.dirty && !vm.invalid && !vm.saving,
        ) { vm.save() }
        AppBtn(if (vm.loading) "লোড হচ্ছে..." else "🔄 Worker থেকে আবার আনুন", Modifier.fillMaxWidth(), color = C.Gray, enabled = !vm.loading && !vm.saving) { vm.load() }
        Small("ℹ️ নতুন রেট ইউজারের অ্যাপে পরের বার খোলা/লগইনে আসে। ইউজার ইতিমধ্যে যে কাজ শুরু করেছে (যেমন ভিডিওর দাম যাচাই হয়ে গেছে) তাতে এই বদল না-ও লাগতে পারে।")
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RateRow(vm: RatesViewModel, d: Rates.Def) {
    val cur = Rates.get(d.key)
    val now = vm.valueOf(d.key)
    InfoCard {
        Text(d.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Small(d.hint, Modifier.padding(top = 2.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppBtn("➖", color = C.Gray, small = true) { vm.bump(d.key, -1) }
            AppField(
                vm.edit[d.key] ?: cur.toString(), { vm.setText(d.key, it) },
                Modifier.width(110.dp), placeholder = "টাকা",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            AppBtn("➕", color = C.Gray, small = true) { vm.bump(d.key, 1) }
            Text("৳ টাকা", fontSize = 13.sp, color = C.Muted)
        }
        val note = when {
            now == null -> "⚠️ ০ থেকে ${Rates.MAX} এর মধ্যে সংখ্যা দিন"
            now != cur -> "বর্তমান ৳" + Bn.num(cur) + " → নতুন ৳" + Bn.num(now) + (if (now == 0) " (ফ্রি)" else "")
            else -> "চালু রেট ৳" + Bn.num(cur)
        }
        Small(note, Modifier.padding(top = 6.dp), color = if (now == null) C.Red else C.Muted)
        if (now != d.default) TextButton(onClick = { vm.toDefault(d.key) }) { Text("↩️ আসল রেট ৳" + Bn.num(d.default), fontSize = 12.sp) }
    }
}
