package com.pn.zenify.data

import android.graphics.drawable.Drawable

/** Coarse running state derived from UsageStats + RunningAppProcesses. */
enum class RunState {
    /** Has a process and is/was recently in the foreground. */
    FOREGROUND,
    /** Has a live background process. */
    RUNNING,
    /** No live process we can see — effectively asleep. */
    STOPPED,
}

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val runState: RunState,
    /** Last time the app was used, epoch millis (0 if unknown). */
    val lastUsed: Long,
    /** User chose to manage (hibernate) this app. */
    val managed: Boolean,
    /** User chose to never hibernate this app. */
    val whitelisted: Boolean,
) {
    val isActive: Boolean get() = runState != RunState.STOPPED
}
