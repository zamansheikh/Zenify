package com.pn.zenify.accessibility

import android.accessibilityservice.AccessibilityService
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
 * Button text varies by OEM/locale, so we match known labels and view-ids.
 */
class ForceStopService : AccessibilityService() {

    private val forceStopLabels = setOf(
        "force stop", "force close", "forcestop", "stop", "force-stop"
    )
    private val confirmLabels = setOf(
        "ok", "force stop", "force close", "yes", "confirm"
    )
    private val forceStopViewIds = listOf(
        "com.android.settings:id/force_stop_button",
        "com.android.settings:id/right_button",
        "miui:id/force_stop_button",
    )

    private companion object {
        /** How often to re-check the screen. */
        const val POLL_MS = 120L

        /** Max polls while waiting for the Force-stop button to render. */
        const val MAX_INFO_POLLS = 30

        /**
         * A freshly-opened page can show "Force stop" briefly disabled before it
         * settles. Only after this many polls (~POLL_MS each) do we trust a
         * disabled button to mean "already stopped" rather than "still loading".
         */
        const val DISABLED_SETTLE_POLLS = 6

        /** Max polls spent confirming the dialog. */
        const val MAX_CONFIRM_POLLS = 22
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The package an info-poll chain is currently running for (dedupes starts). */
    private var infoPollTarget: String? = null

    /** True once we've tapped "OK"; we then poll until the dialog is gone. */
    private var confirmClicked = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        ForceStopController.serviceConnected = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ForceStopController.serviceConnected = false
        handler.removeCallbacksAndMessages(null)
        infoPollTarget = null
        return super.onUnbind(intent)
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

        val button = findAcrossWindows(forceStopLabels, forceStopViewIds)
        if (button != null) {
            val clickable = clickableAncestor(button)
            val enabled = clickable != null && clickable.isEnabled && button.isEnabled
            if (enabled) {
                infoPollTarget = null
                ForceStopController.phase = ForceStopController.Phase.CONFIRM
                clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                startConfirm(target)
                return
            }
            // Button present but greyed. Give the page time to settle before
            // concluding it's truly disabled (already stopped, or protected).
            if (polls < DISABLED_SETTLE_POLLS) {
                handler.postDelayed({ pollInfo(target, polls + 1) }, POLL_MS)
                return
            }
            infoPollTarget = null
            val stoppedNow = !ForceStopController.isProtected(target)
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

        val confirm = findAcrossWindows(confirmLabels, listOf("android:id/button1"))
        if (confirm != null) {
            // Dialog is up — tap OK (again if it lingered; a re-tap is harmless).
            val clickable = clickableAncestor(confirm) ?: confirm
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            confirmClicked = true
            if (polls < MAX_CONFIRM_POLLS) {
                handler.postDelayed({ pollConfirm(target, polls + 1) }, POLL_MS)
            }
            return
        }

        // No dialog visible.
        if (confirmClicked) {
            // We clicked OK and it's now gone → the force-stop went through.
            ForceStopController.onPackageHandled(success = true)
            return
        }
        // Dialog hasn't appeared yet — keep waiting, then defer to the watchdog.
        if (polls < MAX_CONFIRM_POLLS) {
            handler.postDelayed({ pollConfirm(target, polls + 1) }, POLL_MS)
        }
    }

    /**
     * Search the active window and every other window for a node matching any of
     * [labels] (by text/desc) or [viewIds]. Dialogs and some OEM settings panels
     * live in a separate window that `rootInActiveWindow` doesn't return.
     */
    private fun findAcrossWindows(
        labels: Set<String>,
        viewIds: List<String>,
    ): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { findIn(it, labels, viewIds)?.let { n -> return n } }
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                findIn(r, labels, viewIds)?.let { return it }
            }
        }
        return null
    }

    private fun findIn(
        root: AccessibilityNodeInfo,
        labels: Set<String>,
        viewIds: List<String>,
    ): AccessibilityNodeInfo? {
        for (id in viewIds) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        }
        return byText(root, labels)
    }

    private fun byText(root: AccessibilityNodeInfo, labels: Set<String>): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = node.text?.toString()?.trim()?.lowercase()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase()
            if ((text != null && text in labels) || (desc != null && desc in labels)) {
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
