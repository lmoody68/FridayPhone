package com.macvision.fridayphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
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
 * HANG UP (during a call): Vosk fed by a dedicated AudioRecord on the VOICE_COMMUNICATION source. MEASURED
 * 2026-11-23 on this A15: during a Google Voice (VoIP) call, VOICE_COMMUNICATION delivers the user's voice
 * (peak ~13537) while MIC/VOICE_RECOGNITION are faint and a cellular call silences everything. So we capture
 * VOICE_COMMUNICATION mid-call and run the same grammar/recognizer. We switch engines on call state.
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

    // In-call engine: our own AudioRecord on VOICE_COMMUNICATION feeding a Vosk recognizer.
    @Volatile private var inCallRunning = false
    private var inCallThread: Thread? = null
    private var inCallAudio: AudioRecord? = null
    private var inCallRecognizer: Recognizer? = null

    private var lastAnswer = 0L
    private var lastHangup = 0L
    private var lastVoicemail = 0L

    // Require the wake word "friday" + one of these, so the TV / ambient speech can't trip it.
    private val answerActions = listOf("answer", "pick up", "take the call")
    private val hangupActions = listOf("hang up", "hangup", "end call", "end the call", "disconnect", "drop the call", "drop")
    private val voicemailActions = listOf("voicemail", "send to voice", "send it to voice", "send them to voice", "voice mail")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Voice.init(this)
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
        if (isCallActive()) { inCall = true; startInCallListener() } else { inCall = false; startVoskEngine() }
    }

    private fun stopAll() {
        stopVosk()
        stopInCallListener()
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
            startInCallListener()
            updateNotif("On a call — say \"Friday hang up\" to end it.")
            Log.i(TAG, "Call active -> in-call Vosk (VOICE_COMMUNICATION)")
        } else if (!active && inCall) {
            inCall = false
            stopInCallListener()
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

    // ── In-call listener: Vosk on a VOICE_COMMUNICATION AudioRecord (hears DURING a VoIP call) ──
    private fun ensureModel(cb: (Model) -> Unit) {
        val m = voskModel
        if (m != null) { cb(m); return }
        if (!hasVoskModel()) { Log.w(TAG, "No Vosk model for in-call -> SR fallback"); startSR(); return }
        StorageService.unpack(
            this, MODEL_ASSET, "model",
            { model -> voskModel = model; cb(model) },
            { e -> Log.w(TAG, "In-call model unpack failed: ${e.message} -> SR"); startSR() }
        )
    }

    @SuppressLint("MissingPermission")
    private fun startInCallListener() {
        ensureModel { model ->
            try {
                stopInCallListener()
                val rate = SAMPLE_RATE.toInt()
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(rate)
                val audio = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "In-call AudioRecord init failed -> SR fallback")
                    try { audio.release() } catch (_: Exception) {}
                    startSR(); return@ensureModel
                }
                val rec = Recognizer(model, SAMPLE_RATE, inCallGrammarJson())
                inCallAudio = audio
                inCallRecognizer = rec
                inCallRunning = true
                audio.startRecording()
                val t = Thread {
                    val buf = ShortArray(2048)
                    try {
                        while (inCallRunning) {
                            val n = audio.read(buf, 0, buf.size)
                            if (n > 0) {
                                if (rec.acceptWaveForm(buf, n)) scan(extract(rec.result, "text"))
                                else scan(extract(rec.partialResult, "partial"))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "In-call loop error: ${e.message}")
                    }
                }
                inCallThread = t
                t.start()
                Log.i(TAG, "In-call Vosk listener active (VOICE_COMMUNICATION)")
            } catch (e: Exception) {
                Log.w(TAG, "In-call listener start failed: ${e.message} -> SR fallback")
                startSR()
            }
        }
    }

    private fun stopInCallListener() {
        inCallRunning = false
        try { inCallThread?.join(500) } catch (_: Exception) {}
        inCallThread = null
        try { inCallAudio?.stop() } catch (_: Exception) {}
        try { inCallAudio?.release() } catch (_: Exception) {}
        inCallAudio = null
        try { inCallRecognizer?.close() } catch (_: Exception) {}
        inCallRecognizer = null
    }

    // ── SpeechRecognizer (legacy fallback only; silenced mid-call on this device) ──────────────
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
            if (answerActions.any { text.contains(it) } || hangupActions.any { text.contains(it) } ||
                voicemailActions.any { text.contains(it) })
                Log.i(TAG, "ignored (no wake word): \"$text\"")
            return
        }
        Log.i(TAG, "heard: \"$text\"")
        if (inCall) {
            // During a call only HANG UP is meaningful; ignore "answer" so garbled call audio
            // can't fire the wrong command (and the in-call grammar holds hang-up phrases only).
            if (hangupActions.any { text.contains(it) }) maybeFire(ACTION_HANGUP)
            return
        }
        // Not in a call (idle or a call ringing): answer / send-to-voicemail / hang up.
        // Check voicemail first — "send to voicemail" must not be swallowed by another match.
        when {
            voicemailActions.any { text.contains(it) } -> maybeFire(ACTION_VOICEMAIL)
            hangupActions.any { text.contains(it) } -> maybeFire(ACTION_HANGUP)
            answerActions.any { text.contains(it) } -> maybeFire(ACTION_ANSWER)
        }
    }

    // ── Fire the command to MacroDroid ───────────────────────────────────────
    private fun maybeFire(action: String) {
        val now = SystemClock.elapsedRealtime()
        val last = when (action) {
            ACTION_ANSWER -> lastAnswer
            ACTION_VOICEMAIL -> lastVoicemail
            else -> lastHangup
        }
        if (now - last < COOLDOWN_MS) return
        when (action) {
            ACTION_ANSWER -> lastAnswer = now
            ACTION_VOICEMAIL -> lastVoicemail = now
            else -> lastHangup = now
        }
        fire(action)
    }

    private fun fire(action: String) {
        Log.i(TAG, "Wake-word -> $action")
        Handler(Looper.getMainLooper()).post {
            val label = when (action) {
                ACTION_ANSWER -> "ANSWER"
                ACTION_VOICEMAIL -> "VOICEMAIL"
                else -> "HANG UP"
            }
            Toast.makeText(applicationContext, "Friday heard: $label", Toast.LENGTH_SHORT).show()
        }
        sendCommand(applicationContext, action)
    }

    companion object {
        const val TAG = "FridayPhone"
        const val CHANNEL_ID = "friday_listener"
        const val NOTIF_ID = 1001
        const val ACTION_ANSWER = "com.macvision.fridayphone.ANSWER"
        const val ACTION_HANGUP = "com.macvision.fridayphone.HANGUP"
        const val ACTION_VOICEMAIL = "com.macvision.fridayphone.VOICEMAIL"

        const val MODEL_ASSET = "model-en-us"
        const val SAMPLE_RATE = 16000.0f
        const val COOLDOWN_MS = 3000L
        const val RETRY_MS = 2500L

        // Only "friday <action>" phrases (+ [unk] to absorb everything else) so ambient audio doesn't map
        // onto a bare command. Matching ALSO requires "friday" + an action word (see scan()).
        val GRAMMAR = listOf(
            "friday answer the call", "friday answer", "friday pick up", "friday take the call",
            "friday hang up", "friday hang up now", "friday hang up the call",
            "friday end the call", "friday end call", "friday disconnect",
            "friday disconnect the call", "friday disconnect call", "friday drop the call",
            "friday send to voicemail", "friday send it to voicemail", "friday send them to voicemail",
            "friday voicemail", "friday send the call to voicemail",
            "hey friday answer the call", "hey friday answer", "hey friday pick up",
            "hey friday hang up", "hey friday hang up the call", "hey friday end the call",
            "hey friday disconnect", "hey friday disconnect the call", "hey friday drop the call",
            "hey friday send to voicemail", "hey friday send it to voicemail", "hey friday voicemail",
            "[unk]"
        )

        // During a call, hang-up phrases ONLY (+ [unk]) — so garbled call audio can't collapse onto
        // an "answer" phrase. The recognizer's whole search space is hang-up variants.
        val IN_CALL_GRAMMAR = listOf(
            "friday hang up", "friday hang up now", "friday hang up the call",
            "friday end the call", "friday end call", "friday disconnect",
            "friday disconnect the call", "friday disconnect call", "friday drop the call",
            "hey friday hang up", "hey friday hang up the call", "hey friday hang up now",
            "hey friday end the call", "hey friday disconnect", "hey friday disconnect the call",
            "hey friday drop the call",
            "[unk]"
        )

        private fun grammarJson(): String = GRAMMAR.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        private fun inCallGrammarJson(): String = IN_CALL_GRAMMAR.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        /** Perform the call action on the phone itself — Telecom API + Friday's accessibility taps. */
        fun sendCommand(ctx: Context, action: String) {
            when (action) {
                ACTION_ANSWER -> CallController.answer(ctx)
                ACTION_HANGUP -> CallController.hangup(ctx)
                ACTION_VOICEMAIL -> CallController.voicemail(ctx)
            }
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
