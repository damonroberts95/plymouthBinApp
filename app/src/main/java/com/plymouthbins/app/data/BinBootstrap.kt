package com.plymouthbins.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Hidden WebView loads AchieveForms; JS hook intercepts /apibroker/runLookup
 * XHR/fetch to extract sid + csrf. If a postcode is supplied, drives form to
 * guarantee a runLookup fires even when auto-init lookups don't.
 */
object BinBootstrap {

    private val bootstrapMutex = Mutex()

    /**
     * Minimal bootstrap: load the form page, sniff sid/csrf/cookies from the first auto-fired
     * runLookup, return. No postcode driving, no address pick. All subsequent operations
     * (address search, key lookup, premise resolve, schedule fetch) go through BinApi POSTs.
     * v1.7+ flow replaces the JS-driven capture with this.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun bootstrapMinimal(ctx: Context, timeoutMs: Long = 30_000): BootstrapCreds? =
        bootstrapMutex.withLock { bootstrapMinimalInternal(ctx, timeoutMs) }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun bootstrapMinimalInternal(ctx: Context, timeoutMs: Long): BootstrapCreds? {
        val main = Handler(Looper.getMainLooper())
        Progress.set("Starting session…")
        val result = withTimeoutOrNull(timeoutMs) {
            AppLog.i("Bootstrap(min): page-load-only capture")
            suspendCancellableCoroutine<BootstrapCreds?> { cont ->
                main.post {
                    val wv = WebView(ctx.applicationContext)
                    val settings = wv.settings
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = Constants.USER_AGENT
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(wv, true)
                    cm.removeAllCookies(null)
                    cm.flush()
                    try { wv.clearCache(true) } catch (_: Throwable) {}
                    try { wv.clearHistory() } catch (_: Throwable) {}

                    var sid: String? = null
                    var csrf: String? = null
                    var resumed = false
                    fun finish(value: BootstrapCreds?) {
                        if (resumed) return
                        resumed = true
                        try { wv.stopLoading() } catch (_: Throwable) {}
                        try { wv.destroy() } catch (_: Throwable) {}
                        if (cont.isActive) cont.resume(value)
                    }

                    val bridge = object {
                        @JavascriptInterface
                        fun recordRequest(url: String, body: String) {
                            if (!url.contains("/apibroker/runLookup")) return
                            if (sid == null) Regex("[?&]sid=([^&]+)").find(url)?.let { sid = it.groupValues[1] }
                            if (csrf == null) {
                                Regex("\"csrf_token\"\\s*:\\s*\"([a-f0-9]+)\"").find(body)?.let {
                                    csrf = it.groupValues[1]
                                }
                            }
                            if (sid != null && csrf != null) {
                                main.post {
                                    val cookies = CookieManager.getInstance().getCookie(Constants.BASE) ?: ""
                                    AppLog.i("Bootstrap(min): captured sid+csrf, ${cookies.count { it == '=' }} cookies")
                                    finish(BootstrapCreds(sid!!, csrf!!, cookies))
                                }
                            }
                        }
                    }
                    wv.addJavascriptInterface(bridge, "AndroidBridge")
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(HOOK_JS, null)
                        }
                    }
                    cont.invokeOnCancellation { main.post { finish(null) } }
                    wv.loadUrl(Constants.FORM_URL)
                }
            }
        }
        if (result == null) AppLog.w("Bootstrap(min): timed out without capturing sid/csrf")
        return result
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(
        ctx: Context,
        timeoutMs: Long = 60_000,
        triggerPostcode: String? = null,
    ): BootstrapCreds? = bootstrapMutex.withLock { captureInternal(ctx, timeoutMs, triggerPostcode) }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureInternal(
        ctx: Context,
        timeoutMs: Long,
        triggerPostcode: String?,
    ): BootstrapCreds? {
        val main = Handler(Looper.getMainLooper())
        Progress.set("Starting session…")
        val result = withTimeoutOrNull(timeoutMs) {
            AppLog.i("Bootstrap: timeout ${timeoutMs}ms trigger=${triggerPostcode != null}")
            suspendCancellableCoroutine<BootstrapCreds?> { cont ->
                AppLog.i("Bootstrap: launching hidden WebView")
                main.post {
                    val wv = WebView(ctx.applicationContext)
                    val settings = wv.settings
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = Constants.USER_AGENT
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(wv, true)
                    cm.removeAllCookies(null)
                    cm.flush()
                    try { wv.clearCache(true) } catch (_: Throwable) {}
                    try { wv.clearHistory() } catch (_: Throwable) {}
                    AppLog.i("Bootstrap: cleared cookies + cache")

                    var sid: String? = null
                    var csrf: String? = null
                    var resumed = false

                    fun finish(value: BootstrapCreds?) {
                        if (resumed) return
                        resumed = true
                        try { wv.stopLoading() } catch (_: Throwable) {}
                        try { wv.destroy() } catch (_: Throwable) {}
                        if (cont.isActive) cont.resume(value)
                    }

                    val bridge = object {
                        @JavascriptInterface
                        fun recordRequest(url: String, body: String) {
                            if (!url.contains("/apibroker/runLookup")) return
                            if (sid == null) {
                                Regex("[?&]sid=([^&]+)").find(url)?.let { sid = it.groupValues[1] }
                            }
                            if (csrf == null) {
                                Regex("\"csrf_token\"\\s*:\\s*\"([a-f0-9]+)\"").find(body)?.let {
                                    csrf = it.groupValues[1]
                                }
                            }
                            if (sid != null && csrf != null) {
                                main.post {
                                    val cookies = CookieManager.getInstance().getCookie(Constants.BASE) ?: ""
                                    AppLog.i("Bootstrap: captured sid + csrf, ${cookies.count { it == '=' }} cookies")
                                    finish(BootstrapCreds(sid!!, csrf!!, cookies))
                                }
                            }
                        }
                    }
                    wv.addJavascriptInterface(bridge, "AndroidBridge")

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(HOOK_JS, null)
                            if (triggerPostcode != null) {
                                val safe = JSONObject.quote(triggerPostcode)
                                main.postDelayed({
                                    view.evaluateJavascript(
                                        "window.__binDriveSearch && window.__binDriveSearch($safe);",
                                        null,
                                    )
                                }, 1500)
                            }
                        }
                    }

                    cont.invokeOnCancellation { main.post { finish(null) } }
                    wv.loadUrl(Constants.FORM_URL)
                }
            }
        }
        if (result == null) AppLog.w("Bootstrap: timed out without capturing sid/csrf")
        return result
    }

    /**
     * Full-flow capture: loads form, drives postcode search, picks address by UPRN, waits for
     * schedule lookups to fire so the server-side session advances past address-select. This
     * mirrors what AddressCaptureScreen does manually, replayed on every refresh.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun captureFull(
        ctx: Context,
        postcode: String,
        uprn: String,
        timeoutMs: Long = 90_000,
    ): BootstrapCreds? = bootstrapMutex.withLock { captureFullInternal(ctx, postcode, uprn, timeoutMs) }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureFullInternal(
        ctx: Context,
        postcode: String,
        uprn: String,
        timeoutMs: Long,
    ): BootstrapCreds? {
        val main = Handler(Looper.getMainLooper())
        Progress.set("Replaying form flow…")
        val result = withTimeoutOrNull(timeoutMs) {
            AppLog.i("Bootstrap(full): postcode=$postcode uprn=$uprn timeout=${timeoutMs}ms")
            suspendCancellableCoroutine<BootstrapCreds?> { cont ->
                main.post {
                    val wv = WebView(ctx.applicationContext)
                    val settings = wv.settings
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = Constants.USER_AGENT
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(wv, true)
                    cm.removeAllCookies(null)
                    cm.flush()
                    try { wv.clearCache(true) } catch (_: Throwable) {}
                    try { wv.clearHistory() } catch (_: Throwable) {}
                    AppLog.i("Bootstrap(full): cleared cookies + cache")

                    var sid: String? = null
                    var csrf: String? = null
                    val seenIds = java.util.LinkedHashSet<String>()
                    var resumed = false
                    var finalizeScheduled = false

                    fun finish(value: BootstrapCreds?) {
                        if (resumed) return
                        resumed = true
                        try { wv.stopLoading() } catch (_: Throwable) {}
                        try { wv.destroy() } catch (_: Throwable) {}
                        if (cont.isActive) cont.resume(value)
                    }

                    fun scheduleFinalize(delayMs: Long) {
                        if (finalizeScheduled) return
                        finalizeScheduled = true
                        main.postDelayed({
                            val s = sid; val c = csrf
                            if (s != null && c != null) {
                                val cookies = CookieManager.getInstance().getCookie(Constants.BASE) ?: ""
                                AppLog.i("Bootstrap(full): done sid+csrf, ${seenIds.size} lookup IDs, ${cookies.count { it == '=' }} cookies")
                                finish(BootstrapCreds(s, c, cookies))
                            } else {
                                AppLog.w("Bootstrap(full): post-pick wait expired, sid=${s != null} csrf=${c != null}")
                                finish(null)
                            }
                        }, delayMs)
                    }

                    val bridge = object {
                        @JavascriptInterface
                        fun recordRequest(url: String, body: String) {
                            if (!url.contains("/apibroker/runLookup")) return
                            if (sid == null) Regex("[?&]sid=([^&]+)").find(url)?.let { sid = it.groupValues[1] }
                            if (csrf == null) {
                                Regex("\"csrf_token\"\\s*:\\s*\"([a-f0-9]+)\"").find(body)?.let {
                                    csrf = it.groupValues[1]
                                }
                            }
                            Regex("[?&]id=([a-f0-9]+)").find(url)?.let { seenIds += it.groupValues[1] }
                        }

                        @JavascriptInterface
                        fun pickedAddress() {
                            AppLog.i("Bootstrap(full): address picked, waiting 7s for schedule lookups")
                            scheduleFinalize(7000)
                        }

                        @JavascriptInterface
                        fun reportError(msg: String) {
                            AppLog.w("Bootstrap(full) JS: $msg")
                            // Don't give up immediately — give a short window for any creds already in flight.
                            scheduleFinalize(2000)
                        }
                    }
                    wv.addJavascriptInterface(bridge, "AndroidBridge")

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(HOOK_JS, null)
                            val pcArg = JSONObject.quote(postcode)
                            val uprnArg = JSONObject.quote(uprn)
                            main.postDelayed({
                                view.evaluateJavascript(
                                    "window.__binFullCapture && window.__binFullCapture($pcArg, $uprnArg);",
                                    null,
                                )
                            }, 1500)
                        }
                    }

                    cont.invokeOnCancellation { main.post { finish(null) } }
                    wv.loadUrl(Constants.FORM_URL)
                }
            }
        }
        if (result == null) AppLog.w("Bootstrap(full): timed out without capturing sid/csrf")
        return result
    }

    private val HOOK_JS = """
        (function(){
          function install(win){
            try {
              if (!win || win.__binHooked) return;
              win.__binHooked = true;
              var XHR = win.XMLHttpRequest && win.XMLHttpRequest.prototype;
              if (XHR && !XHR.__binWrapped) {
                XHR.__binWrapped = true;
                var origOpen = XHR.open;
                XHR.open = function(method, url){ this.__binUrl = url; return origOpen.apply(this, arguments); };
                var origSend = XHR.send;
                XHR.send = function(body){
                  try {
                    if (this.__binUrl && this.__binUrl.indexOf('/apibroker/runLookup') >= 0) {
                      AndroidBridge.recordRequest(this.__binUrl, body == null ? '' : ''+body);
                    }
                  } catch(e){}
                  return origSend.apply(this, arguments);
                };
              }
              if (win.fetch && !win.fetch.__binWrapped) {
                var origFetch = win.fetch;
                var wrap = function(url, opts){
                  try {
                    var u = typeof url === 'string' ? url : (url && url.url) || '';
                    if (u.indexOf('/apibroker/runLookup') >= 0) {
                      var b = opts && opts.body ? opts.body : '';
                      if (typeof b !== 'string') { try { b = JSON.stringify(b); } catch(e){ b = ''; } }
                      AndroidBridge.recordRequest(u, b);
                    }
                  } catch(e){}
                  return origFetch.apply(this, arguments);
                };
                wrap.__binWrapped = true;
                win.fetch = wrap;
              }
            } catch(e){}
          }
          install(window);
          setInterval(function(){
            try {
              var ifs = document.querySelectorAll('iframe');
              for (var i=0;i<ifs.length;i++){ try { install(ifs[i].contentWindow); } catch(e){} }
            } catch(e){}
          }, 400);

          function iframeDoc(){
            try { var f = document.querySelector('iframe#fillform-frame-1'); return f && f.contentDocument; } catch(e){ return null; }
          }

          window.__binDriveSearch = function(pc){
            var tries = 0;
            function attempt(){
              tries++;
              var doc = iframeDoc();
              var inp = doc && doc.querySelector('input[name="postcode_search"]');
              if (!inp) { if (tries > 80) return; else { setTimeout(attempt, 250); return; } }
              try {
                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                setter.call(inp, pc);
              } catch(e){ inp.value = pc; }
              inp.dispatchEvent(new Event('input', { bubbles: true }));
              inp.dispatchEvent(new Event('change', { bubbles: true }));
              var btn = Array.from(doc.querySelectorAll('button')).find(function(b){ return /search|find/i.test(b.textContent||''); });
              if (btn) btn.click();
              else inp.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', keyCode:13, bubbles:true}));
            }
            attempt();
          };

          window.__binFullCapture = function(pc, uprn){
            var tries = 0;
            function fillSearch(){
              tries++;
              var doc = iframeDoc();
              var inp = doc && doc.querySelector('input[name="postcode_search"]');
              if (!inp) {
                if (tries > 80) { try { AndroidBridge.reportError('postcode field never loaded'); } catch(e){} return; }
                setTimeout(fillSearch, 250); return;
              }
              try {
                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                setter.call(inp, pc);
              } catch(e){ inp.value = pc; }
              inp.dispatchEvent(new Event('input', { bubbles: true }));
              inp.dispatchEvent(new Event('change', { bubbles: true }));
              var btn = Array.from(doc.querySelectorAll('button')).find(function(b){ return /search|find/i.test(b.textContent||''); });
              if (btn) btn.click();
              else inp.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', keyCode:13, bubbles:true}));
              tries = 0;
              setTimeout(pollOptions, 400);
            }
            function pollOptions(){
              tries++;
              var doc = iframeDoc();
              var sel = doc && doc.querySelector('select[name="chooseAddress"]');
              if (sel) {
                var opts = Array.from(sel.options).filter(function(o){ return o.value; });
                var match = opts.find(function(o){ return o.value === uprn; });
                if (opts.length) { doPick(sel, match ? uprn : opts[0].value); return; }
              }
              if (tries > 80) { try { AndroidBridge.reportError('address options never loaded'); } catch(e){} return; }
              setTimeout(pollOptions, 400);
            }
            function doPick(sel, value){
              try {
                var setter = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, 'value').set;
                setter.call(sel, value);
              } catch(e){ sel.value = value; }
              sel.dispatchEvent(new Event('change', { bubbles: true }));
              sel.dispatchEvent(new Event('input', { bubbles: true }));
              try { AndroidBridge.pickedAddress(); } catch(e){}
            }
            fillSearch();
          };
        })();
    """.trimIndent()
}
