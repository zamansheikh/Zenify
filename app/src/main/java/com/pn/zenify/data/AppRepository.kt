package com.pn.zenify.data

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import com.pn.zenify.core.HibernationTracker
import com.pn.zenify.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discovers installed apps and infers a coarse [RunState] from usage stats.
 *
 * Note: without root we can't enumerate live processes, so "running" is a
 * heuristic — an app counts as active if it was the foreground app or was used
 * within [activeWindowMs]. The watcher service compensates by re-checking after
 * each hibernation pass.
 */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val usm: UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /** Apps idle longer than this are considered stopped/asleep. */
    private val activeWindowMs = 30 * 60 * 1000L

    private companion object {
        // android.app.ActivityManager.RunningAppProcessInfo importance values.
        const val IMPORTANCE_FOREGROUND = 100
        const val IMPORTANCE_FOREGROUND_SERVICE = 125
        const val IMPORTANCE_SERVICE = 300
        const val IMPORTANCE_GONE = 1000
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
        val lastUsedMap = lastUsedByPackage(now)
        val foreground = currentForegroundPackage(now)
        val self = context.packageName

        val launchable = launchablePackages()
        val riskReasons = riskyPackages()
        val importance = ShizukuManager.runningImportance()

        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != self }
            .mapNotNull { ai ->
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // Show user apps always; system apps only if requested AND launchable
                if (isSystem && !showSystem) return@mapNotNull null
                if (isSystem && ai.packageName !in launchable && !showSystem) return@mapNotNull null

                val pkg = ai.packageName
                val lastUsed = lastUsedMap[pkg] ?: 0L
                val state = classify(pkg, lastUsed, now, foreground, importance)
                val reason = riskReasons[pkg]
                val isIme = reason == "Active keyboard"
                AppInfo(
                    packageName = pkg,
                    label = pm.getApplicationLabel(ai).toString(),
                    icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                    isSystem = isSystem,
                    runState = state,
                    detail = detailFor(state, isIme, managed.contains(pkg), lastUsed),
                    lastUsed = lastUsed,
                    managed = pkg in managed,
                    whitelisted = pkg in whitelist,
                    risky = reason != null,
                    riskReason = reason,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Decides a [RunState]. Prefers real ActivityManager importance (via
     * Shizuku); falls back to a usage-stats heuristic when that map is empty.
     */
    private fun classify(
        pkg: String,
        lastUsed: Long,
        now: Long,
        foreground: String?,
        importance: Map<String, Int>,
    ): RunState {
        // We just force-stopped it — trust that over stale usage/importance data.
        if (HibernationTracker.isHibernated(pkg, lastUsed)) return RunState.STOPPED

        if (importance.isNotEmpty()) {
            val imp = importance[pkg] ?: return RunState.STOPPED
            return when {
                imp <= IMPORTANCE_FOREGROUND -> RunState.FOREGROUND
                imp <= IMPORTANCE_FOREGROUND_SERVICE -> RunState.FOREGROUND_SERVICE
                imp <= IMPORTANCE_SERVICE -> RunState.WORKING
                imp < IMPORTANCE_GONE -> RunState.CACHED
                else -> RunState.STOPPED
            }
        }

        // Fallback: no privileged data.
        return when {
            pkg == foreground -> RunState.FOREGROUND
            lastUsed > 0 && (now - lastUsed) <= activeWindowMs -> RunState.WORKING
            else -> RunState.STOPPED
        }
    }

    private fun detailFor(state: RunState, isIme: Boolean, managed: Boolean, lastUsed: Long): String {
        if (isIme && state != RunState.STOPPED) return "Being used by input method"
        return when (state) {
            RunState.FOREGROUND -> "In use right now"
            RunState.FOREGROUND_SERVICE -> "Running as foreground"
            RunState.WORKING -> "Working"
            RunState.CACHED -> "Background-free (cached)"
            RunState.STOPPED -> when {
                managed -> "Hibernated"
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

    private fun launchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toHashSet()
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

    /** The package that most recently moved to foreground in the last hour. */
    private fun currentForegroundPackage(now: Long): String? {
        val events = usm.queryEvents(now - 60 * 60 * 1000, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                last = e.packageName
            }
        }
        return last
    }
}
