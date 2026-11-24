package com.digitalcrafting25.newsapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** সেভ করা লগইন প্রোফাইল: ইমেইল + Firebase refresh token (পাসওয়ার্ড রাখা হয় না) */
data class SavedProfile(val email: String, val uid: String, val refresh: String)

/**
 * "লগইন সেভ রাখুন" টিক দিয়ে লগইন করলে প্রোফাইলটা ফোনে থাকে; লগআউটের পরেও ইমেইলে চাপ দিলে পাসওয়ার্ড ছাড়াই ঢোকা যায়।
 * সর্বোচ্চ ৩টা। refresh token Android Keystore-এর AES-GCM কী দিয়ে এনক্রিপ্ট করে রাখা হয় (অ্যাপের বাইরে পড়া যায় না)।
 * পাসওয়ার্ড বদলালে/অ্যাকাউন্ট বন্ধ হলে token অচল হয়, তখন পাসওয়ার্ড দিয়ে আবার লগইন করতে হয়।
 */
object Profiles {
    const val MAX = 3
    private const val PREFS = "profiles"
    private const val KEY = "list"
    private const val ALIAS = "np_profile_key"

    val list = MutableStateFlow<List<SavedProfile>>(emptyList())

    fun load(ctx: Context) {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val out = mutableListOf<SavedProfile>()
        if (!raw.isNullOrEmpty()) runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val rt = dec(o.optString("rt")) ?: continue   // ডিক্রিপ্ট না হলে (যেমন অন্য ফোনে ব্যাকআপ থেকে এসেছে) বাদ
                val email = o.optString("email")
                if (email.isNotBlank() && rt.isNotBlank()) out += SavedProfile(email, o.optString("uid"), rt)
            }
        }
        list.value = out.take(MAX)
    }

    fun has(email: String) = list.value.any { it.email.equals(email.trim(), ignoreCase = true) }
    fun isFull() = list.value.size >= MAX

    /** নতুন প্রোফাইল সেভ বা একই ইমেইলের token নতুন করা। ফেরত false = ৩টা ভরা, নতুনটা রাখা যায়নি */
    fun save(ctx: Context, email: String, uid: String, refresh: String): Boolean {
        val e = email.trim()
        if (e.isEmpty() || refresh.isEmpty()) return false
        val cur = list.value
        val idx = cur.indexOfFirst { it.email.equals(e, ignoreCase = true) }
        val next = when {
            idx >= 0 -> cur.toMutableList().also { it[idx] = SavedProfile(cur[idx].email, uid, refresh) }
            cur.size >= MAX -> return false
            else -> cur + SavedProfile(e, uid, refresh)
        }
        commit(ctx, next)
        return true
    }

    fun remove(ctx: Context, email: String) {
        commit(ctx, list.value.filterNot { it.email.equals(email, ignoreCase = true) })
    }

    /** চলতি সেশনের token বদলালে (রিফ্রেশ) সেভ করা কপিও নতুন রাখা */
    fun updateToken(ctx: Context, uid: String?, refresh: String) {
        if (uid.isNullOrEmpty() || refresh.isEmpty()) return
        val cur = list.value
        if (cur.none { it.uid == uid && it.refresh != refresh }) return
        commit(ctx, cur.map { if (it.uid == uid) it.copy(refresh = refresh) else it })
    }

    private fun commit(ctx: Context, next: List<SavedProfile>) {
        val a = JSONArray()
        next.forEach { p -> a.put(JSONObject().put("email", p.email).put("uid", p.uid).put("rt", enc(p.refresh))) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
        list.value = next
    }

    // ── Keystore AES-GCM ──
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    private fun enc(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun dec(s: String): String? = runCatching {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
