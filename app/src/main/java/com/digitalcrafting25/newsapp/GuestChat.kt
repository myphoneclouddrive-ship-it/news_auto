package com.digitalcrafting25.newsapp

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * গেস্ট AI টেস্ট — লগইন ছাড়া Gemini / ChatGPT ওয়েবে প্রম্পট পাঠিয়ে উত্তর নেওয়া (পরীক্ষামূলক)।
 * অ্যাপের ভেতরের WebView-তে সাইট খোলে, প্রম্পট বসায়, সেন্ড চাপে, উত্তর ৫ সেকেন্ড না বদলালে শেষ ধরে।
 * ক্যাপচা/যাচাই এলে WebView-তে নিজে করতে হবে; পাশ কাটানোর কোনো কৌশল নেই।
 */
private class GuestSite(val label: String, val url: String, val input: List<String>, val answer: List<String>, val send: List<String>)

private val SITES = linkedMapOf(
    "gemini" to GuestSite(
        "Gemini", "https://gemini.google.com/app",
        input = listOf(
            "rich-textarea div[contenteditable=\"true\"]",
            "div.ql-editor[contenteditable=\"true\"]",
            "div[role=\"textbox\"][contenteditable=\"true\"]",
        ),
        answer = listOf("model-response message-content", "message-content .markdown", ".model-response-text"),
        send = listOf("button[aria-label*=\"Send\"]", "button.send-button"),
    ),
    "chatgpt" to GuestSite(
        "ChatGPT", "https://chatgpt.com/",
        input = listOf(
            "#prompt-textarea",
            "div.ProseMirror[contenteditable=\"true\"]",
            "textarea[name=\"prompt-textarea\"]",
            "div[contenteditable=\"true\"]",
            "div[role=\"textbox\"]",
            "textarea",
        ),
        answer = listOf("[data-message-author-role=\"assistant\"]", "article[data-turn=\"assistant\"]", "div.markdown", ".markdown.prose"),
        send = listOf("button[data-testid=\"send-button\"]", "button[aria-label*=\"Send\"]"),
    ),
)

