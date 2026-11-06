package com.digitalcrafting25.newsapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** পুরোনো ওয়েব অ্যাপের একই Firebase প্রজেক্ট ও Worker — তাই ডেটা, ব্যালেন্স, পোস্ট সব এক */
object Cfg {
    const val WORKER = "https://news.mishaurav.workers.dev"
    const val SITE = "আপনার পোর্টালের নাম"
    const val API_KEY = "AIzaSyC-OAb_qGwkw_H00cEkJ0CtMIb-WqDRy3M"
    const val PROJECT = "news-auto-publish-f729b"
    const val PAY_INFO = "bKash / Nagad (Send Money): 01761767167"
}

class ApiError(message: String, val status: Int = 0) : Exception(message)

internal data class Resp(val code: Int, val text: String)

internal fun httpCall(
    method: String,
    url: String,
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
    contentType: String = "application/json; charset=utf-8",
    readMs: Int = 30000,
): Resp {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = readMs
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return Resp(code, text)
    } catch (e: SocketTimeoutException) {
        throw ApiError("সময় শেষ হয়ে গেছে, আবার চেষ্টা করুন")
    } catch (e: IOException) {
        throw ApiError("ইন্টারনেট সংযোগ নেই")
    } finally {
        c.disconnect()
    }
}

/** লগইন সেশন (Firebase Auth REST)। টোকেন ফোনে সংরক্ষিত, মেয়াদ শেষের আগে নিজে রিফ্রেশ হয় — ওয়েবের "লগইন মনে রাখা"র মতো */
object Session {
    private const val PREFS = "session"
    val state = MutableStateFlow(false)

    @Volatile var uid: String? = null
        private set
    @Volatile private var idToken = ""
    @Volatile private var refreshToken = ""
    @Volatile private var expiresAt = 0L
    private val lock = Mutex()

    fun restore(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        uid = p.getString("uid", null)
        refreshToken = p.getString("refresh", "") ?: ""
        idToken = p.getString("id", "") ?: ""
        expiresAt = p.getLong("exp", 0L)
        state.value = uid != null && refreshToken.isNotEmpty()
    }

    private fun persist(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("uid", uid).putString("refresh", refreshToken)
            .putString("id", idToken).putLong("exp", expiresAt).apply()
    }

    fun signOut(ctx: Context) {
        uid = null; idToken = ""; refreshToken = ""; expiresAt = 0L
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        state.value = false
    }

    private fun loginError(raw: String): String = when {
        raw.startsWith("INVALID_LOGIN_CREDENTIALS") || raw.startsWith("EMAIL_NOT_FOUND") || raw.startsWith("INVALID_PASSWORD") -> "ইমেইল বা পাসওয়ার্ড ভুল"
        raw.startsWith("INVALID_EMAIL") || raw.startsWith("MISSING_EMAIL") -> "ইমেইল ঠিক নয়"
        raw.startsWith("MISSING_PASSWORD") -> "পাসওয়ার্ড দিন"
        raw.startsWith("TOO_MANY_ATTEMPTS") -> "অনেকবার ভুল হয়েছে, কিছুক্ষণ পরে চেষ্টা করুন"
        raw.startsWith("USER_DISABLED") -> "এই অ্যাকাউন্ট বন্ধ করা আছে"
        else -> "লগইন হয়নি: $raw"
    }

