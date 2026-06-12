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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
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
    /**
     * Apps shown on the home screen: excluded (whitelisted) apps are hidden
     * here entirely — they live in the Settings exclude list instead — and the
     * search query is applied.
     */
    val filtered: List<AppInfo>
        get() = apps.filter {
            !it.whitelisted && (query.isBlank() || it.label.contains(query, ignoreCase = true))
        }

    /** Excluded (never-hibernate) apps, surfaced only in Settings. */
    val excluded: List<AppInfo>
        get() = apps.filter { it.whitelisted }

    /** Actively running apps — grouped first, most-important on top. */
    val running: List<AppInfo>
        get() = filtered.filter { it.isActive }
            .sortedWith(compareBy<AppInfo> { it.runState.ordinal }.thenBy { it.label.lowercase() })

    /** Cached/background-free apps — running but harmless ("no need"). */
    val cached: List<AppInfo>
        get() = filtered.filter { it.isCached }

    /** Managed apps that are stopped. */
    val hibernated: List<AppInfo>
        get() = filtered.filter { it.managed && it.runState == RunState.STOPPED }

    /** Unmanaged, stopped apps. */
    val others: List<AppInfo>
        get() = filtered.filter { !it.managed && it.runState == RunState.STOPPED }

    val selectionCount: Int get() = selection.size
    val hasEngine: Boolean get() = engineMethod != HibernationEngine.Method.NONE
    val excludedCount: Int get() = apps.count { it.whitelisted }

    /**
     * Top-bar summary line. Counts only the apps actually shown on the home
     * screen (excluded apps are hidden), so the header never reports an "active"
     * app that has no row in the list.
     */
    val summary: String
        get() {
            val visible = apps.filter { !it.whitelisted }
            return "${visible.count { it.isActive }} active · " +
                "${visible.count { it.isCached }} cached · " +
                "${visible.count { it.runState == RunState.STOPPED }} asleep"
        }
}

/** One-shot messages for the UI (snackbars). */
sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data object OpenEngineSetup : UiEvent
}

/** Quick "select all of a kind" options offered in selection mode. */
enum class SelectFilter(val label: String) {
    ALL("All apps"),
    ACTIVE("Running now"),
    CACHED("Cached"),
    HIBERNATED("Hibernated"),
    IDLE("Idle / unmanaged"),
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

        // When the accessibility force-stop queue finishes, refresh so stopped
        // apps move to the hibernated section, and report what couldn't be
        // stopped (disabled "Force stop" buttons on protected apps).
        var wasActive = false
        ForceStopController.progress
            .onEach { p ->
                if (wasActive && !p.active) {
                    refresh()
                    val msg = buildString {
                        append("Hibernated ${p.done}")
                        if (p.skipped > 0) append(" · skipped ${p.skipped} (can't be stopped)")
                    }
                    _events.tryEmit(UiEvent.Message(msg))
                }
                wasActive = p.active
            }
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
            // Keep selections for any still-installed app (selection can span
            // all sections now, not just running).
            val installed = apps.map { it.packageName }.toSet()
            _state.value = _state.value.copy(
                loading = false,
                apps = apps,
                selection = _state.value.selection intersect installed,
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

    /** Select every app currently visible (respects the search filter). */
    fun selectAll() = selectByFilter(SelectFilter.ALL)

    /** Bulk-select every app in a category (active, cached, hibernated, …). */
    fun selectByFilter(filter: SelectFilter) {
        val s = _state.value
        val matches = when (filter) {
            SelectFilter.ALL -> s.filtered
            SelectFilter.ACTIVE -> s.running
            SelectFilter.CACHED -> s.cached
            SelectFilter.HIBERNATED -> s.hibernated
            SelectFilter.IDLE -> s.others
        }
        _state.value = s.copy(
            selectionMode = true,
            selection = matches.map { it.packageName }.toSet(),
        )
    }

    fun exitSelection() {
        _state.value = _state.value.copy(selectionMode = false, selection = emptySet())
    }

    /** Add the current selection to the exclude list (never force-stopped). */
    fun excludeSelected() {
        val pkgs = _state.value.selection
        if (pkgs.isEmpty()) return
        viewModelScope.launch {
            pkgs.forEach { zen.prefs.setWhitelisted(it, true) }
            _state.value = _state.value.copy(selectionMode = false, selection = emptySet())
            _events.tryEmit(UiEvent.Message("Added ${pkgs.size} app(s) to the exclude list."))
            refresh()
        }
    }

    /**
     * Remove the current selection from the hibernation (managed) list. The
     * apps stay installed and asleep — they just leave the Hibernated section
     * and Zenify stops managing them.
     */
    fun removeFromHibernated() {
        val pkgs = _state.value.selection
        if (pkgs.isEmpty()) return
        viewModelScope.launch {
            pkgs.forEach { zen.prefs.setManaged(it, false) }
            _state.value = _state.value.copy(selectionMode = false, selection = emptySet())
            _events.tryEmit(UiEvent.Message("Removed ${pkgs.size} app(s) from the hibernation list."))
            refresh()
        }
    }

    // ---- Manage / exclude ----
    fun toggleManaged(app: AppInfo) {
        viewModelScope.launch {
            zen.prefs.setManaged(app.packageName, !app.managed)
            refresh()
        }
    }

    /** Toggle an app's exclude (never-hibernate) status. */
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
                // Explicit selection overrides the "risky" auto-skip, but the
                // user's exclude list is always respected.
                s.apps.filter { it.packageName in s.selection && !it.whitelisted }
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

            // Apps the OS won't let us force-stop — so the accessibility engine
            // skips them honestly instead of marking them stopped.
            val protectedPackages = s.apps.filter { it.risky }.map { it.packageName }.toSet()

            when (HibernationEngine.method(zen)) {
                HibernationEngine.Method.NONE -> {
                    _events.tryEmit(UiEvent.OpenEngineSetup)
                    return@launch
                }
                HibernationEngine.Method.ACCESSIBILITY -> {
                    _events.tryEmit(
                        UiEvent.Message("Hibernating ${targets.size} app(s) via accessibility…")
                    )
                    HibernationEngine.hibernate(zen, targets, protectedPackages)
                    _state.value = _state.value.copy(selectionMode = false, selection = emptySet())
                }
                HibernationEngine.Method.SHIZUKU -> {
                    _state.value = _state.value.copy(hibernatingNow = true)
                    val result = withContext(Dispatchers.Default) {
                        HibernationEngine.hibernate(zen, targets)
                    }
                    _state.value = _state.value.copy(
                        hibernatingNow = false,
                        selectionMode = false,
                        selection = emptySet(),
                    )
                    val failed = result.failed.size
                    _events.tryEmit(
                        UiEvent.Message(
                            buildString {
                                append("Hibernated ${result.succeeded.size}")
                                if (failed > 0) append(" · $failed couldn't be stopped")
                            }
                        )
                    )
                    refresh()
                }
            }
        }
    }
}
