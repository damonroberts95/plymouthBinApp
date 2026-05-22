package com.plymouthbins.app.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object AppLog {
    private const val TAG = "PlymouthBins"
    private const val MAX = 500
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun i(msg: String) = add(Level.INFO, msg)
    fun w(msg: String) = add(Level.WARN, msg)
    fun e(msg: String, t: Throwable? = null) {
        add(Level.ERROR, if (t != null) "$msg :: ${t.javaClass.simpleName}: ${t.message}" else msg)
    }

    fun clear() { _entries.value = emptyList() }

    private fun add(level: Level, msg: String) {
        val e = LogEntry(LocalDateTime.now().format(fmt), level, msg)
        when (level) {
            Level.INFO -> Log.i(TAG, msg)
            Level.WARN -> Log.w(TAG, msg)
            Level.ERROR -> Log.e(TAG, msg)
        }
        _entries.update { (it + e).takeLast(MAX) }
    }

    private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
        value = transform(value)
    }
}

enum class Level { INFO, WARN, ERROR }
data class LogEntry(val time: String, val level: Level, val message: String)
