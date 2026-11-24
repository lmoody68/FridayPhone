package com.macvision.fridayphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
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
 * Friday Phone — the "ears" (dual-engine, wake-word gated).
 *
 * ANSWER (the ring): Vosk, offline — hears the phrase over the ringtone (mic free until the call connects).
 * HANG UP (during a call): system SpeechRecognizer — a privileged service that can hear mid-call when a
 * third-party app's own AudioRecord (Vosk) cannot. We switch engines on call state (AudioManager mode).
 *
 * To act, the recognized text must contain the wake word "friday" AND a command word — otherwise ambient
 * audio (a TV) trips the tiny grammar. The Vosk grammar itself only holds "friday <action>" phrases + [unk].
 */
class ListenerService : Service() {

    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskService: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
    private var running = false

    private var inCall = false
    private var audioManager: AudioManager? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null

    private var lastAnswer = 0L
    private var lastHangup = 0L

    // Require the wake word "friday" + one of these, so the TV / ambient speech can't trip it.
    private val answerActions = listOf("answer", "pick up", "take the call")
    private val hangupActions = listOf("hang up", "hangup", "end call", "end the call", "disconnect", "drop the call", "drop")

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
        unregisterCallDetector()
        stopAll()
        super.onDestroy()
    }

    // ── Foreground notification (microphone type) ──────────────────────────────────────
    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Friday listening", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Friday is listening for your wake-word."
            nm.createNotificationChannel(ch)
        }
        showNotif(buildNotif("Say \"Friday answer the call\" to pick up on speaker."), foreground = true)
    }

    private fun buildNotif(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Friday is listening")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showNotif(n: Notification, foreground: Boolean = false) {
        if (foreground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } else {
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
            } catch (_: Exception) {}
        }
    }

    private fun updateNotif(text: String) = showNotif(buildNotif(text))

    // ── Listening lifecycle ─────────────────────────────────────────────────
    private fun startListening() {
        if (running) return
        running = true
        registerCallDetector()
        if (isCallActive()) { inCall = true; startSR() } else { inCall = false; startVoskEngine() }
    }

    private fun stopAll() {
        stopVosk()
        stopSR()
    }

    private fun startVoskEngine() {
        if (!running || inCall) return
        if (hasVoskModel()) startVosk() else startSR()
    }

    // ── Call detection via audio mode (no extra permission) ────────────────────────────
    private fun registerCallDetector() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val l = AudioManager.OnModeChangedListener { mode -> onAudioMode(mode) }
            modeListener = l
            try { audioManager?.addOnModeChangedListener(mainExecutor, l) } catch (_: Exception) {}
        }
    }

    private fun unregisterCallDetector() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeListener?.let { try { audioManager?.removeOnModeChangedListener(it) } catch (_: Exception) {} }
        }
        modeListener = null
    }

    private fun isCallActive(): Boolean {
        val m = audioManager?.mode ?: AudioManager.MODE_NORMAL
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun onAudioMode(mode: Int) {
        val active = (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION)
        if (active && !inCall) {
            inCall = true
            stopVosk()
            startSR()
            updateNotif("On a call — say \"Friday hang up\" to end it.")
            Log.i(TAG, "Call active -> SpeechRecognizer (hang-up mode)")
        } else if (!active && inCall) {
            inCall = false
            stopSR()
            if (running) startVoskEngine()
            updateNotif("Say \"Friday answer the call\" to pick up on speaker.")
            Log.i(TAG, "Call ended -> Vosk (answer mode)")
        }
    }

    // ── Vosk (offline; answers the ring) ────────────────────────────────────────
    private fun hasVoskModel(): Boolean =
        try { !(assets.list(MODEL_ASSET).isNullOrEmpty()) } catch (_: Exception) { false }

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
                    startSR()
                }
            },
            { e ->
                Log.w(TAG, "Vosk model unpack failed, falling back: ${e.message}")
                startSR()
            }
        )
    }

    private fun armVosk(model: Model) {
        try { voskService?.shutdown() } catch (_: Exception) {}
        voskService = null
        try { voskRecognizer?.close() } catch (_: Exception) {}
        val rec = Recognizer(model, SAMPLE_RATE, grammarJson())
        voskRecognizer = rec
        val svc = SpeechService(rec, SAMPLE_RATE)
        voskService = svc
        svc.startListening(voskCallback)
    }

    private fun stopVosk() {
        try { voskService?.stop() } catch (_: Exception) {}
        try { voskService?.shutdown() } catch (_: Exception) {}
        voskService = null
        try { voskRecognizer?.close() } catch (_: Exception) {}
        voskRecognizer = null
    }

    private val voskCallback = object : VoskListener {
        override fun onPartialResult(hypothesis: String?) = scan(extract(hypothesis, "partial"))
        override fun onResult(hypothesis: String?) = scan(extract(hypothesis, "text"))
        override fun onFinalResult(hypothesis: String?) = scan(extract(hypothesis, "text"))
        override fun onError(e: Exception?) {
            Log.w(TAG, "Vosk error (mic busy?): ${e?.message}")
            retryVosk()
        }
        override fun onTimeout() { }
    }

    private fun retryVosk() {
        voskService = null
        if (!running || inCall) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (!running || inCall || voskService != null) return@postDelayed
            val model = voskModel ?: return@postDelayed
            try { armVosk(model); Log.i(TAG, "Vosk re-armed") }
            catch (e: Exception) { Log.w(TAG, "Vosk re-arm failed: ${e.message}"); retryVosk() }
        }, RETRY_MS)
    }

    // ── SpeechRecognizer (system service; hears DURING a call -> hang-up) ────────────────────
    private fun startSR() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition unavailable on this device")
            return
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(srListener) }
            srListen()
        } catch (e: Exception) {
            Log.w(TAG, "SR start failed: ${e.message}")
        }
    }

    private fun srListen() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { recognizer?.startListening(intent) } catch (_: Exception) { srRestart() }
    }

    private fun srRestart() {
        if (!running) return
        Handler(Looper.getMainLooper()).postDelayed({ srListen() }, 800)
    }

    private fun stopSR() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private val srListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().forEach { scan(it.lowercase()) }
            srRestart()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().forEach { scan(it.lowercase()) }
        }
        override fun onError(error: Int) = srRestart()
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun extract(hypothesis: String?, key: String): String =
        (try { JSONObject(hypothesis ?: "{}").optString(key, "") } catch (_: Exception) { "" }).lowercase()

    /** Fire only on wake word "friday" + a command; log what we heard so false matches are diagnosable. */
    private fun scan(text: String) {
        if (text.isBlank()) return
        if (!text.contains("friday")) {
            if (answerActions.any { text.contains(it) } || hangupActions.any { text.contains(it) })
                Log.i(TAG, "ignored (no wake word): \"$text\"")
            return
        }
        Log.i(TAG, "heard: \"$text\"")
        when {
            hangupActions.any { text.contains(it) } -> maybeFire(ACTION_HANGUP)
            answerActions.any { text.contains(it) } -> maybeFire(ACTION_ANSWER)
        }
    }

    // ── Fire the command to MacroDroid ───────────────────────────────────────
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

        const val MODEL_ASSET = "model-en-us"
        const val SAMPLE_RATE = 16000.0f
        const val COOLDOWN_MS = 3000L
        const val RETRY_MS = 2500L

        // Only "friday <action>" phrases (+ [unk] to absorb everything else) so ambient audio doesn't map
        // onto a bare command. Matching ALSO requires "friday" + an action word (see scan()).
        val GRAMMAR = listOf(
            "friday answer the call", "friday answer", "friday pick up", "friday take the call",
            "friday hang up", "friday hang up now", "friday end the call", "friday end call",
            "friday disconnect", "friday disconnect the call", "friday drop the call",
            "[unk]"
        )

        private fun grammarJson(): String = GRAMMAR.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

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
