package com.pn.zenify.data

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import com.pn.zenify.core.HibernationTracker
import com.pn.zenify.shizuku.ProcessSnapshot
import com.pn.zenify.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discovers installed apps and decides each one's [RunState] from system-level
 * signals, best first:
 *
 *  1. The live process table read by the shell uid through Shizuku — the same
 *     data Android's own "Running services" screen uses. Ground truth.
 *  2. The package's force-stopped flag ([ApplicationInfo.FLAG_STOPPED]) — the
 *     OS's own record of a force-stop, exactly what greys out "Force stop" in
 *     Settings. Exact and available without Shizuku.
 *  3. The OS usage-event stream (activity resumed / stopped, foreground-service
 *     start / stop) — tells us what is visible and what holds a foreground
 *     service, plus what was opened recently.
 *
 * Purely-background processes that never surface (no activity, no foreground
 * service) are invisible to normal apps since Android 7; only (1) shows them.
 */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val usm: UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /**
     * Fallback only (no live process data): an app foregrounded within this
     * window is treated as still awake. Based on real activity events, not the
     * blip-prone "last used" time, so it shows apps you actually opened.
     */
    private val activeWindowMs = 30 * 60 * 1000L

    private companion object {
        // android.app.ActivityManager.RunningAppProcessInfo importance values.
        const val IMPORTANCE_FOREGROUND = 100
        const val IMPORTANCE_FOREGROUND_SERVICE = 125
        const val IMPORTANCE_SERVICE = 300
        const val IMPORTANCE_GONE = 1000

        // UsageEvents.Event types as literals so they compile on minSdk 26
        // (the newer ones simply never fire on older releases).
        const val EVENT_ACTIVITY_RESUMED = 1   // == MOVE_TO_FOREGROUND
        const val EVENT_ACTIVITY_PAUSED = 2    // == MOVE_TO_BACKGROUND
        const val EVENT_FGS_START = 19         // FOREGROUND_SERVICE_START (API 29)
        const val EVENT_FGS_STOP = 20          // FOREGROUND_SERVICE_STOP (API 29)
        const val EVENT_ACTIVITY_STOPPED = 23  // ACTIVITY_STOPPED (API 29)
        const val EVENT_DEVICE_SHUTDOWN = 26
        const val EVENT_DEVICE_STARTUP = 27

        // Activity visibility state machine.
        const val ACT_RESUMED = 2
        const val ACT_PAUSED = 1
        const val ACT_STOPPED = 0

        /** Never scan more usage events than this, even on a long uptime. */
        const val MAX_EVENT_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000
    }

    fun hasUsageAccess(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    suspend fun loadApps(
        managed: Set<String>,
        whitelist: Set<String>,
        showSystem: Boolean,
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val self = context.packageName
        val lastUsedMap = lastUsedByPackage(now)
        val riskReasons = riskyPackages()
        val installed = pm.getInstalledApplications(0)

        // Ground truth when available: the live process table (Shizuku).
        val processes = ShizukuManager.runningProcesses()
        // Otherwise: what the OS usage-event stream can tell us.
        val fallbackStates =
            if (processes.available) emptyMap() else fallbackRunningStates(now, self)

        installed
            .asSequence()
            .filter { it.packageName != self }
            .mapNotNull { ai ->
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !showSystem) return@mapNotNull null

                val pkg = ai.packageName
                val lastUsed = lastUsedMap[pkg] ?: 0L
                val flagStopped = (ai.flags and ApplicationInfo.FLAG_STOPPED) != 0
                val (state, stopped) =
                    classify(pkg, ai.uid, flagStopped, lastUsed, processes, fallbackStates)
                val reason = riskReasons[pkg]
                val isIme = reason == "Active keyboard"
                AppInfo(
                    packageName = pkg,
                    label = pm.getApplicationLabel(ai).toString(),
                    icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                    isSystem = isSystem,
                    runState = state,
                    detail = detailFor(
                        state, isIme, managed.contains(pkg), stopped, lastUsed, processes.available
                    ),
                    lastUsed = lastUsed,
                    managed = pkg in managed,
                    whitelisted = pkg in whitelist,
                    risky = reason != null,
                    riskReason = reason,
                    stopped = stopped,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Decides a [RunState] and whether the app is in the OS force-stopped state.
     */
    private fun classify(
        pkg: String,
        uid: Int,
        flagStopped: Boolean,
        lastUsed: Long,
        processes: ProcessSnapshot,
        fallbackStates: Map<String, RunState>,
    ): Pair<RunState, Boolean> {
        // 1. A live process seen by the shell uid is ground truth — it also
        //    exposes an app we FAILED to stop, so it must win over any record.
        if (processes.available) {
            val imp = processes.importanceFor(pkg, uid)
            if (imp != null && imp < IMPORTANCE_GONE) {
                HibernationTracker.forget(pkg)
                return importanceToState(imp) to false
            }
        }

        // 2. The OS's own force-stopped flag: exact, no privileges needed.
        if (flagStopped) return RunState.STOPPED to true

        // 3. Live data says no process and the OS says not force-stopped: idle.
        if (processes.available) return RunState.STOPPED to false

        // 4. No live data: force-stopping doesn't move lastTimeUsed, so bridge the
        //    moment between our stop and the flag being observed with our own
        //    record (self-heals via lastUsed once the app is used again).
        if (HibernationTracker.isHibernated(pkg, lastUsed)) return RunState.STOPPED to true

        // 5. Usage-event heuristic.
        return (fallbackStates[pkg] ?: RunState.STOPPED) to false
    }

    private fun importanceToState(imp: Int): RunState = when {
        imp <= IMPORTANCE_FOREGROUND -> RunState.FOREGROUND
        imp <= IMPORTANCE_FOREGROUND_SERVICE -> RunState.FOREGROUND_SERVICE
        imp <= IMPORTANCE_SERVICE -> RunState.WORKING
        imp < IMPORTANCE_GONE -> RunState.CACHED
        else -> RunState.STOPPED
    }

    private fun detailFor(
        state: RunState,
        isIme: Boolean,
        managed: Boolean,
        stopped: Boolean,
        lastUsed: Long,
        live: Boolean,
    ): String {
        if (isIme && state != RunState.STOPPED) return "Being used by input method"
        return when (state) {
            RunState.FOREGROUND -> "In use right now"
            RunState.FOREGROUND_SERVICE -> "Running as foreground"
            RunState.WORKING -> if (live) "Working" else "Recently used · likely awake"
            RunState.CACHED -> "Background-free (cached)"
            RunState.STOPPED -> when {
                stopped && managed -> "Hibernated"
                stopped -> "Stopped"
                live -> "Not running"
                lastUsed == 0L -> "Idle · not used recently"
                else -> "Idle"
            }
        }
    }

    /**
     * Packages that are risky to force-stop, mapped to a short reason. These
     * are skipped by the default "Hibernate all", but the user can still force
     * them by selecting explicitly.
     */
    private fun riskyPackages(): Map<String, String> {
        val map = HashMap<String, String>()

        // Active input methods (keyboards) — killing these breaks typing.
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.forEach { map.putIfAbsent(it.packageName, "Active keyboard") }
        }

        // Current home / launcher.
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
                ?.let { map.putIfAbsent(it, "Home launcher") }
        }

        // Enabled accessibility services (including Zenify itself).
        runCatching {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .forEach { it.resolveInfo?.serviceInfo?.packageName?.let { p -> map.putIfAbsent(p, "Accessibility service") } }
        }

        // Active device-admin apps.
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.activeAdmins?.forEach { map.putIfAbsent(it.packageName, "Device admin") }
        }

        return map
    }

    private fun lastUsedByPackage(now: Long): Map<String, Long> {
        val begin = now - 7L * 24 * 60 * 60 * 1000
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, now)
            ?: return emptyMap()
        val map = HashMap<String, Long>(stats.size)
        for (s in stats) {
            val prev = map[s.packageName] ?: 0L
            if (s.lastTimeUsed > prev) map[s.packageName] = s.lastTimeUsed
        }
        return map
    }

    /**
     * Best-effort running-app detection without Shizuku, from the OS
     * usage-event stream. Scans from the last boot (foreground services and
     * visible activities never survive a reboot) so a long-running service is
     * not missed the way a fixed short window would. Three signals:
     *
     *  - ACTIVITY_RESUMED with no later PAUSED/STOPPED → visible right now
     *    (split-screen partner, picture-in-picture, …). Zenify itself is
     *    excluded: it is always the foreground app while you look at the list.
     *  - FOREGROUND_SERVICE_START without a matching STOP → running as
     *    foreground (music players, VPNs, speed meters…), which never comes to
     *    the foreground as an activity.
     *  - ACTIVITY_RESUMED within [activeWindowMs] → the app was actually opened
     *    recently and is very likely still alive in the background.
     *
     * Purely-background processes (no service, not opened recently) remain
     * invisible — that genuinely requires the shell-level process table.
     */
    private fun fallbackRunningStates(now: Long, self: String): Map<String, RunState> {
        val bootTime = now - SystemClock.elapsedRealtime()
        val begin = maxOf(bootTime - 60_000L, now - MAX_EVENT_LOOKBACK_MS)
        val events = usm.queryEvents(begin, now) ?: return emptyMap()
        val e = UsageEvents.Event()
        val lastFgTime = HashMap<String, Long>()
        val activityState = HashMap<String, Int>()   // pkg -> ACT_*
        val fgServices = HashMap<String, Int>()      // pkg -> active foreground services
        // Before API 29 there is no ACTIVITY_STOPPED, so PAUSED already means "gone".
        val hasStoppedEvents = Build.VERSION.SDK_INT >= 29
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                EVENT_ACTIVITY_RESUMED -> {
                    lastFgTime[pkg] = e.timeStamp
                    activityState[pkg] = ACT_RESUMED
                }
                EVENT_ACTIVITY_PAUSED ->
                    activityState[pkg] = if (hasStoppedEvents) ACT_PAUSED else ACT_STOPPED
                EVENT_ACTIVITY_STOPPED -> activityState[pkg] = ACT_STOPPED
                EVENT_FGS_START -> fgServices[pkg] = (fgServices[pkg] ?: 0) + 1
                EVENT_FGS_STOP -> fgServices[pkg] = ((fgServices[pkg] ?: 0) - 1).coerceAtLeast(0)
                // Nothing that was running before a reboot is running now.
                EVENT_DEVICE_SHUTDOWN, EVENT_DEVICE_STARTUP -> {
                    activityState.clear()
                    fgServices.clear()
                }
            }
        }

        val states = HashMap<String, RunState>()
        // Recently opened activity → likely still alive in the background.
        lastFgTime.forEach { (pkg, t) ->
            if (now - t <= activeWindowMs) states[pkg] = RunState.WORKING
        }
        // Active foreground service → running as foreground (stronger signal).
        fgServices.forEach { (pkg, count) ->
            if (count > 0) states[pkg] = RunState.FOREGROUND_SERVICE
        }
        // Still resumed → visible right now (strongest).
        activityState.forEach { (pkg, s) ->
            if (s == ACT_RESUMED && pkg != self) states[pkg] = RunState.FOREGROUND
        }
        return states
    }
}