    suspend fun signIn(ctx: Context, email: String, pass: String) {
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("email", email).put("password", pass).put("returnSecureToken", true).toString()
            val r = httpCall("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${Cfg.API_KEY}", body)
            val j = runCatching { JSONObject(r.text) }.getOrDefault(JSONObject())
            if (r.code !in 200..299) {
                throw ApiError(loginError(j.optJSONObject("error")?.optString("message").orEmpty()), r.code)
            }
            idToken = j.getString("idToken")
            refreshToken = j.getString("refreshToken")
            uid = j.getString("localId")
            expiresAt = System.currentTimeMillis() + (j.optString("expiresIn", "3600").toLongOrNull() ?: 3600L) * 1000
            persist(ctx)
        }
        state.value = true
    }

    /** বৈধ idToken দেয়; মেয়াদ প্রায় শেষ হলে (বা force হলে) রিফ্রেশ করে */
    suspend fun token(ctx: Context, force: Boolean = false): String = lock.withLock {
        if (!force && idToken.isNotEmpty() && System.currentTimeMillis() < expiresAt - 60_000) {
            idToken
        } else {
            if (refreshToken.isEmpty()) throw ApiError("লগইনের মেয়াদ শেষ, আবার লগইন করুন", 401)
            withContext(Dispatchers.IO) {
                val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
                val r = httpCall("POST", "https://securetoken.googleapis.com/v1/token?key=${Cfg.API_KEY}", form, contentType = "application/x-www-form-urlencoded")
                val j = runCatching { JSONObject(r.text) }.getOrDefault(JSONObject())
                if (r.code in 400..499) {   // রিফ্রেশ টোকেন আর চলে না (পাসওয়ার্ড বদল/অ্যাকাউন্ট বন্ধ ইত্যাদি)
                    signOut(ctx)
                    throw ApiError("লগইনের মেয়াদ শেষ, আবার লগইন করুন", 401)
                }
                if (r.code !in 200..299) throw ApiError("সংযোগে সমস্যা, আবার চেষ্টা করুন", r.code)
                idToken = j.getString("id_token")
                refreshToken = j.optString("refresh_token", refreshToken)
                expiresAt = System.currentTimeMillis() + (j.optString("expires_in", "3600").toLongOrNull() ?: 3600L) * 1000
                persist(ctx)
                idToken
            }
        }
    }
}

data class PostItem(val title: String, val link: String, val at: Long?, val by: String)
data class BookmarkItem(val id: String, val url: String, val name: String)
data class SitePost(val title: String, val link: String)

/** users/<uid>/settings/card-এর সাইট-সংক্রান্ত অংশ (ওয়েবের cfg-র একই নাম) */
data class CardCfg(
    val siteName: String = "",
    val siteUrl: String = "",
    val category: String = "বাংলাদেশ",
    val photoTag: String = "সংগৃহীত",
    val ctaText: String = "বিস্তারিত কমেন্টে",
    val siteTagline: String = "",
    val logo: String = "",          // data:image/png;base64,...
    val icon: String = "",
    val iconShape: String = "round",
    val fiMode: String = "original", // original | fixed
    val fiPreset: String = "1200x630",
    val fiW: Int = 1200,
    val fiH: Int = 630,
    val fiFit: String = "stretch",   // stretch | cover
)

/** 🌅 শুভ সকালের অটো-পোস্ট সেটিং (ওয়েবের cfg.morningOn / morningTime / morningAd) */
data class MorningCfg(
    val on: Boolean? = null,      // null = কখনো সেভ হয়নি
    val time: String = "06:30",
    val ad: String = "auto",      // auto | none | বিজ্ঞাপনের id
)

/** Firestore REST — Rules যেমন ওয়েবে, এখানেও একই (users/<uid>/...) */
object Fs {
    private const val BASE = "https://firestore.googleapis.com/v1/projects/${Cfg.PROJECT}/databases/(default)/documents"

    private fun uidOrThrow(): String = Session.uid ?: throw ApiError("লগইন করা নেই", 401)

    private suspend fun send(ctx: Context, method: String, url: String, body: String?): Resp = withContext(Dispatchers.IO) {
        fun go(t: String) = httpCall(method, url, body, mapOf("Authorization" to "Bearer $t"))
        var r = go(Session.token(ctx))
        if (r.code == 401) r = go(Session.token(ctx, force = true))
        r
    }

