package com.digitalcrafting25.newsapp

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Worker-এর সিডিউল রেকর্ডের মেটাডেটা (status: pending | running | failed | done) */
data class SchedItem(
    val id: String,
    val title: String,
    val at: Long,
    val status: String,
    val kind: String,      // "card" হলে ফোটোকার্ড
    val by: String,
    val error: String,
    val tries: Int,
    val rt: Long,          // পরের আবার-চেষ্টার সময়
    val ra: Long,          // চলমান অবস্থার শেষ সাড়ার সময়
    val link: String,
    val fb: String,
    val doneAt: Long,
)

data class SchedResult(val items: List<SchedItem>, val now: Long, val max: Int)

/** তালিকা আনা + শেষ হওয়া পোস্ট ড্যাশবোর্ডের হিসাবে তুলে নেওয়া (ওয়েবের syncSchedules) */
object SchedSync {
    @Volatile private var lastAt = 0L

    fun forget() { lastAt = 0L }

    private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k)

    private fun parse(d: JSONObject): SchedResult {
        val arr = d.optJSONArray("items")
        val out = ArrayList<SchedItem>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                SchedItem(
                    id = o.s("id"), title = o.s("title"), at = o.optLong("at"),
                    status = o.s("status").ifBlank { "pending" }, kind = o.s("kind"), by = o.s("by"),
                    error = o.s("error"), tries = o.optInt("tries"), rt = o.optLong("rt"), ra = o.optLong("ra"),
                    link = o.s("link"), fb = o.s("fb"), doneAt = o.optLong("doneAt"),
                ),
            )
        }
        val max = d.optInt("max", 25).let { if (it > 0) it else 25 }
        return SchedResult(out.sortedBy { it.at }, d.optLong("now", System.currentTimeMillis()), max)
    }

    suspend fun fetch(ctx: Context): SchedResult {
        val res = parse(Worker.post(ctx, "/schedules", JSONObject(), 20000))
        for (x in res.items.filter { it.status == "done" }) {
            Fs.savePost(ctx, x.id, x.title, x.link, x.fb, x.by, if (x.doneAt > 0) x.doneAt else x.at)
            Worker.post(ctx, "/schedule-ack", JSONObject().put("id", x.id), 20000)
        }
        return res
    }

    /** ড্যাশবোর্ড খোলার সময়: ২ মিনিটে একবার, চুপচাপ (ব্যর্থ হলে পরের বার আবার) */
    suspend fun quiet(ctx: Context) {
        val n = System.currentTimeMillis()
        if (n - lastAt < 120000) return
        lastAt = n
        try { fetch(ctx) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
}

data class SchedState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val items: List<SchedItem> = emptyList(),
    val max: Int = 25,
    val offset: Long = 0L,            // সার্ভারের সময় - ফোনের সময়
    val busy: Set<String> = emptySet(),
    val notice: String? = null,
)

class ScheduleViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(SchedState())
    val state: StateFlow<SchedState> = _state.asStateFlow()

    // সার্ভারের তালিকা দেরিতে হালনাগাদ হয়, তাই সদ্য বাতিল/সরানো পোস্ট ৩ মিনিট লুকিয়ে/নতুন সময়ে দেখাই
    private val gone = HashMap<String, Long>()
    private val moved = HashMap<String, Pair<Long, Long>>()   // id -> (নতুন সময়, কখন)

    fun reset() { _state.value = SchedState(); gone.clear(); moved.clear(); SchedSync.forget() }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun markBusy(id: String, on: Boolean) = setBusy(id, on)

    fun showNotice(msg: String) = _state.update { it.copy(notice = msg) }

    /** অন্য জায়গা (এডিট) থেকে পুরনো সিডিউল সরানো হলে তালিকায় ৩ মিনিট লুকিয়ে রাখি */
    fun markGone(id: String) { gone[id] = System.currentTimeMillis(); _state.update { it.copy(items = it.items.filter { x -> x.id != id }) } }

    private fun apply(d: SchedResult) {
        val now = System.currentTimeMillis()
        gone.entries.removeAll { now - it.value > 180000 }
        moved.entries.removeAll { now - it.value.second > 180000 }
        val items = d.items
            .filter { it.status != "done" && it.id !in gone }
            .map { x ->
                val mv = moved[x.id]
                if (mv != null && x.at != mv.first) x.copy(at = mv.first, rt = 0, error = "") else x
            }
            .sortedBy { it.at }
        _state.update { it.copy(items = items, max = d.max, offset = d.now - now, loaded = true, error = null) }
    }

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                apply(SchedSync.fetch(app))
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "অজানা সমস্যা") }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** রিফ্রেশ: আটকে থাকা ('running') পোস্টও আবার ধরা হয় */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try { Worker.post(app, "/schedules", JSONObject().put("recover", true), 60000) } catch (_: Exception) { }
            _state.update { it.copy(refreshing = false) }
            load()
        }
    }

    private fun setBusy(id: String, on: Boolean) = _state.update { it.copy(busy = if (on) it.busy + id else it.busy - id) }

    private fun removeLocal(id: String) = _state.update { it.copy(items = it.items.filter { x -> x.id != id }) }

    /** ফেইল/আটকে যাওয়া পোস্ট মোছা */
    fun cancel(item: SchedItem) {
        if (item.id in _state.value.busy) return
        setBusy(item.id, true)
        viewModelScope.launch {
            try {
                Worker.post(app, "/schedule-cancel", JSONObject().put("id", item.id), 20000)
                gone[item.id] = System.currentTimeMillis()
                removeLocal(item.id)
                load()
            } catch (e: Exception) {
                _state.update { it.copy(notice = "⚠️ " + (e.message ?: "মোছা যায়নি")) }
            } finally { setBusy(item.id, false) }
        }
    }

    fun reschedule(item: SchedItem, atMs: Long) {
        if (item.id in _state.value.busy) return
        if (atMs < System.currentTimeMillis() + _state.value.offset + 60000) {
            _state.update { it.copy(notice = "⚠️ অন্তত ১ মিনিট পরের সময় দিন") }
            return
        }
        setBusy(item.id, true)
        viewModelScope.launch {
            try {
                val d = Worker.post(app, "/schedule-reschedule", JSONObject().put("id", item.id).put("at", atMs), 20000)
                val at = d.optLong("at", atMs)
                moved[item.id] = at to System.currentTimeMillis()
                _state.update { s ->
                    s.copy(items = s.items.map { x -> if (x.id == item.id) x.copy(at = at, rt = 0, error = "") else x }.sortedBy { it.at })
                }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(notice = "⚠️ " + (e.message ?: "সময় বদলানো যায়নি")) }
            } finally { setBusy(item.id, false) }
        }
    }

    /** এখনই পোস্ট করুন (Worker /schedule-now; সময় লাগতে পারে) */
    fun postNow(item: SchedItem) {
        if (item.id in _state.value.busy) return
        setBusy(item.id, true)
        viewModelScope.launch {
            try {
                val d = Worker.post(app, "/schedule-now", JSONObject().put("id", item.id), 150000)
                if (d.optBoolean("ok")) {
                    gone[item.id] = System.currentTimeMillis()
                    removeLocal(item.id)
                    _state.update { it.copy(notice = "✅ পোস্ট হয়ে গেছে এবং সিডিউল থেকে উঠে গেছে।") }
                } else {
                    val err = d.optString("error").ifBlank { "কারণ অজানা" }
                    val hint = if (d.optString("status") == "retry") "\n\nসিস্টেম কিছুক্ষণ পরপর আবার চেষ্টা করবে। চাইলে ‘সময় বদলান’ চাপুন।" else ""
                    _state.update { it.copy(notice = "⚠️ এখনই পোস্ট করা যায়নি: $err$hint") }
                }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(notice = "⚠️ " + (e.message ?: "পোস্ট করা যায়নি")) }
                load()
            } finally { setBusy(item.id, false) }
        }
    }
}
