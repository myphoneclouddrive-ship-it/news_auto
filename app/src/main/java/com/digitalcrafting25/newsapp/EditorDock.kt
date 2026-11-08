package com.digitalcrafting25.newsapp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// ডকের কাজ: লেয়ার/অডিও/ভয়েস/বিপ যোগ, ছবি বদল — ফাইল-পিকার ও মাইক সহ
// (আগে LayerDock / AudioDock-এর ভেতরে ছিল; এখন এক জায়গায়, টাইমলাইনের "＋ অডিও" ও ডক দুই জায়গা থেকেই চলে)
// ---------------------------------------------------------------------------

@Stable
class DockActions(
    val add: (LayerKind) -> Unit,
    val addAudioFile: () -> Unit,
    val beep: () -> Unit,
    val replaceImage: (Long) -> Unit,
    /** ভয়েস বাটনে লাগানোর মডিফায়ার: চেপে ধরলে রেকর্ড, ছাড়লে লেয়ার বসে */
    val voiceModifier: Modifier,
    val recording: State<Boolean>,
)

@Composable
fun rememberDockActions(tl: Timeline, onMessage: (String?) -> Unit, onInfo: (String?) -> Unit): DockActions {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msg by rememberUpdatedState(onMessage)
    val inf by rememberUpdatedState(onInfo)

    var pendingKind by rememberSaveable { mutableStateOf<LayerKind?>(null) }
    var replaceId by rememberSaveable { mutableStateOf<Long?>(null) }

    val pickNew = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        val k = pendingKind
        pendingKind = null
        if (u != null && k != null) scope.launch {
            val ku = withContext(Dispatchers.IO) { DraftStore.keep(ctx, u) }
            val m = tl.addLayer(k, uri = ku)
            if (m == null) tl.selectedId = null
            msg(m)
        }
    }
    val pickReplace = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        val id = replaceId
        replaceId = null
        if (u != null && id != null) scope.launch {
            val ku = withContext(Dispatchers.IO) { DraftStore.keep(ctx, u) }
            tl.updateLayer(id) {
                when (it) {
                    is ImageLayer -> it.copy(uri = ku)
                    is AdLayer -> it.copy(uri = ku)
                    else -> it
                }
            }
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val (name, dur) = withContext(Dispatchers.IO) { AudioMixer.probe(ctx, u) }
            val has = withContext(Dispatchers.IO) { AudioMixer.hasAudioTrack(ctx, u) }
            if (!has) { msg("এই ফাইলে অডিও পাওয়া যায়নি: $name"); return@launch }
            val ku = withContext(Dispatchers.IO) { DraftStore.keep(ctx, u) }
            val m = tl.addAudioLayer(LayerKind.AUDIO, ku, name, if (dur > 0L) dur else 30_000L)
            msg(m)
            if (m == null) inf("🎵 অডিও বসেছে: $name")
        }
    }

    val recorder = remember { VoiceRecorder(ctx) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    val recording = remember { mutableStateOf(false) }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) inf("🎙️ মাইকের অনুমতি পেয়েছি। এবার বাটন চেপে ধরে কথা বলুন।")
        else msg("মাইকের অনুমতি ছাড়া ভয়েস রেকর্ড করা যাবে না")
    }

    val voiceModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            if (tl.totalMs <= 0L) {
                msg("আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন, তারপর ভয়েস দিন")
                return@detectTapGestures
            }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                askMic.launch(Manifest.permission.RECORD_AUDIO)
                return@detectTapGestures
            }
            val startAt = tl.playheadMs
            val err = recorder.start()
            if (err != null) { msg(err); return@detectTapGestures }
            recording.value = true
            msg(null)
            inf("🎙️ রেকর্ড হচ্ছে… কথা শেষে বাটন ছাড়ুন")
            tryAwaitRelease()
            recording.value = false
            val r = recorder.stop()
            if (r == null) {
                inf(null)
                msg("বাটনটা চেপে ধরে রেখে কথা বলুন (কমপক্ষে আধা সেকেন্ড)")
            } else {
                val (f, dur) = r
                val m = tl.addAudioLayer(LayerKind.VOICE, Uri.fromFile(f), "ভয়েস", dur, startAt)
                msg(m)
                if (m == null) inf("🎙️ ভয়েস বসেছে")
            }
        })
    }

    return remember(tl) {
        DockActions(
            add = { kind ->
                when (kind) {
                    // সেটিংসে সেভ করা বিজ্ঞাপন থাকলে নতুন বার-এ আগে না-বসা বিজ্ঞাপন নিজে বসে (HTML adBtn); না থাকলে ছবি বাছতে বলে
                    LayerKind.AD_BOTTOM, LayerKind.AD_TOP -> scope.launch {
                        val ads = SettingsMedia.ads(ctx)
                        if (ads.isEmpty()) {
                            pendingKind = kind
                            pickNew.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            return@launch
                        }
                        val top = kind == LayerKind.AD_TOP
                        val used = tl.layers.filterIsInstance<AdLayer>().filter { it.isTop == top }.map { it.adId }.toSet()
                        val ad = ads.firstOrNull { it.id !in used } ?: ads.first()
                        val u = SettingsMedia.adUri(ctx, ad)
                        val m = tl.addLayer(kind, uri = u, adId = ad.id)
                        if (m == null) tl.selectedId = null
                        msg(m)
                    }
                    // লোগো: সেটিংসের লোগো (না থাকলে আইকন, তাও না থাকলে সাইটের নাম) নিজে বসে; কিছুই না থাকলে ছবি বাছতে বলে
                    LayerKind.LOGO -> scope.launch {
                        // লোগো আগে থেকে থাকলে নতুন কিছু বসে না: শুধু বাছা হয় (এডিট/মুছতে/টেনে সরাতে)
                        val ex = tl.layers.firstOrNull { it.isLogoLayer() }
                        if (ex != null) {
                            tl.selectedId = null
                            tl.selectedLayerId = ex.id
                            inf("🏷️ লোগো ইন্ট্রো/আউট্রো ছাড়া পুরো ভিডিওতে থাকবে; ✏️ এডিটে মাপ/জায়গা বদলান।")
                            return@launch
                        }
                        val opt = SettingsMedia.logos(ctx).firstOrNull()
                        if (opt == null) {
                            pendingKind = kind
                            pickNew.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            return@launch
                        }
                        val m = tl.addLayer(kind, uri = SettingsMedia.logoUri(ctx, opt))
                        if (m == null) tl.selectedId = null
                        msg(m)
                    }
                    // এডজাস্ট একটাই: আগে থাকলে নতুন লেয়ার নয়, আগেরটাই বাছা হয় (✏️ এডিটে ব্রাইটনেস/কন্ট্রাস্ট বদলান)
                    LayerKind.ADJUST -> {
                        val ex = tl.layers.firstOrNull { it is AdjustLayer }
                        if (ex != null) {
                            tl.selectedId = null
                            tl.selectedLayerId = ex.id
                            inf("☀️ এডজাস্ট আগে থেকেই আছে — ✏️ এডিটে ব্রাইটনেস/কন্ট্রাস্ট বদলান।")
                        } else {
                            val m = tl.addLayer(kind)
                            if (m == null) tl.selectedId = null
                            msg(m)
                        }
                    }
                    // ট্যাগ একটাই: আগে থাকলে নতুন লেয়ার নয়, আগেরটাই বাছা হয় (✏️ এডিটে লেখা/কোণা/রং বদলান)
                    LayerKind.TAG -> {
                        val ex = tl.layers.firstOrNull { it is TagLayer }
                        if (ex != null) {
                            tl.selectedId = null
                            tl.selectedLayerId = ex.id
                            inf("📌 ট্যাগ আগে থেকেই আছে — ✏️ এডিটে লেখা/কোণা/রং বদলান।")
                        } else {
                            val m = tl.addLayer(kind, text = "ট্যাগ")
                            if (m == null) tl.selectedId = null
                            msg(m)
                        }
                    }
                    LayerKind.IMAGE -> {
                        pendingKind = kind
                        pickNew.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    else -> {
                        val m = tl.addLayer(
                            kind,
                            text = when (kind) {
                                LayerKind.TAG -> "ট্যাগ"
                                LayerKind.TICKER -> "এখানে স্ক্রলিং খবর লিখুন"
                                LayerKind.NAME_BAR -> "আপনার নাম"
                                else -> "আপনার লেখা"
                            },
                        )
                        if (m == null) tl.selectedId = null
                        msg(m)
                    }
                }
            },
            addAudioFile = { pickAudio.launch(arrayOf("audio/*")) },
            beep = {
                val m = tl.addAudioLayer(LayerKind.BEEP, null, "বিপ", BeepSound.LEN_MS)
                if (m == null) BeepSound.playOnce()   // HTML-এর মতো বসানোর সময়েই বিপ শোনা যায়
                msg(m)
                if (m == null) inf("🔔 বিপ বসেছে (${fmtTime(tl.layerById(tl.selectedLayerId)?.startMs ?: 0L)}); বিপ চলার সময় বাকি শব্দ বন্ধ থাকবে।")
            },
            replaceImage = { id ->
                replaceId = id
                pickReplace.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            voiceModifier = voiceModifier,
            recording = recording,
        )
    }
}

// ---------------------------------------------------------------------------
// ডক: আইকনের সারি (সবকিছু কিছু বাছা না থাকলে "লেয়ার মেনু"; ক্লিপ বাছলে ক্লিপ-টুল; লেয়ার/অডিও বাছলে তার টুল)
// ---------------------------------------------------------------------------

/** নিচে আটকানো ডক বার (HTML `#vc_dock`): কিছু বাছা না থাকলে লেয়ার মেনু; ক্লিপ বাছলে ক্লিপ-টুল; লেয়ার/অডিও বাছলে তার টুল */
@Composable
fun EditorDockBar(
    tl: Timeline,
    act: DockActions,
    onMessage: (String?) -> Unit,
    onInfo: (String?) -> Unit,
    vm: EditorViewModel,
    editOpen: Boolean,
    onToggleEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val selClip = tl.clipById(tl.selectedId)
    val selLayer = tl.layerById(tl.selectedLayerId)
    val rec by act.recording

    // মেনুর স্তর: null = মূল টুলবার; "audio"/"brand"/"ads"/"card" = ভেতরের সারি (CapCut-এর মতো দুই ধাপ)
    var group by remember { mutableStateOf<String?>(null) }

    // "➕ সামনে ভিডিও" / "➕ পিছনে ভিডিও": ইন্ট্রোর আগে বা আউট্রোর পরে আরও ভিডিও/ছবি (true = সামনে)
    var pickFront by rememberSaveable { mutableStateOf(true) }
    val pickEdge = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            vm.addUris(uris, front = pickFront)
            onInfo(if (pickFront) "➕ সামনে যোগ হচ্ছে…" else "➕ পিছনে যোগ হচ্ছে…")
        }
    }

    fun toMenu() {
        tl.selectedId = null
        tl.selectedLayerId = null
        group = null
    }

    // রেডি ইন্ট্রো/আউট্রো: কার্ড না থাকলে আগে বাছাইয়ের পর্দা (false = ইন্ট্রো, true = আউট্রো); বানানো শুরু হয় তার "বানান" বাটনে
    var cardDlg by remember { mutableStateOf<Boolean?>(null) }
    cardDlg?.let { o -> CardMakeDialog(vm, o, onInfo) { cardDlg = null } }

    // ✂️ ভিডিও ক্রপ ডায়ালগ: খোলার সময়ের ফ্রেমের সময় (ক্লিপের সোর্সে ms) মনে রাখা হয়
    var cropOpen by remember { mutableStateOf(false) }
    var cropMs by remember { mutableStateOf(0L) }
    val cropClip = selClip
    if (cropOpen && cropClip != null && cropClip.card == null) {
        ClipCropDialog(
            clip = cropClip,
            srcMs = cropMs,
            onApply = { x, y, w, h ->
                tl.setClipCrop(cropClip.id, x, y, w, h)
                cropOpen = false
                onInfo(if (w >= 0.995f && h >= 0.995f) "✂️ ক্রপ বাদ" else "✂️ ক্রপ হয়েছে (ফ্রেমের মাপ একই)")
            },
            onCancel = { cropOpen = false },
        )
    }

    EditorDarkTheme {
            Column(Modifier.fillMaxWidth().background(ED.Dock)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(ED.Border))
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 3.dp),
            ) {
                when {
                    // ---- ক্লিপ বাছা: ক্লিপ টুল ----
                    selClip != null -> {
                        val c = selClip
                        val idx = tl.clips.indexOfFirst { it.id == c.id }
                        DockBtn("🔙", "মেনু") { toMenu() }
                        // ইন্ট্রো/আউট্রো কার্ডের লেখা/স্টাইল প্যানেল এই বাটনে খোলে (ট্যাপে নয়)
                        if (c.card != null) DockBtn("✏️", "এডিট", active = editOpen) { onToggleEdit() }
                        DockBtn("✂️", "স্প্লিট") {
                            val m = tl.split()
                            onMessage(m)
                            if (m == null) onInfo("✂️ কাটা হয়েছে")
                        }
                        DockBtn("🔔", "বিপ") { act.beep() }
                        if (!c.isImage) {
                            DockBtn("🎵", "অডিও এক্সট্রাক্ট") {
                                scope.launch {
                                    val has = withContext(Dispatchers.IO) { AudioMixer.hasAudioTrack(ctx, c.uri) }
                                    if (!has) { onMessage("এই ভিডিওতে অডিও ট্র্যাক নেই"); return@launch }
                                    val m = tl.extractAudio(c.id)
                                    onMessage(m)
                                    if (m == null) onInfo("🎧 অডিও আলাদা লেয়ার হয়েছে; মূল অংশ মিউট করা হয়েছে।")
                                }
                            }
                            DockBtn(
                                if (c.muted) "🔊" else "🔇",
                                if (c.muted) "আনমিউট" else "মিউট",
                                active = c.muted,
                            ) { tl.toggleClipMute(c.id) }
                            DockBtn("🔊", "ভলিউম +") {
                                tl.adjustClipVolume(c.id, 10)?.let { onInfo("🔊 ভলিউম $it%" + if (it == 200) " (সর্বোচ্চ)" else "") }
                            }
                            DockBtn("🔉", "ভলিউম −") {
                                tl.adjustClipVolume(c.id, -10)?.let { onInfo("🔉 ভলিউম $it%") }
                            }
                            // অডিও এক্সট্রাক্ট ছাড়াই ক্লিপের শব্দে নয়েজ ক্যানসেল: স্লাইডার + লাইভ মিটারের প্যানেল খোলে
                            if (c.card == null) DockBtn(
                                "🎙️",
                                if (c.noise > 0.001f) "নয়েজ ${Math.round(c.noise * 100)}%" else "নয়েজ ক্যানসেল",
                                active = c.noise > 0.001f || editOpen,
                            ) { onToggleEdit() }
                        }
                        DockBtn("🔍", "জুম +") {
                            tl.setClipZoomPan(c.id, zoom = c.zoom + 0.25f)
                            onInfo("🔍 জুম ${Math.round((c.zoom + 0.25f).coerceIn(1f, 4f) * 100)}%")
                        }
                        DockBtn("🔍", "জুম −") {
                            tl.setClipZoomPan(c.id, zoom = c.zoom - 0.25f)
                            onInfo("🔍 জুম ${Math.round((c.zoom - 0.25f).coerceIn(1f, 4f) * 100)}%")
                        }
                        DockBtn("↺", "জুম রিসেট", enabled = c.zoom > 1.001f || c.panX != 0f || c.panY != 0f) {
                            tl.setClipZoomPan(c.id, zoom = 1f, panX = 0f, panY = 0f)
                        }
                        if (c.card == null) DockBtn("crop", "ক্রপ", active = c.hasCrop()) {
                            val loc = tl.locate(tl.playheadMs)
                            cropMs = if (loc != null && loc.first.id == c.id) loc.second
                            else if (c.isImage) 0L else (c.startSec * 1000f).toLong()
                            cropOpen = true
                        }
                        DockBtn("↔️", "ফ্লিপ", active = c.flipH) {
                            tl.toggleFlip(c.id, true)?.let { onInfo(if (it) "↔️ ডান-বাম ফ্লিপ চালু" else "↔️ ডান-বাম ফ্লিপ বন্ধ") }
                        }
                        DockBtn("↕️", "উল্টো", active = c.flipV) {
                            tl.toggleFlip(c.id, false)?.let { onInfo(if (it) "↕️ ওপর-নিচ ফ্লিপ চালু" else "↕️ ওপর-নিচ ফ্লিপ বন্ধ") }
                        }
                        DockBtn("📋", "ডুপ্লিকেট") { tl.duplicate(c.id) }
                        DockBtn("🗑️", "মুছুন") { tl.remove(c.id) }
                        DockBtn("⏪", "সরান", enabled = idx > 0) { tl.move(c.id, -1) }
                        DockBtn("⏩", "সরান", enabled = idx in 0 until tl.clips.lastIndex) { tl.move(c.id, 1) }
                        DockBtn("⟲", "রিসেট") {
                            val m = tl.resetClipCuts(c.id)
                            onMessage(m)
                            if (m == null) onInfo("⟲ সব কাট বাতিল হয়েছে")
                        }
                    }

                    // ---- অডিও লেয়ার বাছা ----
                    selLayer is AudioLayer -> {
                        val a = selLayer
                        val group = tl.layers.filter { it.isAudio }
                        val gi = group.indexOfFirst { it.id == a.id }
                        DockBtn("🔙", "মেনু") { toMenu() }
                        DockBtn("✏️", "এডিট", active = editOpen) { onToggleEdit() }
                        DockBtn("🔔", "বিপ") { act.beep() }
                        DockBtn("🔊", "ভলিউম +") {
                            val nv = (Math.round(a.volume * 10f) / 10f + 0.1f).coerceIn(0f, 2f)
                            tl.editAudio(a.id) { it.copy(volume = nv, muted = false) }
                            onInfo("🔊 ভলিউম ${Math.round(nv * 100)}%" + if (nv >= 2f) " (সর্বোচ্চ)" else "")
                        }
                        DockBtn("🔉", "ভলিউম −") {
                            val nv = (Math.round(a.volume * 10f) / 10f - 0.1f).coerceIn(0f, 2f)
                            tl.editAudio(a.id) { it.copy(volume = nv) }
                            onInfo("🔉 ভলিউম ${Math.round(nv * 100)}%")
                        }
                        DockBtn(
                            if (a.muted) "🔊" else "🔇",
                            if (a.muted) "আনমিউট" else "মিউট",
                            active = a.muted,
                        ) { tl.editAudio(a.id) { it.copy(muted = !it.muted) } }
                        // প্রতি চাপে ০ → ১ → ২ → ০ সেকেন্ড (HTML-এর মতো)
                        DockBtn("🌅", if (a.fadeInMs > 0L) "ফেড ইন ${a.fadeInMs / 1000}সে" else "ফেড ইন", active = a.fadeInMs > 0L) {
                            val n = (a.fadeInMs + 1000L) % 3000L
                            tl.editAudio(a.id) { it.copy(fadeInMs = n) }
                            onInfo(if (n == 0L) "🌅 ফেড ইন বন্ধ" else "🌅 ফেড ইন ${n / 1000} সেকেন্ড")
                        }
                        DockBtn("🌇", if (a.fadeOutMs > 0L) "ফেড আউট ${a.fadeOutMs / 1000}সে" else "ফেড আউট", active = a.fadeOutMs > 0L) {
                            val n = (a.fadeOutMs + 1000L) % 3000L
                            tl.editAudio(a.id) { it.copy(fadeOutMs = n) }
                            onInfo(if (n == 0L) "🌇 ফেড আউট বন্ধ" else "🌇 ফেড আউট ${n / 1000} সেকেন্ড")
                        }
                        DockBtn("🎙️", if (a.denoise) "নয়েজ ফেরান" else "নয়েজ কমান", active = a.denoise) {
                            tl.editAudio(a.id) { it.copy(denoise = !it.denoise) }
                            onInfo(if (a.denoise) "🎙️ আসল অডিও ফিরেছে" else "🎙️ নয়েজ কমানো চালু (প্রিভিউ ও রেন্ডারে)")
                        }
                        DockBtn("✂️", "স্প্লিট") {
                            val m = tl.splitAudioLayer(a.id)
                            onMessage(m)
                            if (m == null) onInfo("✂️ অডিও দুই ভাগ হয়েছে")
                        }
                        DockBtn("⬆", "ওপরে", enabled = gi in 0 until group.lastIndex) { tl.moveLayer(a.id, 1) }
                        DockBtn("⬇", "নিচে", enabled = gi > 0) { tl.moveLayer(a.id, -1) }
                        DockBtn("📋", "ডুপ্লিকেট") { tl.duplicateLayer(a.id) }
                        DockBtn("🗑️", "মুছুন") { tl.removeLayer(a.id) }
                    }

                    // ---- ভিজ্যুয়াল লেয়ার বাছা ----
                    selLayer != null -> {
                        val l = selLayer
                        val group = tl.layers.filter { !it.isAudio }
                        val gi = group.indexOfFirst { it.id == l.id }
                        DockBtn("🔙", "মেনু") { toMenu() }
                        DockBtn("✏️", "এডিট", active = editOpen) { onToggleEdit() }
                        if (l is AdjustLayer) {
                            fun step(d: Float) {
                                val nv = ((Math.round(l.brightness * 10f) / 10f) + d).coerceIn(-0.8f, 0.8f)
                                tl.updateLayer(l.id) { (it as? AdjustLayer)?.copy(brightness = nv) ?: it }
                                onInfo("☀️ ব্রাইটনেস ${if (nv > 0f) "+" else ""}${Math.round(nv * 100)}%")
                            }
                            DockBtn("🔆", "উজ্জ্বল +") { step(0.1f) }
                            DockBtn("🔅", "উজ্জ্বল −") { step(-0.1f) }
                        }
                        if (l.isSingletonLayer()) {
                            // লোগো/স্ক্রলিং লেখা লেয়ার নয়: ওপর-নিচ/কপি নেই, শুধু এডিট ও মুছুন
                            DockBtn("🗑️", if (l is TickerLayer) "স্ক্রলিং মুছুন" else "লোগো মুছুন") { tl.removeLayer(l.id); toMenu() }
                        } else {
                            DockBtn("⬆", "ওপরে", enabled = tl.canMoveLayer(l.id, 1)) { tl.moveLayer(l.id, 1) }
                            DockBtn("⬇", "নিচে", enabled = tl.canMoveLayer(l.id, -1)) { tl.moveLayer(l.id, -1) }
                            if (l !is OverlayLayer) DockBtn("📋", "কপি") { tl.duplicateLayer(l.id) }
                            DockBtn("🗑️", "লেয়ার মুছুন") { tl.removeLayer(l.id) }
                        }
                    }

                    // ---- কিছু বাছা নেই: মূল টুলবার (৮টা আইকন); কয়েকটা চাপলে ভেতরের সারি খোলে ----
                    else -> {
                        val g = group
                        if (g == null) {
                            DockBtn("music", "অডিও") { group = "audio" }
                            DockBtn("text", "লেখা") { act.add(LayerKind.TEXT) }
                            DockBtn("image", "ছবি") { act.add(LayerKind.IMAGE) }
                            DockBtn("overlay", "ওভারলে") { act.add(LayerKind.OVERLAY) }
                            DockBtn("sticker", "ব্র্যান্ড") { group = "brand" }
                            DockBtn("adBottom", "বিজ্ঞাপন") { group = "ads" }
                            DockBtn(
                                "clapper", "ইন্ট্রো/আউট্রো",
                                active = tl.cardClip(false) != null || tl.cardClip(true) != null,
                            ) { group = "card" }
                            DockBtn("sliders", "এডজাস্ট") { act.add(LayerKind.ADJUST) }
                        } else {
                            DockBtn("back", "মেনু") { group = null }
                            when (g) {
                                "audio" -> {
                                    DockBtn("music", "অডিও ফাইল") { act.addAudioFile() }
                                    DockBtn("bell", "বিপ") { act.beep() }
                                    DockBtn(
                                        "mic",
                                        if (rec) "রেকর্ড হচ্ছে…" else "ভয়েস (চেপে ধরুন)",
                                        modifier = act.voiceModifier,
                                        active = rec,
                                    )
                                }
                                "brand" -> {
                                    DockBtn("logo", "লোগো", active = tl.layers.any { it.isLogoLayer() }) { act.add(LayerKind.LOGO) }
                                    DockBtn("pin", "ট্যাগ", active = tl.layers.any { it is TagLayer }) { act.add(LayerKind.TAG) }
                                    DockBtn("user", "নাম/পদবি") { act.add(LayerKind.NAME_BAR) }
                                    DockBtn("ticker", "স্ক্রলিং লেখা", active = tl.layers.any { it is TickerLayer }) { act.add(LayerKind.TICKER) }
                                }
                                "ads" -> {
                                    DockBtn("adBottom", "নিচের বিজ্ঞাপন") { act.add(LayerKind.AD_BOTTOM) }
                                    DockBtn("adTop", "ওপরের বিজ্ঞাপন") { act.add(LayerKind.AD_TOP) }
                                }
                                "card" -> {
                                    DockBtn("clapper", "রেডি ইন্ট্রো", active = tl.cardClip(false) != null) {
                                        if (tl.cardClip(false) == null && tl.clips.isNotEmpty()) { onMessage(null); cardDlg = false; return@DockBtn }
                                        val m = vm.addCard(false, onInfo)
                                        onMessage(m)
                                        if (m == null) onInfo(if (tl.cardClip(false) != null) "🎬 ইন্ট্রো বাছা হয়েছে — লেখা/স্টাইল বদলাতে ✏️ এডিট চাপুন" else "🎬 ইন্ট্রো বানানো হচ্ছে…")
                                    }
                                    DockBtn("flag", "রেডি আউট্রো", active = tl.cardClip(true) != null) {
                                        if (tl.cardClip(true) == null && tl.clips.isNotEmpty()) { onMessage(null); cardDlg = true; return@DockBtn }
                                        val m = vm.addCard(true, onInfo)
                                        onMessage(m)
                                        if (m == null) onInfo(if (tl.cardClip(true) != null) "🏁 আউট্রো বাছা হয়েছে — স্টাইল বদলাতে ✏️ এডিট চাপুন" else "🏁 আউট্রো বানানো হচ্ছে…")
                                    }
                                    DockBtn("plus", "সামনে ভিডিও") {
                                        if (tl.clips.isEmpty()) onMessage("আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন")
                                        else {
                                            onMessage(null)
                                            pickFront = true
                                            pickEdge.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                        }
                                    }
                                    DockBtn("plus", "পিছনে ভিডিও") {
                                        if (tl.clips.isEmpty()) onMessage("আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন")
                                        else {
                                            onMessage(null)
                                            pickFront = false
                                            pickEdge.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                        }
                                    }
                                    if (vm.cardBusy) {
                                        Text(
                                            "🎬 কার্ড বানানো হচ্ছে… ${vm.cardProgress}%",
                                            color = ED.Sub, fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp).align(androidx.compose.ui.Alignment.CenterVertically),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
    }
}

/** ডকে কিছু বাছলে তার এডিট প্যানেল (ক্লিপ: ট্রিম/সময়/কার্ড; লেয়ার: তার সেটিং) — স্ক্রল অংশে বসে */
@Composable
fun EditorDockPanel(
    tl: Timeline,
    act: DockActions,
    vm: EditorViewModel,
    editOpen: Boolean,
    onMessage: (String?) -> Unit,
    onInfo: (String?) -> Unit,
) {
    val selClip = tl.clipById(tl.selectedId)
    val selLayer = tl.layerById(tl.selectedLayerId)
    EditorDarkTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ---- নিচের এডিট প্যানেল ----
            when {
                // HTML-এ ক্লিপের আলাদা প্যানেল নেই (ট্রিম = টাইমলাইনের হাতল টানা); শুধু ইন্ট্রো/আউট্রো কার্ডের লেখা বদলানোর প্যানেল থাকে
                selClip != null -> if (editOpen) {
                    val card = selClip.card
                    if (card != null) Column(Modifier.edPanel()) { CardEditor(vm, selClip, card, onInfo) }
                    else if (!selClip.isImage) ClipNoiseEditor(tl, selClip)
                }
                selLayer != null && editOpen -> {
                    if (selLayer is AudioLayer) AudioEditor(tl, selLayer)
                    else LayerEditor(tl, selLayer) { act.replaceImage(selLayer.id) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// বাছা ক্লিপের প্যানেল: ট্রিম / ছবির সময় (বাকি টুল ডকে)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipEditor(tl: Timeline, c: Clip, vm: EditorViewModel, onMessage: (String?) -> Unit, onInfo: (String?) -> Unit) {
    val idx = tl.clips.indexOfFirst { it.id == c.id }
    Column(Modifier.edPanel(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("✂️ ক্লিপ ${idx + 1}: ${c.name}", fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            (if (c.isImage) "ছবি" else "ভিডিও") + " · টাইমলাইনে ${fmtTime(c.lengthMs())}" +
                if (c.isImage) "" else if (c.muted) " · শব্দ মিউট 🔇" else " · শব্দ ${Math.round(c.volume * 100)}%",
            color = ED.Muted, fontSize = 13.sp,
        )
        c.card?.let { CardEditor(vm, c, it, onInfo) }
        if (c.isImage) {
            Text("ছবি দেখানোর সময়: ${"%.1f".format(c.imageSec)} সে.", fontSize = 13.sp, color = ED.Sub)
            var v by remember(c.id, c.imageSec) { mutableStateOf(c.imageSec) }
            Slider(
                value = v.coerceIn(0.5f, 30f),
                onValueChange = { v = it },
                onValueChangeFinished = { tl.setImageSec(c.id, v) },
                valueRange = 0.5f..30f,
            )
        } else if (c.durationMs > 0L) {
            val dur = c.durationMs / 1000f
            var r by remember(c.id, c.startSec, c.endSec) {
                mutableStateOf(c.startSec.coerceIn(0f, dur)..c.effEndSec().coerceIn(0f, dur))
            }
            Text(
                "ট্রিম: ${"%.1f".format(r.start)} সে. → ${"%.1f".format(r.endInclusive)} সে. (ভিডিও ${"%.1f".format(dur)} সে.)",
                fontSize = 13.sp, color = ED.Sub,
            )
            RangeSlider(
                value = r,
                onValueChange = { r = it },
                onValueChangeFinished = { tl.trim(c.id, r.start, r.endInclusive) },
                valueRange = 0f..dur,
            )
        } else {
            Text("এই ভিডিওর দৈর্ঘ্য জানা যায়নি, তাই ট্রিম/কাটা বন্ধ।", color = ED.Muted, fontSize = 13.sp)
        }

        // ---- ০.১ সেকেন্ড নাজ ----
        val nudge = { which: Int, d: Float -> onMessage(tl.nudgeClip(c.id, which, d)) }
        if (c.isImage) {
            Text("ছবির সময় ০.১ সে. করে", fontSize = 13.sp, color = ED.Sub)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallBtn("সময় −০.১", Modifier.weight(1f)) { nudge(1, -0.1f) }
                SmallBtn("সময় +০.১", Modifier.weight(1f)) { nudge(1, 0.1f) }
            }
        } else if (c.durationMs > 0L) {
            Text("ট্রিম নাজ (০.১ সে. করে)", fontSize = 13.sp, color = ED.Sub)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallBtn("শুরু −০.১", Modifier.weight(1f)) { nudge(0, -0.1f) }
                SmallBtn("শুরু +০.১", Modifier.weight(1f)) { nudge(0, 0.1f) }
                SmallBtn("শেষ −০.১", Modifier.weight(1f)) { nudge(1, -0.1f) }
                SmallBtn("শেষ +০.১", Modifier.weight(1f)) { nudge(1, 0.1f) }
            }
        }

        // ---- ট্রানজিশন (HTML-এর মতো সাতটা): এই ক্লিপ ও পরের ক্লিপের জোড়ায়, কাটের ±০.২৮ সেকেন্ড ----
        val ci = tl.clips.indexOfFirst { it.id == c.id }
        if (ci in 0 until tl.clips.lastIndex) {
            Text("🎞️ ট্রানজিশন (পরের ক্লিপের সাথে জোড়ায়): ${ClipTr.label(c.trans)}", fontSize = 13.sp, color = ED.Sub)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OptionTile(label = "কোনোটি নয়", selected = c.trans.isEmpty()) {
                    tl.setClipTransition(c.id, "")
                    onInfo("🎞️ ট্রানজিশন বন্ধ")
                }
                ClipTr.LIST.forEach { (k, ic, nm) ->
                    OptionTile(label = nm, selected = c.trans == k, icon = ic) {
                        tl.setClipTransition(c.id, k)
                        onInfo("🎞️ ট্রানজিশন: $nm")
                    }
                }
            }
        }

        // ---- জুম / পজিশন ----
        Text(
            "🔍 জুম: ${Math.round(c.zoom * 100)}%" +
                (if (c.flipH) " · ↔️ ফ্লিপ" else "") + (if (c.flipV) " · ↕️ ফ্লিপ" else ""),
            fontSize = 13.sp, color = ED.Sub,
        )
        Slider(
            value = c.zoom.coerceIn(Timeline.ZOOM_MIN, Timeline.ZOOM_MAX),
            onValueChange = { tl.setClipZoomPan(c.id, zoom = it) },
            valueRange = Timeline.ZOOM_MIN..Timeline.ZOOM_MAX,
        )
        val canPan = c.zoom > 1.001f
        Text(
            if (canPan) "অবস্থান ← →  (জুম করা ছবির কোন অংশ দেখাবে)" else "অবস্থান: আগে জুম বাড়ান",
            fontSize = 13.sp, color = if (canPan) ED.Sub else ED.Muted,
        )
        Slider(
            value = c.panX.coerceIn(-1f, 1f),
            onValueChange = { tl.setClipZoomPan(c.id, panX = it) },
            valueRange = -1f..1f,
            enabled = canPan,
        )
        Text("অবস্থান ↑ ↓", fontSize = 13.sp, color = if (canPan) ED.Sub else ED.Muted)
        Slider(
            value = c.panY.coerceIn(-1f, 1f),
            onValueChange = { tl.setClipZoomPan(c.id, panY = it) },
            valueRange = -1f..1f,
            enabled = canPan,
        )
        if (c.hasTransform() || c.panX != 0f || c.panY != 0f) {
            SmallBtn("↺ জুম/অবস্থান/ফ্লিপ রিসেট", Modifier.fillMaxWidth()) {
                tl.resetClipTransform(c.id)
                onInfo("↺ ক্লিপ আগের অবস্থায় ফিরেছে")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ইন্ট্রো/আউট্রো কার্ডের এডিটর: স্টাইল, শিরোনাম, উপশিরোনাম (সময় নিচের ছবির-সময় স্লাইডারে)
// ---------------------------------------------------------------------------

@Composable
private fun CardEditor(vm: EditorViewModel, c: Clip, spec: CardSpec, onInfo: (String?) -> Unit) {
    var style by remember(c.id, spec) { mutableStateOf(spec.style) }
    var title by remember(c.id, spec) { mutableStateOf(spec.title) }
    var sub by remember(c.id, spec) { mutableStateOf(spec.subtitle) }
    // আউট্রোর লেখা ফিক্সড ("ধন্যবাদ! আমাদের সাথে থাকুন" + লাইক/কমেন্ট/শেয়ার), তাই শুধু ইন্ট্রোর লেখা বদলানো যায়
    val changed = style != spec.style || (!spec.outro && (title != spec.title || sub != spec.subtitle)) ||
        (vm.logo != spec.logo)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (spec.outro) "🏁 আউট্রো কার্ড" else "🎬 ইন্ট্রো কার্ড", fontWeight = FontWeight.Bold, color = ED.Amber, fontSize = 13.sp)
        // বাছা স্টাইল/লেখা/লোগো দিয়ে কার্ড ঠিক কেমন হবে তার প্রিভিউ + প্রতিটা স্টাইলের থাম্বনেইল (HTML-এর মতো)
        val live = spec.copy(style = style, title = if (spec.outro) spec.title else title, subtitle = if (spec.outro) spec.subtitle else sub, logo = vm.logo)
        CardPreviewBox(live, vm.aspect, maxHeight = 150.dp)
        CardStyleStrip(live, vm.aspect) { style = it }
        if (!spec.outro) {
            androidx.compose.material3.OutlinedTextField(
                value = title, onValueChange = { title = it.take(40) },
                label = { Text("বড় লেখা (যেমন: ব্রেকিং নিউজ)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.material3.OutlinedTextField(
                value = sub, onValueChange = { sub = it.take(70) },
                label = { Text("নিচের ছোট লেখা (যেমন: সর্বশেষ সংবাদ • সবার আগে)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            if (vm.logo != null) "নিচের \"লোগো\" বাছা আছে, তাই কার্ডে লোগোও বসবে।" else "লোগো বাছা থাকলে কার্ডে তা-ও বসবে।",
            color = ED.Muted, fontSize = 11.sp,
        )
        val busy = vm.cardBusy
        if (busy) {
            Text("🎬 কার্ড বানানো হচ্ছে… ${vm.cardProgress}%", color = ED.Sub, fontSize = 12.sp)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { vm.cardProgress / 100f }, modifier = Modifier.fillMaxWidth(),
            )
        }
        SmallBtn(
            if (changed) "🔄 কার্ডে প্রয়োগ করুন" else "✔ কার্ড হালনাগাদ",
            Modifier.fillMaxWidth(), enabled = changed && !busy,
        ) {
            vm.updateCard(
                c.id,
                spec.copy(style = style, title = if (spec.outro) spec.title else title, subtitle = if (spec.outro) spec.subtitle else sub, logo = vm.logo),
                onResult = onInfo,
            )
            onInfo("🔄 কার্ড বদলানো হচ্ছে…")
        }
    }
}
