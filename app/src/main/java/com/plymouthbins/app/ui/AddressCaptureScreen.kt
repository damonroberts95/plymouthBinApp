package com.plymouthbins.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.plymouthbins.app.data.AppLog
import com.plymouthbins.app.data.Constants
import com.plymouthbins.app.data.LocationHelper
import com.plymouthbins.app.data.NotifyPrefs
import com.plymouthbins.app.data.Prefs
import com.plymouthbins.app.data.ScheduleCache
import com.plymouthbins.app.work.NotificationScheduler
import com.plymouthbins.app.work.RefreshWorker
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class Phase { IDLE, SEARCHING, LIST, CAPTURING, REFRESHING, ERROR }

private data class AddressOption(val uprn: String, val label: String)

private class CapturedCreds(
    var sid: String? = null,
    var csrf: String? = null,
    var uprn: String? = null,
    var key: String? = null,
    val lookupIds: java.util.LinkedHashSet<String> = java.util.LinkedHashSet(),
)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AddressCaptureScreen(
    showBack: Boolean,
    onCaptured: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(Phase.IDLE) }
    var postcode by remember { mutableStateOf("") }
    var triedGps by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            LocationHelper.lastKnownPostcode(ctx) { pc ->
                if (!pc.isNullOrBlank() && postcode.isBlank()) {
                    postcode = pc.uppercase()
                    AppLog.i("Capture: prefilled postcode from GPS = $postcode")
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (triedGps || postcode.isNotBlank()) return@LaunchedEffect
        triedGps = true
        if (LocationHelper.hasLocationPermission(ctx)) {
            LocationHelper.lastKnownPostcode(ctx) { pc ->
                if (!pc.isNullOrBlank() && postcode.isBlank()) {
                    postcode = pc.uppercase()
                    AppLog.i("Capture: prefilled postcode from GPS = $postcode")
                }
            }
        } else {
            locationLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    var addresses by remember { mutableStateOf<List<AddressOption>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val captured = remember { CapturedCreds() }

    fun runJs(js: String) {
        webView?.post { webView?.evaluateJavascript(js, null) }
    }

    fun startSearch() {
        val cleaned = postcode.trim().uppercase().replace(" ", "")
        if (cleaned.isBlank()) return
        addresses = emptyList()
        error = null
        phase = Phase.SEARCHING
        AppLog.i("Capture: searching postcode $cleaned")
        runJs("window.__doFillAndSearch && window.__doFillAndSearch(${JSONObject.quote(cleaned)});")
    }

    var pickedLabel by remember { mutableStateOf("") }

    fun pickAddress(opt: AddressOption) {
        phase = Phase.CAPTURING
        pickedLabel = opt.label
        AppLog.i("Capture: picked ${opt.label} UPRN=${opt.uprn}")
        runJs("window.__doPickAddress && window.__doPickAddress(${JSONObject.quote(opt.uprn)});")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick your address") },
                navigationIcon = if (showBack) {
                    {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                } else {
                    {}
                },
            )
        }
    ) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            PostcodeField(
                value = postcode,
                onValue = { postcode = it.uppercase() },
                enabled = phase == Phase.IDLE || phase == Phase.LIST || phase == Phase.ERROR,
                onSubmit = ::startSearch,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = ::startSearch,
                enabled = postcode.trim().length >= 5 &&
                        (phase == Phase.IDLE || phase == Phase.LIST || phase == Phase.ERROR),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Find addresses") }

            Spacer(modifier = Modifier.height(16.dp))
            when (phase) {
                Phase.SEARCHING -> LoadingRow("Searching addresses…")
                Phase.CAPTURING -> {
                    LoadingRow("Fetching bin schedule IDs…")
                    Spacer(modifier = Modifier.height(8.dp))
                    LogTail()
                }
                Phase.REFRESHING -> {
                    LoadingRow("Loading collections…")
                    Spacer(modifier = Modifier.height(8.dp))
                    LogTail()
                }
                Phase.LIST -> AddressList(addresses, onPick = ::pickAddress)
                Phase.ERROR -> error?.let { ErrorBox(it) }
                Phase.IDLE -> Text(
                    "Enter postcode then pick the matching address.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Hidden WebView driving the AchieveForms form via injected JS.
        Box(modifier = Modifier.size(1.dp).alpha(0f)) {
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        val s: WebSettings = settings
                        s.javaScriptEnabled = true
                        s.domStorageEnabled = true
                        s.userAgentString = Constants.USER_AGENT
                        s.cacheMode = WebSettings.LOAD_NO_CACHE
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun recordRequest(url: String, body: String) {
                                if (!url.contains("/apibroker/runLookup")) return
                                Regex("[?&]id=([a-f0-9]+)").find(url)?.let {
                                    captured.lookupIds.add(it.groupValues[1])
                                }
                                if (captured.sid == null) {
                                    Regex("[?&]sid=([^&]+)").find(url)?.let {
                                        captured.sid = it.groupValues[1]
                                    }
                                }
                                if (captured.csrf == null) {
                                    Regex("\"csrf_token\"\\s*:\\s*\"([a-f0-9]+)\"").find(body)?.let {
                                        captured.csrf = it.groupValues[1]
                                    }
                                }
                                runCatching {
                                    val obj = JSONObject(body)
                                    val fv = obj.optJSONObject("formValues") ?: return@runCatching
                                    walkNamed(fv, "collectiveKey")?.takeIf { it.length > 20 }
                                        ?.let { if (captured.key == null) captured.key = it }
                                    walkNamed(fv, "collectiveUPRN")?.takeIf { it.all(Char::isDigit) }
                                        ?.let { if (captured.uprn == null) captured.uprn = it }
                                }
                                if (captured.uprn != null && captured.key != null
                                    && captured.sid != null && captured.csrf != null
                                    && phase == Phase.CAPTURING
                                ) {
                                    val ids = captured.lookupIds.toList()
                                    AppLog.i("Capture: UPRN=${captured.uprn} key=${captured.key!!.take(12)}…, ${ids.size} IDs")
                                    finalizeCapture(
                                        ctx, scope, captured.uprn!!, captured.key!!, ids,
                                        postcode = postcode.trim().uppercase(),
                                        addressLabel = pickedLabel,
                                        onPhase = { phase = it },
                                        onError = { error = it; phase = Phase.ERROR },
                                        onDone = onCaptured,
                                    )
                                }
                            }

                            @JavascriptInterface
                            fun recordAddresses(json: String) {
                                runCatching {
                                    val arr = JSONArray(json)
                                    val list = buildList {
                                        for (i in 0 until arr.length()) {
                                            val o = arr.optJSONObject(i) ?: continue
                                            add(
                                                AddressOption(
                                                    uprn = o.optString("value"),
                                                    label = o.optString("label"),
                                                )
                                            )
                                        }
                                    }
                                    post {
                                        addresses = list
                                        phase = if (list.isEmpty()) Phase.ERROR else Phase.LIST
                                        if (list.isEmpty()) error = "No addresses found for $postcode"
                                        AppLog.i("Capture: received ${list.size} addresses")
                                    }
                                }
                            }

                            @JavascriptInterface
                            fun reportError(msg: String) {
                                post {
                                    AppLog.w("Capture JS error: $msg")
                                    error = msg
                                    phase = Phase.ERROR
                                }
                            }
                        }, "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                view.evaluateJavascript(HOOK_JS, null)
                            }
                        }
                        loadUrl(Constants.FORM_URL)
                        webView = this
                    }
                },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}

private fun finalizeCapture(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    uprn: String,
    key: String,
    ids: List<String>,
    postcode: String,
    addressLabel: String,
    onPhase: (Phase) -> Unit,
    onError: (String) -> Unit,
    onDone: () -> Unit,
) {
    scope.launch {
        try {
            Prefs.setUprn(ctx, uprn)
            Prefs.setCollectiveKey(ctx, key)
            Prefs.setLookupIds(ctx, ids)
            Prefs.setPremiseLookupId(ctx, "")
            Prefs.setNeedsRecapture(ctx, false)
            Prefs.setPostcode(ctx, postcode)
            Prefs.setAddressLabel(ctx, addressLabel)
            ScheduleCache.write(ctx, emptyList())
            NotificationScheduler.reschedule(ctx, emptyList(), Prefs.current(ctx))
            AppLog.i("Capture: saved, triggering immediate refresh")
            onPhase(Phase.REFRESHING)
            runCatching { RefreshWorker.fetchSchedule(ctx) }
                .onSuccess { rows ->
                    ScheduleCache.write(ctx, rows)
                    NotificationScheduler.reschedule(ctx, rows, Prefs.current(ctx))
                    AppLog.i("Post-capture refresh complete: ${rows.size} collections")
                }
                .onFailure { AppLog.w("Post-capture refresh failed: ${it.message}") }
            onDone()
        } catch (t: Throwable) {
            onError(t.message ?: "Save failed")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostcodeField(
    value: String,
    onValue: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text("Postcode") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = { onSubmit() },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LoadingRow(text: String) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun AddressList(addresses: List<AddressOption>, onPick: (AddressOption) -> Unit) {
    Text(
        "${addresses.size} addresses",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(addresses) { opt ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(opt) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(opt.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("UPRN ${opt.uprn}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ErrorBox(msg: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            msg,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private fun walkNamed(node: Any?, name: String): String? {
    when (node) {
        is JSONObject -> {
            if (node.optString("name") == name) {
                val v = node.opt("value")
                if (v is String && v.isNotBlank()) return v
            }
            node.keys().forEach { k ->
                walkNamed(node.opt(k), name)?.let { return it }
            }
        }
        is JSONArray -> {
            for (i in 0 until node.length()) {
                walkNamed(node.opt(i), name)?.let { return it }
            }
        }
    }
    return null
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

  window.__doFillAndSearch = function(pc){
    var tries = 0;
    function attempt(){
      tries++;
      var doc = iframeDoc();
      var inp = doc && doc.querySelector('input[name="postcode_search"]');
      if (!inp) { if (tries > 80) AndroidBridge.reportError('postcode field not loaded'); else setTimeout(attempt, 250); return; }
      try {
        var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(inp, pc);
      } catch(e){ inp.value = pc; }
      inp.dispatchEvent(new Event('input', { bubbles: true }));
      inp.dispatchEvent(new Event('change', { bubbles: true }));
      var btn = Array.from(doc.querySelectorAll('button')).find(function(b){ return /search|find/i.test(b.textContent||''); });
      if (btn) btn.click();
      else inp.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', keyCode:13, bubbles:true}));
      var polls = 0;
      var iv = setInterval(function(){
        polls++;
        var d = iframeDoc();
        var sel = d && d.querySelector('select[name="chooseAddress"]');
        if (sel) {
          var opts = Array.from(sel.options).filter(function(o){ return o.value; })
            .map(function(o){ return { value: o.value, label: (o.textContent||'').trim() }; });
          if (opts.length) {
            clearInterval(iv);
            AndroidBridge.recordAddresses(JSON.stringify(opts));
            return;
          }
        }
        if (polls > 80) {
          clearInterval(iv);
          AndroidBridge.recordAddresses('[]');
        }
      }, 400);
    }
    attempt();
  };

  window.__doPickAddress = function(uprn){
    var tries = 0;
    function attempt(){
      tries++;
      var doc = iframeDoc();
      var sel = doc && doc.querySelector('select[name="chooseAddress"]');
      if (!sel) { if (tries > 40) AndroidBridge.reportError('address select missing'); else setTimeout(attempt, 200); return; }
      try {
        var setter = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, 'value').set;
        setter.call(sel, uprn);
      } catch(e){ sel.value = uprn; }
      sel.dispatchEvent(new Event('change', { bubbles: true }));
      sel.dispatchEvent(new Event('input', { bubbles: true }));
    }
    attempt();
  };
})();
""".trimIndent()
