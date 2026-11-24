package com.macvision.fridayphone

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Friday's hands. An AccessibilityService can read the on-screen node tree and tap buttons that
 * no public API can reach — specifically Google Voice's own "Hang up" control (GV runs a
 * self-managed VoIP call, so TelecomManager.endCall() does not end it; the proven fix is to click
 * GV's button). It also serves as the fallback tap for the incoming-call Accept / Decline controls.
 *
 * It reacts to nothing on its own; [clickCallButton] is called on demand by [CallController] when a
 * voice command fires. Targets were captured on the A15 (2026-11-24):
 *   GV in-call hang up : content-desc "Hang up" (no resource-id — Jetpack Compose)
 *   incoming accept    : com.android.systemui:id/accept_btn (desc "Accept")
 *   incoming decline   : com.android.systemui:id/end_call_btn (desc "End call")
 */
class FridayA11yService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(ListenerService.TAG, "Friday accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* on-demand only */ }
    override fun onInterrupt() { }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Search every window's node tree for a button by resource-id (exact) or content-description
     * (case-insensitive contains) and click it (or its nearest clickable ancestor). Returns true on
     * the first successful click.
     */
    fun clickCallButton(resIds: List<String>, descs: List<String>): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        try { windows?.forEach { w -> try { w.root?.let { roots.add(it) } } catch (_: Exception) {} } } catch (_: Exception) {}

        // 1) by resource-id (most precise — the systemui call buttons expose real ids)
        for (root in roots) {
            for (id in resIds) {
                try {
                    val found = root.findAccessibilityNodeInfosByViewId(id) ?: continue
                    for (n in found) if (clickNodeOrAncestor(n)) {
                        Log.i(ListenerService.TAG, "a11y clicked id=$id"); return true
                    }
                } catch (_: Exception) {}
            }
        }
        // 2) by content-description (GV's Compose "Hang up" has no id)
        val lower = descs.map { it.lowercase() }
        for (root in roots) {
            val n = findByDesc(root, lower)
            if (n != null && clickNodeOrAncestor(n)) {
                Log.i(ListenerService.TAG, "a11y clicked desc match"); return true
            }
        }
        Log.w(ListenerService.TAG, "a11y: no matching call button found")
        return false
    }

    private fun findByDesc(node: AccessibilityNodeInfo?, descsLower: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val cd = node.contentDescription?.toString()?.lowercase()
        if (cd != null && descsLower.any { cd.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val r = findByDesc(node.getChild(i), descsLower)
            if (r != null) return r
        }
        return null
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo?): Boolean {
        var n = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            hops++
        }
        return try { node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false } catch (_: Exception) { false }
    }

    companion object {
        @Volatile
        var instance: FridayA11yService? = null

        fun isEnabled(): Boolean = instance != null
    }
}