private const val RUNNER_JS = """
(function(cfg, prompt, timeoutSec){
  if (window.__gcActive) return;
  window.__gcActive = true;
  var B = window.GCBridge;
  function vis(el){ if(!el) return false; var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  function firstVis(sels){
    for (var i = 0; i < sels.length; i++){
      var n = document.querySelectorAll(sels[i]);
      for (var j = n.length - 1; j >= 0; j--){ if (vis(n[j])) return n[j]; }
    }
    return null;
  }
  function count(sels){
    var m = 0;
    for (var i = 0; i < sels.length; i++){ m = Math.max(m, document.querySelectorAll(sels[i]).length); }
    return m;
  }
  function lastText(sels){
    for (var i = 0; i < sels.length; i++){
      var n = document.querySelectorAll(sels[i]);
      if (n.length) return (n[n.length - 1].innerText || '').trim();
    }
    return '';
  }
  function boxText(el){ if(!el) return ''; return ((el.value !== undefined && el.tagName !== 'DIV') ? el.value : (el.innerText || '')).trim(); }
  function busy(){ return !!firstVis(['button[data-testid="stop-button"]','button[aria-label*="Stop"]','button[aria-label*="stop"]']); }
  function fbText(){
    var m = document.querySelector('main') || document.body; var t = (m.innerText || '');
    var last = (prompt.trim().split('\n').pop() || '').trim();
    if (!last) return '';
    var i = t.lastIndexOf(last); if (i < 0) return '';
    var r = t.slice(i + last.length);
    ['Log in for more','Sign up for free','ChatGPT is AI','Get smarter responses','Stay logged out'].forEach(function(m){
      var k = r.toLowerCase().indexOf(m.toLowerCase()); if (k >= 0) r = r.slice(0, k);
    });
    return r.trim();
  }
  function pressEnter(el){
    ['keydown','keypress','keyup'].forEach(function(t){
      el.dispatchEvent(new KeyboardEvent(t, {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
    });
  }
  function dismiss(){
    var texts = ['Stay logged out','No thanks','Not now','Skip','Reject all'];
    var els = document.querySelectorAll('a,button,div[role="button"]');
    for (var i = 0; i < els.length; i++){
      var t = (els[i].innerText || '').trim();
      if (texts.indexOf(t) >= 0 && vis(els[i])){ els[i].click(); B.status('পপআপ বন্ধ করা হয়েছে: ' + t); return true; }
    }
    return false;
  }
  function diag(){
    var ce = document.querySelectorAll('[contenteditable="true"]').length, ta = document.querySelectorAll('textarea').length;
    var body = (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').slice(0, 150);
    return 'পেজ: ' + location.hostname + ' | title: ' + document.title + ' | contenteditable=' + ce + ' textarea=' + ta + ' | লেখা: ' + body;
  }
  var t0 = Date.now(), phase = 0, baseline = 0, lastTxt = '', lastChange = 0, sendAt = 0, sentAt = 0, retried = false, box = null;
  var iv = setInterval(function(){
    try {
      if (Date.now() - t0 > timeoutSec * 1000){
        clearInterval(iv); window.__gcActive = false; B.done(lastTxt, false); return;
      }
      if (phase === 0){
        dismiss();
        box = firstVis(cfg.input);
        if (!box){
          if (Date.now() - t0 > 15000) B.status('⚠️ লেখার বক্স পাওয়া যায়নি। ' + diag());
          else B.status('লেখার বক্স খুঁজছি... (যাচাই/লগইন পপআপ এলে নিচের ব্রাউজারে নিজে করুন)');
          return;
        }
        baseline = count(cfg.answer);
        box.focus();
        if (box.isContentEditable){
          document.execCommand('selectAll', false, null);
          document.execCommand('insertText', false, prompt);
        } else {
          var proto = box.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
          Object.getOwnPropertyDescriptor(proto, 'value').set.call(box, prompt);
          box.dispatchEvent(new Event('input', {bubbles:true}));
        }
        sendAt = Date.now() + 800; phase = 1;
        B.status('প্রম্পট বসানো হয়েছে');
        return;
      }
      if (phase === 1){
        if (Date.now() < sendAt) return;
        var btn = firstVis(cfg.send);
        if (btn && !btn.disabled) btn.click(); else pressEnter(box);
        phase = 2; sentAt = Date.now(); B.sent(); B.status('পাঠানো হয়েছে, উত্তরের অপেক্ষা...');
        return;
      }
      if (phase === 2){
        var tx = '';
        if (count(cfg.answer) > baseline){
          tx = lastText(cfg.answer);
        } else {
          var left = boxText(box);
          // Enter আবার চাপা হবে শুধু যদি লেখার বক্সে প্রম্পট তখনও থেকে যায় (মানে সত্যিই যায়নি)
          if (!retried && left.length > 0 && Date.now() - sentAt > 6000){ retried = true; pressEnter(box); B.status('আবার Enter চাপা হয়েছে...'); return; }
          // বক্স খালি = পাঠানো হয়ে গেছে; সিলেক্টর না মিললে পেজের লেখা থেকে উত্তর নিই
          if (left.length === 0 && Date.now() - sentAt > 8000) tx = fbText();
          if (!tx){ B.status('উত্তরের অপেক্ষা...'); return; }
        }
        if (tx !== lastTxt){ lastTxt = tx; lastChange = Date.now(); B.status('উত্তর আসছে... (' + tx.length + ' অক্ষর)'); }
        else if (busy()){ lastChange = Date.now(); }
        else if (tx && Date.now() - lastChange >= 5000){ clearInterval(iv); window.__gcActive = false; B.done(tx, true); }
      }
    } catch (e) { B.status('JS ত্রুটি: ' + e); }
  }, 1000);
})(%CFG%, %PROMPT%, %TIMEOUT%);
"""

private class GuestBridge(
    private val onStatus: (String) -> Unit,
    private val onSent: () -> Unit,
    private val onDone: (String, Boolean) -> Unit,
) {
    private val h = Handler(Looper.getMainLooper())
    @JavascriptInterface fun status(m: String) { h.post { onStatus(m) } }
    @JavascriptInterface fun sent() { h.post { onSent() } }
    @JavascriptInterface fun done(text: String, ok: Boolean) { h.post { onDone(text, ok) } }
}

private fun buildScript(s: GuestSite, prompt: String, timeoutSec: Int): String {
    val cfg = JSONObject()
        .put("input", JSONArray(s.input))
        .put("answer", JSONArray(s.answer))
        .put("send", JSONArray(s.send))
    return RUNNER_JS
        .replace("%CFG%", cfg.toString())
        .replace("%PROMPT%", JSONObject.quote(prompt))
        .replace("%TIMEOUT%", timeoutSec.toString())
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun makeWebView(ctx: Context, bridge: GuestBridge): WebView =
    WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        addJavascriptInterface(bridge, "GCBridge")
        setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
    }


// ───────── লিংক থেকে নিউজ রিরাইট ─────────

private class Article(val title: String, val text: String)

