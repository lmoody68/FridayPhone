package com.macvision.fridayphone

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Friday's mouth. A single on-device TextToSpeech engine that speaks a short confirmation when a
 * call action fires ("Connecting now, sir." / "Call disconnected, sir." / "Sending to voicemail,
 * sir."). British English, a female voice where the device has one.
 *
 * The device default engine on this A15 is Samsung TTS, which REFUSES to bind to a third-party app
 * ("not allowed to bind to private engine [-4]"), so we ask for Google TTS explicitly and fall back
 * to the default only if Google is unavailable. Anything spoken before the engine is ready is queued.
 */
object Voice {

    private const val GOOGLE_TTS = "com.google.android.tts"

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayList<String>()

    // Half-duplex ("one mouth"): while Friday is speaking — plus a short tail for the speaker echo to
    // die — the listener must ignore the mic, or she hears her own confirmation ("…say Friday stop to
    // cancel") and acts on it. isSpeaking() is checked at the top of ListenerService.scan().
    private const val ECHO_TAIL_MS = 900L
    @Volatile private var speakingNow = false
    @Volatile private var quietUntil = 0L

    fun isSpeaking(): Boolean = speakingNow || SystemClock.elapsedRealtime() < quietUntil

    fun init(ctx: Context) {
        if (tts != null) return
        initWith(ctx.applicationContext, GOOGLE_TTS)
    }

    private fun initWith(appCtx: Context, engine: String?) {
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                configure()
                ready = true
                synchronized(pending) { pending.forEach { utter(it) }; pending.clear() }
                Log.i(ListenerService.TAG, "Friday voice ready (engine=${engine ?: "default"})")
            } else {
                Log.w(ListenerService.TAG, "TTS init failed ($status) engine=${engine ?: "default"}")
                if (engine != null) {                       // Google unavailable → try the device default once
                    try { tts?.shutdown() } catch (_: Exception) {}
                    tts = null
                    initWith(appCtx, null)
                }
            }
        }
        tts = if (engine != null) TextToSpeech(appCtx, listener, engine) else TextToSpeech(appCtx, listener)
    }

    private fun configure() {
        val t = tts ?: return
        try {
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { speakingNow = true }
                override fun onDone(id: String?) { speakingNow = false; quietUntil = SystemClock.elapsedRealtime() + ECHO_TAIL_MS }
                @Deprecated("deprecated base") override fun onError(id: String?) { speakingNow = false; quietUntil = SystemClock.elapsedRealtime() + ECHO_TAIL_MS }
                override fun onError(id: String?, code: Int) { speakingNow = false; quietUntil = SystemClock.elapsedRealtime() + ECHO_TAIL_MS }
            })
        } catch (_: Exception) {}
        try { t.language = Locale.UK } catch (_: Exception) {}
        try {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    // Media usage stays audible on the loudspeaker; the assistant channel gets ducked
                    // or routed to the earpiece the moment a call connects, so the confirmation was silent.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) {}
        try {
            val v = t.voices?.firstOrNull {
                (it.locale?.language == "en") &&
                    (it.name?.contains("gb", true) == true) &&
                    (it.name?.contains("female", true) == true || it.name?.contains("fml", true) == true)
            }
            if (v != null) t.voice = v
        } catch (_: Exception) {}
    }

    fun say(text: String) {
        if (!ready) { synchronized(pending) { pending.add(text) }; return }
        utter(text)
    }

    private fun utter(text: String) {
        speakingNow = true   // close the gap before onStart fires so scan() ignores the very first word
        quietUntil = SystemClock.elapsedRealtime() + 2500L
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "friday") } catch (_: Exception) { speakingNow = false }
    }

    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }
}
