package com.plymouthbins.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Progress {
    private val _state = MutableStateFlow<String?>(null)
    val state: StateFlow<String?> = _state.asStateFlow()

    fun set(msg: String?) {
        _state.value = msg
        if (msg != null) AppLog.i("Progress: $msg")
    }

    fun clear() {
        _state.value = null
    }
}
