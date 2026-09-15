package com.pn.zenify.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The hands that tap "Force stop". For each queued package [ForceStopController]
 * opens the App-info page; this service finds and clicks the button, then
 * confirms the dialog, then tells the controller to advance.
 *
 * Both the "Force stop" button and the confirmation dialog are driven by an
 * active poll rather than by accessibility events. With a short
 * notificationTimeout the framework coalesces events, and under load (e.g.
 * stopping a few hundred apps) the App-info page often isn't rendered yet when
 * the one event we get arrives — so a purely event-driven search misses the
 * button entirely and the app is wrongly skipped. The poll re-checks every
 * [POLL_MS], searches every window (button/dialog can live in their own
 * window), waits out a transiently-disabled button during load, and verifies
 * the dialog actually dismissed. An accessibility event only *starts* the poll;
 * the controller's per-attempt watchdog is the backstop that reopens or skips.
 *
 * Completion is judged by the OS, not by the screen: once the package's
 * force-stopped flag flips ([ApplicationInfo.FLAG_STOPPED] — the same state
 * that greys out the button in Settings) the package is done, whatever
 * dialog a ROM shows or skips. Button text varies by OEM/locale, so we match
 * known labels and view-ids.
 */
class ForceStopService : AccessibilityService() {

    private val forceStopLabels = setOf(
        "force stop", "force close", "forcestop", "stop", "force-stop"
    )

    /**
     * Confirm labels that never appear on the App-info page itself, so they
     * are safe to accept in any window.
     */
    private val neutralConfirmLabels = setOf("ok", "yes", "confirm")

    /**
     * Confirm labels that ALSO label the App-info page's own Force-stop button.
     * Accepted only in a window other than the one we tapped that button in
     * (the dialog is always its own window). Matching these on the page itself
     * is what used to make the poll re-find the greyed-out Force-stop button
     * forever after the dialog closed, never report success, and let the
     * watchdog open App info a second time for every app.
     */
    private val ambiguousConfirmLabels = setOf("force stop", "force close", "forcestop", "force-stop")

    private val forceStopViewIds = listOf(
        "com.android.settings:id/force_stop_button",
        "com.android.settings:id/right_button",
        "miui:id/force_stop_button",
    )
    private val confirmViewIds = listOf("android:id/button1")

    companion object {
        /** How often to re-check the screen. */
        const val POLL_MS = 120L

        /** Max polls while waiting for the Force-stop button to render. */
        const val MAX_INFO_POLLS = 30

        /**
         * A freshly-opened page can show "Force stop" briefly disabled before it
         * settles. Only after this many polls (~POLL_MS each) do we trust a
         * disabled button to mean "already stopped / protected" rather than
         * "still loading".
         */
        const val DISABLED_SETTLE_POLLS = 6

        /** Max polls spent confirming the dialog. */
        const val MAX_CONFIRM_POLLS = 25

        /** The bound service, so the controller can ask about on-screen state. */
        @Volatile
        var instance: ForceStopService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The package an info-poll chain is currently running for (dedupes starts). */
    private var infoPollTarget: String? = null

    /** True once we've tapped "OK"; we then poll until the dialog is gone. */
    private var confirmClicked = false

    /** Window that held the Force-stop button we tapped; the dialog is another one. */
    private var forceStopWindowId = -1

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ForceStopController.serviceConnected = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        ForceStopController.serviceConnected = false
        handler.removeCallbacksAndMessages(null)
        infoPollTarget = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
        infoPollTarget = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val target = ForceStopController.currentPackage ?: return
        // The confirm dialog has its own poll; only the INFO phase starts here.
        if (ForceStopController.phase != ForceStopController.Phase.INFO) return
        // Start (once) an active poll for this package's Force-stop button. We
        // don't act on this single event's tree snapshot — the page may not be
        // rendered yet — the poll keeps looking until it appears.
        if (infoPollTarget != target) {
            infoPollTarget = target
            handler.removeCallbacksAndMessages(null)
            pollInfo(target, 0)
        }
    }

    // ---- OS / screen probes (also used by the controller's watchdog) ------------