private fun fetchArticle(url: String): Article? = runCatching {
    val r = httpCall("GET", url, null, mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
        "Accept-Language" to "bn,en;q=0.8",
    ), readMs = 20000)
    if (r.code !in 200..299) return@runCatching null
    val opt = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    var h = r.text
    fun plain(x: String) = android.text.Html.fromHtml(x, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0', ' ').trim()
    val title = Regex("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)", opt).find(h)?.groupValues?.get(1)
        ?: Regex("<title[^>]*>(.*?)</title>", opt).find(h)?.groupValues?.get(1) ?: ""
    h = h.replace(Regex("<(script|style|noscript|nav|footer|aside|header|form|svg)[^>]*>.*?</\\1>", opt), " ")
    val art = Regex("<article[^>]*>(.*?)</article>", opt).findAll(h).map { it.groupValues[1] }.maxByOrNull { it.length } ?: h
    val paras = Regex("<p[^>]*>(.*?)</p>", opt).findAll(art).map { plain(it.groupValues[1]) }.filter { it.length >= 40 }.toList()
    val text = paras.joinToString("\n").take(7000)
    if (text.length < 300) null else Article(plain(title), text)
}.getOrNull()

private fun buildNewsPrompt(a: Article?, url: String): String {
    val rules = "তুমি একজন পেশাদার বাংলাদেশি নিউজ রিপোর্টার। নিচের খবরটি পড়ে নিজের ভাষায় নতুন করে লেখো, হুবহু কপি করবে না, তবে সব তথ্য ঠিক রাখবে।\n" +
        "নিয়ম:\n" +
        "১) শুধু খবরটাই লিখবে। কোনো ভূমিকা, মন্তব্য, ব্যাখ্যা, পরামর্শ, প্রশ্ন, ইমোজি বা মার্কডাউন (**, #) থাকবে না।\n" +
        "২) নিজে থেকে কোনো তথ্য, নাম, সংখ্যা বা উদ্ধৃতি যোগ করবে না।\n" +
        "৩) ফরম্যাট ঠিক এরকম হবে: প্রথম লাইনে \"শিরোনাম: \" লিখে একটি আকর্ষণীয় শিরোনাম, তারপর একটা ফাঁকা লাইন, তারপর ছোট ছোট অনুচ্ছেদে খবরের বডি (প্রতি অনুচ্ছেদের মাঝে ফাঁকা লাইন)।\n\n"
    val src = if (a != null) "মূল খবরের শিরোনাম: ${a.title}\nমূল খবর:\n${a.text}\n\n"
              else "খবরের লিংক (এটি খুলে পড়ো): $url\n\n"
    return rules + src + "শুধু উপরের ফরম্যাটে নিউজটি লিখুন, আর কিছু নয়।"
}

/** গেস্ট ChatGPT/Gemini পেজের নিচের বাড়তি লেখা (লগইন অনুরোধ ইত্যাদি) কেটে ফেলে */
private fun cleanTail(t: String): String {
    val marks = listOf("Log in for more", "Sign up for free", "ChatGPT is AI", "Get smarter responses", "Stay logged out")
    var cut = t.length
    for (m in marks) { val i = t.indexOf(m, ignoreCase = true); if (i in 0 until cut) cut = i }
    return t.substring(0, cut).trim()
}

/** AI-র উত্তর থেকে (শিরোনাম, বডি) আলাদা করে; বাড়তি চিহ্ন/প্রিফিক্স ছেঁটে ফেলে */
private fun parseNews(raw: String): Pair<String, String> {
    var lines = raw.replace("\r", "").lines().map { it.replace("**", "").replace(Regex("^#+\\s*"), "").trimEnd() }
    lines = lines.dropWhile { it.isBlank() || Regex("^(ChatGPT said:?|Gemini said:?|You said:?)\\s*\$", RegexOption.IGNORE_CASE).matches(it.trim()) }
    val ti = lines.indexOfFirst { Regex("^\\s*(শিরোনাম|Title)\\s*[:：]").containsMatchIn(it) }
    val title: String; val rest: List<String>
    if (ti >= 0) {
        title = lines[ti].replace(Regex("^\\s*(শিরোনাম|Title)\\s*[:：]\\s*"), "").trim().trim('"', '“', '”')
        rest = lines.drop(ti + 1)
    } else { title = lines.firstOrNull()?.trim() ?: ""; rest = lines.drop(1) }
    val body = rest.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    return title to body
}

