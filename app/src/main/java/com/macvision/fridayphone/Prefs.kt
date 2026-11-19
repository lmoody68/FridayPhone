package com.macvision.fridayphone

import android.content.Context

/** Tiny shared-prefs wrapper. `listening` lets BootReceiver know whether to re-arm after reboot. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)

    var listening: Boolean
        get() = sp.getBoolean("listening", false)
        set(v) { sp.edit().putBoolean("listening", v).apply() }
}
