package com.plymouthbins.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.plymouthbins.app.data.AppLog
import com.plymouthbins.app.data.BinApi
import com.plymouthbins.app.data.BinBootstrap
import com.plymouthbins.app.data.BootstrapCreds
import com.plymouthbins.app.data.BootstrapResult
import com.plymouthbins.app.data.Prefs
import com.plymouthbins.app.data.ScheduleCache
import com.plymouthbins.app.work.NotificationScheduler
import com.plymouthbins.app.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Phase { BOOTSTRAPPING, READY, SEARCHING, LIST, CAPTURING, REFRESHING, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressCaptureScreen(
    showBack: Boolean,
    onCaptured: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(Phase.BOOTSTRAPPING) }
    var postcode by remember { mutableStateOf("") }
    var addresses by remember { mutableStateOf<List<BinApi.AddressOption>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var creds by remember { mutableStateOf<BootstrapCreds?>(null) }

    fun runBootstrap() {
        error = null
        phase = Phase.BOOTSTRAPPING
        scope.launch {
            val res = withContext(Dispatchers.Main) {
                BinBootstrap.bootstrapMinimal(ctx)
            }
            when (res) {
                is BootstrapResult.Success -> {
                    val c = res.creds
                    creds = c
                    Prefs.setSavedCreds(ctx, c.sid, c.csrf, c.cookieHeader)
                    phase = Phase.READY
                }
                is BootstrapResult.Unreachable -> {
                    error = "Plymouth Council site is unreachable (HTTP ${res.httpCode} ${res.reason}). " +
                            "This is a problem on their end — please try again later."
                    phase = Phase.ERROR
                }
                is BootstrapResult.NetworkError -> {
                    error = "Network error: ${res.description}. Check your connection and try again."
                    phase = Phase.ERROR
                }
                BootstrapResult.Timeout -> {
                    error = "Session start timed out. Council site may be slow — try again."
                    phase = Phase.ERROR
                }
            }
        }
    }

    LaunchedEffect(Unit) { runBootstrap() }

    fun startSearch() {
        val cleaned = postcode.trim().uppercase().replace(" ", "")
        if (cleaned.isBlank()) return
        val c = creds ?: return
        addresses = emptyList()
        error = null
        phase = Phase.SEARCHING
        AppLog.i("Capture: searching postcode $cleaned")
        scope.launch {
            runCatching { BinApi.searchAddresses(c, cleaned) }
                .onSuccess { list ->
                    addresses = list
                    if (list.isEmpty()) {
                        error = "No addresses found for $cleaned"
                        phase = Phase.ERROR
                    } else phase = Phase.LIST
                }
                .onFailure {
                    AppLog.e("Capture: search failed", it)
                    error = it.message ?: "Search failed"
                    phase = Phase.ERROR
                }
        }
    }

    fun pickAddress(opt: BinApi.AddressOption) {
        val c = creds ?: return
        phase = Phase.CAPTURING
        AppLog.i("Capture: picked ${opt.label} UPRN=${opt.uprn}")
        scope.launch {
            runCatching {
                val key = BinApi.fetchCollectiveKey(c, opt.uprn)
                if (key.isBlank()) error("Council returned no collectiveKey for that address.")
                Prefs.setUprn(ctx, opt.uprn)
                Prefs.setCollectiveKey(ctx, key)
                Prefs.setNeedsRecapture(ctx, false)
                Prefs.setConsecutiveEmpty(ctx, 0)
                Prefs.setCachedRelatedUprn(ctx, "")
                Prefs.setPostcode(ctx, postcode.trim().uppercase().replace(" ", ""))
                Prefs.setAddressLabel(ctx, opt.label)
                ScheduleCache.write(ctx, emptyList())
                NotificationScheduler.reschedule(ctx, emptyList(), Prefs.current(ctx))
                phase = Phase.REFRESHING
                val rows = RefreshWorker.fetchSchedule(ctx)
                ScheduleCache.write(ctx, rows)
                NotificationScheduler.reschedule(ctx, rows, Prefs.current(ctx))
                AppLog.i("Capture: ${rows.size} rows fetched post-capture")
            }.onSuccess { onCaptured() }
                .onFailure {
                    AppLog.e("Capture: finalize failed", it)
                    error = it.message ?: "Capture failed"
                    phase = Phase.ERROR
                }
        }
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
                enabled = phase == Phase.READY || phase == Phase.LIST || phase == Phase.ERROR,
                onSubmit = ::startSearch,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = ::startSearch,
                enabled = postcode.trim().length >= 5 && creds != null &&
                        (phase == Phase.READY || phase == Phase.LIST || phase == Phase.ERROR),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Find addresses") }

            Spacer(modifier = Modifier.height(16.dp))
            when (phase) {
                Phase.BOOTSTRAPPING -> Loading("Starting session…")
                Phase.SEARCHING -> Loading("Searching addresses…")
                Phase.CAPTURING -> Loading("Fetching bin key…")
                Phase.REFRESHING -> {
                    Loading("Loading collections…")
                    Spacer(modifier = Modifier.height(8.dp))
                    LogTail()
                }
                Phase.LIST -> AddressList(addresses, onPick = ::pickAddress)
                Phase.ERROR -> error?.let {
                    ErrorBox(
                        msg = it,
                        retryLabel = if (creds == null) "Retry session" else "Try again",
                        onRetry = { if (creds == null) runBootstrap() else startSearch() },
                    )
                }
                Phase.READY -> Text(
                    "Enter postcode then pick the matching address.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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
private fun Loading(text: String) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AddressList(addresses: List<BinApi.AddressOption>, onPick: (BinApi.AddressOption) -> Unit) {
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
private fun ErrorBox(msg: String, retryLabel: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                msg,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(retryLabel) }
        }
    }
}
