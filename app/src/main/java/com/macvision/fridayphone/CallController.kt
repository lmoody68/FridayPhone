package com.macvision.fridayphone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

    // Friday's accessibility taps are confined to these packages — real call screens only, never
    // whatever app happens to be in the foreground (e.g. the Claude app).
    private val CALL_PKGS = listOf(
        "com.google.android.apps.googlevoice",
        "com.samsung.android.incallui", "com.android.incallui",
        "com.samsung.android.dialer", "com.android.dialer",
        "com.android.systemui"
    )

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
                listOf("Accept", "Answer"), CALL_PKGS
            ) ?: false
        }
        resetSpeakerForNewCall()    // new call window — allow a fresh speaker sequence
        speakerOn(ctx)              // early kick; the audio-mode "call active" hook retries until it sticks
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
                listOf("End call", "Decline", "Ignore", "Reject"), CALL_PKGS, allowEndish = true
            ) ?: false
        }
        Log.i(ListenerService.TAG, "CallController.voicemail -> $ok")
    }

    @SuppressLint("MissingPermission")
    fun hangup(ctx: Context) {
        Voice.init(ctx); Voice.say("Call disconnected, sir.")
        // Google Voice VoIP first: tap its own "Hang up" (endCall() can't end a self-managed VoIP call).
        var ok = FridayA11yService.instance?.clickCallButton(
            listOf("com.android.systemui:id/end_call_btn", "com.android.incallui:id/end_button",
                   "com.samsung.android.incallui:id/end_button"),
            listOf("Hang up", "End call", "Leave call", "End"), CALL_PKGS, allowEndish = true
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
     * Place an OUTBOUND call by voice ("Friday call Chris"). Fires ACTION_CALL (needs CALL_PHONE),
     * then turns the speaker on so it's hands-free like an answered call. The confirmation +
     * cancel window happen in ListenerService BEFORE this is called, so by here we are dialing.
     */
    @SuppressLint("MissingPermission")
    fun callOut(ctx: Context, number: String, name: String) {
        Voice.init(ctx)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Voice.say("I don't have permission to place calls yet, sir.")
            Log.w(ListenerService.TAG, "callOut: CALL_PHONE not granted")
            return
        }
        try {
            val i = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            Log.i(ListenerService.TAG, "CallController.callOut -> dialing $name")
            resetSpeakerForNewCall()    // new call — allow a fresh speaker sequence
            speakerOn(ctx)              // early kick; the audio-mode "call active" hook retries until it sticks
        } catch (e: Exception) {
            Log.w(ListenerService.TAG, "callOut: ${e.message}")
            Voice.say("I couldn't place the call, sir.")
        }
    }

    /**
     * Turn on speakerphone after answering. On a Samsung native call the dialer owns audio routing,
     * so the AudioManager API alone won't move it to the loudspeaker — the reliable way is to tap the
     * dialer's own "Speaker" button (same approach as hang-up). We tap it ONCE, the moment the in-call
     * UI shows the button (retrying only while it's not yet found, so we never toggle it back off), and
     * fall back to the audio API only if the button never appears.
     */
    // Speaker is confirmed ON at most ONCE per call (a second successful tap would TOGGLE it back off),
    // but we KEEP RETRYING within a call until that one success — the in-call control bar can take several
    // seconds to render (especially on an OUTBOUND dial, where the old +1s/3.7s window stabbed at the
    // still-dialing screen and never found the button). speakerOn() is therefore safe to call repeatedly:
    // as an early kick from answer()/callOut() AND, reliably, from the audio-mode "call active" transition
    // (when the UI is definitely up). resetSpeakerForNewCall() clears the per-call latch.
    @Volatile private var speakerConfirmed = false
    @Volatile private var speakerSeqRunning = false

    fun resetSpeakerForNewCall() { speakerConfirmed = false; speakerSeqRunning = false }

    // Resource-ids seen for the speaker toggle across Samsung/AOSP dialers (exact-id match is most reliable).
    private val SPEAKER_IDS = listOf(
        "com.samsung.android.incallui:id/speaker", "com.samsung.android.incallui:id/btn_speaker",
        "com.android.incallui:id/speakerButton", "com.android.incallui:id/audioButton",
        "com.android.dialer:id/speakerButton", "com.google.android.dialer:id/speakerButton"
    )

    fun speakerOn(ctx: Context) {
        if (speakerConfirmed || speakerSeqRunning) return   // one success per call; never stack sequences
        speakerSeqRunning = true
        val h = Handler(Looper.getMainLooper())
        fun attempt(n: Int) {
            if (speakerConfirmed) { speakerSeqRunning = false; return }
            val ok = FridayA11yService.instance?.clickCallButton(
                SPEAKER_IDS,
                listOf("Speaker", "Speakerphone", "Speaker on", "Turn on speaker", "Loudspeaker"),
                CALL_PKGS
            ) ?: false
            if (ok) {
                speakerConfirmed = true; speakerSeqRunning = false
                Log.i(ListenerService.TAG, "speaker: tapped Speaker button (attempt $n)"); return
            }
            if (n < 8) {
                h.postDelayed({ attempt(n + 1) }, 800)   // ~7s of retries — the in-call bar can render slowly
            } else {
                setSpeaker(ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                speakerSeqRunning = false
                Log.w(ListenerService.TAG, "speaker: UI button not found over ~7s, tried audio API")
            }
        }
        h.postDelayed({ attempt(0) }, 600)
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
