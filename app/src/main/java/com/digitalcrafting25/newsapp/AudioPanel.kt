package com.digitalcrafting25.newsapp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal inline fun Timeline.editAudio(id: Long, crossinline f: (AudioLayer) -> AudioLayer) {
    updateLayer(id) { if (it is AudioLayer) f(it) else it }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEditor(tl: Timeline, l: AudioLayer) {
    val total = tl.totalMs

    Column(
        Modifier.edPanel(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("✏️ ${l.kind.label} লেয়ার", fontWeight = FontWeight.Bold)
        if (l.name.isNotBlank()) Text(l.name, fontSize = 12.sp, color = ED.Muted, maxLines = 2)

        // ভলিউম + মিউট
        Text(
            if (l.muted) "ভলিউম: মিউট 🔇" else "ভলিউম: ${Math.round(l.volume * 100)}%",
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = {
                    tl.editAudio(l.id) { it.copy(volume = (Math.round(it.volume * 10f) / 10f - 0.1f).coerceIn(0f, 2f)) }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("🔉 −১০%", fontSize = 12.sp) }
            OutlinedButton(
                onClick = {
                    tl.editAudio(l.id) {
                        it.copy(volume = (Math.round(it.volume * 10f) / 10f + 0.1f).coerceIn(0f, 2f), muted = false)
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("🔊 +১০%", fontSize = 12.sp) }
            OutlinedButton(
                onClick = { tl.editAudio(l.id) { it.copy(muted = !it.muted) } },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(if (l.muted) "🔊 আনমিউট" else "🔇 মিউট", fontSize = 12.sp) }
        }
        Slider(
            value = l.volume.coerceIn(0f, 2f),
            onValueChange = { v -> tl.editAudio(l.id) { it.copy(volume = (Math.round(v * 20f) / 20f)) } },
            valueRange = 0f..2f,
        )

        // নয়েজ ক্যানসেল: পরিমাণ বার + লাইভ মিটার (প্লে চললে শোনা ও দেখা যায়)
        val myMeter = NoiseMeter.layerId == l.id
        NoiseControl(
            enabled = l.denoise,
            amount = l.denoiseAmt,
            onToggle = { tl.editAudio(l.id) { it.copy(denoise = !it.denoise, denoiseAmt = if (it.denoiseAmt < 0.05f) 0.6f else it.denoiseAmt) } },
            onAmount = { v -> tl.editAudio(l.id) { it.copy(denoise = v > 0.001f, denoiseAmt = if (v > 0.001f) v else it.denoiseAmt) } },
            inLevel = if (myMeter) NoiseMeter.layerIn else 0f,
            outLevel = if (myMeter) NoiseMeter.layerOut else 0f,
        )

        // সময়
        if (total > LayerDefaults.MIN_LAYER_MS) {
            var r by remember(l.id, l.startMs, l.endMs, total) {
                val s = l.startMs.toFloat().coerceIn(0f, total.toFloat())
                mutableStateOf(s..l.endMs.toFloat().coerceIn(s, total.toFloat()))
            }
            Text("সময়: ${fmtTime(r.start.toLong())} → ${fmtTime(r.endInclusive.toLong())}", fontSize = 13.sp)
            RangeSlider(
                value = r,
                onValueChange = { r = it },
                onValueChangeFinished = { tl.setLayerTime(l.id, r.start.toLong(), r.endInclusive.toLong()) },
                valueRange = 0f..total.toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { tl.setLayerTime(l.id, tl.playheadMs, maxOf(l.endMs, tl.playheadMs + LayerDefaults.MIN_LAYER_MS)) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("▶ এখান থেকে শুরু", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { tl.setLayerTime(l.id, minOf(l.startMs, tl.playheadMs - LayerDefaults.MIN_LAYER_MS).coerceAtLeast(0L), tl.playheadMs) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("⏹ এখানে শেষ", fontSize = 12.sp) }
            }
        }

        // অডিও ফাইলের কোন জায়গা থেকে বাজবে (শুধু ফাইল/এক্সট্রাক্ট/ভয়েস, বিপ নয়)
        if (l.kind != LayerKind.BEEP && l.sourceDurationMs > 1000L) {
            val maxOff = (l.sourceDurationMs - LayerDefaults.MIN_LAYER_MS).coerceAtLeast(1L).toFloat()
            var off by remember(l.id, l.sourceOffsetMs) { mutableStateOf(l.sourceOffsetMs.toFloat().coerceIn(0f, maxOff)) }
            Text("অডিওর কোন জায়গা থেকে শুরু: ${fmtTime(off.toLong())}", fontSize = 13.sp)
            Slider(
                value = off,
                onValueChange = { off = it },
                onValueChangeFinished = { tl.editAudio(l.id) { it.copy(sourceOffsetMs = off.toLong()) } },
                valueRange = 0f..maxOff,
            )
        }

        if (l.kind == LayerKind.BEEP) {
            Text("বিপ চলার সময় ক্লিপ ও অন্য সব অডিও চুপ থাকবে।", fontSize = 12.sp, color = ED.Muted)
        }
    }
}
