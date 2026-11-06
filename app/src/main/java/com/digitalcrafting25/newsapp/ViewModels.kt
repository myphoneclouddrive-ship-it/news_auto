package com.digitalcrafting25.newsapp

import android.app.Application
import android.app.DownloadManager
import android.os.Environment
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(private val app: Application) : AndroidViewModel(app) {
    val clips = mutableStateListOf<Clip>()
    var aspect by mutableStateOf(Aspect.LANDSCAPE)
    var caption by mutableStateOf("")
    var logo by mutableStateOf<Uri?>(null)
    var music by mutableStateOf<Uri?>(null)
    var musicName by mutableStateOf("")
    var musicVolume by mutableFloatStateOf(0.3f)
    var keepAudio by mutableStateOf(true)
    private var nextId = 1L

    private fun displayName(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: "ফাইল"

    fun addUris(uris: List<Uri>) {
        viewModelScope.launch {
            for (u in uris) {
                val clip = withContext(Dispatchers.IO) {
                    val isImage = app.contentResolver.getType(u)?.startsWith("image/") == true
                    var dur = 0L
                    if (!isImage) {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(app, u)
                            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } catch (_: Exception) {
                        } finally {
                            runCatching { r.release() }
                        }
                    }
                    Clip(nextId++, u, isImage, dur, displayName(u))
                }
                clips.add(clip)
            }
        }
    }

    fun chooseMusic(u: Uri) {
        music = u
        musicName = displayName(u)
    }

    fun update(id: Long, f: (Clip) -> Clip) {
        val i = clips.indexOfFirst { it.id == id }
        if (i >= 0) clips[i] = f(clips[i])
    }

    fun move(index: Int, delta: Int) {
        val j = index + delta
        if (index !in clips.indices || j !in clips.indices) return
        val c = clips.removeAt(index)
        clips.add(j, c)
    }

    fun remove(index: Int) {
        if (index in clips.indices) clips.removeAt(index)
    }

    /** সমস্যা থাকলে বার্তা ফেরত দেয়, ঠিক থাকলে রেন্ডার সার্ভিস চালু করে ও null দেয় */
    fun startRender(): String? {
        if (clips.isEmpty()) return "আগে অন্তত একটা ভিডিও বা ছবি যোগ করুন"
        for ((i, c) in clips.withIndex()) {
            if (c.isImage) {
                if (c.imageSec <= 0f) return "ক্লিপ ${i + 1}: ছবির সময় ০ এর বেশি দিন"
            } else {
                val durSec = c.durationMs / 1000f
                if (c.startSec < 0f || (durSec > 0 && c.startSec >= durSec)) return "ক্লিপ ${i + 1}: শুরুর সময় ভিডিওর দৈর্ঘ্যের মধ্যে দিন"
                if (c.endSec != 0f && c.endSec <= c.startSec) return "ক্লিপ ${i + 1}: শেষ সময় শুরুর চেয়ে বেশি দিন (০ = শেষ পর্যন্ত)"
            }
        }
        RenderBus.spec = RenderSpec(clips.toList(), aspect, caption, logo, music, musicVolume, keepAudio)
        RenderBus.status.value = RenderStatus.Running(0)
        ContextCompat.startForegroundService(app, Intent(app, RenderService::class.java))
        return null
    }

    fun cancelRender() {
        app.startService(Intent(app, RenderService::class.java).setAction(RenderService.ACTION_CANCEL))
    }
}

data class UpdateUiState(
    val busy: Boolean = false,
    val message: String = "",
    val available: UpdateInfo? = null,
    val showPopup: Boolean = false,
)

class UpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** ম্যানুয়াল আপডেট চেক */
    fun check() {
        if (_state.value.busy) return
        _state.value = UpdateUiState(busy = true, message = "চেক করা হচ্ছে…")
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                _state.value = when {
                    info == null -> UpdateUiState(message = "কোনো রিলিজ পাওয়া যায়নি")
                    Updater.isNewer(info) -> UpdateUiState(message = "🎉 নতুন সংস্করণ পাওয়া গেছে: ${info.versionName}", available = info)
                    else -> UpdateUiState(message = "✅ আপনার অ্যাপ আপ-টু-ডেট")
                }
            } catch (e: Exception) {
                _state.value = UpdateUiState(message = "❌ চেক করা যায়নি: ${e.message}")
            }
        }
    }

    /**
     * অটো চেক: অ্যাপ খুললে (সর্বোচ্চ ৬ ঘণ্টায় একবার) চুপচাপ চেক করে।
     * নতুন ভার্সন থাকলে হোম পেজে পপ-আপ দেখায়। নেট/GitHub সমস্যায় কিছুই দেখায় না।
     */
    fun autoCheck() {
        if (autoChecked) return
        autoChecked = true
        val prefs = app.getSharedPreferences("updater", android.content.Context.MODE_PRIVATE)
        val last = prefs.getLong("lastCheck", 0L)
        if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000L) return
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                if (info != null && Updater.isNewer(info)) {
                    _state.update { it.copy(available = info, showPopup = true) }
                }
            } catch (_: Exception) { /* চুপচাপ বাদ — পরের বার আবার চেক হবে */ }
        }
    }

    fun dismissPopup() = _state.update { it.copy(showPopup = false) }

    /**
     * অ্যাপের ভেতর থেকেই ডাউনলোড শুরু করে (সিস্টেমের DownloadManager দিয়ে)।
     * ডাউনলোড শেষে নোটিফিকেশনে ট্যাপ করলে ফোনের ইন্সটলার খোলে — ইন্সটল আপনি নিজে কনফার্ম করবেন।
     * অ্যাপে কোনো ইন্সটল-পারমিশন লাগে না।
     */
    fun startDownload() {
        val info = _state.value.available ?: return
        try {
            val req = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("নিউজ পাবলিশার ${info.versionName}")
                .setDescription("আপডেট ডাউনলোড হচ্ছে")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "news-publisher-${info.versionName}.apk")
            (app.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            _state.update {
                it.copy(
                    showPopup = false,
                    message = "⬇️ ডাউনলোড শুরু হয়েছে। শেষ হলে নোটিফিকেশনে ট্যাপ করে ইন্সটল করুন।",
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(message = "❌ ডাউনলোড শুরু করা যায়নি: ${e.message}") }
        }
    }

    private companion object { var autoChecked = false }
}
