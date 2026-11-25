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
    fun clickCallButton(resIds: List<String>, descs: List<String>, pkgs: List<String> = emptyList(),
                        allowEndish: Boolean = false): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        try { windows?.forEach { w -> try { w.root?.let { roots.add(it) } } catch (_: Exception) {} } } catch (_: Exception) {}

        // Always log the candidate clickables (restricted to the allowed packages) so the real
        // End button can be identified even when a match is wrong.
        logClickables(roots, pkgs)

        // 1) by resource-id — only inside the allowed packages (never a random foreground app)
        for (root in roots) for (id in resIds) {
            try {
                root.findAccessibilityNodeInfosByViewId(id)?.forEach { n ->
                    if (pkgOk(n, pkgs) && clickNodeOrAncestor(n)) { logClicked("id=$id", n); return true }
                }
            } catch (_: Exception) {}
        }
        // 2) by content-description / text
        val lower = descs.map { it.lowercase() }
        for (root in roots) {
            val n = findByDesc(root, lower, pkgs)
            if (n != null && clickNodeOrAncestor(n)) { logClicked("desc", n); return true }
        }
        // 3) aggressive: any clickable node whose id/desc/text contains an end-call keyword.
        //    OPT-IN ONLY (hang-up / decline). NEVER for Speaker or Answer — otherwise a missed
        //    Speaker tap falls through to "End call" and hangs up the call (the outbound-dial bug).
        if (allowEndish) {
            val endish = listOf("end call", "end_call", "endcall", "hang up", "hangup", "hang_up",
                                "disconnect", "end button", "endbutton", "leave call", "reject")
            for (root in roots) {
                val n = findEndish(root, endish, pkgs)
                if (n != null && clickNodeOrAncestor(n)) { logClicked("endish", n); return true }
            }
        }
        Log.w(ListenerService.TAG, "a11y: no matching call button found")
        return false
    }

    /**
     * True when an editable text field currently has input focus — i.e. Les is typing or DICTATING
     * into an app (e.g. the Claude Code prompt box). Used to suppress ONLY the outbound-call command
     * so dictation that contains "call <name>" can't dial. Reads the live foreground window.
     */
    /**
     * True when the foreground app is one Les dictates INTO (notably the Claude Code app, whose own
     * name is also "Friday"). Claude's prompt box doesn't expose an editable a11y node, so the reliable
     * tell is simply that its window is in front. Used to suppress ONLY the outbound-call command.
     */
    fun isDictationAppForeground(): Boolean {
        return try {
            val pkg = rootInActiveWindow?.packageName?.toString()
            val hit = pkg != null && DICTATION_APPS.any { pkg == it }
            if (hit) Log.i(ListenerService.TAG, "dictation app foreground: $pkg — outbound call suppressed")
            hit
        } catch (_: Exception) { false }
    }

    /**
     * Read the incoming-call screen and return the caller the dialer already resolved — the saved
     * contact NAME if the number is known, otherwise the raw number — for "Friday, who's calling?".
     * Walks the ring screen's node tree (call packages only), skips UI chrome ("Incoming call",
     * button labels, SIM/carrier), and returns the caller: a name-looking text (biggest on screen,
     * the caller label is the largest font) wins; else a phone number; else the largest text. The
     * dialer does the contact lookup for us, so whatever it displays is exactly what Les wants read.
     * Returns null if nothing usable is on screen.
     */
    fun readCallerId(): String? {
        val pkgs = listOf(
            "com.samsung.android.incallui", "com.android.incallui",
            "com.samsung.android.dialer", "com.android.dialer", "com.google.android.dialer",
            "com.android.systemui", "com.google.android.apps.googlevoice"
        )
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        try { windows?.forEach { w -> try { w.root?.let { roots.add(it) } } catch (_: Exception) {} } } catch (_: Exception) {}

        // Known ring-screen chrome — never the caller. Case-insensitive substring match.
        val chrome = listOf(
            "incoming", "swipe", "answer", "decline", "reject", "ignore", "remind", "message",
            "reply", "silence", "call back", "add call", "hold", "mute", "keypad", "speaker",
            "video", "calling via", "google voice", "mobile", "sim ", "sim1", "sim2", "dual",
            "carrier", "voicemail", "contacts", "emergency", "slide", "end call", "hang up",
            "on hold", "merge", "record", "bluetooth", "to answer", "to decline"
        )
        data class Cand(val text: String, val area: Int, val digits: Int, val letters: Int)
        val cands = ArrayList<Cand>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (pkgOk(n, pkgs)) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrBlank() && t.length in 2..40) {
                    val low = t.lowercase()
                    if (chrome.none { low.contains(it) }) {
                        val b = android.graphics.Rect(); n.getBoundsInScreen(b)
                        cands.add(Cand(t, b.width() * b.height(),
                            t.count { it.isDigit() }, t.count { it.isLetter() }))
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        for (r in roots) walk(r)
        if (cands.isEmpty()) { Log.i(ListenerService.TAG, "readCallerId: no caller text on ring screen"); return null }

        // A real NAME (has letters, not mostly digits) is best — read the biggest one (caller label).
        val nameCand = cands.filter { it.letters >= 2 && it.digits < 7 }.maxByOrNull { it.area }
        val numberCand = cands.filter { it.digits >= 7 }.maxByOrNull { it.digits }
        val best = nameCand ?: numberCand ?: cands.maxByOrNull { it.area }
        Log.i(ListenerService.TAG, "readCallerId: cands=${cands.map { "'${it.text}'(a=${it.area})" }} -> '${best?.text}'")
        return best?.text
    }

    fun isTextInputFocused(): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        try { windows?.forEach { w -> try { w.root?.let { roots.add(it) } } catch (_: Exception) {} } } catch (_: Exception) {}
        Log.i(ListenerService.TAG, "a11y windows: " + roots.joinToString { "${it.packageName}" })
        // 1) the global input-focused node, per window root
        for (r in roots) {
            try {
                val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (f != null) {
                    val ed = f.isEditable || (f.className?.toString()?.contains("EditText", true) == true)
                    Log.i(ListenerService.TAG, "a11y INPUT-focus pkg=${f.packageName} cls=${f.className} editable=${f.isEditable} -> ed=$ed")
                    if (ed) return true
                }
            } catch (_: Exception) {}
        }
        // 2) fallback: any focused, editable node anywhere in the trees
        for (r in roots) if (searchFocusedEditable(r)) { Log.i(ListenerService.TAG, "a11y found focused editable (deep)"); return true }
        return false
    }

    private fun searchFocusedEditable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
            if (node.isFocused && (node.isEditable ||
                    (node.className?.toString()?.contains("EditText", true) == true))) return true
        } catch (_: Exception) {}
        for (i in 0 until node.childCount) {
            if (searchFocusedEditable(node.getChild(i))) return true
        }
        return false
    }

    /** True if no package filter, or the node belongs to one of the allowed packages. */
    private fun pkgOk(n: AccessibilityNodeInfo?, pkgs: List<String>): Boolean {
        if (pkgs.isEmpty()) return true
        val p = n?.packageName?.toString() ?: return false
        return pkgs.any { p == it || p.contains(it) }
    }

    private fun logClicked(how: String, n: AccessibilityNodeInfo?) {
        Log.i(ListenerService.TAG, "a11y CLICKED ($how) pkg=${n?.packageName} id=${n?.viewIdResourceName} " +
            "desc='${n?.contentDescription}' text='${n?.text}' cls=${n?.className}")
    }

    /** Any clickable node (in an allowed package) whose id/desc/text contains an end-call keyword. */
    private fun findEndish(node: AccessibilityNodeInfo?, keys: List<String>, pkgs: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable && pkgOk(node, pkgs)) {
            val hay = ((node.viewIdResourceName ?: "") + "|" +
                       (node.contentDescription ?: "") + "|" + (node.text ?: "")).lowercase()
            if (keys.any { hay.contains(it) }) return node
        }
        for (i in 0 until node.childCount) {
            val r = findEndish(node.getChild(i), keys, pkgs)
            if (r != null) return r
        }
        return null
    }

    /** Diagnostic: log clickable nodes (restricted to the allowed packages) — pkg/id/desc/text/bounds. */
    private fun logClickables(roots: List<AccessibilityNodeInfo>, pkgs: List<String>) {
        var count = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count > 80) return
            if (n.isClickable && pkgOk(n, pkgs)) {
                val b = android.graphics.Rect(); n.getBoundsInScreen(b)
                Log.i(ListenerService.TAG, "NODE pkg=${n.packageName} id=${n.viewIdResourceName} " +
                    "desc='${n.contentDescription}' text='${n.text}' cls=${n.className} bounds=$b")
                count++
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        Log.i(ListenerService.TAG, "=== a11y clickables in call pkgs (roots=${roots.size}) ===")
        for (r in roots) walk(r)
    }

    private fun findByDesc(node: AccessibilityNodeInfo?, descsLower: List<String>, pkgs: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        if (pkgOk(node, pkgs)) {
            val cd = node.contentDescription?.toString()?.lowercase()
            val tx = node.text?.toString()?.lowercase()             // some controls (Samsung "Speaker") label by text
            if ((cd != null && descsLower.any { cd.contains(it) }) ||
                (tx != null && descsLower.any { tx.contains(it) })) return node
        }
        for (i in 0 until node.childCount) {
            val r = findByDesc(node.getChild(i), descsLower, pkgs)
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

        // Apps Les dictates INTO where "Friday" means his CLI assistant, not the phone. While one of
        // these is in front, the phone suppresses OUTBOUND calls only (answer/decline stay live).
        val DICTATION_APPS = setOf("com.anthropic.claude")

        fun isEnabled(): Boolean = instance != null
    }
}
