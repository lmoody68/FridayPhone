package com.macvision.fridayphone

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Required companion to [FridayInteractionService]. The assistant framework needs a session service,
 * but Friday shows no assistant overlay — if a session is opened (e.g. an assist gesture), it closes
 * immediately. Friday's real interaction is the always-on wake-word listener.
 */
class FridaySessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = FridaySession(this)
}

class FridaySession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        try { hide() } catch (_: Exception) {}
    }
}
