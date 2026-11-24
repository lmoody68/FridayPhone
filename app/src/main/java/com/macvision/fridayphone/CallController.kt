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
        enableSpeakerSoon(ctx)
        Log.i(ListenerService.TAG, "CallController.answer -> $ok")
    }

    @SuppressLint("MissingPermission")
    fun voicemail(ctx: Context) {
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

    /** Route call audio to the loudspeaker. Retried a few times as the call transitions to active. */
    private fun enableSpeakerSoon(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val h = Handler(Looper.getMainLooper())
        for (delay in listOf(500L, 1300L, 2500L)) {
            h.postDelayed({ setSpeaker(am) }, delay)
        }
    }

    @Suppress("DEPRECATION")
    private fun setSpeaker(am: AudioManager) {
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
