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
        // 2) by content-description / text (GV's Compose "Hang up" has no id)
        val lower = descs.map { it.lowercase() }
        for (root in roots) {
            val n = findByDesc(root, lower)
            if (n != null && clickNodeOrAncestor(n)) {
                Log.i(ListenerService.TAG, "a11y clicked desc match"); return true
            }
        }
        // 3) aggressive: any clickable node whose id/desc/text contains an "end call" keyword
        val endish = listOf("end call", "end_call", "endcall", "hang up", "hangup", "hang_up",
                            "disconnect", "end button", "endbutton", "leave call", "reject")
        for (root in roots) {
            val n = findEndish(root, endish)
            if (n != null && clickNodeOrAncestor(n)) {
                Log.i(ListenerService.TAG, "a11y clicked endish match"); return true
            }
        }
        // 4) nothing matched — log everything we CAN see so the real button can be identified
        logClickables(roots)
        Log.w(ListenerService.TAG, "a11y: no matching call button found")
        return false
    }

    /** Any clickable node whose resource-id, content-desc or text contains an end-call keyword. */
    private fun findEndish(node: AccessibilityNodeInfo?, keys: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable) {
            val hay = ((node.viewIdResourceName ?: "") + "|" +
                       (node.contentDescription ?: "") + "|" + (node.text ?: "")).lowercase()
            if (keys.any { hay.contains(it) }) return node
        }
        for (i in 0 until node.childCount) {
            val r = findEndish(node.getChild(i), keys)
            if (r != null) return r
        }
        return null
    }

    /** Diagnostic: log every clickable node across the given roots (pkg/id/desc/text/bounds). */
    private fun logClickables(roots: List<AccessibilityNodeInfo>) {
        var count = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count > 80) return
            if (n.isClickable) {
                val b = android.graphics.Rect(); n.getBoundsInScreen(b)
                Log.i(ListenerService.TAG, "NODE pkg=${n.packageName} id=${n.viewIdResourceName} " +
                    "desc='${n.contentDescription}' text='${n.text}' cls=${n.className} bounds=$b")
                count++
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        Log.i(ListenerService.TAG, "=== a11y clickable nodes dump (roots=${roots.size}) ===")
        for (r in roots) walk(r)
    }

    private fun findByDesc(node: AccessibilityNodeInfo?, descsLower: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val cd = node.contentDescription?.toString()?.lowercase()
        val tx = node.text?.toString()?.lowercase()                 // some controls (Samsung "Speaker") label by text
        if ((cd != null && descsLower.any { cd.contains(it) }) ||
            (tx != null && descsLower.any { tx.contains(it) })) return node
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
