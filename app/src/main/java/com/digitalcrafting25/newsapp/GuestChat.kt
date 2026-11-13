package com.digitalcrafting25.newsapp

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
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
private class GuestSite(val label: String, val url: String, val input: List<String>, val answer: List<String>, val send: List<String>, val domains: List<String> = emptyList())

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
        domains = listOf("gemini.google.com", "accounts.google.com", "google.com"),
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
        domains = listOf("chatgpt.com", "openai.com"),
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
    ['Log in for more','Sign up for free','ChatGPT is AI','Get smarter responses','Stay logged out','Sign in to connect','Sign in','Gemini can make mistakes'].forEach(function(m){
      var k = r.toLowerCase().indexOf(m.toLowerCase()); if (k >= 0) r = r.slice(0, k);
    });
    return r.trim();
  }
  function fail(msg){ clearInterval(iv); window.__gcActive = false; B.fail(msg); }
  function captcha(){
    if (document.querySelector('iframe[src*="captcha"],iframe[src*="recaptcha"],iframe[src*="turnstile"],iframe[src*="challenges.cloudflare"],.cf-turnstile,#challenge-form,#challenge-running')) return true;
    var t = (document.body ? document.body.innerText : '').slice(0, 2000).toLowerCase();
    return /verify you are human|are you a robot|unusual traffic|captcha|checking your browser|just a moment/.test(t);
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
          var el0 = Date.now() - t0;
          if (el0 > 6000 && captcha()) return fail('ক্যাপচা/যাচাই এসেছে');
          if (el0 > 25000) return fail('লেখার বক্স পাওয়া যায়নি');
          B.status('লেখার বক্স খুঁজছি...');
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
          if (retried && left.length > 0 && Date.now() - sentAt > 20000) return fail('প্রম্পট সাবমিট হয়নি');
          if (left.length === 0 && Date.now() - sentAt > 8000) { tx = fbText(); if (tx && !/[\u0980-\u09FF]/.test(tx)) tx = ''; }
          if (!tx){
            if (Date.now() - sentAt > 8000 && captcha()) return fail('ক্যাপচা/যাচাই এসেছে');
            if (Date.now() - sentAt > 75000) return fail('উত্তর আসেনি');
            B.status('উত্তরের অপেক্ষা...'); return;
          }
        }
        if (tx !== lastTxt){ lastTxt = tx; lastChange = Date.now(); B.status('উত্তর আসছে... (' + tx.length + ' অক্ষর)'); if (B.partial) B.partial(tx); }
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
    private val onFail: (String) -> Unit,
    private val onPartial: (String) -> Unit = {},
) {
    private val h = Handler(Looper.getMainLooper())
    @JavascriptInterface fun status(m: String) { h.post { onStatus(m) } }
    @JavascriptInterface fun sent() { h.post { onSent() } }
    @JavascriptInterface fun done(text: String, ok: Boolean) { h.post { onDone(text, ok) } }
    @JavascriptInterface fun fail(m: String) { h.post { onFail(m) } }
    @JavascriptInterface fun partial(t: String) { h.post { onPartial(t) } }
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
    val text = paras.joinToString("\n").take(10000)
    if (text.length < 300) null else Article(plain(title), text)
}.getOrNull()

private fun buildNewsPrompt(a: Article?, url: String, notes: String = ""): String {
    // ধাপ ১: আগে মূল খবরের লেখা (লিংক খুলে যা পাওয়া গেছে) পেস্ট, তারপর নির্দেশ। লিংক থেকে লেখা না এলে লিংকটাই যায়।
    var src = if (a != null) (if (a.title.isNotBlank()) "${a.title}\n\n" else "") + a.text.trim() + "\n\n"
              else if (url.isNotBlank()) "খবরের লিংক (এটি খুলে পড়ো): $url\n\n"
              else ""
    if (notes.isNotBlank()) src += "প্রতিবেদকের নিজের সংগ্রহ করা তথ্য:\n${notes.trim().take(3000)}\n\n"
    val rules = "এই নিউজটা তুমি আমাকে রিরাইট করে দাও। হুবহু কপি হবে না। মূল লেখার থেকে ডিফারেন্ট হবে। তথ্য ঠিক থাকবে। " +
        "যেন মনে হয় কোনো সাংবাদিক লিখেছে। কোনো এক্সট্রা কথা যোগ করবে না। তোমার থিংকিং বা সাজেশন যোগ করবে না। " +
        "লেখা প্লেইন টেক্সট এ হবে যেনো কপি পেস্ট করতে পারি। তিনটা স্তর থাকবে:\n" +
        "১. টাইটেল:\n" +
        "২. ফটোকার্ড টাইটেল:\n" +
        "৩. নিউজ:\n" +
        "ছোট করবে না নিউজটা।"
    return src + rules
}

