package com.plymouthbins.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "bin_prefs")

data class NotifyPrefs(
    val dayBefore: Boolean,
    val sameDay: Boolean,
    val hour: Int,
    val minute: Int,
    val uprn: String,
    val collectiveKey: String,
    val daysAhead: Int,
    val needsRecapture: Boolean,
    val postcode: String,
    val addressLabel: String,
    val savedSid: String = "",
    val savedCsrf: String = "",
    val savedCookieHeader: String = "",
    val savedCredsAtMs: Long = 0L,
    val consecutiveEmpty: Int = 0,
    val lastRefreshAtMs: Long = 0L,
    val disabledCategories: Set<String> = emptySet(),
    // Cached relatedUPRN from premise probe. "" = unknown (probe needed),
    // == own UPRN = private dwelling (skip probe), != own UPRN = communal.
    val cachedRelatedUprn: String = "",
    val dismissedUpdateTag: String = "",
    // "YYYY-MM-DD|WasteType" keys user marked as "put out" via notification action.
    val markedOut: Set<String> = emptySet(),
)

object Prefs {
    private val KEY_DAY_BEFORE = booleanPreferencesKey("day_before")
    private val KEY_SAME_DAY = booleanPreferencesKey("same_day")
    private val KEY_HOUR = intPreferencesKey("hour")
    private val KEY_MINUTE = intPreferencesKey("minute")
    private val KEY_UPRN = stringPreferencesKey("uprn")
    private val KEY_COLLECTIVE_KEY = stringPreferencesKey("collective_key")
    private val KEY_DAYS_AHEAD = intPreferencesKey("days_ahead")
    private val KEY_NEEDS_RECAPTURE = booleanPreferencesKey("needs_recapture")
    private val KEY_CACHED_RELATED_UPRN = stringPreferencesKey("cached_related_uprn")
    private val KEY_DISMISSED_UPDATE_TAG = stringPreferencesKey("dismissed_update_tag")
    private val KEY_MARKED_OUT = stringPreferencesKey("marked_out_keys")
    private val KEY_POSTCODE = stringPreferencesKey("postcode")
    private val KEY_ADDRESS_LABEL = stringPreferencesKey("address_label")
    private val KEY_SAVED_SID = stringPreferencesKey("saved_sid")
    private val KEY_SAVED_CSRF = stringPreferencesKey("saved_csrf")
    private val KEY_SAVED_COOKIES = stringPreferencesKey("saved_cookies")
    private val KEY_SAVED_CREDS_AT = longPreferencesKey("saved_creds_at_ms")
    private val KEY_CONSECUTIVE_EMPTY = intPreferencesKey("consecutive_empty")
    private val KEY_LAST_REFRESH_AT = longPreferencesKey("last_refresh_at_ms")
    private val KEY_DISABLED_CATEGORIES = stringPreferencesKey("disabled_categories")

    fun flow(ctx: Context): Flow<NotifyPrefs> = ctx.dataStore.data.map { p ->
        NotifyPrefs(
            dayBefore = p[KEY_DAY_BEFORE] ?: true,
            sameDay = p[KEY_SAME_DAY] ?: false,
            hour = p[KEY_HOUR] ?: 19,
            minute = p[KEY_MINUTE] ?: 0,
            uprn = p[KEY_UPRN] ?: "",
            collectiveKey = p[KEY_COLLECTIVE_KEY] ?: "",
            daysAhead = p[KEY_DAYS_AHEAD] ?: 14,
            needsRecapture = p[KEY_NEEDS_RECAPTURE] ?: false,
            cachedRelatedUprn = p[KEY_CACHED_RELATED_UPRN] ?: "",
            dismissedUpdateTag = p[KEY_DISMISSED_UPDATE_TAG] ?: "",
            postcode = p[KEY_POSTCODE] ?: "",
            addressLabel = p[KEY_ADDRESS_LABEL] ?: "",
            savedSid = p[KEY_SAVED_SID] ?: "",
            savedCsrf = p[KEY_SAVED_CSRF] ?: "",
            savedCookieHeader = p[KEY_SAVED_COOKIES] ?: "",
            savedCredsAtMs = p[KEY_SAVED_CREDS_AT] ?: 0L,
            consecutiveEmpty = p[KEY_CONSECUTIVE_EMPTY] ?: 0,
            lastRefreshAtMs = p[KEY_LAST_REFRESH_AT] ?: 0L,
            disabledCategories = (p[KEY_DISABLED_CATEGORIES] ?: "")
                .split(',').filter { it.isNotBlank() }.toSet(),
            markedOut = (p[KEY_MARKED_OUT] ?: "").split('\n').filter { it.isNotBlank() }.toSet(),
        )
    }

