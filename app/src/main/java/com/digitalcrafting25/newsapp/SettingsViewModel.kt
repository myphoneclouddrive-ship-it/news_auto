package com.digitalcrafting25.newsapp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Worker-এর /creds-get উত্তরের একটা ঘর (পাসওয়ার্ড/টোকেনের মান Worker নিজেই ঢেকে পাঠায়) */
data class CredInfo(val set: Boolean = false, val value: String = "", val fromEnv: Boolean = false)

data class AiKey(
    val id: String,
    val hint: String,
    val fromEnv: Boolean,
    val cooling: Boolean,
    val usage: String,
)

data class KeyCheck(val state: String, val msg: String)

/** Worker /cslot-list-এর একটা ঘর: ইউজারের ব্যালেন্স যোগের অনুরোধ (অনুমোদনের অপেক্ষায়) */
data class WalletReq(val key: String, val email: String, val amount: Double, val phone: String, val trx: String, val at: Long)

/** ওয়েবের AIP: provider আইডি Worker-এর provOf()-এর সাথে মেলে */
enum class AiProv(
    val id: String,
    val label: String,
    val listKey: String,      // /ai-get উত্তরে তালিকার নাম
    val fieldKey: String,     // /ai-get fields-এর নাম (গোনার জন্য)
    val saveField: String,    // /ai-save-এ কোন নামে যাবে
    val placeholder: String,
    val title: String,
) {
    GEMINI("gemini", "Gemini", "keys", "GEMINI_API_KEYS", "GEMINI_API_KEYS", "নতুন কী বসান (প্রতি লাইনে একটা), আগের কী থাকবে", "Gemini API Key (একাধিক হলে প্রতি লাইনে একটা)"),
    CLAUDE("claude", "Claude", "claude_keys", "ANTHROPIC_API_KEY", "ANTHROPIC_API_KEY", "sk-ant-... (নতুন কী বসান, আগের কী থাকবে)", "Claude (Anthropic) API Key (ঐচ্ছিক, একাধিক হলে প্রতি লাইনে একটা)"),
    GROQ("groq", "Groq", "groq_keys", "GROQ_API_KEYS", "GROQ_API_KEYS", "gsk_... (নতুন কী বসান, আগের কী থাকবে)", "Groq API Key (ঐচ্ছিক, একাধিক হলে প্রতি লাইনে একটা)"),
    CF("cf", "Cloudflare", "cf_keys", "CF_AI_KEYS", "CF_AI_KEYS", "ACCOUNT_ID:API_TOKEN (মাঝে কোলন, নতুন কী বসান, আগের কী থাকবে)", "Cloudflare Workers AI কী (ঐচ্ছিক, প্রতি লাইনে একটা: ACCOUNT_ID:API_TOKEN)"),
}

class SettingsViewModel(private val app: Application) : AndroidViewModel(app) {

    // ───────── সাধারণ ─────────
    /** এখন কোন কাজ চলছে (বাটন বন্ধ রাখতে); null = কিছু না */
    var busy by mutableStateOf<String?>(null)
        private set

    /** উপরে দেখানো বার্তা (সফল/ব্যর্থ) */
    var notice by mutableStateOf<String?>(null)

