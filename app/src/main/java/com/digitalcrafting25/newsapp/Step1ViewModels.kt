package com.digitalcrafting25.newsapp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** বাংলা সংখ্যা ও তারিখ (ওয়েবের toLocaleString('bn-BD') এর মতো) */
object Bn {
    private val loc: Locale = Locale.Builder().setLanguage("bn").setRegion("BD").build()
    fun num(n: Number): String = NumberFormat.getInstance(loc).format(n)
    fun today(): String = SimpleDateFormat("EEEE, d MMMM, yyyy", loc).format(Date())
    fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, loc).format(Date(ms))
}

class AuthViewModel(private val app: Application) : AndroidViewModel(app) {
    val loggedIn: StateFlow<Boolean> = Session.state
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set

    fun login(email: String, pass: String) {
        if (busy) return
        error = ""
        busy = true
        viewModelScope.launch {
            try {
                Session.signIn(app, email.trim(), pass)
            } catch (e: ApiError) {
                error = e.message ?: "লগইন হয়নি"
            } catch (e: Exception) {
                error = "লগইন হয়নি: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun logout() {
        error = ""
        Session.signOut(app)
    }
}

data class DashState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val siteName: String = "",
    val total: Int = 0,
    val today: Int = 0,
    val month: Int = 0,
    val recent: List<PostItem> = emptyList(),
    val balance: Double? = null,
    val pending: Int = 0,
    val error: String? = null,
)

class DashboardViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(DashState())
    val state: StateFlow<DashState> = _state.asStateFlow()

    fun reset() { _state.value = DashState() }

    /** ওয়েবের loadDash(): মোট/আজ/মাস, সর্বশেষ ৫ পোস্ট, ব্যালেন্স, সাইটের নাম */
    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // ব্যালেন্স আলাদা চলে; ব্যর্থ হলে বাকি ড্যাশবোর্ড আটকায় না (ওয়েবেও তাই)
            launch {
                try {
                    val d = Worker.post(app, "/cslot-get", JSONObject(), 15000)
                    _state.update { it.copy(balance = d.optDouble("balance", 0.0), pending = d.optInt("pending", 0)) }
                } catch (_: Exception) {
                }
            }
            launch {
                try {
                    val n = Fs.siteName(app)
                    if (n.isNotBlank()) _state.update { it.copy(siteName = n) }
                } catch (_: Exception) {
                }
            }
            try {
                coroutineScope {
                    val total = async { Fs.totalPosts(app) }
                    val today = async { Fs.postsSinceToday(app) }
                    val month = async { Fs.postsSinceMonth(app) }
                    val recent = async { Fs.recentPosts(app, 5) }
                    val t = total.await(); val td = today.await(); val m = month.await(); val r = recent.await()
                    _state.update { it.copy(total = t, today = td, month = m, recent = r, loaded = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "অজানা সমস্যা") }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** ব্যালেন্স যোগের অনুরোধ (অ্যাডমিন অনুমোদন দিলে যোগ হয়) — Worker /wallet-request */
    suspend fun walletRequest(amount: String, phone: String, trx: String): String {
        Worker.post(
            app, "/wallet-request",
            JSONObject().put("amount", amount).put("phone", phone).put("trx", trx), 20000,
        )
        load()
        return "✅ অনুরোধ পাঠানো হয়েছে। অ্যাডমিন টাকা পাওয়া যাচাই করে অনুমোদন দিলে ব্যালেন্সে যোগ হবে (পরে ড্যাশবোর্ড রিফ্রেশ করে দেখুন)।"
    }
}

data class BmPosts(val busy: Boolean = false, val posts: List<SitePost>? = null, val error: String? = null)

class BookmarkViewModel(private val app: Application) : AndroidViewModel(app) {
    val items = mutableStateListOf<BookmarkItem>()
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var adding by mutableStateOf(false)
        private set
    var formError by mutableStateOf<String?>(null)
        private set
    val posts = mutableStateMapOf<String, BmPosts>()

    fun reset() { items.clear(); posts.clear(); loadError = null; formError = null }

    fun load() {
        if (loading) return
        loading = true
        loadError = null
        viewModelScope.launch {
            try {
                val list = Fs.bookmarks(app)
                items.clear(); items.addAll(list)
            } catch (e: Exception) {
                loadError = e.message ?: "অজানা সমস্যা"
            } finally {
                loading = false
            }
        }
    }

    /** ওয়েবের bmNorm(): https যোগ, শেষের / বাদ, পাথ রাখা (bd24live.com/bangla আর bd24live.com আলাদা) */
    private fun normalize(raw: String): String {
        var v = raw.trim()
        if (v.isEmpty()) throw ApiError("সাইটের লিংক দিন")
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(v)) v = "https://$v"
        val u = try { URI(v) } catch (_: Exception) { throw ApiError("লিংকটি ঠিক নয়") }
        val host = u.host ?: throw ApiError("লিংকটি ঠিক নয়")
        if (!host.contains('.')) throw ApiError("লিংকটি ঠিক নয়")
        val port = if (u.port > 0) ":${u.port}" else ""
        return u.scheme.lowercase() + "://" + host + port + (u.rawPath ?: "").trimEnd('/')
    }

    /** সফল হলে onDone ডাকে */
    fun add(urlRaw: String, nameRaw: String, onDone: () -> Unit) {
        if (adding) return
        formError = null
        val url = try { normalize(urlRaw) } catch (e: ApiError) { formError = e.message; return }
        if (items.any { it.url == url }) { formError = "এই সাইট আগেই বুকমার্ক করা আছে"; return }
        val name = nameRaw.trim().take(60).ifBlank { URI(url).host.removePrefix("www.") }
        adding = true
        viewModelScope.launch {
            try {
                Fs.addBookmark(app, url, name)
                onDone()
                adding = false
                load()
            } catch (e: Exception) {
                formError = e.message ?: "সেভ হয়নি"
                adding = false
            }
        }
    }

    fun delete(item: BookmarkItem) {
        viewModelScope.launch {
            try {
                Fs.deleteBookmark(app, item.id)
                posts.remove(item.id)
                load()
            } catch (e: Exception) {
                loadError = e.message ?: "মোছা যায়নি"
            }
        }
    }

    /** "সর্বশেষ ৫টি পোস্ট দেখুন" — Worker /site-posts */
    fun showPosts(item: BookmarkItem) {
        if (posts[item.id]?.busy == true) return
        posts[item.id] = BmPosts(busy = true)
        viewModelScope.launch {
            try {
                val d = Worker.post(app, "/site-posts", JSONObject().put("url", item.url), 40000)
                val arr = d.optJSONArray("posts")
                val list = ArrayList<SitePost>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(SitePost(o.optString("title"), o.optString("link")))
                }
                posts[item.id] = BmPosts(posts = list)
            } catch (e: Exception) {
                posts[item.id] = BmPosts(error = e.message ?: "পোস্ট আনা যায়নি")
            }
        }
    }
}
