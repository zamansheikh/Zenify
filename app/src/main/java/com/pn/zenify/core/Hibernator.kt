package com.pn.zenify.core

import android.util.Log
import com.pn.zenify.data.AppInfo
import com.pn.zenify.data.RunState
import com.pn.zenify.shizuku.ShizukuManager

/** Result of a hibernation pass. */
data class HibernateResult(
    val attempted: List<String>,
    val succeeded: List<String>,
) {
    val failed: List<String> get() = attempted - succeeded.toSet()
}

/**
 * Stateless orchestrator: decides which apps are eligible and force-stops them
 * through [ShizukuManager]. Eligible = managed, not whitelisted, currently
 * active, and (optionally) idle past the delay window.
 */
object Hibernator {

    private const val TAG = "ZenifyHibernator"

    fun eligible(
        apps: List<AppInfo>,
        idleThresholdMs: Long,
        now: Long,
    ): List<AppInfo> = apps.filter { app ->
        app.managed &&
            !app.whitelisted &&
            app.runState != RunState.STOPPED &&
            app.runState != RunState.FOREGROUND &&
            (app.lastUsed == 0L || now - app.lastUsed >= idleThresholdMs)
    }

    suspend fun hibernate(packages: List<String>): HibernateResult {
        val succeeded = mutableListOf<String>()
        for (pkg in packages) {
            val ok = ShizukuManager.hibernate(pkg)
            if (ok) succeeded.add(pkg) else Log.w(TAG, "Could not hibernate $pkg")
        }
        return HibernateResult(packages, succeeded)
    }
}
