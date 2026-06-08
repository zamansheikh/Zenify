package com.pn.zenify.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
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
                val state = when {
                    pkg == foreground -> RunState.FOREGROUND
                    lastUsed > 0 && (now - lastUsed) <= activeWindowMs -> RunState.RUNNING
                    else -> RunState.STOPPED
                }
                AppInfo(
                    packageName = pkg,
                    label = pm.getApplicationLabel(ai).toString(),
                    icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                    isSystem = isSystem,
                    runState = state,
                    lastUsed = lastUsed,
                    managed = pkg in managed,
                    whitelisted = pkg in whitelist,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
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
