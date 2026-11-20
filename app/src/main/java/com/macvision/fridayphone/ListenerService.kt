package com.macvision.fridayphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import org.vosk.android.RecognitionListener as VoskListener

/**
 * Friday Phone — the "ears".
 *
 * A microphone foreground service that listens OFFLINE for a wake phrase via **Vosk** (Apache-2.0, no
 * account, no cloud — unlike Picovoice, which now gates its free tier behind commercial approval). When it
 * hears the phrase it broadcasts an intent that MacroDroid's "Intent Received" trigger catches and runs the
 * proven answer-on-speaker / end-call macros. Keeping the telephony in MacroDroid means this app only has to
 * listen and poke — minimal surface, minimal bugs.
 *
 *   "Friday answer the call"  -> ACTION_ANSWER  (answer + speaker)
 *   "Friday hang up"          -> ACTION_HANGUP  (end call)  — see the hard limit below
 *
 * HARD ANDROID LIMIT: no third-party app can read the mic DURING an active call (telephony owns it), so
 * voice hang-up only works while you are NOT already connected. The reliable hang-up is **shake-to-hang-up**,
 * which is a MacroDroid Shake trigger (a sensor, no mic) and works mid-call. Design = speak to answer, shake
 * to hang up. When a call connects the mic goes busy and Vosk errors; we simply re-arm so we're listening
 * again the instant the call ends.
 *
 * If the Vosk model isn't bundled (e.g. a local build without the CI fetch step), it falls back to Android's
 * on-device SpeechRecognizer so the pipeline is still testable — but that fallback cannot hear during a ring.
 */
class ListenerService : Service() {

    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskService: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
    private var running = false

    // A spoken phrase arrives across many partial frames — cool down so it fires once, not on every frame.
    private var lastAnswer = 0L
    private var lastHangup = 0L

