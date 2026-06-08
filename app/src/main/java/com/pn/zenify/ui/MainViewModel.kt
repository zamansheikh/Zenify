package com.pn.zenify.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pn.zenify.ZenifyApp
import com.pn.zenify.accessibility.ForceStopController
import com.pn.zenify.core.AccessibilityUtil
import com.pn.zenify.core.HibernationEngine
import com.pn.zenify.data.AppInfo
import com.pn.zenify.data.RunState
import com.pn.zenify.service.HibernationService
import com.pn.zenify.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val loading: Boolean = true,
    val apps: List<AppInfo> = emptyList(),
    val query: String = "",
    val selectionMode: Boolean = false,
    val selection: Set<String> = emptySet(),
    val shizukuState: ShizukuManager.State = ShizukuManager.State.UNAVAILABLE,
    val accessibilityEnabled: Boolean = false,
    val engineMethod: HibernationEngine.Method = HibernationEngine.Method.NONE,
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

    /** Running/awake apps — grouped first, foreground on top. */
    val running: List<AppInfo>
        get() = filtered.filter { it.isActive }
            .sortedWith(compareByDescending<AppInfo> { it.runState == RunState.FOREGROUND }
                .thenBy { it.label.lowercase() })

    /** Managed apps that are currently stopped. */
    val hibernated: List<AppInfo>
        get() = filtered.filter { it.managed && !it.isActive }

    /** Everything else (idle, unmanaged or managed-but-asleep non-running). */
    val others: List<AppInfo>
        get() = filtered.filter { !it.isActive && !it.managed }

    val selectionCount: Int get() = selection.size
    val hasEngine: Boolean get() = engineMethod != HibernationEngine.Method.NONE
}

/** One-shot messages for the UI (snackbars). */
sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data object OpenEngineSetup : UiEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val zen = app as ZenifyApp
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        ShizukuManager.state
            .onEach { s ->
                _state.value = _state.value.copy(
                    shizukuState = s,
                    engineMethod = HibernationEngine.method(zen),
                )
            }
            .launchIn(viewModelScope)

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

        // When the accessibility force-stop queue finishes, the apps it stopped
        // need to move to the hibernated section — refresh automatically.
        ForceStopController.progress
            .map { it.active }
            .distinctUntilChanged()
            .onEach { active -> if (!active) refresh() }
            .launchIn(viewModelScope)

        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                usageAccessGranted = zen.repository.hasUsageAccess(),
                accessibilityEnabled = AccessibilityUtil.isEnabled(zen),
                engineMethod = HibernationEngine.method(zen),
            )
            ShizukuManager.refreshState()
            val managed = zen.prefs.managed.first()
            val whitelist = zen.prefs.whitelist.first()
            val showSystem = zen.prefs.showSystem.first()
            val apps = zen.repository.loadApps(managed, whitelist, showSystem)
            // Drop selections for apps that are no longer running.
            val stillRunning = apps.filter { it.isActive }.map { it.packageName }.toSet()
            _state.value = _state.value.copy(
                loading = false,
                apps = apps,
                selection = _state.value.selection intersect stillRunning,
            )
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    // ---- Selection (entered via long-press) ----
    fun enterSelection(app: AppInfo) {
        _state.value = _state.value.copy(
            selectionMode = true,
            selection = setOf(app.packageName),
        )
    }

    fun toggleSelect(app: AppInfo) {
        if (!_state.value.selectionMode) return
        val current = _state.value.selection.toMutableSet()
        if (!current.add(app.packageName)) current.remove(app.packageName)
        _state.value = _state.value.copy(selection = current)
    }

    fun selectAllRunning() {
        _state.value = _state.value.copy(
            selectionMode = true,
            selection = _state.value.running.map { it.packageName }.toSet(),
        )
    }

    fun exitSelection() {
        _state.value = _state.value.copy(selectionMode = false, selection = emptySet())
    }

    // ---- Manage / whitelist ----
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

    /**
     * Hibernate the current selection, or — if nothing is selected — every
     * running app that is safe to stop. Explicit selection overrides the safety
     * skip (so the user CAN force a keyboard/launcher if they really select it),
     * while the default "Hibernate all" leaves risky apps running.
     */
    fun hibernateSelectedOrAll() {
        viewModelScope.launch {
            val s = _state.value
            val explicit = s.selectionMode && s.selection.isNotEmpty()
            val targets = if (explicit) {
                s.apps.filter { it.packageName in s.selection }
            } else {
                s.running.filter { !it.whitelisted && !it.risky }
            }.map { it.packageName }

            if (targets.isEmpty()) {
                _events.tryEmit(
                    UiEvent.Message(
                        if (!explicit && s.running.any { it.risky })
                            "Only risky apps are running (e.g. keyboard). Long-press to select them manually."
                        else "Nothing running to hibernate."
                    )
                )
                return@launch
            }

            when (HibernationEngine.method(zen)) {
                HibernationEngine.Method.NONE -> {
                    _events.tryEmit(UiEvent.OpenEngineSetup)
                    return@launch
                }
                HibernationEngine.Method.ACCESSIBILITY -> {
                    _events.tryEmit(
                        UiEvent.Message("Hibernating ${targets.size} app(s) via accessibility…")
                    )
                    HibernationEngine.hibernate(zen, targets)
                    _state.value = _state.value.copy(selectionMode = false, selection = emptySet())
                }
                HibernationEngine.Method.SHIZUKU -> {
                    _state.value = _state.value.copy(hibernatingNow = true)
                    withContext(Dispatchers.Default) {
                        HibernationEngine.hibernate(zen, targets)
                    }
                    _state.value = _state.value.copy(
                        hibernatingNow = false,
                        selectionMode = false,
                        selection = emptySet(),
                    )
                    _events.tryEmit(UiEvent.Message("Hibernated ${targets.size} app(s)."))
                    refresh()
                }
            }
        }
    }
}
