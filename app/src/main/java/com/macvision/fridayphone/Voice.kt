package com.macvision.fridayphone

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Friday's mouth. A single on-device TextToSpeech engine that speaks a short confirmation when a
 * call action fires ("Connecting now, sir." / "Call disconnected, sir." / "Sending to voicemail,
 * sir."). British English, a female voice where the device has one — to match Friday. Anything
 * spoken before the engine is ready is queued and flushed on init.
 */
object Voice {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayList<String>()

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts
                try { t?.language = Locale.UK } catch (_: Exception) {}
                try {
                    t?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                } catch (_: Exception) {}
                try {
                    val v = t?.voices?.firstOrNull {
                        (it.locale?.language == "en") &&
                            (it.name?.contains("gb", true) == true) &&
                            (it.name?.contains("female", true) == true || it.name?.contains("fml", true) == true)
                    }
                    if (v != null) t?.voice = v
                } catch (_: Exception) {}
                ready = true
                synchronized(pending) { pending.forEach { utter(it) }; pending.clear() }
                Log.i(ListenerService.TAG, "Friday voice ready")
            } else {
                Log.w(ListenerService.TAG, "TTS init failed: $status")
            }
        }
    }

    fun say(text: String) {
        if (!ready) { synchronized(pending) { pending.add(text) }; return }
        utter(text)
    }

    private fun utter(text: String) {
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "friday") } catch (_: Exception) {}
    }

    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }
}
