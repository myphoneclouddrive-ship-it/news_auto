package com.digitalcrafting25.newsapp

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** ওয়েবের "ফিচার ইমেজ" মাপের তালিকা */
val FI_SIZES = listOf(
    "original" to "আসল ছবি, ক্রপ ছাড়া",
    "1200x630" to "১২০০×৬৩০ (ফেসবুক/সোশ্যাল শেয়ার)",
    "1200x675" to "১২০০×৬৭৫ (১৬:৯)",
    "1280x720" to "১২৮০×৭২০ (HD ১৬:৯)",
    "1200x900" to "১২০০×৯০০ (৪:৩)",
    "1080x1080" to "১০৮০×১০৮০ (স্কয়ার)",
    "custom" to "নিজের মাপ দিন",
)

/** সেটিংসের "সাইট" ট্যাব: লোগো, আইকন, সাইটের তথ্য, ফিচার ইমেজের মাপ (ধাপ ২খ) */
class SiteViewModel(private val app: Application) : AndroidViewModel(app) {
    var loaded by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)

    var siteName by mutableStateOf("")
    var siteTagline by mutableStateOf("")
    var siteUrl by mutableStateOf("")
    var category by mutableStateOf("")
    var photoTag by mutableStateOf("")
    var ctaText by mutableStateOf("")
    var logo by mutableStateOf("")
        private set
    var icon by mutableStateOf("")
        private set
    var iconShape by mutableStateOf("round")
    /** "original" বা প্রিসেট ("1200x630", ..., "custom") */
    var fiSize by mutableStateOf("original")
    var fiW by mutableStateOf("1200")
    var fiH by mutableStateOf("630")
    var fiFit by mutableStateOf("stretch")

    fun reset() {
        loaded = false; loadError = null; busy = false; notice = null
        siteName = ""; siteTagline = ""; siteUrl = ""; category = ""; photoTag = ""; ctaText = ""
        logo = ""; icon = ""; iconShape = "round"; fiSize = "original"; fiW = "1200"; fiH = "630"; fiFit = "stretch"
    }

    fun load(force: Boolean = false) {
        if (busy || (loaded && !force)) return
        busy = true
        viewModelScope.launch {
            try {
                val c = Fs.cardCfg(app)
                siteName = c.siteName; siteTagline = c.siteTagline; siteUrl = c.siteUrl
                category = c.category; photoTag = c.photoTag; ctaText = c.ctaText
                logo = c.logo; icon = c.icon; iconShape = c.iconShape
                fiSize = if (c.fiMode == "fixed") c.fiPreset.ifBlank { "1200x630" } else "original"
                fiW = c.fiW.toString(); fiH = c.fiH.toString(); fiFit = c.fiFit
                loaded = true; loadError = null
            } catch (e: Exception) {
                loadError = e.message ?: "সাইটের তথ্য আনা যায়নি"
            } finally {
                busy = false
            }
        }
    }

    /** ছবি ছোট করে PNG data URL বানায় (ওয়েবের মতো: লোগো সর্বোচ্চ ৫০০px, আইকন ৩০০px) */
    private suspend fun toDataUrl(uri: Uri, maxDim: Int): String = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ApiError("ছবিটা পড়া যায়নি")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw ApiError("ছবিটা পড়া যায়নি")
        val k = minOf(1f, maxDim.toFloat() / maxOf(raw.width, raw.height))
        val bmp = if (k < 1f) Bitmap.createScaledBitmap(raw, (raw.width * k).toInt().coerceAtLeast(1), (raw.height * k).toInt().coerceAtLeast(1), true) else raw
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun pickLogo(uri: Uri) = pick(uri, 500) { logo = it }
    fun pickIcon(uri: Uri) = pick(uri, 300) { icon = it }
    fun clearLogo() { logo = "" }
    fun clearIcon() { icon = "" }

    private fun pick(uri: Uri, max: Int, set: (String) -> Unit) {
        viewModelScope.launch {
            try { set(toDataUrl(uri, max)); notice = "ছবি বসানো হয়েছে। নিচের \"সেভ করুন\" চাপলে সেভ হবে।" }
            catch (e: Exception) { notice = "⚠️ " + (e.message ?: "ছবি নেওয়া যায়নি") }
        }
    }

    fun save() {
        if (busy) return
        val fixed = fiSize != "original"
        var w = fiW.trim().toIntOrNull() ?: 0
        var h = fiH.trim().toIntOrNull() ?: 0
        if (fixed && fiSize == "custom") {
            if (w !in 200..4000 || h !in 200..4000) { notice = "⚠️ মাপ ২০০ থেকে ৪০০০ px-এর মধ্যে দিন"; return }
        } else if (fixed) {
            val p = fiSize.split("x")
            w = p.getOrNull(0)?.toIntOrNull() ?: 1200; h = p.getOrNull(1)?.toIntOrNull() ?: 630
        } else {
            if (w !in 200..4000) w = 1200
            if (h !in 200..4000) h = 630
        }
        val cfg = CardCfg(
            siteName = siteName.trim(), siteUrl = siteUrl.trim(), category = category.trim(),
            photoTag = photoTag.trim(), ctaText = ctaText.trim(), siteTagline = siteTagline.trim(),
            logo = logo, icon = icon, iconShape = iconShape,
            fiMode = if (fixed) "fixed" else "original", fiPreset = if (fixed) fiSize else "1200x630",
            fiW = w, fiH = h, fiFit = fiFit,
        )
        busy = true
        viewModelScope.launch {
            try {
                Fs.saveCardCfg(app, cfg)
                notice = "✅ সেভ হয়েছে"
            } catch (e: Exception) {
                notice = "⚠️ " + (e.message ?: "সেভ হয়নি")
            } finally {
                busy = false
            }
        }
    }
}
