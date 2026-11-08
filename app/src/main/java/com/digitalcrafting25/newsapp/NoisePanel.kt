package com.digitalcrafting25.newsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** নয়েজ কমানোর ডিফল্ট পরিমাণ (প্রথমবার চালু করলে) */
private const val DEFAULT_NOISE = 0.6f

/**
 * ক্লিপের নিজের শব্দের নয়েজ ক্যানসেল (অডিও এক্সট্রাক্ট ছাড়াই):
 *  - ✅ চালু/বন্ধ বোতাম আর ০–১০০% স্লাইডার (০% = বন্ধ)
 *  - স্লাইডার টানলে প্লে চলা অবস্থায় সাথে সাথে শব্দে ফল শোনা যায় (প্রিভিউয়ের ফিল্টারই রেন্ডারে যায়)
 *  - লাইভ মিটার: আগে বনাম পরে শব্দের জোর, আর কত dB কমছে — রেন্ডারের আগেই বোঝা যায়
 */
@Composable
fun ClipNoiseEditor(tl: Timeline, c: Clip) {
    var last by remember(c.id) { mutableFloatStateOf(if (c.noise > 0.001f) c.noise else DEFAULT_NOISE) }
    val on = c.noise > 0.001f
    Column(Modifier.edPanel(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("🎙️ নয়েজ ক্যানসেল — ${c.name}", fontWeight = FontWeight.Bold, maxLines = 1)
        Text("ভিডিওর নিজের শব্দেই কাজ করে, অডিও আলাদা করতে হয় না।", fontSize = 12.sp, color = ED.Muted)
        NoiseControl(
            enabled = on,
            amount = c.noise,
            onToggle = { tl.setClipNoise(c.id, if (on) 0f else last) },
            onAmount = { v -> if (v > 0.001f) last = v; tl.setClipNoise(c.id, v) },
            inLevel = NoiseMeter.clipIn, outLevel = NoiseMeter.clipOut,
        )
    }
}

/** চালু/বন্ধ + পরিমাণের স্লাইডার + লাইভ মিটার (ক্লিপ ও অডিও লেয়ার দুই জায়গায় একই) */
@Composable
fun NoiseControl(
    enabled: Boolean,
    amount: Float,
    onToggle: () -> Unit,
    onAmount: (Float) -> Unit,
    inLevel: Float,
    outLevel: Float,
) {
    val shown = if (enabled) amount.coerceIn(0f, 1f) else 0f
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Text(if (enabled) "✅ নয়েজ ক্যানসেল চালু" else "▫️ নয়েজ ক্যানসেল বন্ধ", fontSize = 13.sp)
        }
    }
    Text(
        if (enabled) "পরিমাণ: ${Math.round(shown * 100)}%" else "পরিমাণ: ০% (বন্ধ)",
        fontSize = 13.sp, color = ED.Sub,
    )
    Slider(value = shown, onValueChange = onAmount, valueRange = 0f..1f)
    Text("কম = হালকা, বেশি = চুপ জায়গার হিস/গুঞ্জন বেশি কমে (খুব বেশি দিলে কথার শেষটা কাটা শোনাতে পারে)", fontSize = 11.sp, color = ED.Muted)

    // ---- লাইভ মিটার ----
    Text("📊 লাইভ মিটার (প্লে চাপলে চলে)", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    val playing = inLevel > 0.000_01f
    Text("আগে (আসল শব্দ)", fontSize = 12.sp, color = ED.Sub)
    LinearProgressIndicator(progress = { if (enabled) NoiseMeter.bar(inLevel) else 0f }, modifier = Modifier.fillMaxWidth())
    Text("পরে (নয়েজ কমার পর)", fontSize = 12.sp, color = ED.Sub)
    LinearProgressIndicator(progress = { if (enabled) NoiseMeter.bar(outLevel) else 0f }, modifier = Modifier.fillMaxWidth())
    val red = NoiseMeter.reductionDb(inLevel, outLevel)
    Text(
        when {
            !enabled -> "বন্ধ আছে — চালু করে প্লে চাপলে ফল শোনা ও দেখা যাবে"
            !playing -> "▶ প্লে চাপুন: চলার সময় এখানে দেখা যাবে নয়েজ কমছে কিনা"
            red >= 1.5f -> "✅ নয়েজ কমছে: −${"%.1f".format(red)} dB"
            else -> "〰️ এখন জোরালো শব্দ/কথা চলছে, তাই ফিল্টার প্রায় ছুঁচ্ছে না (চুপ অংশে কাজ করে)"
        },
        fontSize = 13.sp, color = if (enabled && playing && red >= 1.5f) ED.Ok else ED.Sub,
    )
}