    private fun fail(r: Resp): ApiError {
        val detail = runCatching {
            val t = r.text.trim()
            val o = if (t.startsWith("[")) JSONArray(t).optJSONObject(0) else JSONObject(t)
            o?.optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val msg = when (r.code) {
            401 -> "লগইনের মেয়াদ শেষ, আবার লগইন করুন"
            403 -> "অনুমতি নেই (Firestore Rules ঠিক আছে কিনা দেখুন)"
            else -> "সার্ভার থেকে সাড়া এসেছে ${r.code}" + if (detail.isNotBlank()) ": $detail" else ""
        }
        return ApiError(msg, r.code)
    }

    private fun JSONObject.fld(k: String): JSONObject? = optJSONObject("fields")?.optJSONObject(k)
    private fun JSONObject.str(k: String): String = fld(k)?.optString("stringValue").orEmpty()
    private fun JSONObject.time(k: String): Long? =
        fld(k)?.optString("timestampValue")?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun userUrl(): String = "$BASE/users/${uidOrThrow()}"

    private fun query(coll: String, orderDesc: Boolean = true, limit: Int? = null): JSONObject {
        val q = JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", coll)))
        if (orderDesc) {
            q.put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "createdAt")).put("direction", "DESCENDING")))
        }
        if (limit != null) q.put("limit", limit)
        return q
    }

    private suspend fun runQuery(ctx: Context, q: JSONObject): List<JSONObject> {
        val r = send(ctx, "POST", userUrl() + ":runQuery", JSONObject().put("structuredQuery", q).toString())
        if (r.code !in 200..299) throw fail(r)
        val arr = JSONArray(r.text)
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optJSONObject("document")?.let { out.add(it) }
        return out
    }

    /** since = ISO সময় (এর পর থেকে গোনা); null হলে মোট */
    private suspend fun count(ctx: Context, coll: String, since: Instant?): Int {
        val q = query(coll, orderDesc = false)
        if (since != null) {
            q.put(
                "where",
                JSONObject().put(
                    "fieldFilter",
                    JSONObject()
                        .put("field", JSONObject().put("fieldPath", "createdAt"))
                        .put("op", "GREATER_THAN_OR_EQUAL")
                        .put("value", JSONObject().put("timestampValue", since.toString())),
                ),
            )
        }
        val body = JSONObject().put(
            "structuredAggregationQuery",
            JSONObject().put("structuredQuery", q)
                .put("aggregations", JSONArray().put(JSONObject().put("alias", "c").put("count", JSONObject()))),
        )
        val r = send(ctx, "POST", userUrl() + ":runAggregationQuery", body.toString())
        if (r.code !in 200..299) throw fail(r)
        val v = JSONArray(r.text).optJSONObject(0)?.optJSONObject("result")?.optJSONObject("aggregateFields")?.optJSONObject("c")
        return v?.optString("integerValue")?.toIntOrNull() ?: 0
    }

    suspend fun totalPosts(ctx: Context) = count(ctx, "posts", null)

    suspend fun postsSinceToday(ctx: Context): Int {
        val s = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        return count(ctx, "posts", s)
    }

    suspend fun postsSinceMonth(ctx: Context): Int {
        val s = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        return count(ctx, "posts", s)
    }

    suspend fun recentPosts(ctx: Context, n: Int = 5): List<PostItem> =
        runQuery(ctx, query("posts", limit = n)).map { d ->
            val fb = d.str("fb")
            PostItem(
                title = d.str("title"),
                link = d.str("link").ifBlank { if (fb.isNotBlank()) "https://www.facebook.com/$fb" else "" },
                at = d.time("createdAt"),
                by = d.str("by"),
            )
        }

    /**
     * সিডিউল হয়ে যাওয়া পোস্ট ড্যাশবোর্ডের হিসাবে তোলা (ওয়েবের setDoc(posts/<সিডিউল id>))।
     * ডকুমেন্টের id = সিডিউল id, তাই একই পোস্ট দুবার গোনা হয় না।
     */
    suspend fun savePost(ctx: Context, id: String, title: String, link: String, fb: String, by: String, atMs: Long) {
        fun s(v: String) = JSONObject().put("stringValue", v)
        val fields = JSONObject()
            .put("title", s(title)).put("link", s(link)).put("fb", s(fb)).put("by", s(by))
            .put("scheduled", JSONObject().put("booleanValue", true))
            .put("createdAt", JSONObject().put("timestampValue", Instant.ofEpochMilli(atMs).toString()))
        val r = send(ctx, "PATCH", userUrl() + "/posts/" + URLEncoder.encode(id, "UTF-8"), JSONObject().put("fields", fields).toString())
        if (r.code !in 200..299) throw fail(r)
    }

    /** সেটিংসের "সাইটের নাম" (users/<uid>/settings/card.siteName) */
    suspend fun siteName(ctx: Context): String {
        val r = send(ctx, "GET", userUrl() + "/settings/card", null)
        if (r.code == 404) return ""
        if (r.code !in 200..299) throw fail(r)
        return JSONObject(r.text).str("siteName")
    }

    suspend fun bookmarks(ctx: Context): List<BookmarkItem> =
        runQuery(ctx, query("bookmarks")).map { d ->
            BookmarkItem(id = d.optString("name").substringAfterLast('/'), url = d.str("url"), name = d.str("name"))
        }

    suspend fun addBookmark(ctx: Context, url: String, name: String) {
        val fields = JSONObject()
            .put("url", JSONObject().put("stringValue", url))
            .put("name", JSONObject().put("stringValue", name))
            .put("createdAt", JSONObject().put("timestampValue", Instant.now().toString()))
        val r = send(ctx, "POST", userUrl() + "/bookmarks", JSONObject().put("fields", fields).toString())
        if (r.code !in 200..299) throw fail(r)
    }

    suspend fun deleteBookmark(ctx: Context, id: String) {
        val r = send(ctx, "DELETE", userUrl() + "/bookmarks/" + URLEncoder.encode(id, "UTF-8"), null)
        if (r.code !in 200..299) throw fail(r)
    }

    /** সেটিংসের সাইট-তথ্য; ডকুমেন্ট না থাকলে ওয়েবের ডিফল্ট মান */
    suspend fun cardCfg(ctx: Context): CardCfg {
        val r = send(ctx, "GET", userUrl() + "/settings/card", null)
        if (r.code == 404) return CardCfg()
        if (r.code !in 200..299) throw fail(r)
        val d = JSONObject(r.text)
        val def = CardCfg()
        fun has(k: String) = d.fld(k) != null
        fun st(k: String, dflt: String) = if (has(k)) d.str(k) else dflt
        fun num(k: String, dflt: Int): Int {
            val f = d.fld(k) ?: return dflt
            return f.optString("integerValue").toIntOrNull() ?: f.optDouble("doubleValue", dflt.toDouble()).toInt()
        }
        return CardCfg(
            siteName = st("siteName", def.siteName), siteUrl = st("siteUrl", def.siteUrl),
            category = st("category", def.category), photoTag = st("photoTag", def.photoTag),
            ctaText = st("ctaText", def.ctaText), siteTagline = st("siteTagline", def.siteTagline),
            logo = st("logo", def.logo), icon = st("icon", def.icon),
            iconShape = st("iconShape", def.iconShape).ifBlank { "round" },
            fiMode = st("fiMode", def.fiMode), fiPreset = st("fiPreset", def.fiPreset),
            fiW = num("fiW", def.fiW), fiH = num("fiH", def.fiH),
            fiFit = if (st("fiFit", def.fiFit) == "cover") "cover" else "stretch",
        )
    }

    /** সেভ করা বিজ্ঞাপন তালিকা + শেষ ব্যবহৃত বিজ্ঞাপনের id (ওয়েবের cfg.ads / cfg.adLast); পুরনো একক ফরম্যাট থাকলে সেটাও তালিকায় নেয় */
    suspend fun adsCfg(ctx: Context): Pair<List<Ad>, String> {
        val r = send(ctx, "GET", userUrl() + "/settings/card", null)
        if (r.code == 404) return emptyList<Ad>() to ""
        if (r.code !in 200..299) throw fail(r)
        val d = JSONObject(r.text)
        fun JSONObject.hex(k: String, dflt: String) = str(k).ifBlank { dflt }
        val out = ArrayList<Ad>()
        val arr = d.fld("ads")?.optJSONObject("arrayValue")?.optJSONArray("values")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
                val m = JSONObject().put("fields", f)
                out.add(
                    Ad(
                        id = m.str("id").ifBlank { newAdId() },
                        on = m.fld("on")?.optBoolean("booleanValue") == true,
                        mode = if (m.str("mode") == "build") "build" else "upload",
                        img = m.str("img"), logo = m.str("logo"),
                        t1 = m.str("t1"), t2 = m.str("t2"), t3 = m.str("t3"),
                        bg1 = m.hex("bg1", "#c2185b"), bg2 = m.hex("bg2", "#8e0e4b"),
                        c1 = m.hex("c1", "#ffffff"), c2 = m.hex("c2", "#ffd400"),
                    ),
                )
            }
        }
        if (out.isEmpty() && (d.str("adImg").isNotBlank() || d.str("adT1").isNotBlank() || d.str("adT2").isNotBlank() || d.str("adT3").isNotBlank())) {
            out.add(
                Ad(
                    id = newAdId(), on = d.fld("adOn")?.optBoolean("booleanValue") == true,
                    mode = if (d.str("adMode") == "build") "build" else "upload",
                    img = d.str("adImg"), logo = d.str("adLogo"),
                    t1 = d.str("adT1"), t2 = d.str("adT2"), t3 = d.str("adT3"),
                    bg1 = d.hex("adBg1", "#c2185b"), bg2 = d.hex("adBg2", "#8e0e4b"),
                    c1 = d.hex("adC1", "#ffffff"), c2 = d.hex("adC2", "#ffd400"),
                ),
            )
        }
        var seen = false   // একাধিক টিক থাকলে প্রথমটা রেখে বাকি বাদ (ওয়েবের মতো)
        val fixed = out.take(AD_MAX).map { a -> if (a.on && !seen) { seen = true; a } else a.copy(on = false) }
        return fixed to d.str("adLast")
    }

    /** শুধু ads, adLast (আর পুরনো একক ফিল্ডগুলো মোছা) বদলায়; বাকি সেটিংস অক্ষত */
    suspend fun saveAds(ctx: Context, ads: List<Ad>, adLast: String) {
        fun s(v: String) = JSONObject().put("stringValue", v)
        val values = JSONArray()
        for (a in ads) {
            val f = JSONObject()
                .put("id", s(a.id)).put("on", JSONObject().put("booleanValue", a.on)).put("mode", s(a.mode))
                .put("img", s(a.img)).put("logo", s(a.logo))
                .put("t1", s(a.t1)).put("t2", s(a.t2)).put("t3", s(a.t3))
                .put("bg1", s(a.bg1)).put("bg2", s(a.bg2)).put("c1", s(a.c1)).put("c2", s(a.c2))
            values.put(JSONObject().put("mapValue", JSONObject().put("fields", f)))
        }
        val arrayValue = JSONObject().put("arrayValue", if (values.length() > 0) JSONObject().put("values", values) else JSONObject())
        val fields = JSONObject().put("ads", arrayValue).put("adLast", s(adLast))
            .put("updatedAt", JSONObject().put("timestampValue", Instant.now().toString()))
        val legacy = listOf("adOn", "adMode", "adImg", "adLogo", "adT1", "adT2", "adT3", "adBg1", "adBg2", "adC1", "adC2")
        val mask = (fields.keys().asSequence().toList() + legacy).joinToString("&") { "updateMask.fieldPaths=$it" }
        val r = send(ctx, "PATCH", userUrl() + "/settings/card?" + mask, JSONObject().put("fields", fields).toString())
        if (r.code !in 200..299) throw fail(r)
    }

    /** শুধু এই ফিল্ডগুলো বদলায় (updateMask), তাই টেমপ্লেট/বিজ্ঞাপন ইত্যাদি অন্য ফিল্ড অক্ষত থাকে */
    suspend fun saveCardCfg(ctx: Context, c: CardCfg) {
        fun s(v: String) = JSONObject().put("stringValue", v)
        fun n(v: Int) = JSONObject().put("integerValue", v.toString())
        val fields = JSONObject()
            .put("siteName", s(c.siteName)).put("siteUrl", s(c.siteUrl)).put("category", s(c.category))
            .put("photoTag", s(c.photoTag)).put("ctaText", s(c.ctaText)).put("siteTagline", s(c.siteTagline))
            .put("logo", s(c.logo)).put("icon", s(c.icon)).put("iconShape", s(c.iconShape))
            .put("fiMode", s(c.fiMode)).put("fiPreset", s(c.fiPreset))
            .put("fiW", n(c.fiW)).put("fiH", n(c.fiH)).put("fiFit", s(c.fiFit))
            .put("updatedAt", JSONObject().put("timestampValue", Instant.now().toString()))
        val mask = fields.keys().asSequence().joinToString("&") { "updateMask.fieldPaths=$it" }
        val r = send(ctx, "PATCH", userUrl() + "/settings/card?" + mask, JSONObject().put("fields", fields).toString())
        if (r.code !in 200..299) throw fail(r)
    }

    /** শুভ সকালের সময়/চালু-বন্ধ/ব্যানার পছন্দ; ডকুমেন্ট না থাকলে ডিফল্ট */
    suspend fun morningCfg(ctx: Context): MorningCfg {
        val r = send(ctx, "GET", userUrl() + "/settings/card", null)
        if (r.code == 404) return MorningCfg()
        if (r.code !in 200..299) throw fail(r)
        val d = JSONObject(r.text)
        val on = d.fld("morningOn")?.takeIf { it.has("booleanValue") }?.optBoolean("booleanValue")
        return MorningCfg(
            on = on,
            time = d.str("morningTime").ifBlank { "06:30" },
            ad = d.str("morningAd").ifBlank { "auto" },
        )
    }

    /** শুধু morningOn, morningTime, morningAd আর siteName বদলায় (updateMask); বাকি সেটিংস অক্ষত */
    suspend fun saveMorningCfg(ctx: Context, on: Boolean, time: String, ad: String, siteName: String) {
        fun s(v: String) = JSONObject().put("stringValue", v)
        val fields = JSONObject()
            .put("morningOn", JSONObject().put("booleanValue", on))
            .put("morningTime", s(time)).put("morningAd", s(ad)).put("siteName", s(siteName))
            .put("updatedAt", JSONObject().put("timestampValue", Instant.now().toString()))
        val mask = fields.keys().asSequence().joinToString("&") { "updateMask.fieldPaths=$it" }
        val r = send(ctx, "PATCH", userUrl() + "/settings/card?" + mask, JSONObject().put("fields", fields).toString())
        if (r.code !in 200..299) throw fail(r)
    }
}

/** Cloudflare Worker কল — ওয়েবের api() ফাংশনের মতোই (Bearer = Firebase idToken) */
object Worker {
    suspend fun post(ctx: Context, path: String, data: JSONObject = JSONObject(), ms: Int = 180000): JSONObject = withContext(Dispatchers.IO) {
        fun go(t: String) = httpCall("POST", Cfg.WORKER + path, data.toString(), mapOf("Authorization" to "Bearer $t"), readMs = ms)
        var r = go(Session.token(ctx))
        if (r.code == 401) r = go(Session.token(ctx, force = true))
        val j = runCatching { JSONObject(r.text) }.getOrDefault(JSONObject())
        if (r.code !in 200..299) {
            throw ApiError(j.optString("error", "").ifBlank { "সমস্যা হয়েছে" }, r.code)
        }
        j
    }
}