/** ব্যর্থ সাইটের কুকি, লোকাল স্টোরেজ ও ক্যাশ মুছে ফেলে (অন্য সাইটের কুকি ছোঁয়া হয় না)।
 *  "; Secure" লাগে কারণ __Secure-/__Host- প্রিফিক্সের কুকি Secure ছাড়া মুছতে দেয় না (ChatGPT-র সেশন কুকি এরকম)। */
private fun clearSiteData(s: GuestSite, w: WebView?) {
    runCatching {
        val cm = CookieManager.getInstance()
        for (d in s.domains) {
            val url = "https://$d"
            val names = (cm.getCookie(url) ?: "").split(";").map { it.substringBefore("=").trim() }.filter { it.isNotEmpty() }
            for (n in names) for (dom in listOf("", "; Domain=$d", "; Domain=.$d")) {
                cm.setCookie(url, "$n=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/$dom; Secure")
            }
            WebStorage.getInstance().deleteOrigin(url)
        }
        cm.flush()
    }
    runCatching { w?.clearCache(true); w?.clearFormData(); w?.clearHistory() }
}

/** গেস্ট ChatGPT/Gemini পেজের নিচের বাড়তি লেখা (লগইন অনুরোধ ইত্যাদি) কেটে ফেলে */
private fun cleanTail(t: String): String {
    val marks = listOf("Log in for more", "Sign up for free", "ChatGPT is AI", "Get smarter responses", "Stay logged out", "Sign in to connect", "Sign in", "Gemini can make mistakes")
    var cut = t.length
    for (m in marks) { val i = t.indexOf(m, ignoreCase = true); if (i in 0 until cut) cut = i }
    return t.substring(0, cut).trim()
}

/** AI-র উত্তর থেকে (শিরোনাম, বডি) আলাদা করে; বাড়তি চিহ্ন/প্রিফিক্স ছেঁটে ফেলে */
private fun parseNews(raw: String): Pair<String, String> = parseNews3(raw).let { it.first to it.third }

/** Gemini ওয়েব-সার্চ করলে অনুচ্ছেদের শেষে সোর্স-চিপ বসায় ("শিক্ষাবার্তা ডট কম", "CZN24", "+ 1", "Citizen Journal"); innerText-এ সেগুলো লেখার ভেতরে ঢুকে যায়।
 *  চেনার উপায়: "+ N" লাইন সবসময় চিপ; আর আগের লাইন বাক্য-শেষ (। . ? ! ” ) হয়ে ফাঁকা লাইন ছাড়াই নামা ছোট (≤ ৪৫ অক্ষর, ≤ ৬ শব্দ), দাঁড়ি-ছাড়া, বুলেট/নম্বর-ছাড়া লাইন চিপ। */
private fun stripCites(body: String): String {
    val enders = "।.?!”\"’)"
    val plus = Regex("^\\+\\s*[0-9\u09E6-\u09EF]+$")
    val out = ArrayList<String>()
    for (raw in body.lines()) {
        val l = raw.trim()
        if (l.isNotEmpty() && plus.matches(l)) continue
        val prev = out.lastOrNull()
        val chip = l.isNotEmpty() && prev != null && prev.isNotBlank() &&
            prev.trimEnd().last() in enders && l.length <= 45 && l.split(Regex("\\s+")).size <= 6 &&
            l.last() !in enders && !Regex("^([•\\-*]|[0-9\u09E6-\u09EF]+[.)])").containsMatchIn(l)
        if (chip) continue
        out.add(raw)
    }
    return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}

/** (শিরোনাম, কার্ড-শিরোনাম, বডি); কার্ড-শিরোনাম না থাকলে ফাঁকা।
 *  নতুন ফরম্যাট: "১. টাইটেল: …", "২. ফটোকার্ড টাইটেল: …", "৩. নিউজ: …" (নম্বর বাংলা/ইংরেজি, থাকতেও পারে না-ও পারে)।
 *  এই লেবেল না পেলে আগের "শিরোনাম:"/"কার্ড:" ফরম্যাট ধরা হয়। */
