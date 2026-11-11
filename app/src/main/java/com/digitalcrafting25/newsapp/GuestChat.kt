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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
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
        input = listOf("#prompt-textarea", "div[contenteditable=\"true\"]#prompt-textarea", "textarea[name=\"prompt-textarea\"]"),
        answer = listOf("[data-message-author-role=\"assistant\"]"),
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
  function pressEnter(el){
    ['keydown','keypress','keyup'].forEach(function(t){
      el.dispatchEvent(new KeyboardEvent(t, {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
    });
  }
  var t0 = Date.now(), phase = 0, baseline = 0, lastTxt = '', lastChange = 0, sendAt = 0, sentAt = 0, retried = false, box = null;
  var iv = setInterval(function(){
    try {
      if (Date.now() - t0 > timeoutSec * 1000){
        clearInterval(iv); window.__gcActive = false; B.done(lastTxt, false); return;
      }
      if (phase === 0){
        box = firstVis(cfg.input);
        if (!box){ B.status('লেখার বক্স খুঁজছি... (যাচাই/লগইন পপআপ এলে নিচের ব্রাউজারে নিজে করুন)'); return; }
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
        if (count(cfg.answer) <= baseline){
          if (!retried && Date.now() - sentAt > 6000){ retried = true; pressEnter(box); B.status('আবার Enter চাপা হয়েছে...'); }
          return;
        }
        var tx = lastText(cfg.answer);
        if (tx !== lastTxt){ lastTxt = tx; lastChange = Date.now(); B.status('উত্তর আসছে... (' + tx.length + ' অক্ষর)'); }
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

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
    var showWeb by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }

    val timeoutSec = 180
    val currentRunning by androidx.compose.runtime.rememberUpdatedState(running)
    val currentSent by androidx.compose.runtime.rememberUpdatedState(sent)

    val bridge = remember {
        GuestBridge(
            onStatus = { status = it },
            onSent = { sent = true },
            onDone = { text, ok ->
                running = false
                answer = text
                status = when {
                    ok -> "✅ উত্তর পাওয়া গেছে (${text.length} অক্ষর)"
                    text.isNotBlank() -> "⚠️ সময় শেষ — যতটুকু এসেছে ততটুকু দেখানো হলো"
                    else -> "⚠️ উত্তর পাওয়া যায়নি। গেস্ট সীমা শেষ, যাচাই বাকি, বা সাইটের ডিজাইন বদলেছে — নিচের ব্রাউজার দেখুন"
                }
            },
        )
    }

    val web = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(bridge, "GCBridge")
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { web.stopLoading(); web.destroy() } } }

    fun start() {
        val s = SITES[siteKey] ?: return
        val p = prompt.trim()
        if (p.isEmpty()) { status = "⚠️ প্রম্পট লিখুন"; return }
        answer = ""; copied = false; sent = false; running = true; runId++
        status = "খুলছি: ${s.url}"
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (currentRunning && !currentSent) {
                    view.evaluateJavascript(buildScript(s, p, timeoutSec), null)
                }
            }
        }
        web.loadUrl(s.url)
    }

    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        delay((timeoutSec + 25) * 1000L)
        if (running) { running = false; status = "⚠️ সময় শেষ, উত্তর আসেনি" }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("🧪 গেস্ট AI টেস্ট", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { if (running) web.stopLoading(); onClose() }) { Text("✖ বন্ধ") }
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
                    running = false; web.stopLoading(); web.evaluateJavascript("window.__gcActive=false", null); status = "থামানো হয়েছে"
                })
            }

            if (status.isNotEmpty()) Text(status, fontSize = 13.sp, color = if (status.startsWith("✅")) C.Green else if (status.startsWith("⚠️")) C.Red else C.Muted)

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
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("🌐 ব্রাউজার (যাচাই/ক্যাপচা এলে এখানে করুন)", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { showWeb = !showWeb }) { Text(if (showWeb) "লুকান" else "দেখান") }
        }
        AndroidView(
            factory = { web },
            modifier = if (showWeb) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(1.dp),
        )
    }
}
