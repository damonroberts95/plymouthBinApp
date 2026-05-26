package com.plymouthbins.app.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class BinCollection(
    val date: LocalDate,
    val wasteType: String,
    val status: String,
    val round: String = "",
) {
    val dateString: String get() = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val isCompleted: Boolean get() {
        val s = status.lowercase()
        return "complete" in s || "collected" in s || "done" in s
    }
    val isInProgress: Boolean get() {
        val s = status.lowercase()
        return "progress" in s || "out for" in s
    }
    // Dedup key across lookups: same physical job has different per-lookup collectiveIDs,
    // so we identify by date+waste+round instead.
    fun id(): String = "$dateString|$wasteType|$round"
}

fun List<BinCollection>.visibleToday(): List<BinCollection> {
    val today = LocalDate.now()
    return filter { !it.isCompleted && !it.date.isBefore(today) }
}

data class BootstrapCreds(
    var sid: String,
    var csrf: String,
    var cookieHeader: String,
)

sealed class BootstrapResult {
    data class Success(val creds: BootstrapCreds) : BootstrapResult()
    /** Main-frame HTTP error from council site (e.g. 504 Gateway Timeout). */
    data class Unreachable(val httpCode: Int, val reason: String) : BootstrapResult()
    /** Transport-level failure: DNS, connection refused, TLS, no network. */
    data class NetworkError(val code: Int, val description: String) : BootstrapResult()
    /** Page loaded but runLookup probe never captured within timeout. */
    object Timeout : BootstrapResult()
}
