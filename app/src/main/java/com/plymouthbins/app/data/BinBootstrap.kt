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
import kotlin.coroutines.resume

/**
 * One-time WebView bootstrap: loads the AchieveForms page so the council JS auto-fires
 * a runLookup; intercepts the XHR via injected JS to harvest sid + csrf + cookies.
 * All subsequent operations (address search, key fetch, schedule fetch) use those creds
 * via plain OkHttp POSTs in BinApi — no further WebView/DOM driving needed.
 */
object BinBootstrap {

    private val bootstrapMutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun bootstrapMinimal(ctx: Context, timeoutMs: Long = 60_000): BootstrapResult =
        bootstrapMutex.withLock { bootstrapMinimalInternal(ctx, timeoutMs) }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun bootstrapMinimalInternal(ctx: Context, timeoutMs: Long): BootstrapResult {
        val main = Handler(Looper.getMainLooper())
        Progress.set("Starting session…")
        val result: BootstrapResult? = withTimeoutOrNull(timeoutMs) {
            AppLog.i("Bootstrap(min): page-load-only capture")

            // Fix A: synchronously await cookie wipe before any page load. Async
            // removeAllCookies(null) used to race against loadUrl, letting stale
            // session cookies prevent the council SPA from firing its runLookup probe.
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            suspendCancellableCoroutine<Unit> { c ->
                main.post {
                    cm.removeAllCookies { ok ->
                        AppLog.i("Bootstrap(min): cookies wiped ($ok)")
                        cm.flush()
                        if (c.isActive) c.resume(Unit)
                    }
                }
            }

            suspendCancellableCoroutine<BootstrapResult> { cont ->
                main.post {
                    val wv = WebView(ctx.applicationContext)
                    val settings = wv.settings
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = Constants.USER_AGENT
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE

                    cm.setAcceptThirdPartyCookies(wv, true)
                    try { wv.clearCache(true) } catch (_: Throwable) {}
                    try { wv.clearHistory() } catch (_: Throwable) {}

                    var sid: String? = null
                    var csrf: String? = null
                    var resumed = false
                    fun finish(value: BootstrapResult) {
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
                            AppLog.i("Bootstrap(min): runLookup seen (have sid=${sid != null} csrf=${csrf != null})")
                            if (sid == null) Regex("[?&]sid=([^&]+)").find(url)?.let { sid = it.groupValues[1] }
                            if (csrf == null) {
                                Regex("\"csrf_token\"\\s*:\\s*\"([a-f0-9]+)\"").find(body)?.let {
                                    csrf = it.groupValues[1]
                                }
                            }
                            val s = sid
                            val c = csrf
                            if (s != null && c != null) {
                                // Fix C: snapshot locals on the JS bridge thread before
                                // crossing to main. Avoids JMM visibility hole.
                                main.post {
                                    val cookies = CookieManager.getInstance().getCookie(Constants.BASE) ?: ""
                                    AppLog.i("Bootstrap(min): captured sid+csrf, ${cookies.count { it == '=' }} cookies")
                                    finish(BootstrapResult.Success(BootstrapCreds(s, c, cookies)))
                                }
                            }
                        }
                    }
                    wv.addJavascriptInterface(bridge, "AndroidBridge")
                    wv.webViewClient = object : WebViewClient() {
                        // Fix B: inject on BOTH started and finished. HOOK_JS is
                        // idempotent (guarded by __binHooked) so double-install is safe.
                        // Closes the race where SPA fires runLookup before onPageFinished.
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            AppLog.i("Bootstrap(min): onPageStarted $url")
                            view.evaluateJavascript(HOOK_JS, null)
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            AppLog.i("Bootstrap(min): onPageFinished $url")
                            view.evaluateJavascript(HOOK_JS, null)
                        }

                        // Surface HTTP errors on the main frame (504, 502, 5xx, 4xx) as
                        // BootstrapResult.Unreachable so the UI can distinguish "council
                        // site down" from "session capture failed".
                        override fun onReceivedHttpError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            errorResponse: android.webkit.WebResourceResponse,
                        ) {
                            if (!request.isForMainFrame) return
                            val code = errorResponse.statusCode
                            val reason = errorResponse.reasonPhrase ?: "HTTP $code"
                            AppLog.w("Bootstrap(min): main-frame HTTP $code ($reason) ${request.url}")
                            finish(BootstrapResult.Unreachable(code, reason))
                        }

                        // Transport-level errors: DNS lookup, connection refused, TLS,
                        // no network. Distinguished from 5xx for clearer messaging.
                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            if (!request.isForMainFrame) return
                            val desc = error.description?.toString() ?: "network error"
                            AppLog.w("Bootstrap(min): main-frame network error ${error.errorCode} ($desc) ${request.url}")
                            finish(BootstrapResult.NetworkError(error.errorCode, desc))
                        }
                    }
                    cont.invokeOnCancellation { main.post { finish(BootstrapResult.Timeout) } }
                    wv.loadUrl(Constants.FORM_URL)
                }
            }
        }
        if (result == null) {
            AppLog.w("Bootstrap(min): timed out without capturing sid/csrf")
            return BootstrapResult.Timeout
        }
        return result
    }

    // Injected into the form page + every iframe. Wraps XHR/fetch to intercept
    // /apibroker/runLookup calls so we can read sid + csrf from the auto-fired probe.
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
        })();
    """.trimIndent()
}
