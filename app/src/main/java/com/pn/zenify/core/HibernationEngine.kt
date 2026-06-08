package com.pn.zenify.core

import android.content.Context
import com.pn.zenify.accessibility.ForceStopController
import com.pn.zenify.shizuku.ShizukuManager

/**
 * Picks how to hibernate. Shizuku is preferred (instant + silent); when it
 * isn't active we fall back to the accessibility automation (visible taps
 * through the system "App info" screen). Neither is mandatory — if nothing is
 * set up we report [Method.NONE] so the UI can guide the user.
 */
object HibernationEngine {

    enum class Method { SHIZUKU, ACCESSIBILITY, NONE }

    fun method(context: Context): Method = when {
        ShizukuManager.isReady() -> Method.SHIZUKU
        AccessibilityUtil.isEnabled(context) -> Method.ACCESSIBILITY
        else -> Method.NONE
    }

    /**
     * Hibernate [packages]. For Shizuku this completes synchronously and the
     * result reflects real success. For accessibility it enqueues the visible
     * automation and returns optimistically (true outcome is observed on the
     * next refresh once the user returns to Zenify).
     */
    suspend fun hibernate(context: Context, packages: List<String>): HibernateResult {
        if (packages.isEmpty()) return HibernateResult(emptyList(), emptyList())
        return when (method(context)) {
            Method.SHIZUKU -> Hibernator.hibernate(packages)
            Method.ACCESSIBILITY -> {
                ForceStopController.enqueue(context, packages)
                HibernateResult(packages, packages)
            }
            Method.NONE -> HibernateResult(packages, emptyList())
        }
    }
}
