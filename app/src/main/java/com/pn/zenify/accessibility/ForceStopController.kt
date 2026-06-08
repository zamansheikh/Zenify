package com.pn.zenify.accessibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.pn.zenify.core.HibernationTracker
import com.pn.zenify.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Drives the no-root, Greenify-style hibernation: for each queued package it
 * opens the system "App info" screen, and [ForceStopService] taps the
 * "Force stop" button + the confirmation for us. This object owns the queue and
 * the per-package phase; the service owns the node-clicking.
 */
object ForceStopController {

    enum class Phase { INFO, CONFIRM }

    data class Progress(
        val active: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val currentPackage: String? = null,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val queue = ArrayDeque<String>()

    @Volatile var currentPackage: String? = null
        private set

    @Volatile var phase: Phase = Phase.INFO

    private var total = 0
    private var done = 0
    private var appContext: Context? = null

    /** True once the user has enabled our accessibility service and it bound. */
    @Volatile var serviceConnected = false

    @Synchronized
    fun enqueue(context: Context, packages: List<String>) {
        if (packages.isEmpty()) return
        appContext = context.applicationContext
        queue.addAll(packages)
        total += packages.size
        if (currentPackage == null) advance()
    }

    /**
     * Called by the service once a package's force-stop flow has finished.
     * [success] is false when we gave up (button never appeared) so we don't
     * falsely mark it hibernated.
     */
    @Synchronized
    fun onPackageHandled(success: Boolean) {
        val pkg = currentPackage
        if (pkg != null) {
            done++
            if (success) HibernationTracker.mark(pkg)
        }
        currentPackage = null
        advance()
    }

    @Synchronized
    private fun advance() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            finish()
            return
        }
        currentPackage = next
        phase = Phase.INFO
        _progress.value = Progress(true, total, done, next)
        openAppInfo(next)
    }

    private fun finish() {
        val ctx = appContext
        currentPackage = null
        _progress.value = Progress(false, total, done, null)
        total = 0
        done = 0
        // Bring the user back to Zenify so they see the result.
        ctx?.let {
            it.startActivity(
                Intent(it, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    private fun openAppInfo(pkg: String) {
        val ctx = appContext ?: return
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    fun isRunning(): Boolean = currentPackage != null
}