    private fun run(tag: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = tag
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiError) {
                notice = "⚠️ " + (e.message ?: "সমস্যা হয়েছে")
            } catch (e: Exception) {
                notice = "⚠️ " + (e.message ?: "অজানা সমস্যা")
            } finally {
                busy = null
            }
        }
    }

    fun reset() {
        busy = null; notice = null
        creds.clear(); kvOk = true; credsErr = null
        wpUrl = ""; wpUser = ""; wpPass = ""; fbPage = ""; fbToken = ""
        wpMsg = null; fbMsg = null
        aiOwner = null; aiStatus = ""; aiStatusOk = false
        aiKeys.clear(); aiChecks.clear(); aiInputs.clear()
        groqModel = ""; cfModel = ""; geminiModels = ""
        csOwner = false; csItems = emptyList(); csMsg = null; csLoading = false; csBusyKey = null
        vcBusy = false; vcMsg = null
    }

    // ───────── WordPress / Facebook ─────────
    val creds = mutableStateMapOf<String, CredInfo>()
    var kvOk by mutableStateOf(true)
        private set
    var credsErr by mutableStateOf<String?>(null)
        private set

    var wpUrl by mutableStateOf("")
    var wpUser by mutableStateOf("")
    var wpPass by mutableStateOf("")
    var fbPage by mutableStateOf("")
    var fbToken by mutableStateOf("")

    /** (সফল?, লেখা) */
    var wpMsg by mutableStateOf<Pair<Boolean, String>?>(null)
        private set
    var fbMsg by mutableStateOf<Pair<Boolean, String>?>(null)
        private set

    private val wpKeys = listOf("WP_URL", "WP_USER", "WP_APP_PASS")
    private val fbKeys = listOf("FB_PAGE_ID", "FB_TOKEN")

    fun credSet(keys: List<String>) = keys.all { creds[it]?.set == true }
    fun wpOk() = credSet(wpKeys)
    fun fbOk() = credSet(fbKeys)
    fun credEnv(keys: List<String>) = keys.any { creds[it]?.fromEnv == true }
    fun secretHint(key: String): String? = creds[key]?.takeIf { it.set }?.value

    fun loadCreds() {
        viewModelScope.launch {
            try {
                val d = Worker.post(app, "/creds-get", JSONObject(), 20000)
                val f = d.optJSONObject("fields") ?: JSONObject()
                for (k in wpKeys + fbKeys) {
                    val x = f.optJSONObject(k)
                    creds[k] = CredInfo(
                        set = x?.optBoolean("set") == true,
                        value = x?.optString("value").orEmpty(),
                        fromEnv = x?.optString("source") == "env",
                    )
                }
                // সাধারণ ঘরগুলো: ইউজার নিজে কিছু না লিখে থাকলে সেভ করা মান বসে
                if (wpUrl.isBlank()) wpUrl = creds["WP_URL"]?.value.orEmpty()
                if (wpUser.isBlank()) wpUser = creds["WP_USER"]?.value.orEmpty()
                if (fbPage.isBlank()) fbPage = creds["FB_PAGE_ID"]?.value.orEmpty()
                kvOk = d.optBoolean("kv", true)
                credsErr = null
            } catch (e: Exception) {
                credsErr = e.message ?: "সংযোগের তথ্য আনা যায়নি"
            }
        }
    }

    fun saveCreds() = run("credsSave") {
        val values = JSONObject()
        fun put(k: String, v: String) { if (v.isNotBlank()) values.put(k, v.trim()) }
        put("WP_URL", wpUrl); put("WP_USER", wpUser); put("WP_APP_PASS", wpPass)
        put("FB_PAGE_ID", fbPage); put("FB_TOKEN", fbToken)
        if (values.length() == 0) throw ApiError("কোনো নতুন তথ্য দেননি")
        Worker.post(app, "/creds-save", JSONObject().put("values", values), 20000)
        wpPass = ""; fbToken = ""
        loadCreds()
        notice = "✅ সংযোগের তথ্য সেভ হয়েছে। এবার \"টেস্ট করুন\" চেপে দেখে নিন।"
    }

    fun testCred(which: String) = run("test$which") {
        fun set(m: Pair<Boolean, String>?) {
            if (which == "wp") wpMsg = m else fbMsg = m
        }
        set(true to "পরীক্ষা চলছে...")
        try {
            val d = Worker.post(app, "/creds-test", JSONObject().put("which", which), 30000)
            set(true to d.optString("message", "✅ ঠিক আছে"))
        } catch (e: Exception) {
            set(false to "⚠️ " + (e.message ?: "পরীক্ষা ব্যর্থ"))
        }
    }

    fun clearCreds(which: String) = run("clear$which") {
        val fields = if (which == "wp") wpKeys else fbKeys
        Worker.post(app, "/creds-clear", JSONObject().put("fields", JSONArray(fields)), 20000)
        if (which == "wp") { wpUrl = ""; wpUser = ""; wpPass = ""; wpMsg = null } else { fbPage = ""; fbToken = ""; fbMsg = null }
        loadCreds()
    }

    // ───────── AI কী (শুধু অ্যাডমিন) ─────────
    /** null = এখনও জানা নেই; false = অ্যাডমিন নন (কার্ড লুকানো) */
    var aiOwner by mutableStateOf<Boolean?>(null)
        private set
    var aiStatus by mutableStateOf("")
        private set
    var aiStatusOk by mutableStateOf(false)
        private set
    var geminiModels by mutableStateOf("")
        private set
    val aiKeys = mutableStateMapOf<String, List<AiKey>>()
    /** চাবি: "provider:keyId" */
    val aiChecks = mutableStateMapOf<String, KeyCheck>()
    /** প্রতিটি provider-এর নতুন কী বসানোর ঘর */
    val aiInputs = mutableStateMapOf<String, String>()
    var groqModel by mutableStateOf("")
    var cfModel by mutableStateOf("")

    private fun fmtUse(u: JSONObject?): String {
        if (u == null) return ""
        val req = u.optInt("req"); val inT = u.optLong("in"); val out = u.optLong("out"); val ws = u.optInt("ws")
        var s = "📊 আজ: ${Bn.num(req)}টি রিকোয়েস্ট • ইনপুট ${Bn.num(inT)} • আউটপুট ${Bn.num(out)} টোকেন"
        if (ws > 0) s += " • ${Bn.num(ws)}টি web search"
        val ms = u.optJSONObject("models")
        if (ms != null) {
            val parts = ArrayList<String>()
            for (name in ms.keys()) {
                val x = ms.optJSONObject(name) ?: continue
                val r = x.optInt("req")
                if (r > 0 || x.has("left")) {
                    parts.add("$name: ${Bn.num(r)}টি" + if (x.has("left")) " (বাকি ~${Bn.num(x.optInt("left"))}টি)" else "")
                }
            }
            if (parts.isNotEmpty()) s += "\n" + parts.joinToString(" • ")
        }
        return s
    }

    fun loadAi() {
        viewModelScope.launch {
            try {
                val d = Worker.post(app, "/ai-get", JSONObject(), 20000)
                val owner = d.optBoolean("owner") && BuildConfig.IS_ADMIN
                aiOwner = owner
                if (!owner) return@launch
                val f = d.optJSONObject("fields") ?: JSONObject()
                fun one(nm: String, k: String): String {
                    val x = f.optJSONObject(k)
                    return if (x?.optBoolean("set") == true) "✅ $nm: ${Bn.num(x.optInt("count"))}টি কী" else "❌ $nm কী নেই"
                }
                val anySet = AiProv.entries.any { f.optJSONObject(it.fieldKey)?.optBoolean("set") == true }
                val bind = d.optBoolean("cf_binding")
                val env = AiProv.entries.any { f.optJSONObject(it.fieldKey)?.optString("source") == "env" }
                aiStatusOk = anySet || bind
                aiStatus = listOf(one("Gemini", "GEMINI_API_KEYS"), one("Groq", "GROQ_API_KEYS"), one("Cloudflare", "CF_AI_KEYS"), one("Claude", "ANTHROPIC_API_KEY"))
                    .joinToString(" • ") + (if (bind) " • ✅ Cloudflare AI binding" else "") + (if (env) " (কিছু কী Worker সিক্রেট থেকে)" else "")
                val gm = d.optJSONArray("gemini_models")
                geminiModels = if (gm != null && gm.length() > 0) {
                    "🧠 Gemini মডেল: লেটেস্ট ${gm.optString(0)}" +
                        (if (gm.length() > 1) " • \"অনেক চাপ\" এরর এলে এক ধাপ কম ${gm.optString(1)}" else "") +
                        (if (gm.length() > 2) " • তারপর ${gm.optString(2)}" else "")
                } else ""
                groqModel = f.optJSONObject("GROQ_MODEL")?.optString("value").orEmpty()
                cfModel = f.optJSONObject("CF_AI_MODEL")?.optString("value").orEmpty()
                for (p in AiProv.entries) {
                    val arr = d.optJSONArray(p.listKey) ?: JSONArray()
                    aiKeys[p.id] = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        AiKey(
                            id = o.optString("id"), hint = o.optString("hint"),
                            fromEnv = o.optString("source") == "env", cooling = o.optBoolean("cool"),
                            usage = fmtUse(o.optJSONObject("use")),
                        )
                    }
                }
            } catch (e: Exception) {
                // ওয়েবের মতোই: ব্যর্থ হলে কার্ড লুকানো থাকে (মালিক না হলে ৪০৩ আসে)
                if (aiOwner == null) aiOwner = false
            }
        }
    }

    fun addKeys(p: AiProv) = run("add${p.id}") {
        val text = aiInputs[p.id].orEmpty().trim()
        if (text.isEmpty()) throw ApiError("নতুন কী বসান (প্রতি লাইনে একটা)")
        val r = Worker.post(app, "/ai-key-add", JSONObject().put("provider", p.id).put("keys", text), 20000)
        aiInputs[p.id] = ""
        loadAi()
        val added = r.optInt("added"); val skipped = r.optInt("skipped")
        notice = if (added > 0) {
            "✅ ${Bn.num(added)}টি নতুন ${p.label} কী যোগ হয়েছে" + (if (skipped > 0) " (${Bn.num(skipped)}টি আগেই ছিল)" else "") + "। আগের কী-গুলো যেমন ছিল তেমনই আছে।"
        } else "ℹ️ এই কী আগেই যোগ করা আছে।"
    }

    fun deleteKey(p: AiProv, id: String) = run("del$id") {
        Worker.post(app, "/ai-key-del", JSONObject().put("provider", p.id).put("id", id), 20000)
        aiChecks.remove("${p.id}:$id")
        loadAi()
    }

    private suspend fun checkOne(p: AiProv, id: String) {
        val key = "${p.id}:$id"
        aiChecks[key] = KeyCheck("checking", "")
        aiChecks[key] = try {
            val d = Worker.post(app, "/ai-key-check", JSONObject().put("provider", p.id).put("id", id), 60000)
            KeyCheck(d.optString("state", "error"), d.optString("msg"))
        } catch (e: Exception) {
            KeyCheck("error", e.message.orEmpty())
        }
    }

    fun checkKey(p: AiProv, id: String) {
        if (aiChecks["${p.id}:$id"]?.state == "checking") return
        viewModelScope.launch { checkOne(p, id) }
    }

    /** একসাথে ৩টা করে চেক, তাই সব কী-র জন্য অনেক কম সময় লাগে (ওয়েবেও তাই) */
    fun checkAll(p: AiProv) = run("all${p.id}") {
        val ids = aiKeys[p.id].orEmpty().map { it.id }
        coroutineScope { ids.chunked(3).forEach { grp -> grp.map { async { checkOne(p, it) } }.awaitAll() } }
    }

    /** মডেল সেভ; কী-র ঘরে কিছু লেখা থাকলে সেটাও যোগ হয় (আগেরগুলো মোছে না) */
    fun saveAiModels() = run("aiSave") {
        val values = JSONObject()
        for (p in AiProv.entries) aiInputs[p.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { values.put(p.saveField, it) }
        if (groqModel.isNotBlank()) values.put("GROQ_MODEL", groqModel.trim())
        if (cfModel.isNotBlank()) values.put("CF_AI_MODEL", cfModel.trim())
        if (values.length() == 0) throw ApiError("কোনো নতুন তথ্য দেননি")
        Worker.post(app, "/ai-save", JSONObject().put("values", values), 20000)
        aiInputs.clear()
        loadAi()
        notice = "✅ সেভ হয়েছে। এখন থেকে সব ইউজারের জন্য কাজ করবে।"
    }

    fun clearAllAi() = run("aiClear") {
        val fields = JSONArray(listOf("GEMINI_API_KEYS", "ANTHROPIC_API_KEY", "ANTHROPIC_API_KEYS", "GROQ_API_KEYS", "GROQ_MODEL", "CF_AI_KEYS", "CF_AI_MODEL"))
        Worker.post(app, "/ai-clear", JSONObject().put("fields", fields), 20000)
        aiChecks.clear()
        loadAi()
    }

    // ───────── 📩 বিকাশ SMS অটো কনফার্ম (অ্যাডমিনের ফোন) ─────────
    var bkAuto by mutableStateOf(BkashPrefs.enabled(app))
        private set
    var bkLast by mutableStateOf(BkashPrefs.last(app))
        private set
    fun updateBkAuto(on: Boolean) { BkashPrefs.setEnabled(app, on); bkAuto = on; BkashPrefs.syncSchedule(app) }
    fun refreshBk() { bkLast = BkashPrefs.last(app) }

    // ───────── 💳 ব্যালেন্স যোগের অনুরোধ (শুধু অ্যাডমিন; ওয়েবের csOwner / loadCsList) ─────────
    var csOwner by mutableStateOf(false)
        private set
    var csItems by mutableStateOf<List<WalletReq>>(emptyList())
        private set
    var csLoading by mutableStateOf(false)
        private set
    /** এখন যে অনুরোধের অনুমোদন/বাতিল চলছে (একই অনুরোধে দ্বিতীয় চাপ ঠেকাতে) */
    var csBusyKey by mutableStateOf<String?>(null)
        private set
    /** (সফল?, লেখা) */
    var csMsg by mutableStateOf<Pair<Boolean, String>?>(null)

    /** Worker /cslot-get দিয়ে অ্যাডমিন কিনা জানা; অ্যাডমিন হলে অনুরোধের তালিকাও আনে */
    fun loadWallet() {
        viewModelScope.launch {
            val d = runCatching { Worker.post(app, "/cslot-get", JSONObject(), 15000) }.getOrNull() ?: return@launch
            csOwner = d.optBoolean("owner", false) && BuildConfig.IS_ADMIN
            if (csOwner) loadCsList()
        }
    }

    fun loadCsList() {
        if (csLoading) return
        csLoading = true
        viewModelScope.launch {
            try {
                val d = Worker.post(app, "/cslot-list", JSONObject(), 20000)
                val a = d.optJSONArray("items") ?: JSONArray()
                csItems = List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    WalletReq(
                        key = o.optString("key"), email = o.optString("email"), amount = o.optDouble("amount", 0.0),
                        phone = o.optString("phone"), trx = o.optString("trx"), at = o.optLong("at", 0L),
                    )
                }
            } catch (e: Exception) {
                csMsg = false to (e.message ?: "তালিকা আনা যায়নি")
            } finally { csLoading = false }
        }
    }

    /** ✔ অনুমোদন (amount = অ্যাডমিনের মিলিয়ে নেওয়া পরিমাণ, দরকারে বদলানো) বা ✕ বাতিল — Worker /cslot-approve */
    fun csDecide(req: WalletReq, ok: Boolean, amount: String) {
        if (csBusyKey != null) return
        csBusyKey = req.key; csMsg = null
        viewModelScope.launch {
            try {
                Worker.post(app, "/cslot-approve", JSONObject().put("key", req.key).put("ok", ok).put("amount", amount), 20000)
                csMsg = true to (if (ok) "✅ অনুমোদন হয়েছে, ইউজারের ব্যালেন্সে যোগ হয়েছে।" else "✅ অনুরোধ বাতিল হয়েছে।")
            } catch (e: Exception) {
                csMsg = false to ("⚠️ " + (e.message ?: "সমস্যা হয়েছে"))
            } finally {
                csBusyKey = null
                csLoading = false
                loadCsList()   // সফল/ব্যর্থ যা-ই হোক তালিকা আবার আনা (ওয়েবের finally)
            }
        }
    }

    // ───────── 🎬 ভিডিও ক্যাশ ও ডাটা ক্লিয়ার (ওয়েবের vcClearData; কী মোছে তা VideoCache.kt-এ লেখা) ─────────
    var vcBusy by mutableStateOf(false)
        private set
    var vcMsg by mutableStateOf<Pair<Boolean, String>?>(null)

    fun clearVideoData(ev: EditorViewModel) {
        if (vcBusy) return
        if (ev.publish.busy) {
            vcMsg = false to "⚠️ পাবলিশ চলছে, শেষ হলে আবার চেষ্টা করুন।"
            return
        }
        if (RenderBus.status.value is RenderStatus.Running || ev.cardBusy) {
            vcMsg = false to "⚠️ রেন্ডার বা ইন্ট্রো/আউট্রো বানানো চলছে, শেষ হলে আবার চেষ্টা করুন।"
            return
        }
        vcBusy = true; vcMsg = null
        viewModelScope.launch {
            try {
                val inUse = ev.uriInUse()
                val editing = ev.clips.isNotEmpty() || ev.drafts.isNotEmpty()
                val r = withContext(Dispatchers.IO) { VideoCache.clear(app, inUse) }
                withContext(Dispatchers.IO) { LastRender.clear(app) }   // রেন্ডার করা ভিডিও, টাইটেল ও পাবলিশের অবস্থাও মোছে (ওয়েবের vidClean + vidStoreClearAll)
                ev.clearRender()
                ev.resetVideoDefaults()
                if (RenderBus.status.value is RenderStatus.Done) RenderBus.status.value = RenderStatus.Idle   // রেন্ডার করা ভিডিওর পেইজ (ওয়েবের vidClean)
                vcMsg = true to (
                    "✅ ভিডিও ক্যাশ ও ডাটা ক্লিয়ার হয়েছে।\n🗑️ " + Bn.num(r.files) + "টি ফাইল মোছা হয়েছে (" + VideoCache.sizeText(r.bytes) + ")।" +
                        (if (editing) "\n📝 অসমাপ্ত ভিডিও এডিট আগের মতোই আছে।" else "") +
                        "\nএখন নতুন ভিডিও দিলে ইন্ট্রো-আউট্রো, ব্যানার, স্ক্রলিং লেখা, ট্যাগ ও লোগো নতুন করে নিজে বসবে।"
                    )
            } catch (e: Exception) {
                vcMsg = false to ("⚠️ " + (e.message ?: "ক্লিয়ার করা যায়নি"))
            } finally { vcBusy = false }
        }
    }
}
