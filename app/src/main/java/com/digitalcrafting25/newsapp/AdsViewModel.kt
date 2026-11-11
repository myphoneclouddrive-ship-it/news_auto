package com.digitalcrafting25.newsapp

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** সেটিংসের "বিজ্ঞাপন" ট্যাব (ধাপ ২গ): সর্বোচ্চ ১০টি ব্যানার, আপলোড বা অ্যাপে বানানো */
class AdsViewModel(private val app: Application) : AndroidViewModel(app) {
    val ads = mutableStateListOf<Ad>()
    var adLast by mutableStateOf("")
        private set
    var loaded by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var dirty by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
    /** এখন যেটা সম্পাদনা হচ্ছে (-1 = কোনোটা না) */
    var editIdx by mutableStateOf(-1)
        private set
    private var newId = ""

    fun reset() {
        ads.clear(); adLast = ""; loaded = false; loadError = null; busy = false
        dirty = false; notice = null; editIdx = -1; newId = ""
    }

    fun load(force: Boolean = false) {
        if (busy || (loaded && !force)) return
        busy = true
        viewModelScope.launch {
            try {
                val (list, last) = Fs.adsCfg(app)
                ads.clear(); ads.addAll(list); adLast = last
                editIdx = -1; dirty = false; loaded = true; loadError = null
            } catch (e: Exception) {
                loadError = e.message ?: "বিজ্ঞাপনের তথ্য আনা যায়নি"
            } finally {
                busy = false
            }
        }
    }

    fun next(): Ad? = nextAd(ads, adLast)
    val current: Ad? get() = ads.getOrNull(editIdx)

    private fun edit(f: (Ad) -> Ad) {
        val i = editIdx
        if (i !in ads.indices) return
        ads[i] = f(ads[i]); dirty = true
    }

    /** একটায় টিক দিলে বাকিগুলোর টিক উঠে যায় (ওয়েবের adSelect) */
    private fun selectOnly(id: String) {
        for (i in ads.indices) ads[i] = ads[i].copy(on = ads[i].id == id)
    }

    fun newAd() {
        if (ads.size >= AD_MAX) { notice = "সর্বোচ্চ ${Bn.num(AD_MAX)}টি বিজ্ঞাপন সেভ করা যায়। নতুন করতে আগে একটা মুছুন।"; return }
        val a = Ad(id = newAdId())
        ads.add(a); newId = a.id; editIdx = ads.size - 1; dirty = true
    }

    fun startEdit(i: Int) { editIdx = i }

    fun remove(i: Int) {
        if (i !in ads.indices) return
        ads.removeAt(i); editIdx = -1; newId = ""; dirty = true
    }

    /** "✔ হয়েছে": কিছু না দিলে ফাঁকা বিজ্ঞাপন বাদ; নতুন হলে সেটাই টিক পায় */
    fun done() {
        val a = current
        if (a != null && !a.hasContent) { remove(editIdx); return }
        if (a != null && a.id == newId) selectOnly(a.id)
        newId = ""; editIdx = -1
    }

    fun setMode(m: String) = edit { it.copy(mode = m) }
    fun setT1(v: String) = edit { it.copy(t1 = v) }
    fun setT2(v: String) = edit { it.copy(t2 = v) }
    fun setT3(v: String) = edit { it.copy(t3 = v) }
    fun setBg1(v: String) = edit { it.copy(bg1 = v) }
    fun setBg2(v: String) = edit { it.copy(bg2 = v) }
    fun setC1(v: String) = edit { it.copy(c1 = v) }
    fun setC2(v: String) = edit { it.copy(c2 = v) }
    fun clearImg() = edit { it.copy(img = "") }
    fun clearLogo() = edit { it.copy(logo = "") }

    /** ব্যানার ছবি: মাপ ঠিক ১০৮০×১২০ না হলে নেওয়া হয় না (ওয়েবের নিয়ম) */
    fun pickBanner(uri: Uri) {
        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    val bmp = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: throw ApiError("ছবিটা পড়া যায়নি")
                    if (bmp.width != AD_W || bmp.height != AD_H) {
                        throw ApiError("ব্যানারের মাপ ঠিক নেই।\nআপনার ছবি: ${bmp.width}×${bmp.height} পিক্সেল\nলাগবে: ${AD_W}×${AD_H} পিক্সেল (কম বা বেশি হলে নেওয়া যাবে না)")
                    }
                    val flat = Bitmap.createBitmap(AD_W, AD_H, Bitmap.Config.ARGB_8888)
                    Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                    val out = ByteArrayOutputStream()
                    flat.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }
                edit { it.copy(img = url, mode = "upload") }
                current?.let { selectOnly(it.id) }
            } catch (e: Exception) {
                notice = "⚠️ " + (e.message ?: "ছবি নেওয়া যায়নি")
            }
        }
    }

    /** ডানের লোগো: সর্বোচ্চ ৩০০px, PNG */
    fun pickLogo(uri: Uri) {
        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0) throw ApiError("ছবিটা পড়া যায়নি")
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 300) sample *= 2
                    val raw = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                        ?: throw ApiError("ছবিটা পড়া যায়নি")
                    val k = minOf(1f, 300f / maxOf(raw.width, raw.height))
                    val bmp = if (k < 1f) Bitmap.createScaledBitmap(raw, (raw.width * k).toInt().coerceAtLeast(1), (raw.height * k).toInt().coerceAtLeast(1), true) else raw
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }
                edit { it.copy(logo = url) }
            } catch (e: Exception) {
                notice = "⚠️ " + (e.message ?: "ছবি নেওয়া যায়নি")
            }
        }
    }

    fun save() {
        if (busy) return
        // সম্পাদনা খোলা থাকলে সেটাও "হয়েছে" ধরে নেওয়া (ফাঁকা হলে বাদ)
        if (editIdx >= 0) done()
        busy = true
        viewModelScope.launch {
            try {
                // adLast এখন আর তালিকায় না থাকলে ফাঁকা করে দেওয়া (পরেরবার প্রথমটা থেকে ঘুরবে)
                val last = if (ads.any { it.id == adLast }) adLast else ""
                Fs.saveAds(app, ads.toList(), last)
                adLast = last; dirty = false
                notice = "✅ বিজ্ঞাপন সেভ হয়েছে"
            } catch (e: Exception) {
                notice = "⚠️ " + (e.message ?: "সেভ হয়নি")
            } finally {
                busy = false
            }
        }
    }
}
