package com.macvision.fridayphone

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Makes Friday a selectable "digital assistant." When the user sets Friday as the default assistant,
 * the system binds this service — and the assistant is the ONE app type Android exempts from the
 * in-call microphone silencing (AOSP concurrent-capture policy). That exemption is what we need so
 * the in-call listener can hear "Friday hang up" while a Google Voice call holds the mic.
 *
 * Friday has no pop-up assistant UI; the always-on ListenerService does the real work, so this just
 * makes sure that listener is running whenever we're the bound assistant.
 */
class FridayInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(ListenerService.TAG, "Friday assistant ready (VoiceInteractionService bound)")
        try { ListenerService.start(this) } catch (_: Exception) {}
    }
}
