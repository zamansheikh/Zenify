package com.pn.zenify.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The hands that tap "Force stop". Per queued package, [ForceStopController]
 * opens the App-info page; this service finds and clicks the button, then
 * confirms the dialog, then tells the controller to advance.
 *
 * Button text varies by OEM/locale, so we match a set of known labels and fall
 * back to well-known view-ids. A per-package watchdog skips apps where the
 * button never appears (e.g. already stopped, or an unsupported ROM screen).
 */
class ForceStopService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var watchdogPackage: String? = null

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
        "android:id/button1",
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        ForceStopController.serviceConnected = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ForceStopController.serviceConnected = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val target = ForceStopController.currentPackage ?: return
        val root = rootInActiveWindow ?: return

        // (Re)arm a watchdog so a missing button doesn't stall the whole queue.
        armWatchdog(target)

        when (ForceStopController.phase) {
            ForceStopController.Phase.INFO -> handleInfoPage(root)
            ForceStopController.Phase.CONFIRM -> handleConfirmDialog(root)
        }
        root.recycle()
    }

    private fun handleInfoPage(root: AccessibilityNodeInfo) {
        val button = findForceStopButton(root)
        if (button == null) return // wait for next content event / watchdog

        val clickable = clickableAncestor(button)
        if (clickable == null || !clickable.isEnabled) {
            // App is already stopped (button greyed out) — count it as success.
            finishCurrent(success = true)
            return
        }
        ForceStopController.phase = ForceStopController.Phase.CONFIRM
        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun handleConfirmDialog(root: AccessibilityNodeInfo) {
        // The active window is now the dialog. Click its confirm button.
        val confirm = findConfirmButton(root)
        if (confirm != null) {
            val clickable = clickableAncestor(confirm) ?: confirm
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            finishCurrent(success = true)
        }
    }

    private fun findForceStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        byText(root, forceStopLabels)?.let { return it }
        for (id in forceStopViewIds) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        }
        return null
    }

    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Prefer the standard positive dialog button id, then text.
        root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()?.let { return it }
        return byText(root, confirmLabels)
    }

    private fun byText(root: AccessibilityNodeInfo, labels: Set<String>): AccessibilityNodeInfo? {
        // Match exactly against trimmed lowercase text to avoid hitting
        // descriptive paragraphs that merely contain the word "stop".
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

    private fun armWatchdog(pkg: String) {
        if (watchdogPackage == pkg) return
        watchdogPackage = pkg
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (ForceStopController.currentPackage == pkg) {
                // Button never showed up — skip this one and move on.
                finishCurrent(success = false)
            }
        }, 4000)
    }

    private fun finishCurrent(success: Boolean) {
        handler.removeCallbacksAndMessages(null)
        watchdogPackage = null
        ForceStopController.onPackageHandled(success)
    }
}
