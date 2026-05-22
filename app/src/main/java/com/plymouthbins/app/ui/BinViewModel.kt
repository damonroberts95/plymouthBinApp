package com.plymouthbins.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plymouthbins.app.data.AppLog
import com.plymouthbins.app.data.BinCollection
import com.plymouthbins.app.data.Prefs
import com.plymouthbins.app.data.ScheduleCache
import com.plymouthbins.app.data.visibleToday
import com.plymouthbins.app.work.NotificationScheduler
import com.plymouthbins.app.work.RefreshWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BinUiState(
    val loading: Boolean = false,
    val collections: List<BinCollection> = emptyList(),
    val error: String? = null,
    val progressMessage: String? = null,
)

class BinViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(BinUiState())
    val state: StateFlow<BinUiState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(collections = ScheduleCache.read(getApplication()).visibleToday())
        viewModelScope.launch {
            com.plymouthbins.app.data.Progress.state.collect { msg ->
                _state.value = _state.value.copy(progressMessage = msg)
            }
        }
    }

    fun reloadFromCache() {
        _state.value = _state.value.copy(
            collections = ScheduleCache.read(getApplication()).visibleToday(),
        )
    }

    fun refresh(forceBootstrap: Boolean = false) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        AppLog.i("Manual refresh requested (forceBootstrap=$forceBootstrap)")
        viewModelScope.launch {
            runCatching {
                val prefs = Prefs.current(getApplication())
                if (prefs.uprn.isBlank() || prefs.collectiveKey.isBlank()) {
                    error("Address not set. Open Settings → Change address.")
                }
                val rows = RefreshWorker.fetchSchedule(getApplication(), forceBootstrap = forceBootstrap)
                ScheduleCache.write(getApplication(), rows)
                NotificationScheduler.reschedule(getApplication(), rows, prefs)
                rows
            }.onSuccess { rows ->
                com.plymouthbins.app.data.Progress.clear()
                _state.value = _state.value.copy(loading = false, collections = rows.visibleToday(), error = null, progressMessage = null)
            }.onFailure { t ->
                AppLog.e("Refresh failed", t)
                com.plymouthbins.app.data.Progress.clear()
                _state.value = _state.value.copy(loading = false, error = t.message ?: "error", progressMessage = null)
            }
        }
    }

    fun rescheduleOnPrefChange() {
        viewModelScope.launch {
            val prefs = Prefs.current(getApplication())
            NotificationScheduler.reschedule(getApplication(), _state.value.collections, prefs)
        }
    }
}
