package com.pn.zenify.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pn.zenify.ZenifyApp
import com.pn.zenify.core.Hibernator
import com.pn.zenify.data.AppInfo
import com.pn.zenify.service.HibernationService
import com.pn.zenify.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val loading: Boolean = true,
    val apps: List<AppInfo> = emptyList(),
    val query: String = "",
    val shizukuState: ShizukuManager.State = ShizukuManager.State.UNAVAILABLE,
    val usageAccessGranted: Boolean = false,
    val autoHibernate: Boolean = false,
    val delayMinutes: Int = 5,
    val showSystem: Boolean = false,
    val hibernateOnScreenOff: Boolean = true,
    val hibernatingNow: Boolean = false,
) {
    private val filtered: List<AppInfo>
        get() = if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, ignoreCase = true) }

    /** Managed + active → the "not hibernating automatically / active" bucket. */
    val activeManaged: List<AppInfo>
        get() = filtered.filter { it.managed && it.isActive }

    /** Managed + stopped → "hibernated". */
    val hibernated: List<AppInfo>
        get() = filtered.filter { it.managed && !it.isActive }

    /** Everything not yet managed — candidates to add. */
    val candidates: List<AppInfo>
        get() = filtered.filter { !it.managed }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val zen = app as ZenifyApp
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Mirror Shizuku state into the UI.
        ShizukuManager.state
            .onEach { s -> _state.value = _state.value.copy(shizukuState = s) }
            .launchIn(viewModelScope)

        // Mirror prefs into the UI.
        combine(
            zen.prefs.autoHibernate,
            zen.prefs.delayMinutes,
            zen.prefs.showSystem,
            zen.prefs.hibernateOnScreenOff,
        ) { auto, delay, showSystem, screenOff ->
            _state.value = _state.value.copy(
                autoHibernate = auto,
                delayMinutes = delay,
                showSystem = showSystem,
                hibernateOnScreenOff = screenOff,
            )
        }.launchIn(viewModelScope)

        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                usageAccessGranted = zen.repository.hasUsageAccess(),
            )
            ShizukuManager.refreshState()
            val managed = zen.prefs.managed.first()
            val whitelist = zen.prefs.whitelist.first()
            val showSystem = zen.prefs.showSystem.first()
            val apps = zen.repository.loadApps(managed, whitelist, showSystem)
            _state.value = _state.value.copy(loading = false, apps = apps)
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun toggleManaged(app: AppInfo) {
        viewModelScope.launch {
            zen.prefs.setManaged(app.packageName, !app.managed)
            refresh()
        }
    }

    fun toggleWhitelist(app: AppInfo) {
        viewModelScope.launch {
            zen.prefs.setWhitelisted(app.packageName, !app.whitelisted)
            refresh()
        }
    }

    fun requestShizuku() = ShizukuManager.requestPermission()

    fun setAutoHibernate(on: Boolean) {
        viewModelScope.launch {
            zen.prefs.setAutoHibernate(on)
            if (on) HibernationService.start(zen)
        }
    }

    fun setDelayMinutes(minutes: Int) {
        viewModelScope.launch { zen.prefs.setDelayMinutes(minutes) }
    }

    fun setShowSystem(on: Boolean) {
        viewModelScope.launch {
            zen.prefs.setShowSystem(on)
            refresh()
        }
    }

    fun setHibernateOnScreenOff(on: Boolean) {
        viewModelScope.launch { zen.prefs.setHibernateOnScreenOff(on) }
    }

    /** The big Zzz button: hibernate every managed, non-whitelisted active app now. */
    fun hibernateNow() {
        viewModelScope.launch {
            if (!ShizukuManager.isReady()) {
                ShizukuManager.requestPermission()
                return@launch
            }
            _state.value = _state.value.copy(hibernatingNow = true)
            val targets = _state.value.apps.filter {
                it.managed && !it.whitelisted && it.isActive
            }.map { it.packageName }
            withContext(Dispatchers.Default) {
                Hibernator.hibernate(targets)
            }
            _state.value = _state.value.copy(hibernatingNow = false)
            refresh()
        }
    }
}
