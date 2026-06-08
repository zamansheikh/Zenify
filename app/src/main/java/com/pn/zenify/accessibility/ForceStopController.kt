package com.pn.zenify.accessibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.pn.zenify.core.HibernationTracker
import com.pn.zenify.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Drives the no-root, Greenify-style hibernation: for each queued package it
 * opens the system "App info" screen, and [ForceStopService] taps the
 * "Force stop" button + the confirmation for us. This object owns the queue,
 * the per-package phase, and a watchdog timeout; the service owns the clicking.
 *
 * The timeout lives HERE (not in the service) on purpose: a disabled "Force
 * stop" button or an unsupported screen produces no further accessibility
 * events, so an event-driven watchdog could stall forever. This Handler fires
 * regardless, guaranteeing the queue always advances.
 */
object ForceStopController {

    enum class Phase { INFO, CONFIRM }

    data class Progress(
        val active: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val skipped: Int = 0,
        val currentPackage: String? = null,
    )

    /** Per-package budget before we give up and move on. */
    private const val STEP_TIMEOUT_MS = 4000L

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val protectedPkgs = HashSet<String>()

    @Volatile var currentPackage: String? = null
        private set

    @Volatile var phase: Phase = Phase.INFO

    /** Incremented every advance; stale timeouts compare against it. */
    private var generation = 0
    private var total = 0
    private var done = 0
    private var skipped = 0
    private var appContext: Context? = null

    /** True once the user has enabled our accessibility service and it bound. */
    @Volatile var serviceConnected = false

    /** Apps the OS won't let us force-stop (keyboard, launcher, a11y, admin). */
    fun isProtected(pkg: String): Boolean = pkg in protectedPkgs

    @Synchronized
    fun enqueue(context: Context, packages: List<String>, protectedPackages: Set<String> = emptySet()) {
        if (packages.isEmpty()) return
        appContext = context.applicationContext
        protectedPkgs.addAll(protectedPackages)
        queue.addAll(packages)
        total += packages.size
        if (currentPackage == null) advance()
    }

    /**
     * Called by the service when a package's flow ends. [success] = we stopped
     * it (or it was already stopped). When false it's a protected app we can't
     * stop, or a screen we couldn't drive — reported as "skipped", not marked.
     */
    @Synchronized
    fun onPackageHandled(success: Boolean) {
        handler.removeCallbacksAndMessages(null)
        val pkg = currentPackage
        if (pkg != null) {
            if (success) {
                done++
                HibernationTracker.mark(pkg)
            } else {
                skipped++
            }
        }
        currentPackage = null
        advance()
    }

    @Synchronized
    private fun advance() {
        handler.removeCallbacksAndMessages(null)
        val next = queue.removeFirstOrNull()
        if (next == null) {
            finish()
            return
        }
        currentPackage = next
        phase = Phase.INFO
        generation++
        val gen = generation
        _progress.value = Progress(true, total, done, skipped, next)
        openAppInfo(next)
        // Watchdog: if the service hasn't resolved this package in time (e.g. a
        // disabled button that emits no events), skip it and move on.
        handler.postDelayed({ onTimeout(gen) }, STEP_TIMEOUT_MS)
    }

    @Synchronized
    private fun onTimeout(gen: Int) {
        if (gen != generation || currentPackage == null) return
        // Couldn't act in time — count as skipped and continue.
        skipped++
        currentPackage = null
        advance()
    }

    private fun finish() {
        val ctx = appContext
        currentPackage = null
        protectedPkgs.clear()
        _progress.value = Progress(false, total, done, skipped, null)
        total = 0
        done = 0
        skipped = 0
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
