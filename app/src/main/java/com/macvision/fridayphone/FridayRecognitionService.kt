package com.macvision.fridayphone

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * A voice-interaction service's config REQUIRES a recognitionService. Friday does its own offline
 * recognition (Vosk) and never routes through this, so this is a valid-but-inert stub that simply
 * reports busy. It exists so the assistant registration is well-formed.
 */
class FridayRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) } catch (_: Exception) {}
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