    private val answerPhrases = listOf("answer the call", "answer call", "friday answer", "pick up", "take the call")
    private val hangupPhrases = listOf("hang up", "hangup", "end the call", "end call", "disconnect", "drop the call")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        Prefs(this).listening = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopListening()
        super.onDestroy()
    }

    // ── Foreground notification (microphone type) ───────────────────────────────────────────────
    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Friday listening", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Friday is listening for your wake-word."
            nm.createNotificationChannel(ch)
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Friday is listening")
            .setContentText("Say \"Friday answer the call\" to pick up on speaker.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ── Listening ───────────────────────────────────────────────────────────────────────────────
    private fun startListening() {
        if (running) return
        running = true
        if (hasVoskModel()) {
            startVosk()
        } else {
            Log.w(TAG, "Vosk model missing — using SpeechRecognizer fallback")
            startSpeechRecognizer()
        }
    }

    private fun stopListening() {
        try { voskService?.stop() } catch (_: Exception) {}
        try { voskService?.shutdown() } catch (_: Exception) {}
        voskService = null
        try { voskRecognizer?.close() } catch (_: Exception) {}
        voskRecognizer = null
        try { voskModel?.close() } catch (_: Exception) {}
        voskModel = null
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    // ── Vosk (offline, production) ──────────────────────────────────────────────────────────────
    private fun hasVoskModel(): Boolean =
        try { !(assets.list(MODEL_ASSET).isNullOrEmpty()) } catch (_: Exception) { false }

    /** Unpack the bundled model once (assets -> internal storage), then start continuous recognition. */
    private fun startVosk() {
        StorageService.unpack(
            this, MODEL_ASSET, "model",
            { model ->
                try {
                    voskModel = model
                    armVosk(model)
                    Log.i(TAG, "Vosk wake-word active")
                } catch (e: Exception) {
                    Log.w(TAG, "Vosk start failed, falling back: ${e.message}")
                    startSpeechRecognizer()
                }
            },
            { e ->
                Log.w(TAG, "Vosk model unpack failed, falling back: ${e.message}")
                startSpeechRecognizer()
            }
        )
    }

    /** (Re)build a grammar-restricted recognizer + capture service and start listening. */
    private fun armVosk(model: Model) {
        try { voskService?.shutdown() } catch (_: Exception) {}
        voskService = null
        try { voskRecognizer?.close() } catch (_: Exception) {}
        // A tight grammar (only our phrases + "[unk]") makes keyword spotting fast and accurate.
        val rec = Recognizer(model, SAMPLE_RATE, grammarJson())
        voskRecognizer = rec
        val svc = SpeechService(rec, SAMPLE_RATE)
        voskService = svc
        svc.startListening(voskCallback)
    }

    private val voskCallback = object : VoskListener {
        override fun onPartialResult(hypothesis: String?) = scan(extract(hypothesis, "partial"))
        override fun onResult(hypothesis: String?) = scan(extract(hypothesis, "text"))
        override fun onFinalResult(hypothesis: String?) = scan(extract(hypothesis, "text"))
        override fun onError(e: Exception?) {
            // Most common cause: the mic went busy because a call CONNECTED (telephony owns it). Expected —
            // voice hang-up mid-call is impossible; shake-to-hang-up (MacroDroid) covers it. Re-arm so we're
            // listening again the moment the mic frees up (the call ends).
            Log.w(TAG, "Vosk error (mic busy?): ${e?.message}")
            retryVosk()
        }
        override fun onTimeout() { /* continuous mode: nothing to do */ }
    }

    private fun extract(hypothesis: String?, key: String): String =
        (try { JSONObject(hypothesis ?: "{}").optString(key, "") } catch (_: Exception) { "" }).lowercase()

    private fun scan(text: String) {
        if (text.isBlank()) return
        when {
            answerPhrases.any { text.contains(it) } -> maybeFire(ACTION_ANSWER)
            hangupPhrases.any { text.contains(it) } -> maybeFire(ACTION_HANGUP)
        }
    }

    private fun retryVosk() {
        voskService = null
        if (!running) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (!running || voskService != null) return@postDelayed
            val model = voskModel ?: return@postDelayed
            try {
                armVosk(model)
                Log.i(TAG, "Vosk re-armed")
            } catch (e: Exception) {
                Log.w(TAG, "Vosk re-arm failed, will retry: ${e.message}")
                retryVosk()
            }
        }, RETRY_MS)
    }

    // ── SpeechRecognizer (no-model fallback; CANNOT hear during a ring) ───────────────────────────
    private fun startSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition unavailable on this device")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        listen()
    }

    private fun listen() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { recognizer?.startListening(intent) } catch (_: Exception) { restart() }
    }

    private fun restart() {
        if (!running) return
        Handler(Looper.getMainLooper()).postDelayed({ listen() }, 800)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val hits = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map { it.lowercase() }
            when {
                hits.any { h -> hangupPhrases.any { h.contains(it) } } -> maybeFire(ACTION_HANGUP)
                hits.any { h -> answerPhrases.any { h.contains(it) } } -> maybeFire(ACTION_ANSWER)
            }
            restart()
        }
        override fun onError(error: Int) = restart()
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Fire the command to MacroDroid ──────────────────────────────────────────────────────────
    private fun maybeFire(action: String) {
        val now = SystemClock.elapsedRealtime()
        if (action == ACTION_ANSWER) {
            if (now - lastAnswer < COOLDOWN_MS) return
            lastAnswer = now
        } else {
            if (now - lastHangup < COOLDOWN_MS) return
            lastHangup = now
        }
        fire(action)
    }

    private fun fire(action: String) {
        Log.i(TAG, "Wake-word -> $action")
        Handler(Looper.getMainLooper()).post {
            val label = if (action == ACTION_ANSWER) "ANSWER" else "HANG UP"
            Toast.makeText(applicationContext, "Friday heard: $label", Toast.LENGTH_SHORT).show()
        }
        sendCommand(applicationContext, action)
    }

    companion object {
        const val TAG = "FridayPhone"
        const val CHANNEL_ID = "friday_listener"
        const val NOTIF_ID = 1001
        const val MACRODROID_PKG = "com.arlosoft.macrodroid"
        const val ACTION_ANSWER = "com.macvision.fridayphone.ANSWER"
        const val ACTION_HANGUP = "com.macvision.fridayphone.HANGUP"

        /** Folder in assets/ holding the unpacked Vosk model (populated by the CI build step). */
        const val MODEL_ASSET = "model-en-us"
        const val SAMPLE_RATE = 16000.0f
        const val COOLDOWN_MS = 3000L      // one phrase fires once, not on every partial frame
        const val RETRY_MS = 2500L         // re-arm cadence while the mic is busy (during a call)

        /** The only utterances Vosk will decode — keeps keyword spotting fast and false-positives rare. */
        val GRAMMAR = listOf(
            "friday answer the call", "answer the call", "answer call", "pick up", "take the call",
            "friday hang up", "friday hang up now", "hang up", "end the call", "end call", "disconnect",
            "[unk]"
        )

        private fun grammarJson(): String = GRAMMAR.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        /** Fire a command to MacroDroid: explicit broadcast to its package, plus an implicit one as backup. */
        fun sendCommand(ctx: Context, action: String) {
            ctx.sendBroadcast(Intent(action).setPackage(MACRODROID_PKG))
            ctx.sendBroadcast(Intent(action))
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, ListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            Prefs(ctx).listening = false
            ctx.stopService(Intent(ctx, ListenerService::class.java))
        }
    }
}