private fun parseNews3(raw: String): Triple<String, String, String> {
    var lines = raw.replace("\r", "").lines().map { it.replace("**", "").replace(Regex("^#+\\s*"), "").trimEnd() }
    lines = lines.dropWhile { it.isBlank() || Regex("^(ChatGPT said:?|Gemini said:?|You said:?)\\s*\$", RegexOption.IGNORE_CASE).matches(it.trim()) }
    val num = "(?:[0-9\u09E6-\u09EF]+\\s*[.)।:\\-]?\\s*)?"
    val cardRe = Regex("^\\s*" + num + "(?:ফটোকার্ড|ফোটোকার্ড|ফটো\\s*কার্ড|কার্ড|Photo\\s*card|Card)(?:\\s*(?:টাইটেল|শিরোনাম|title))?\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    val titleRe = Regex("^\\s*" + num + "(?:টাইটেল|শিরোনাম|Title)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    val newsRe = Regex("^\\s*" + num + "(?:নিউজ|খবর|News|Body)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    val ni = lines.indexOfFirst { newsRe.containsMatchIn(it) }
    val tIdx = lines.indexOfFirst { titleRe.containsMatchIn(it) }
    if (ni >= 0 && tIdx in 0 until ni) {
        // লেবেলের পরের মান: একই লাইনে না থাকলে পরের প্রথম অ-ফাঁকা লাইন
        fun valueAt(i: Int, re: Regex, limit: Int): String {
            val same = lines[i].replace(re, "").trim().trim('"', '“', '”')
            if (same.isNotEmpty()) return same
            val j = (i + 1 until limit).firstOrNull { lines[it].isNotBlank() } ?: return ""
            return lines[j].trim().trim('"', '“', '”')
        }
        val cIdx = lines.indexOfFirst { cardRe.containsMatchIn(it) }.takeIf { it in (tIdx + 1) until ni } ?: -1
        val title = valueAt(tIdx, titleRe, if (cIdx >= 0) cIdx else ni)
        val card = if (cIdx >= 0) valueAt(cIdx, cardRe, ni) else ""
        val first = lines[ni].replace(newsRe, "")
        val body = stripCites((listOf(first) + lines.drop(ni + 1)).joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim())
        return Triple(title, card, body)
    }
    // আগের ফরম্যাট
    val ti = lines.indexOfFirst { Regex("^\\s*(শিরোনাম|টাইটেল|Title)\\s*[:：]").containsMatchIn(it) }
    val title: String; val rest: List<String>
    if (ti >= 0) {
        title = lines[ti].replace(Regex("^\\s*(শিরোনাম|টাইটেল|Title)\\s*[:：]\\s*"), "").trim().trim('"', '“', '”')
        rest = lines.drop(ti + 1)
    } else { title = lines.firstOrNull()?.trim() ?: ""; rest = lines.drop(1) }
    val ci = rest.indexOfFirst { it.isNotBlank() }
    var card = ""; var rest2 = rest
    if (ci in 0..2 && cardRe.containsMatchIn(rest[ci])) {
        card = rest[ci].replace(cardRe, "").trim().trim('"', '“', '”')
        rest2 = rest.drop(ci + 1)
    }
    val body = stripCites(rest2.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim())
    return Triple(title, card, body)
}

// ───────── নতুন পোস্টের "AI দিয়ে নতুন করে লিখুন": প্রসেসিং প্যানেলের পেছনে অদৃশ্য রানার ─────────

/** একটা গেস্ট লেখার কাজ। ভিউমডেল ধরে রাখে; স্ক্রিনে থাকলে [GuestHiddenRunner] চালায় */
/** title: Worker (/fetch-source) থেকে আনা মূল শিরোনাম; input তখন মূল লেখা (লিংক নয়) */
class GuestJob(val id: Int, val input: String, val notes: String, val title: String = "")

/** খবরের লেখায় অন্তত কিছু বাংলা অক্ষর থাকতেই হবে; না থাকলে সেটা সাইটের সাইন-ইন/ব্যানারের লেখা, উত্তর নয় */
private fun hasBangla(t: String) = t.any { it in '\u0980'..'\u09FF' }

/** চলমান উত্তরের শেষ ৩ লাইন (প্রসেসিং প্যানেলের লাইভ লগের জন্য)। সাইটের নাম কখনো দেখায় না */
fun guestLiveLines(raw: String): List<String> {
    val clean = cleanTail(raw)
    if (!hasBangla(clean)) return emptyList()   // সাইন-ইন ব্যানারের ইংরেজি লেখা কখনো শিরোনাম হিসেবে দেখাবে না
    val (t, b) = parseNews(clean)
    val out = ArrayList<String>()
    if (t.isNotBlank()) out += "📰 শিরোনাম: $t"
    b.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
    return out
        .filter { !it.contains("chatgpt", ignoreCase = true) && !it.contains("gemini", ignoreCase = true) }
        .takeLast(3).map { if (it.length > 150) "…" + it.takeLast(150) else it }
}

private class GuestRun {
    var web: WebView? = null
    var running = true
    var sent = false
    var runKey = ""
    var prompt = ""
    val tried = mutableSetOf<String>()
}

/**
 * কোনো বাটন/লেখা/ব্রাউজার দেখায় না। ১dp উচ্চতার স্বচ্ছ বক্সে WebView চলে।
 * একটা সাইটে ব্যর্থ (ক্যাপচা, সাবমিট হয়নি, সময় শেষ ইত্যাদি) হলে কুকি-ক্যাশ মুছে অন্য সাইটে একই প্রম্পট;
 * সবগুলো ব্যর্থ হলে [onFail] (ভিউমডেল তখন আগের মতো API দিয়ে লেখে)।
 */
@Composable
fun GuestHiddenRunner(
    job: GuestJob,
    onSent: () -> Unit,
    onPartial: (String) -> Unit,
    onDone: (title: String, card: String, body: String) -> Unit,
    onFail: () -> Unit,
    onStage: (Int) -> Unit = {},   // ১ = Gemini, ২ = ChatGPT (প্যানেলে শুধু নম্বর দেখায়, নাম নয়)
) {
    val ctx = LocalContext.current
    val st = remember(job.id) { GuestRun() }
    var web by remember(job.id) { mutableStateOf<WebView?>(null) }
    var runId by remember(job.id) { mutableStateOf(0) }
    val cbSent by rememberUpdatedState(onSent)
    val cbPartial by rememberUpdatedState(onPartial)
    val cbDone by rememberUpdatedState(onDone)
    val cbFail by rememberUpdatedState(onFail)
    val cbStage by rememberUpdatedState(onStage)
    val timeoutSec = 180

    fun begin(w: WebView, s: GuestSite, p: String) {
        st.sent = false; runId++
        cbStage(SITES.keys.indexOf(st.runKey) + 1)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (st.running && !st.sent) view.evaluateJavascript(buildScript(s, p, timeoutSec), null)
            }
        }
        w.loadUrl(s.url)
    }

    fun handleFail() {
        if (!st.running) return
        val failed = SITES[st.runKey]
        val w = st.web
        if (failed != null) clearSiteData(failed, w)
        val next = SITES.keys.firstOrNull { it !in st.tried }
        val ns = next?.let { SITES[it] }
        if (next == null || ns == null || w == null) { st.running = false; cbFail(); return }
        st.tried.add(next); st.runKey = next
        begin(w, ns, st.prompt)
    }

    val bridge = remember(job.id) {
        GuestBridge(
            onStatus = {},   // ইচ্ছে করেই কিছু দেখানো হয় না
            onSent = { st.sent = true; if (st.running) cbSent() },
            onPartial = { if (st.running) cbPartial(it) },
            onDone = { raw, ok ->
                if (st.running) {
                    val text = cleanTail(raw)
                    if (text.isBlank() || !hasBangla(text) || (!ok && text.length < 400)) handleFail()   // সময় শেষে অল্প লেখা এলে গ্রহণ করি না
                    else {
                        val (t, c, b) = parseNews3(text)
                        if (t.isBlank() || b.isBlank()) handleFail() else { st.running = false; cbDone(t, c, b) }
                    }
                }
            },
            onFail = { handleFail() },
        )
    }

    LaunchedEffect(job.id) {
        val u = job.input.trim()
        val isUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(u)
        val art = if (isUrl) withContext(Dispatchers.IO) { fetchArticle(u) }
                  else if (u.length >= 100) Article(job.title, u.take(10000)) else null
        st.prompt = buildNewsPrompt(art, if (isUrl) u else "", job.notes)
        val firstKey = SITES.keys.first()
        st.tried.clear(); st.tried.add(firstKey); st.runKey = firstKey
        delay(300)   // WebView প্রথমবার তৈরি হতে সময় নেয়
        if (!st.running) return@LaunchedEffect
        val nw = makeWebView(ctx, bridge)
        st.web = nw; web = nw
        begin(nw, SITES.getValue(firstKey), st.prompt)
    }

    // JS মরে গেলেও (পেজ বদলে) যেন আটকে না থাকে
    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        delay((timeoutSec + 25) * 1000L)
        if (st.running) handleFail()
    }

    DisposableEffect(job.id) {
        onDispose {
            st.running = false
            val w = st.web; st.web = null
            if (w != null) {
                runCatching { w.stopLoading() }
                Handler(Looper.getMainLooper()).postDelayed({ runCatching { w.destroy() } }, 500)   // স্ক্রিন থেকে সরার পর destroy
            }
        }
    }

    val w = web
    if (w != null) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).clipToBounds().alpha(0f)) {
            AndroidView(factory = { w }, modifier = Modifier.fillMaxWidth().requiredHeight(640.dp))
        }
    }
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

