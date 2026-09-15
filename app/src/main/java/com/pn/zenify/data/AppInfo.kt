package com.pn.zenify.data

import android.graphics.drawable.Drawable

/**
 * Process state, mirroring the granularity Greenify shows. With Shizuku we know
 * the real [android.app.ActivityManager] importance; without it we collapse to
 * FOREGROUND / FOREGROUND_SERVICE / WORKING / STOPPED from the OS usage-event
 * stream plus the package's force-stopped flag.
 */
enum class RunState {
    /** Top / visible to the user right now. */
    FOREGROUND,
    /** Running a foreground service (evades background limits). */
    FOREGROUND_SERVICE,
    /** Has a running service or is perceptible — actively doing work. */
    WORKING,
    /** Process exists but is cached/background — harmless, no need to hibernate. */
    CACHED,
    /** No live process — asleep. */
    STOPPED,
}

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val runState: RunState,
    /** Greenify-style status line, e.g. "Background-free (cached)". */
    val detail: String,
    /** Last time the app was used, epoch millis (0 if unknown). */
    val lastUsed: Long,
    /** User chose to manage (hibernate) this app. */
    val managed: Boolean,
    /** User chose to never hibernate this app. */
    val whitelisted: Boolean,
    /** Risky to force-stop (keyboard, launcher, accessibility, device admin). */
    val risky: Boolean = false,
    /** Short reason shown to the user when [risky]. */
    val riskReason: String? = null,
    /**
     * The OS's own force-stopped state ([android.content.pm.ApplicationInfo.FLAG_STOPPED]):
     * exactly what greys out "Force stop" in Settings. True until the app is
     * launched again.
     */
    val stopped: Boolean = false,
) {
    /** Actively running (worth hibernating). Cached apps are NOT active. */
    val isActive: Boolean
        get() = runState == RunState.FOREGROUND ||
            runState == RunState.FOREGROUND_SERVICE ||
            runState == RunState.WORKING

    val isCached: Boolean get() = runState == RunState.CACHED
}