    suspend fun current(ctx: Context): NotifyPrefs = flow(ctx).first()

    suspend fun setDayBefore(ctx: Context, v: Boolean) = ctx.dataStore.edit { it[KEY_DAY_BEFORE] = v }
    suspend fun setSameDay(ctx: Context, v: Boolean) = ctx.dataStore.edit { it[KEY_SAME_DAY] = v }
    suspend fun setTime(ctx: Context, hour: Int, minute: Int) = ctx.dataStore.edit {
        it[KEY_HOUR] = hour; it[KEY_MINUTE] = minute
    }
    suspend fun setUprn(ctx: Context, v: String) = ctx.dataStore.edit { it[KEY_UPRN] = v }
    suspend fun setCollectiveKey(ctx: Context, v: String) = ctx.dataStore.edit { it[KEY_COLLECTIVE_KEY] = v }
    suspend fun setDaysAhead(ctx: Context, v: Int) = ctx.dataStore.edit { it[KEY_DAYS_AHEAD] = v.coerceIn(1, 30) }
    suspend fun setNeedsRecapture(ctx: Context, v: Boolean) =
        ctx.dataStore.edit { it[KEY_NEEDS_RECAPTURE] = v }
    suspend fun setCachedRelatedUprn(ctx: Context, v: String) =
        ctx.dataStore.edit { it[KEY_CACHED_RELATED_UPRN] = v }
    suspend fun setDismissedUpdateTag(ctx: Context, v: String) =
        ctx.dataStore.edit { it[KEY_DISMISSED_UPDATE_TAG] = v }
    suspend fun setPostcode(ctx: Context, v: String) =
        ctx.dataStore.edit { it[KEY_POSTCODE] = v }
    suspend fun setAddressLabel(ctx: Context, v: String) =
        ctx.dataStore.edit { it[KEY_ADDRESS_LABEL] = v }
    suspend fun setSavedCreds(ctx: Context, sid: String, csrf: String, cookies: String) =
        ctx.dataStore.edit {
            it[KEY_SAVED_SID] = sid
            it[KEY_SAVED_CSRF] = csrf
            it[KEY_SAVED_COOKIES] = cookies
            it[KEY_SAVED_CREDS_AT] = System.currentTimeMillis()
        }
    suspend fun clearSavedCreds(ctx: Context) = ctx.dataStore.edit {
        it.remove(KEY_SAVED_SID); it.remove(KEY_SAVED_CSRF)
        it.remove(KEY_SAVED_COOKIES); it.remove(KEY_SAVED_CREDS_AT)
    }
    suspend fun setConsecutiveEmpty(ctx: Context, v: Int) =
        ctx.dataStore.edit { it[KEY_CONSECUTIVE_EMPTY] = v }
    suspend fun setLastRefreshAt(ctx: Context, ms: Long) =
        ctx.dataStore.edit { it[KEY_LAST_REFRESH_AT] = ms }
    suspend fun setDisabledCategories(ctx: Context, cats: Set<String>) =
        ctx.dataStore.edit { it[KEY_DISABLED_CATEGORIES] = cats.joinToString(",") }
    suspend fun setMarkedOut(ctx: Context, keys: Set<String>) =
        ctx.dataStore.edit { it[KEY_MARKED_OUT] = keys.joinToString("\n") }
    suspend fun addMarkedOut(ctx: Context, key: String) = ctx.dataStore.edit {
        val cur = (it[KEY_MARKED_OUT] ?: "").split('\n').filter { s -> s.isNotBlank() }.toMutableSet()
        cur += key
        it[KEY_MARKED_OUT] = cur.joinToString("\n")
    }
}
