package com.pn.zenify.accessibility

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

    /** Per-attempt budget before we re-open or give up on a package. */
    private const val STEP_TIMEOUT_MS = 4500L

    /**
     * Pause between finishing one package and opening the next. Force-stopping
     * is a chain of system-screen transitions and dialog dismissals; launching
     * the next App-info screen the instant we tap "OK" floods slower devices and
     * makes screens fail to load. This gap lets the system settle so the run is
     * smooth and reliable instead of fast-but-flaky.
     */
    private const val SETTLE_MS = 450L

    /** How many times to (re)open a package's App-info page before skipping. */
    private const val MAX_OPEN_ATTEMPTS = 2

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val protectedPkgs = HashSet<String>()

    /** Open attempts spent on the package currently being handled. */
    private var openAttempts = 0

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
        // Let the dialog dismiss and the system settle before the next package
        // so rapid back-to-back force-stops don't overwhelm the device.
        scheduleAdvance()
    }

    /** Move to the next package after a short settle gap. */
    private fun scheduleAdvance() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ advance() }, SETTLE_MS)
    }

    @Synchronized
    private fun advance() {
        handler.removeCallbacksAndMessages(null)
        while (true) {
            val next = queue.removeFirstOrNull()
            if (next == null) {
                finish()
                return
            }
            // Already force-stopped per the OS: nothing to tap, so don't open
            // Settings at all — count it and move straight on.
            if (isStoppedByOs(next)) {
                done++
                HibernationTracker.mark(next)
                continue
            }
            currentPackage = next
            openAttempts = 0
            openCurrent()
            return
        }
    }

    /** The OS's own force-stopped flag — the same state that greys out "Force stop". */
    private fun isStoppedByOs(pkg: String): Boolean {
        val pm = appContext?.packageManager ?: return false
        return try {
            (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** (Re)open the App-info page for [currentPackage] and arm the watchdog. */
    @Synchronized
    private fun openCurrent() {
        val pkg = currentPackage ?: return
        openAttempts++
        phase = Phase.INFO
        generation++
        val gen = generation
        _progress.value = Progress(true, total, done, skipped, pkg)
        openAppInfo(pkg)
        // Watchdog: if the service hasn't resolved this package in time (e.g. a
        // screen that never loaded, or a disabled button that emits no events),
        // retry the page or skip — guaranteeing the queue always advances.
        handler.postDelayed({ onTimeout(gen) }, STEP_TIMEOUT_MS)
    }

    @Synchronized
    private fun onTimeout(gen: Int) {
        val pkg = currentPackage
        if (gen != generation || pkg == null) return
        // Ask the OS (and the screen) before retrying: the stop may already have
        // gone through without the service noticing — a ROM with no confirm
        // dialog, or coalesced accessibility events. Re-opening App info for a
        // package that is already stopped is exactly the "opens twice" bug.
        val service = ForceStopService.instance
        if (isStoppedByOs(pkg) ||
            (phase == Phase.CONFIRM && service?.isForceStopButtonDisabled() == true)
        ) {
            onPackageHandled(success = true)
            return
        }
        // The screen may not have loaded under load — give it another open
        // before deciding the package can't be handled.
        if (openAttempts < MAX_OPEN_ATTEMPTS) {
            openCurrent()
            return
        }
        // Couldn't act in time — count as skipped and continue.
        skipped++
        currentPackage = null
        scheduleAdvance()
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
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    // Don't litter Recents with one Settings task per app.
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        runCatching { ctx.startActivity(intent) }
    }

    fun isRunning(): Boolean = currentPackage != null
}
