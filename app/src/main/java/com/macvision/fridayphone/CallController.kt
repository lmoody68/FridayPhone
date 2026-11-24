package com.macvision.fridayphone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Friday's call-control engine — on-phone, no MacroDroid.
 *
 *  answer    : TelecomManager.acceptRingingCall() + speaker on. UI fallback = tap "Accept".
 *  voicemail : TelecomManager.endCall() rejects the ringing call → it goes to voicemail. UI fallback = tap "End call".
 *  hangup    : tap Google Voice's own "Hang up" (a self-managed VoIP call can't be ended by endCall()); Telecom fallback.
 *
 * Telecom paths need the ANSWER_PHONE_CALLS runtime permission; the UI-tap paths need Friday's
 * AccessibilityService enabled. Each action tries the robust path first, then falls back.
 */
object CallController {

    private fun hasAnswerPerm(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    private fun telecom(ctx: Context): TelecomManager? =
        ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    @SuppressLint("MissingPermission")
    fun answer(ctx: Context) {
        Voice.init(ctx); Voice.say("Connecting now, sir.")
        var ok = false
        val tm = telecom(ctx)
        if (ok.not() && hasAnswerPerm(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && tm != null) {
            try { tm.acceptRingingCall(); ok = true } catch (e: Exception) {
                Log.w(ListenerService.TAG, "acceptRingingCall: ${e.message}")
            }
        }
        if (!ok) {
            ok = FridayA11yService.instance?.clickCallButton(
                listOf("com.android.systemui:id/accept_btn", "com.android.incallui:id/answer_video_button"),
                listOf("Accept", "Answer")
            ) ?: false
        }
        speakerOn(ctx)
        Log.i(ListenerService.TAG, "CallController.answer -> $ok")
    }

    @SuppressLint("MissingPermission")
    fun voicemail(ctx: Context) {
        Voice.init(ctx); Voice.say("Sending to voicemail, sir.")
        var ok = false
        val tm = telecom(ctx)
        if (hasAnswerPerm(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && tm != null) {
            try { ok = tm.endCall() } catch (e: Exception) {
                Log.w(ListenerService.TAG, "endCall(reject): ${e.message}")
            }
        }
        if (!ok) {
            ok = FridayA11yService.instance?.clickCallButton(
                listOf("com.android.systemui:id/end_call_btn", "com.android.incallui:id/decline_button"),
                listOf("End call", "Decline", "Ignore", "Reject")
            ) ?: false
        }
        Log.i(ListenerService.TAG, "CallController.voicemail -> $ok")
    }

    @SuppressLint("MissingPermission")
    fun hangup(ctx: Context) {
        Voice.init(ctx); Voice.say("Call disconnected, sir.")
        // Google Voice VoIP first: tap its own "Hang up" (endCall() can't end a self-managed VoIP call).
        var ok = FridayA11yService.instance?.clickCallButton(
            listOf("com.android.systemui:id/end_call_btn"),
            listOf("Hang up", "End call", "Leave call")
        ) ?: false
        if (!ok) {
            val tm = telecom(ctx)
            if (hasAnswerPerm(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && tm != null) {
                try { ok = tm.endCall() } catch (e: Exception) {
                    Log.w(ListenerService.TAG, "endCall(hangup): ${e.message}")
                }
            }
        }
        Log.i(ListenerService.TAG, "CallController.hangup -> $ok")
    }

    /**
     * Turn on speakerphone after answering. On a Samsung native call the dialer owns audio routing,
     * so the AudioManager API alone won't move it to the loudspeaker — the reliable way is to tap the
     * dialer's own "Speaker" button (same approach as hang-up). We tap it ONCE, the moment the in-call
     * UI shows the button (retrying only while it's not yet found, so we never toggle it back off), and
     * fall back to the audio API only if the button never appears.
     */
    @Volatile private var lastSpeakerOn = 0L

    private fun speakerOn(ctx: Context) {
        // A command can be heard twice (lingering audio) -> answer() runs twice -> two Speaker taps
        // would TOGGLE the speaker back off. Only run one speaker sequence per call window.
        val now = SystemClock.elapsedRealtime()
        if (now - lastSpeakerOn < 12_000L) return
        lastSpeakerOn = now
        val h = Handler(Looper.getMainLooper())
        fun attempt(n: Int) {
            val ok = FridayA11yService.instance?.clickCallButton(
                emptyList(), listOf("Speaker", "Speakerphone")
            ) ?: false
            if (ok) { Log.i(ListenerService.TAG, "speaker: tapped Speaker button"); return }
            if (n < 4) {
                h.postDelayed({ attempt(n + 1) }, 900)
            } else {
                setSpeaker(ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                Log.w(ListenerService.TAG, "speaker: UI button not found, tried audio API")
            }
        }
        h.postDelayed({ attempt(0) }, 1000)
    }

    @Suppress("DEPRECATION")
    private fun setSpeaker(am: AudioManager?) {
        if (am == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val spk = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (spk != null) am.setCommunicationDevice(spk) else am.isSpeakerphoneOn = true
            } else {
                am.isSpeakerphoneOn = true
            }
        } catch (_: Exception) {}
    }
}