    /**
     * The OS's own force-stopped state for [pkg]. A package that is gone
     * (uninstalled mid-run) counts as stopped: there is nothing left to do.
     */
    fun isStopped(pkg: String): Boolean = try {
        (packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        true
    } catch (t: Throwable) {
        false
    }

    /** True if the App-info page currently shows a greyed-out Force-stop button. */
    fun isForceStopButtonDisabled(): Boolean {
        val button = findAcrossWindows(forceStopLabels, forceStopViewIds) ?: return false
        val clickable = clickableAncestor(button)
        return !(button.isEnabled && (clickable == null || clickable.isEnabled))
    }

    // ---- INFO phase: find + tap "Force stop" ----------------------------------

    /** Look for (and click) the Force-stop button, retrying until it renders. */
    private fun pollInfo(target: String, polls: Int) {
        // Run moved on or this package already resolved — stop and allow a fresh
        // poll to start for whatever comes next.
        if (ForceStopController.currentPackage != target ||
            ForceStopController.phase != ForceStopController.Phase.INFO
        ) {
            infoPollTarget = null
            return
        }

        // The OS already reports it stopped — nothing to tap.
        if (isStopped(target)) {
            infoPollTarget = null
            ForceStopController.onPackageHandled(success = true)
            return
        }

        val button = findAcrossWindows(forceStopLabels, forceStopViewIds)
        if (button != null) {
            val clickable = clickableAncestor(button)
            val enabled = clickable != null && clickable.isEnabled && button.isEnabled
            if (enabled) {
                infoPollTarget = null
                forceStopWindowId = button.windowId
                ForceStopController.phase = ForceStopController.Phase.CONFIRM
                clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                startConfirm(target)
                return
            }
            // Button present but greyed. Give the page time to settle before
            // concluding it's truly disabled.
            if (polls < DISABLED_SETTLE_POLLS) {
                handler.postDelayed({ pollInfo(target, polls + 1) }, POLL_MS)
                return
            }
            infoPollTarget = null
            // Greyed out although the OS doesn't call it stopped: a protected
            // app (keyboard, launcher, admin…) the system refuses to stop. For
            // anything else a disabled button still means "already stopped".
            val stoppedNow = isStopped(target) || !ForceStopController.isProtected(target)
            ForceStopController.onPackageHandled(success = stoppedNow)
            return
        }

        // Button not in the tree yet — keep waiting for the page to render.
        if (polls < MAX_INFO_POLLS) {
            handler.postDelayed({ pollInfo(target, polls + 1) }, POLL_MS)
        } else {
            // Page never produced a button this attempt; let the controller
            // watchdog reopen or skip. Clear so a later event can retry.
            infoPollTarget = null
        }
    }

    // ---- CONFIRM phase: tap "OK", verify the stop ------------------------------

    /** Begin polling for (and clicking) the confirmation dialog's OK button. */
    private fun startConfirm(target: String) {
        handler.removeCallbacksAndMessages(null)
        confirmClicked = false
        handler.postDelayed({ pollConfirm(target, 0) }, POLL_MS)
    }

    private fun pollConfirm(target: String, polls: Int) {
        // Bail if the run moved on (new package) or the package already resolved.
        if (ForceStopController.currentPackage != target ||
            ForceStopController.phase != ForceStopController.Phase.CONFIRM
        ) return

        // 1. The OS says it's stopped → done, whatever the screen shows.
        if (isStopped(target)) {
            finishConfirm(success = true)
            return
        }

        // 2. Dialog is up → tap OK (again if it lingered; a re-tap is harmless).
        val confirm = findConfirmButton()
        if (confirm != null) {
            (clickableAncestor(confirm) ?: confirm).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            confirmClicked = true
            if (polls < MAX_CONFIRM_POLLS) {
                handler.postDelayed({ pollConfirm(target, polls + 1) }, POLL_MS)
            }
            // else: the dialog never went away — leave it to the watchdog.
            return
        }

        // 3. No dialog visible. If we already tapped OK, or the page now shows
        //    the button greyed out (ROMs that skip the confirmation), it went
        //    through.
        if (confirmClicked || isForceStopButtonDisabled()) {
            finishConfirm(success = true)
            return
        }

        // 4. Dialog hasn't appeared yet — keep waiting, then defer to the watchdog.
        if (polls < MAX_CONFIRM_POLLS) {
            handler.postDelayed({ pollConfirm(target, polls + 1) }, POLL_MS)
        }
    }

    private fun finishConfirm(success: Boolean) {
        forceStopWindowId = -1
        confirmClicked = false
        ForceStopController.onPackageHandled(success)
    }

    /**
     * The dialog's positive button. By view-id first (AOSP AlertDialog), then by
     * label: neutral labels anywhere, page-ambiguous labels only outside the
     * window that holds the page's own Force-stop button.
     */
    private fun findConfirmButton(): AccessibilityNodeInfo? {
        val roots = allRoots()
        for (root in roots) {
            for (id in confirmViewIds) {
                root.findAccessibilityNodeInfosByViewId(id)
                    .firstOrNull { it.isEnabled }
                    ?.let { return it }
            }
        }
        for (root in roots) {
            byText(root) { node, label ->
                node.isEnabled && (
                    label in neutralConfirmLabels ||
                        (label in ambiguousConfirmLabels && node.windowId != forceStopWindowId)
                    )
            }?.let { return it }
        }
        return null
    }

    // ---- Tree search helpers ------------------------------------------------------

    /**
     * Search the active window and every other window for a node matching any of
     * [labels] (by text/desc) or [viewIds]. Dialogs and some OEM settings panels
     * live in a separate window that `rootInActiveWindow` doesn't return.
     */
    private fun findAcrossWindows(
        labels: Set<String>,
        viewIds: List<String>,
    ): AccessibilityNodeInfo? {
        for (root in allRoots()) {
            for (id in viewIds) {
                root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
            }
            byText(root) { _, label -> label in labels }?.let { return it }
        }
        return null
    }

    /** Root of the active window first, then every other window (deduped). */
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()
        rootInActiveWindow?.let {
            roots.add(it)
            seen.add(it.windowId)
        }
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                if (seen.add(r.windowId)) roots.add(r)
            }
        }
        return roots
    }

    private fun byText(
        root: AccessibilityNodeInfo,
        accept: (node: AccessibilityNodeInfo, label: String) -> Boolean,
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = node.text?.toString()?.trim()?.lowercase()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase()
            if ((text != null && accept(node, text)) || (desc != null && accept(node, desc))) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return node
    }
}