/** সেটিংসের কার্ডে শুধু একটা বাটন; চাপলে নিজের পুরো স্ক্রিনে খোলে */
@Composable
fun GuestChatBody() {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Small("লগইন ছাড়া Gemini/ChatGPT ওয়েবে প্রম্পট পাঠিয়ে উত্তর আনে। পরীক্ষামূলক — সাইট বদলালে বা গেস্ট সীমা/ক্যাপচা এলে কাজ নাও করতে পারে।")
        AppBtn("🧪 গেস্ট AI টেস্ট খুলুন", color = C.Green, onClick = { open = true })
    }
    if (open) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { open = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.White) {
                GuestChatScreen(onClose = { open = false })
            }
        }
    }
}

@Composable
private fun GuestChatScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var siteKey by rememberSaveable { mutableStateOf("gemini") }
    var prompt by rememberSaveable { mutableStateOf("বাংলাদেশের রাজধানী কোথায়? এক বাক্যে বলো") }
    var status by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var runId by remember { mutableStateOf(0) }
    var showWeb by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var newsUrl by rememberSaveable { mutableStateOf("") }
    var newsMode by remember { mutableStateOf(false) }
    var newsTitle by remember { mutableStateOf("") }
    var newsBody by remember { mutableStateOf("") }
    var copiedKey by remember { mutableStateOf("") }

    val timeoutSec = 180
    val currentRunning by androidx.compose.runtime.rememberUpdatedState(running)
    val currentSent by androidx.compose.runtime.rememberUpdatedState(sent)

    // WebView প্রথমবার তৈরি হতে অনেক সময় নেয়; তাই স্ক্রিন খোলার সময় নয়, বাটন চাপার পরে তৈরি হয়
    var web by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()

    /** কাজ শেষে ওয়েব ভিউ বন্ধ: আগে স্ক্রিন থেকে সরায়, একটু পরে destroy */
    fun closeWeb() {
        val w0 = web ?: return
        web = null; showWeb = false
        scope.launch { delay(400); runCatching { w0.stopLoading(); w0.destroy() } }
    }

    val bridge = remember {
        GuestBridge(
            onStatus = { status = it },
            onSent = { sent = true },
            onDone = { text0, ok ->
                val text = cleanTail(text0)
                running = false
                closeWeb()
                if (newsMode && text.isNotBlank()) {
                    val (t, b) = parseNews(text); newsTitle = t; newsBody = b; answer = ""
                } else answer = text
                status = when {
                    ok -> "✅ উত্তর পাওয়া গেছে (${text.length} অক্ষর)"
                    text.isNotBlank() -> "⚠️ সময় শেষ — যতটুকু এসেছে ততটুকু দেখানো হলো"
                    else -> "⚠️ উত্তর পাওয়া যায়নি। গেস্ট সীমা শেষ, যাচাই বাকি, বা সাইটের ডিজাইন বদলেছে — নিচের ব্রাউজার দেখুন"
                }
            },
        )
    }

    DisposableEffect(Unit) { onDispose { runCatching { web?.stopLoading(); web?.destroy() } } }

    fun begin(w: WebView, s: GuestSite, p: String) {
        status = "খুলছি: ${s.url}"
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (currentRunning && !currentSent) {
                    view.evaluateJavascript(buildScript(s, p, timeoutSec), null)
                }
            }
        }
        w.loadUrl(s.url)
    }

    fun launchPrompt(p: String) {
        val s = SITES[siteKey] ?: return
        answer = ""; copied = false; sent = false; running = true; runId++
        val w = web
        if (w != null) {
            begin(w, s, p)
        } else {
            status = "ব্রাউজার চালু হচ্ছে (প্রথমবার কয়েক সেকেন্ড লাগতে পারে, অপেক্ষা করুন)..."
            scope.launch {
                delay(300)
                val nw = makeWebView(ctx, bridge)
                web = nw
                begin(nw, s, p)
            }
        }
    }

    fun start() {
        val p = prompt.trim()
        if (p.isEmpty()) { status = "⚠️ প্রম্পট লিখুন"; return }
        newsMode = false; newsTitle = ""; newsBody = ""
        launchPrompt(p)
    }

    // শুধু এই বাটন চাপলেই লিংক পড়া ও AI চালু হয়
    fun startNews() {
        val u = newsUrl.trim()
        if (!Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(u)) { status = "⚠️ সঠিক লিংক দিন (https://...)"; return }
        newsMode = true; newsTitle = ""; newsBody = ""; answer = ""; running = true
        status = "📰 লিংক থেকে খবর পড়ছি..."
        scope.launch {
            val art = withContext(Dispatchers.IO) { fetchArticle(u) }
            if (art == null) status = "⚠️ লিংক থেকে খবরের লেখা আনা যায়নি; AI-কে লিংক দিয়েই পড়তে বলছি (নাও পারতে পারে)"
            launchPrompt(buildNewsPrompt(art, u))
        }
    }

    // JS মরে গেলেও (পেজ বদলে) যেন আটকে না থাকে
    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        delay((timeoutSec + 25) * 1000L)
        if (running) { running = false; status = "⚠️ সময় শেষ, উত্তর আসেনি"; closeWeb() }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("🧪 গেস্ট AI টেস্ট", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { if (running) web?.stopLoading(); onClose() }) { Text("✖ বন্ধ") }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SITES.forEach { (k, s) ->
                    FilterChip(selected = siteKey == k, onClick = { if (!running) siteKey = k }, label = { Text(s.label) })
                }
            }

            AppField(prompt, { prompt = it }, placeholder = "প্রম্পট লিখুন...", singleLine = false)

            AppBtn(
                if (running) "⏳ চলছে..." else "🚀 পাঠান ও উত্তর আনুন",
                color = C.Green, enabled = !running,
                onClick = { start() },
            )
            if (running) {
                AppBtn("⏹️ থামান", color = C.Gray, onClick = {
                    running = false; web?.stopLoading(); web?.evaluateJavascript("window.__gcActive=false", null); status = "থামানো হয়েছে"; closeWeb()
                })
            }

            if (status.isNotEmpty()) Text(status, fontSize = 13.sp, color = if (status.startsWith("✅")) C.Green else if (status.startsWith("⚠️")) C.Red else C.Muted)


            FieldLabel("📰 লিংক থেকে নিউজ রিরাইট")
            AppField(newsUrl, { newsUrl = it }, placeholder = "নিউজের লিংক পেস্ট করুন (https://...)")
            AppBtn(if (running && newsMode) "⏳ লেখা হচ্ছে..." else "📰 নিউজ পড়ে নতুন করে লিখুন", color = C.Red, enabled = !running, onClick = { startNews() })

            if (newsTitle.isNotEmpty() || newsBody.isNotEmpty()) {
                fun cp(key: String, text: String) {
                    runCatching {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("news", text))
                        copiedKey = key
                    }
                }
                FieldLabel("শিরোনাম")
                SelectionContainer {
                    Text(newsTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().border(1.dp, C.Border, RoundedCornerShape(8.dp)).padding(10.dp))
                }
                AppBtn(if (copiedKey == "t") "✅ কপি হয়েছে" else "📋 শিরোনাম কপি", color = C.Blue, onClick = { cp("t", newsTitle) })
                FieldLabel("বডি")
                SelectionContainer {
                    Text(newsBody, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().border(1.dp, C.Border, RoundedCornerShape(8.dp)).padding(10.dp))
                }
                AppBtn(if (copiedKey == "b") "✅ কপি হয়েছে" else "📋 বডি কপি", color = C.Blue, onClick = { cp("b", newsBody) })
                AppBtn(if (copiedKey == "a") "✅ কপি হয়েছে" else "📋 শিরোনাম + বডি একসাথে কপি", color = C.Green, onClick = { cp("a", newsTitle + "\n\n" + newsBody) })
            }

            if (answer.isNotEmpty()) {
                FieldLabel("উত্তর")
                SelectionContainer {
                    Text(
                        answer, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().border(1.dp, C.Border, RoundedCornerShape(8.dp)).padding(10.dp),
                    )
                }
                AppBtn(if (copied) "✅ কপি হয়েছে" else "📋 উত্তর কপি করুন", color = C.Blue, onClick = {
                    runCatching {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("answer", answer))
                        copied = true
                    }
                })
            }
        }
        // ব্রাউজার ব্যাকগ্রাউন্ডে চলে (দেখা যায় না)। শুধু যাচাই/ক্যাপচা এলে চলার সময় "দেখান" চেপে নিজে করা যাবে
        val w = web
        if (w != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (showWeb) "🌐 ব্রাউজার (যাচাই/ক্যাপচা এখানে করুন)" else "", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { showWeb = !showWeb }) { Text(if (showWeb) "লুকান" else "🌐 যাচাই চাইলে ব্রাউজার দেখান") }
            }
            androidx.compose.foundation.layout.Box(
                if (showWeb) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(1.dp).clipToBounds(),
            ) {
                AndroidView(
                    factory = { w },
                    modifier = if (showWeb) Modifier.fillMaxSize() else Modifier.fillMaxWidth().requiredHeight(640.dp),
                )
            }
        }
    }
}
