package com.digitalcrafting25.newsapp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val editorVm: EditorViewModel by viewModels()
    private val updateVm: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFC62828))) {
                Surface(Modifier.fillMaxSize()) { AppRoot(editorVm, updateVm) }
            }
        }
    }

    override fun onStart() { super.onStart(); AppState.foreground = true }
    override fun onStop() { super.onStop(); AppState.foreground = false }
}

@Composable
fun AppRoot(ev: EditorViewModel, uv: UpdateViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("🎬 ভিডিও এডিটর") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("🔄 আপডেট") })
        }
        if (tab == 0) EditorScreen(ev) else UpdateScreen(uv)
    }
}

private fun fmtMs(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
fun Thumb(uri: Uri) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(160, 160), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(Color(0xFFDDDDDD))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun NumField(label: String, value: Float, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    var text by remember { mutableStateOf(if (value == 0f) "" else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it.toFloatOrNull() ?: 0f) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val status by RenderBus.status.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addUris(it) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) vm.logo = u }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.chooseMusic(u) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("➕ ভিডিও / ছবি যোগ করুন") }

        if (vm.clips.isEmpty()) Text("এখনো কিছু যোগ করা হয়নি। যে ক্রমে যোগ করবেন, সেই ক্রমেই জোড়া লাগবে।")

        vm.clips.forEachIndexed { i, c ->
            key(c.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Thumb(c.uri)
                            Column(Modifier.weight(1f)) {
                                Text("${i + 1}. ${c.name}", maxLines = 1)
                                Text(if (c.isImage) "ছবি" else "ভিডিও · ${fmtMs(c.durationMs)}")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Text("⬆️") }
                            TextButton(onClick = { vm.move(i, 1) }, enabled = i < vm.clips.lastIndex) { Text("⬇️") }
                            TextButton(onClick = { vm.remove(i) }) { Text("🗑️ মুছুন") }
                        }
                        if (c.isImage) {
                            NumField("কত সেকেন্ড দেখাবে", c.imageSec, Modifier.fillMaxWidth()) { v -> vm.update(c.id) { it.copy(imageSec = v) } }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumField("শুরু (সে.)", c.startSec, Modifier.weight(1f)) { v -> vm.update(c.id) { it.copy(startSec = v) } }
                                NumField("শেষ (সে.) ০=শেষ পর্যন্ত", c.endSec, Modifier.weight(1f)) { v -> vm.update(c.id) { it.copy(endSec = v) } }
                            }
                        }
                    }
                }
            }
        }

        Text("ভিডিওর মাপ")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Aspect.values().forEach { a ->
                FilterChip(selected = vm.aspect == a, onClick = { vm.aspect = a }, label = { Text(a.label) })
            }
        }

        OutlinedTextField(
            value = vm.caption,
            onValueChange = { vm.caption = it },
            label = { Text("শিরোনাম (নিচে লাল বারে দেখাবে, ঐচ্ছিক)") },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (vm.logo == null) "🖼️ লোগো বাছুন" else "🖼️ লোগো বদলান") }
            if (vm.logo != null) TextButton(onClick = { vm.logo = null }) { Text("লোগো সরান") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickMusic.launch(arrayOf("audio/*")) }) {
                Text(if (vm.music == null) "🎵 মিউজিক বাছুন" else "🎵 বদলান")
            }
            if (vm.music != null) TextButton(onClick = { vm.music = null }) { Text("মিউজিক সরান") }
        }
        if (vm.music != null) {
            Text("মিউজিক: ${vm.musicName}", maxLines = 1)
            Text("মিউজিকের ভলিউম: ${(vm.musicVolume * 100).toInt()}%")
            Slider(value = vm.musicVolume, onValueChange = { vm.musicVolume = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(checked = vm.keepAudio, onCheckedChange = { vm.keepAudio = it })
            Text("ভিডিওর আসল সাউন্ড রাখুন", Modifier.padding(top = 12.dp))
        }

        error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error) }

        when (val s = status) {
            is RenderStatus.Running -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("রেন্ডার হচ্ছে… ${s.progress}%")
                    LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("অ্যাপ ব্যাকগ্রাউন্ডে গেলেও চলবে। নোটিফিকেশনে % দেখা যাবে।")
                    OutlinedButton(onClick = { vm.cancelRender() }) { Text("বাতিল") }
                }
            }
            is RenderStatus.Done -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✅ ভিডিও তৈরি হয়েছে (Movies/NewsPublisher)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, "video/mp4")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        }) { Text("▶️ দেখুন") }
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
                                .putExtra(Intent.EXTRA_STREAM, s.uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { ctx.startActivity(Intent.createChooser(send, "শেয়ার করুন")) }
                        }) { Text("📤 শেয়ার") }
                    }
                }
            }
            is RenderStatus.Failed -> Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
            RenderStatus.Idle -> {}
        }

        Button(
            onClick = { error = vm.startRender() },
            enabled = status !is RenderStatus.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("🎬 রেন্ডার শুরু করুন") }
    }
}

@Composable
fun UpdateScreen(vm: UpdateViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("বর্তমান সংস্করণ: ${BuildConfig.VERSION_NAME} (কোড ${BuildConfig.VERSION_CODE})")
        Text(vm.lastAuto())
        Button(onClick = { vm.check() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("🔍 এখনই আপডেট চেক করুন")
        }
        if (st.message.isNotEmpty()) Text(st.message)
        st.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        st.available?.let { info ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("নতুন সংস্করণ: ${info.versionName}")
                    if (info.notes.isNotBlank()) Text(info.notes)
                    Button(onClick = { vm.installNow() }, enabled = !st.busy) { Text("⬇️ ডাউনলোড ও ইন্সটল") }
                }
            }
        }
    }
}
