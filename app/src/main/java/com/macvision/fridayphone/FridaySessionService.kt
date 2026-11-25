package com.macvision.fridayphone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Required companion to [FridayInteractionService]. Friday is the default assistant ONLY so her
 * in-call listener is exempt from the mic-silencing policy — she shows no assistant UI of her own.
 *
 * But being the default assistant also captures the assist gesture (press-and-hold the side key),
 * which would otherwise do nothing. So when that gesture opens a session, we HAND IT OFF to Google
 * Assistant and close — the user keeps Google Assistant on the button AND keeps Friday's call powers.
 */
class FridaySessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = FridaySession(this)
}

class FridaySession(private val ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        launchGoogleAssistant()
        try { hide() } catch (_: Exception) {}
    }

    /** Open Google Assistant explicitly (NOT the default assist, which is Friday → would loop). */
    private fun launchGoogleAssistant() {
        val intents = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND).setPackage(GOOGLE_PKG),
            Intent("android.intent.action.VOICE_ASSIST").setPackage(GOOGLE_PKG),
            Intent(Intent.ACTION_ASSIST).setPackage(GOOGLE_PKG),
            Intent(Intent.ACTION_VOICE_COMMAND)   // last resort: system voice command
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                Log.i(ListenerService.TAG, "assist button -> handed off to Google Assistant (${i.action})")
                return
            } catch (_: Exception) {}
        }
        Log.w(ListenerService.TAG, "assist button -> could not launch Google Assistant")
    }

    companion object {
        // The Google app that hosts Google Assistant.
        const val GOOGLE_PKG = "com.google.android.googlequicksearchbox"
    }
}
