package com.pn.zenify.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The hands that tap "Force stop". For each queued package [ForceStopController]
 * opens the App-info page; this service finds and clicks the button, then
 * confirms the dialog, then tells the controller to advance.
 *
 * Button text varies by OEM/locale, so we match a set of known labels and fall
 * back to well-known view-ids. Advancement on stuck screens is handled by the
 * controller's timeout — this service never blocks the queue.
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
        when (ForceStopController.phase) {
            ForceStopController.Phase.INFO -> handleInfoPage(root, target)
            ForceStopController.Phase.CONFIRM -> handleConfirmDialog(root)
        }
        root.recycle()
    }

    private fun handleInfoPage(root: AccessibilityNodeInfo, target: String) {
        val button = findForceStopButton(root) ?: return // wait; controller will time out

        val clickable = clickableAncestor(button)
        val enabled = clickable != null && clickable.isEnabled && button.isEnabled
        if (!enabled) {
            // Greyed-out button. Either the app is already stopped (no process →
            // treat as hibernated) or it's a protected app the OS won't let us
            // stop (keyboard / launcher / a11y / device-admin → skip honestly).
            val stoppedNow = !ForceStopController.isProtected(target)
            ForceStopController.onPackageHandled(success = stoppedNow)
            return
        }
        ForceStopController.phase = ForceStopController.Phase.CONFIRM
        clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun handleConfirmDialog(root: AccessibilityNodeInfo) {
        val confirm = findConfirmButton(root) ?: return // wait; controller will time out
        val clickable = clickableAncestor(confirm) ?: confirm
        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ForceStopController.onPackageHandled(success = true)
    }

    private fun findForceStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        byText(root, forceStopLabels)?.let { return it }
        for (id in forceStopViewIds) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        }
        return null
    }

    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()?.let { return it }
        return byText(root, confirmLabels)
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
