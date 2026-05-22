package com.plymouthbins.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AuthExpired(msg: String) : RuntimeException(msg)

object BinApi {

    // Single shared connection pool / dispatcher across all sessions. Derived per-fetch
    // clients only differ by cookieJar, so HTTP/2 connections are reused.
    private val sharedPool = ConnectionPool(maxIdleConnections = 5, keepAliveDuration = 5, TimeUnit.MINUTES)
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .connectionPool(sharedPool)
        .build()

    private class JarCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, Cookie>()
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.values.filter { it.matches(url) }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) store["${c.domain}|${c.name}"] = c
        }
        fun seed(host: String, header: String) {
            for (pair in header.split(";")) {
                val kv = pair.trim().split("=", limit = 2)
                if (kv.size != 2 || kv[0].isBlank()) continue
                val c = Cookie.Builder()
                    .domain(host)
                    .path("/")
                    .name(kv[0].trim())
                    .value(kv[1].trim())
                    .build()
                store["${c.domain}|${c.name}"] = c
            }
        }
    }

    private fun clientForJar(jar: JarCookieJar): OkHttpClient =
        baseClient.newBuilder().cookieJar(jar).build()

    data class FetchResult(
        val rows: List<BinCollection>,
        val premiseLookupId: String,
    )

    data class AddressOption(val uprn: String, val label: String)

    /** POST 560d with a postcode; returns the address list. WebView-free. */
    suspend fun searchAddresses(creds: BootstrapCreds, postcode: String): List<AddressOption> =
        withContext(Dispatchers.IO) {
            val jar = JarCookieJar().apply { seed(Constants.BASE.toHttpUrl().host, creds.cookieHeader) }
            val fv = JSONObject().apply {
                put("postcode_search", field("postcode_search", postcode))
                put("chooseAddress", field("chooseAddress", "", "select"))
            }
            val data = runLookup(jar, creds, Constants.LOOKUP_ADDRESS_SEARCH, fv) ?: return@withContext emptyList()
            val rows = (data.optJSONObject("integration")?.optJSONObject("transformed")?.opt("rows_data")) ?: return@withContext emptyList()
            val out = mutableListOf<AddressOption>()
            val push: (JSONObject?) -> Unit = { r ->
                if (r != null) {
                    val uprn = r.optString("uprn").ifBlank { r.optString("name") }
                    val label = r.optString("display").ifBlank { r.optString("name") }
                    if (uprn.isNotBlank() && label.isNotBlank())
                        out += AddressOption(uprn, label)
                }
            }
            when (rows) {
                is JSONArray -> for (i in 0 until rows.length()) push(rows.optJSONObject(i))
                is JSONObject -> rows.keys().forEach { push(rows.optJSONObject(it)) }
            }
            out
        }

    /** POST 6936 with a UPRN; returns the matching collectiveKey. WebView-free. */
    suspend fun fetchCollectiveKey(creds: BootstrapCreds, uprn: String): String =
        withContext(Dispatchers.IO) {
            val jar = JarCookieJar().apply { seed(Constants.BASE.toHttpUrl().host, creds.cookieHeader) }
            val fv = JSONObject().apply {
                put("collectiveUPRN", field("collectiveUPRN", uprn))
                put("collectiveUPRNGarden", field("collectiveUPRNGarden", uprn))
                put("UPRN", field("UPRN", uprn))
                put("collectivePremiseDetailGetUPRN", field("collectivePremiseDetailGetUPRN", uprn))
            }
            val data = runLookup(jar, creds, Constants.LOOKUP_COLLECTIVE_KEY, fv) ?: return@withContext ""
            val rows = data.optJSONObject("integration")?.optJSONObject("transformed")?.opt("rows_data")
                ?: return@withContext ""
            val first: JSONObject? = when (rows) {
                is JSONArray -> rows.optJSONObject(0)
                is JSONObject -> rows.keys().asSequence().firstOrNull()?.let { rows.optJSONObject(it) }
                else -> null
            }
            first?.optString("collectiveKey")?.trim().orEmpty()
        }

    private fun runLookup(jar: JarCookieJar, creds: BootstrapCreds, lookupId: String, formValues: JSONObject): JSONObject? {
        val url = (Constants.BASE + "/apibroker/runLookup").toHttpUrl().newBuilder()
            .addQueryParameter("id", lookupId)
            .addQueryParameter("repeat_against", "")
            .addQueryParameter("noRetry", "false")
            .addQueryParameter("getOnlyTokens", "undefined")
            .addQueryParameter("log_id", "")
            .addQueryParameter("app_name", "AF-Renderer::Self")
            .addQueryParameter("_", System.currentTimeMillis().toString())
            .addQueryParameter("sid", creds.sid)
            .build()
        val body = JSONObject().apply {
            put("stopOnFailure", true)
            put("usePHPIntegrations", true)
            put("stage_id", Constants.STAGE_ID)
            put("stage_name", Constants.STAGE_NAME)
            put("formId", Constants.FORM_ID)
            put("processId", Constants.PROCESS_ID)
            put("formValues", JSONObject().put("Section 1", formValues))
            put("tokens", JSONObject().put("csrf_token", creds.csrf))
        }.toString()
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-requested-with", "XMLHttpRequest")
            .header("content-type", "application/json")
            .header("referer", Constants.FILLFORM_URL)
            .header("accept", "*/*")
            .header("User-Agent", Constants.USER_AGENT)
            .build()
        clientForJar(jar).newCall(req).execute().use { resp ->
            if (resp.code in setOf(401, 403, 419)) throw AuthExpired("HTTP ${resp.code}")
            if (!resp.isSuccessful) return null
            val raw = resp.body?.string() ?: return null
            val json = JSONObject(raw)
            harvestCsrf(json, creds)
            if (json.optString("status") != "done") return null
            return json
        }
    }

    suspend fun fetchSchedule(
        creds: BootstrapCreds,
        uprn: String,
        collectiveKey: String,
        lookupIds: List<String>,
        knownPremiseId: String,
        daysAhead: Int = 60,
        daysBack: Int = 1,
        rebootstrap: (suspend () -> BootstrapCreds)? = null,
    ): FetchResult {
        val ids = lookupIds.ifEmpty {
            AppLog.w("API: no captured lookup IDs, using built-in fallback")
            listOf(Constants.LOOKUP_PREMISE) + Constants.SCHEDULE_LOOKUPS
        }.filter { it in Constants.SCHEDULE_FETCH_ALLOWLIST }
        val jar = JarCookieJar()
        val host = Constants.BASE.toHttpUrl().host
        jar.seed(host, creds.cookieHeader)

        var premiseId = knownPremiseId.takeIf { it in ids } ?: ""
        var relatedUprn = uprn
        var rebootstrapped = false

        suspend fun maybeRebootstrap(t: Throwable): Boolean {
            if (rebootstrapped || rebootstrap == null) return false
            if (t !is AuthExpired) return false
            AppLog.w("API: auth expired (${t.message}), re-bootstrapping mid-loop")
            val fresh = rebootstrap()
            creds.sid = fresh.sid
            creds.csrf = fresh.csrf
            creds.cookieHeader = fresh.cookieHeader
            jar.seed(host, fresh.cookieHeader)
            rebootstrapped = true
            return true
        }

        Progress.set("Checking premise…")
        if (premiseId.isNotEmpty()) {
            try {
                val rel = fetchRelatedUprn(jar, creds, uprn, collectiveKey, premiseId)
                if (rel.isNotEmpty() && rel != uprn) relatedUprn = rel
                AppLog.i("API: premise=$premiseId -> related=$rel")
            } catch (t: Throwable) {
                AppLog.w("API: known premise $premiseId failed (${t.message}), re-probing")
                if (maybeRebootstrap(t)) {
                    runCatching { fetchRelatedUprn(jar, creds, uprn, collectiveKey, premiseId) }
                        .onSuccess { rel ->
                            if (rel.isNotEmpty() && rel != uprn) relatedUprn = rel
                        }
                        .onFailure { premiseId = "" }
                } else {
                    premiseId = ""
                }
            }
        }

        if (premiseId.isEmpty()) {
            for (lid in ids) {
                val attempt = runCatching { fetchRelatedUprn(jar, creds, uprn, collectiveKey, lid) }
                if (attempt.isFailure) {
                    val t = attempt.exceptionOrNull()
                    if (t != null && maybeRebootstrap(t)) {
                        runCatching { fetchRelatedUprn(jar, creds, uprn, collectiveKey, lid) }
                            .onSuccess { rel ->
                                if (rel.isNotEmpty() && rel != uprn) {
                                    premiseId = lid
                                    relatedUprn = rel
                                    AppLog.i("API: detected premise=$lid related=$rel (post-rebootstrap)")
                                }
                            }
                    }
                } else {
                    val rel = attempt.getOrThrow()
                    if (rel.isNotEmpty() && rel != uprn) {
                        premiseId = lid
                        relatedUprn = rel
                        AppLog.i("API: detected premise=$lid related=$rel")
                    }
                }
                if (premiseId.isNotEmpty()) break
            }
        }

        val scheduleIds = ids.filter { it != premiseId }
        Progress.set("Fetching ${scheduleIds.size} schedule lookups…")
        // Parallelize schedule POSTs — shared connection pool + HTTP/2 multiplex.
        val perLookup: Map<String, List<BinCollection>> = coroutineScope {
            scheduleIds.map { lid ->
                lid to async(Dispatchers.IO) {
                    runCatching {
                        fetchScheduleLookup(jar, creds, uprn, relatedUprn, collectiveKey, daysAhead, daysBack, lid)
                    }.onSuccess { rows ->
                        AppLog.i("API: $lid -> ${rows.size} rows")
                    }.onFailure { t ->
                        AppLog.w("API: $lid failed (${t.javaClass.simpleName}: ${t.message})")
                    }.getOrElse { emptyList() }
                }
            }.associate { (lid, deferred) -> lid to deferred.await() }
        }

        val isCommunal = premiseId.isNotEmpty() && relatedUprn != uprn
        val merged = mergeAndFilter(perLookup, isCommunal)
        return FetchResult(
            rows = merged.distinctBy { it.id() }.sortedBy { it.date },
            premiseLookupId = premiseId,
        )
    }

    private fun fetchScheduleLookup(
        jar: JarCookieJar,
        creds: BootstrapCreds,
        gardenUprn: String,
        relatedUprn: String,
        collectiveKey: String,
        daysAhead: Int,
        daysBack: Int,
        lookupId: String,
    ): List<BinCollection> =
        fetchOnce(jar, creds, gardenUprn, relatedUprn, collectiveKey, daysAhead, daysBack, lookupId)

    private fun fetchRelatedUprn(
        jar: JarCookieJar,
        creds: BootstrapCreds,
        uprn: String,
        collectiveKey: String,
        lookupId: String = Constants.LOOKUP_PREMISE,
    ): String {
        val now = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val start = now.format(fmt)
        val end = now.plusDays(14).format(fmt)

        val url = (Constants.BASE + "/apibroker/runLookup").toHttpUrl().newBuilder()
            .addQueryParameter("id", lookupId)
            .addQueryParameter("repeat_against", "")
            .addQueryParameter("noRetry", "true")
            .addQueryParameter("getOnlyTokens", "undefined")
            .addQueryParameter("log_id", "")
            .addQueryParameter("app_name", "AF-Renderer::Self")
            .addQueryParameter("_", System.currentTimeMillis().toString())
            .addQueryParameter("sid", creds.sid)
            .build()

        val body = JSONObject().apply {
            put("stopOnFailure", true)
            put("usePHPIntegrations", true)
            put("stage_id", Constants.STAGE_ID)
            put("stage_name", Constants.STAGE_NAME)
            put("formId", Constants.FORM_ID)
            put("processId", Constants.PROCESS_ID)
            put("formValues", JSONObject().put("Section 1", JSONObject().apply {
                put("collectiveKey", field("collectiveKey", collectiveKey, "textarea"))
                put("collectiveUPRNGarden", field("collectiveUPRNGarden", uprn))
                put("collectiveUPRN", field("collectiveUPRN", uprn))
                put("UPRN", field("UPRN", uprn))
                put("collectivePremiseDetailGetUPRN", field("collectivePremiseDetailGetUPRN", uprn))
                put("collectiveGetJobStartDate", field("collectiveGetJobStartDate", start))
                put("collectiveGetJobEndDate", field("collectiveGetJobEndDate", end))
            }))
            put("tokens", JSONObject().put("csrf_token", creds.csrf))
        }.toString()

        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-requested-with", "XMLHttpRequest")
            .header("content-type", "application/json")
            .header("referer", Constants.FILLFORM_URL)
            .header("accept", "*/*")
            .header("User-Agent", Constants.USER_AGENT)
            .build()

        clientForJar(jar).newCall(req).execute().use { resp ->
            if (resp.code in setOf(401, 403, 419)) throw AuthExpired("HTTP ${resp.code}")
            if (!resp.isSuccessful) error("premise HTTP ${resp.code}")
            val raw = resp.body?.string() ?: error("premise empty body")
            val json = JSONObject(raw)
            harvestCsrf(json, creds)
            if (json.optString("status") != "done") {
                if (looksLikeAuthFailure(raw)) throw AuthExpired("status=${json.optString("status")}")
            }
            val rows = json.optJSONObject("integration")
                ?.optJSONObject("transformed")
                ?.opt("rows_data") ?: return ""
            val firstRow: JSONObject? = when (rows) {
                is JSONArray -> rows.optJSONObject(0)
                is JSONObject -> rows.keys().asSequence().firstOrNull()?.let { rows.optJSONObject(it) }
                else -> null
            }
            val related = firstRow?.optString("collectiveRelatedUPRN", "")?.trim() ?: ""
            return related
        }
    }

    private fun fetchOnce(
        jar: JarCookieJar,
        creds: BootstrapCreds,
        gardenUprn: String,
        relatedUprn: String,
        collectiveKey: String,
        daysAhead: Int,
        daysBack: Int,
        lookupId: String,
    ): List<BinCollection> {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val now = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0)
        val start = now.minusDays(daysBack.toLong()).format(fmt)
        val end = now.plusDays(daysAhead.toLong() + 1).format(fmt)

        val url = (Constants.BASE + "/apibroker/runLookup").toHttpUrl().newBuilder()
            .addQueryParameter("id", lookupId)
            .addQueryParameter("repeat_against", "")
            .addQueryParameter("noRetry", "false")
            .addQueryParameter("getOnlyTokens", "undefined")
            .addQueryParameter("log_id", "")
            .addQueryParameter("app_name", "AF-Renderer::Self")
            .addQueryParameter("_", System.currentTimeMillis().toString())
            .addQueryParameter("sid", creds.sid)
            .build()

        val formValues = JSONObject().apply {
            put("Section 1", JSONObject().apply {
                put("collectiveKey", field("collectiveKey", collectiveKey, "textarea"))
                put("collectiveUPRNGarden", field("collectiveUPRNGarden", gardenUprn))
                put("collectiveUPRN", field("collectiveUPRN", relatedUprn))
                put("UPRN", field("UPRN", gardenUprn))
                put("collectiveRelatedUPRN", field("collectiveRelatedUPRN", relatedUprn))
                put("collectivePremiseDetailGetUPRN", field("collectivePremiseDetailGetUPRN", gardenUprn))
                put("collectiveGetJobStartDate", field("collectiveGetJobStartDate", start))
                put("collectiveGetJobEndDate", field("collectiveGetJobEndDate", end))
            })
        }

        val body = JSONObject().apply {
            put("stopOnFailure", true)
            put("usePHPIntegrations", true)
            put("stage_id", Constants.STAGE_ID)
            put("stage_name", Constants.STAGE_NAME)
            put("formId", Constants.FORM_ID)
            put("processId", Constants.PROCESS_ID)
            put("formValues", formValues)
            put("tokens", JSONObject().put("csrf_token", creds.csrf))
        }.toString()

        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-requested-with", "XMLHttpRequest")
            .header("content-type", "application/json")
            .header("referer", Constants.FILLFORM_URL)
            .header("accept", "*/*")
            .header("User-Agent", Constants.USER_AGENT)
            .build()

        AppLog.i("API: POST $lookupId garden=$gardenUprn related=$relatedUprn")
        clientForJar(jar).newCall(req).execute().use { resp ->
            if (resp.code in setOf(401, 403, 419)) {
                AppLog.e("API: HTTP ${resp.code} (auth)")
                throw AuthExpired("HTTP ${resp.code}")
            }
            if (!resp.isSuccessful) {
                AppLog.e("API: HTTP ${resp.code}")
                error("HTTP ${resp.code}")
            }
            val raw = resp.body?.string() ?: error("empty body")
            val json = JSONObject(raw)
            harvestCsrf(json, creds)
            if (json.optString("status") != "done") {
                if (looksLikeAuthFailure(raw)) {
                    AppLog.e("API: $lookupId auth-shaped failure :: ${raw.take(200)}")
                    throw AuthExpired("status=${json.optString("status")}")
                }
                AppLog.e("API: $lookupId status != done :: ${raw.take(300)}")
                error("Lookup failed: $raw")
            }
            val rows = json.optJSONObject("integration")
                ?.optJSONObject("transformed")
                ?.opt("rows_data") ?: return emptyList()
            return parseRows(rows)
        }
    }

    private fun harvestCsrf(json: JSONObject, creds: BootstrapCreds) {
        val candidates = listOf(
            json.optString("csrf_token"),
            json.optJSONObject("tokens")?.optString("csrf_token") ?: "",
            json.optJSONObject("integration")?.optJSONObject("transformed")
                ?.optJSONObject("tokens")?.optString("csrf_token") ?: "",
        )
        for (c in candidates) {
            if (c.isNotBlank() && c.matches(Regex("[a-f0-9]{16,}")) && c != creds.csrf) {
                AppLog.i("API: csrf rotated -> ${c.take(8)}…")
                creds.csrf = c
                return
            }
        }
    }

    private fun looksLikeAuthFailure(raw: String): Boolean {
        val s = raw.lowercase()
        return "csrf" in s || "session" in s && "expir" in s || "unauthor" in s || "token" in s && "invalid" in s
    }

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("d MMM yyyy"),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy"),
    )

    private fun tryParseDate(raw: String): LocalDate? {
        val s = raw.trim().substringBefore('T').let { if (it.contains(' ')) raw.trim().substringBefore(' ') else it }
        for (f in DATE_FORMATS) {
            runCatching { return LocalDate.parse(if (s.length in 8..10) s else raw.trim(), f) }
        }
        runCatching { return LocalDate.parse(raw.trim()) }
        return null
    }

    private fun parseRows(rows: Any): List<BinCollection> {
        val list = mutableListOf<BinCollection>()
        var rejectedDate = 0
        var rejectedBlank = 0
        var rejectedSentinel = 0
        var firstSample: String? = null
        val each: (JSONObject) -> Unit = { r ->
            if (firstSample == null) firstSample = r.toString().take(300)
            val ds = r.optString("collectiveCollectionDate")
            val wt = r.optString("collectiveWasteType")
            val st = r.optString("collectiveStatus")
            val wp = r.optString("collectiveWorkpackName")
            val round = Regex("Waste-([A-Z]\\d+)-").find(wp)?.groupValues?.getOrNull(1) ?: ""
            val date = tryParseDate(ds)
            when {
                wt.isBlank() -> rejectedBlank++
                wt.equals("No jobs found", ignoreCase = true) -> rejectedSentinel++
                date == null -> { rejectedDate++; AppLog.w("Row parse: bad date '$ds' waste='$wt'") }
                else -> list += BinCollection(date, wt, st, round = round)
            }
        }
        when (rows) {
            is JSONArray -> for (i in 0 until rows.length()) (rows.optJSONObject(i))?.let(each)
            is JSONObject -> rows.keys().forEach { k -> rows.optJSONObject(k)?.let(each) }
        }
        if (firstSample != null) AppLog.i("Row sample: $firstSample")
        val rej = rejectedDate + rejectedBlank + rejectedSentinel
        if (rej > 0) AppLog.w("Rows rejected: date=$rejectedDate blank=$rejectedBlank sentinel=$rejectedSentinel")
        return list.sortedBy { it.date }
    }

    /**
     * Combine rows from each schedule lookup. For communal premises (relatedUPRN ≠ gardenUPRN),
     * keep only rounds that appear in BOTH lookups — drops phantom extra rounds that one lookup
     * returns but the other doesn't (e.g. Adelaide Friday H22 ghost rows). For private premises,
     * trust both lookups and let collectiveID dedup handle overlap.
     */
    private fun mergeAndFilter(
        perLookup: Map<String, List<BinCollection>>,
        isCommunal: Boolean,
    ): List<BinCollection> {
        if (perLookup.isEmpty()) return emptyList()
        // Only lookups that returned actual rows participate in the trust intersection.
        val nonEmpty = perLookup.filter { it.value.isNotEmpty() }
        if (!isCommunal || nonEmpty.size < 2) {
            return perLookup.values.flatten()
        }
        val roundsByLookup = nonEmpty.mapValues { (_, rows) ->
            rows.mapNotNull { it.round.takeIf { r -> r.isNotBlank() } }.toSet()
        }
        val trustedRounds = roundsByLookup.values.reduce { a, b -> a.intersect(b) }
        val all = nonEmpty.values.flatten()
        if (trustedRounds.isEmpty()) {
            AppLog.w("Communal: no shared round across lookups, keeping all rows")
            return all
        }
        val kept = all.filter { it.round.isBlank() || it.round in trustedRounds }
        val dropped = all.size - kept.size
        if (dropped > 0) {
            val droppedRounds = all.filter { it.round !in trustedRounds && it.round.isNotBlank() }
                .map { it.round }.distinct()
            AppLog.i("Communal trust filter: kept rounds=$trustedRounds, dropped $dropped rows from rounds=$droppedRounds")
        }
        return kept
    }

    private fun field(name: String, value: String, ftype: String = "text"): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("type", ftype)
            put("value", value)
            put("value_changed", true)
            put("hidden", false)
            put("_hidden", true)
            put("valid", true)
            put("isMandatory", false)
            put("isRepeatable", false)
        }
}