/** নতুন পোস্টের "ফ্রি গেস্ট AI" (ইউজার ও অ্যাডমিন দুই অ্যাপেই): খবর/লিংক/নোট দিয়ে লিখিয়ে শিরোনাম+বডি ফেরত দেয় */
/** onFallback: Gemini ও ChatGPT দুটোই ব্যর্থ হলে ডায়ালগ বন্ধ করে আগের মতো API দিয়ে লেখার জন্য */
class GuestRewrite(val input: String, val notes: String, val onUse: (title: String, body: String) -> Unit, val onFallback: (() -> Unit)? = null)

@Composable
fun GuestRewriteDialog(input: String, notes: String, onUse: (String, String) -> Unit, onClose: () -> Unit, onFallback: (() -> Unit)? = null) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.White) {
            GuestChatScreen(onClose = onClose, rw = GuestRewrite(input, notes, onUse, onFallback))
        }
    }
}

@Composable
private fun GuestChatScreen(onClose: () -> Unit, rw: GuestRewrite? = null) {
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
    var newsUrl by rememberSaveable { mutableStateOf(rw?.input ?: "") }
    val newsNotes = rw?.notes ?: ""
    var newsMode by remember { mutableStateOf(false) }
    var newsTitle by remember { mutableStateOf("") }
    var newsBody by remember { mutableStateOf("") }
    var copiedKey by remember { mutableStateOf("") }
    var failReason by remember { mutableStateOf<String?>(null) }
    var runKey by remember { mutableStateOf("") }
    var runPrompt by remember { mutableStateOf("") }
    val tried = remember { mutableSetOf<String>() }

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
                if (text.isBlank()) {
                    failReason = "উত্তর পাওয়া যায়নি"   // অন্য সাইটে চেষ্টা (নিচের LaunchedEffect)
                } else {
                    running = false
                    closeWeb()
                    if (newsMode) {
                        val (t, b) = parseNews(text); newsTitle = t; newsBody = b; answer = ""
                    } else answer = text
                    status = if (ok) "✅ উত্তর পাওয়া গেছে (${text.length} অক্ষর)" else "⚠️ সময় শেষ — যতটুকু এসেছে ততটুকু দেখানো হলো"
                }
            },
            onFail = { failReason = it },
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
        tried.clear(); tried.add(siteKey); runKey = siteKey; runPrompt = p
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
        val isUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(u)
        if (rw == null && !isUrl) { status = "⚠️ সঠিক লিংক দিন (https://...)"; return }
        if (rw != null && !isUrl && u.length < 100 && newsNotes.trim().length < 60) { status = "⚠️ লিংক দিন, অথবা পুরো খবর/বিস্তারিত নোট লিখুন"; return }
        newsMode = true; newsTitle = ""; newsBody = ""; answer = ""; running = true
        status = if (isUrl) "📰 লিংক থেকে খবর পড়ছি..." else "📰 খবর সাজাচ্ছি..."
        scope.launch {
            val art = if (isUrl) withContext(Dispatchers.IO) { fetchArticle(u) }
                      else if (u.length >= 100) Article("", u.take(7000)) else null
            if (isUrl && art == null) status = "⚠️ লিংক থেকে খবরের লেখা আনা যায়নি; AI-কে লিংক দিয়েই পড়তে বলছি (নাও পারতে পারে)"
            launchPrompt(buildNewsPrompt(art, if (isUrl) u else "", newsNotes))
        }
    }

    // ক্যাপচা / সাবমিট ব্যর্থ / লেখা আনতে না পারা → ব্যর্থ সাইটের কুকি-ক্যাশ মুছে অন্য সাইটে (একই প্রম্পট) চেষ্টা
    LaunchedEffect(failReason) {
        val r = failReason ?: return@LaunchedEffect
        failReason = null
        if (!running) return@LaunchedEffect
        val failed = SITES[runKey]
        val w = web
        if (failed != null) clearSiteData(failed, w)
        val next = SITES.keys.firstOrNull { it !in tried }
        val ns = next?.let { SITES[it] }
        if (next == null || ns == null || w == null) {
            running = false
            status = "⚠️ ${failed?.label ?: "সাইট"} ব্যর্থ ($r)। অন্য সাইটেও হয়নি। কুকি-ক্যাশ মুছে দেওয়া হয়েছে, একটু পরে আবার চেষ্টা করুন"
            closeWeb()
            if (rw?.onFallback != null && newsMode) rw.onFallback.invoke()   // দুই সাইটই ব্যর্থ → আগের মতো API দিয়ে লেখা
            return@LaunchedEffect
        }
        tried.add(next); runKey = next; siteKey = next; sent = false; runId++
        begin(w, ns, runPrompt)
        status = "⚠️ ${failed?.label} ব্যর্থ ($r) — কুকি-ক্যাশ মুছে ${ns.label}-এ চেষ্টা করছি..."
    }

    // JS মরে গেলেও (পেজ বদলে) যেন আটকে না থাকে
    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        delay((timeoutSec + 25) * 1000L)
        if (running) failReason = "সময় শেষ, উত্তর আসেনি"   // অন্য সাইটে চেষ্টা; সবগুলো ব্যর্থ হলে ওপরের LaunchedEffect শেষ করে/API-তে যায়
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (rw != null) "🆓 ফ্রি AI দিয়ে লিখুন" else "🧪 গেস্ট AI টেস্ট", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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

            if (rw == null) {
                AppField(prompt, { prompt = it }, placeholder = "প্রম্পট লিখুন...", singleLine = false)

                AppBtn(
                    if (running) "⏳ চলছে..." else "🚀 পাঠান ও উত্তর আনুন",
                    color = C.Green, enabled = !running,
                    onClick = { start() },
                )
            }
            if (running) {
                AppBtn("⏹️ থামান", color = C.Gray, onClick = {
                    running = false; web?.stopLoading(); web?.evaluateJavascript("window.__gcActive=false", null); status = "থামানো হয়েছে"; closeWeb()
                })
            }

            if (status.isNotEmpty()) Text(status, fontSize = 13.sp, color = if (status.startsWith("✅")) C.Green else if (status.startsWith("⚠️")) C.Red else C.Muted)


            FieldLabel(if (rw != null) "📰 খবরের লিংক অথবা পুরো খবর" else "📰 লিংক থেকে নিউজ রিরাইট")
            AppField(newsUrl, { newsUrl = it }, placeholder = if (rw != null) "লিংক (https://...) অথবা খবরের লেখা" else "নিউজের লিংক পেস্ট করুন (https://...)", singleLine = rw == null)
            if (rw != null && newsNotes.isNotBlank()) Small("📍 আপনার মাঠের তথ্যও সাথে যাবে।")
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
                if (rw != null) {
                    AppBtn("✅ এই লেখা পোস্টে বসান", color = C.Green, enabled = !running && newsTitle.isNotBlank() && newsBody.isNotBlank(), onClick = {
                        rw.onUse(newsTitle, newsBody); onClose()
                    })
                    Small("বসানোর পর ২ নম্বর ধাপে শিরোনাম ও বডি নিজে এডিট করতে পারবেন। এতে ব্যালেন্স কাটে না।")
                }
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
