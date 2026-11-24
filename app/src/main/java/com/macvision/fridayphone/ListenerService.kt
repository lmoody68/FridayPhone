package com.macvision.fridayphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
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
import org.vosk.android.StorageService

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
    private var recognizer: SpeechRecognizer? = null
    private var running = false

    private var inCall = false
    @Volatile private var ringing = false
    private var audioManager: AudioManager? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var telephony: TelephonyManager? = null
    private var telephonyCb: TelephonyCallback? = null
    private var phoneListener: PhoneStateListener? = null

    // Capture engine: our own AudioRecord feeding a Vosk recognizer (idle = MIC, in-call = VOICE_COMMUNICATION).
    @Volatile private var capRunning = false
    private var capThread: Thread? = null
    private var capAudio: AudioRecord? = null
    private var capRecognizer: Recognizer? = null

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
        unregisterRingDetector()
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
        registerRingDetector()
        if (isCallActive()) { inCall = true; startInCallListener() } else { inCall = false; startIdleListener() }
    }

    private fun stopAll() {
        stopCapture()
        stopSR()
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

    // ── Reliable RINGING detection via telephony call state (audio mode never reports RINGTONE here) ──
    private fun registerRingDetector() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE not granted — ring-gate disabled (grant Phone permission)")
            return
        }
        telephony = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        ringing = (state == TelephonyManager.CALL_STATE_RINGING)
                    }
                }
                telephonyCb = cb
                telephony?.registerTelephonyCallback(mainExecutor, cb)
            } else {
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        ringing = (state == TelephonyManager.CALL_STATE_RINGING)
                    }
                }
                phoneListener = l
                @Suppress("DEPRECATION")
                telephony?.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            }
            Log.i(TAG, "Ring detector registered")
        } catch (e: Exception) {
            Log.w(TAG, "Ring detector register failed: ${e.message}")
        }
    }

    private fun unregisterRingDetector() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCb?.let { telephony?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                phoneListener?.let { telephony?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {}
        telephonyCb = null
        phoneListener = null
    }

    private fun isCallActive(): Boolean {
        val m = audioManager?.mode ?: AudioManager.MODE_NORMAL
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun onAudioMode(mode: Int) {
        // `ringing` is owned by the telephony CallState listener (audio mode never reports RINGTONE on this device).
        val active = (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION)
        if (active && !inCall) {
            inCall = true
            stopCapture()
            startInCallListener()
            updateNotif("On a call — say \"Friday hang up\" to end it.")
            Log.i(TAG, "Call active -> in-call listener (VOICE_COMMUNICATION)")
        } else if (!active && inCall) {
            inCall = false
            stopCapture()
            stopSR()
            if (running) startIdleListener()
            updateNotif("Say \"Friday answer the call\" to pick up on speaker.")
            Log.i(TAG, "Call ended -> idle listener (MIC)")
        }
    }

    // ── Robust capture: OUR OWN AudioRecord loop feeding a Vosk Recognizer, for BOTH the idle
    //    wake-word listener (MIC) and the in-call listener (VOICE_COMMUNICATION). Vosk's bundled
    //    SpeechService could report itself "active" while its recorder silently stopped (no active
    //    record client) — this loop is under our control, logs its state, and re-arms if a read fails.
    private fun hasVoskModel(): Boolean =
        try { !(assets.list(MODEL_ASSET).isNullOrEmpty()) } catch (_: Exception) { false }

    private fun ensureModel(cb: (Model) -> Unit) {
        val m = voskModel
        if (m != null) { cb(m); return }
        if (!hasVoskModel()) { Log.w(TAG, "No Vosk model -> SR fallback"); startSR(); return }
        StorageService.unpack(
            this, MODEL_ASSET, "model",
            { model -> voskModel = model; cb(model) },
            { e -> Log.w(TAG, "Vosk model unpack failed: ${e.message} -> SR"); startSR() }
        )
    }

    /** Idle wake-word listener (not in a connected call): MIC source + full grammar. */
    private fun startIdleListener() {
        if (!running || inCall) return
        startCapture(MediaRecorder.AudioSource.MIC, grammarJson(), "idle")
    }

    /** In-call listener (hears DURING a VoIP call): VOICE_COMMUNICATION source + hang-up grammar. */
    private fun startInCallListener() {
        startCapture(MediaRecorder.AudioSource.VOICE_COMMUNICATION, inCallGrammarJson(), "in-call")
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(source: Int, grammar: String, label: String) {
        ensureModel { model ->
            try {
                stopCapture()
                val rate = SAMPLE_RATE.toInt()
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(rate)
                val audio = AudioRecord(
                    source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "$label AudioRecord init failed -> SR fallback")
                    try { audio.release() } catch (_: Exception) {}
                    startSR(); return@ensureModel
                }
                val rec = Recognizer(model, SAMPLE_RATE, grammar)
                capAudio = audio
                capRecognizer = rec
                capRunning = true
                audio.startRecording()
                val t = Thread {
                    val buf = ShortArray(2048)
                    var faulted = false
                    try {
                        while (capRunning) {
                            val n = audio.read(buf, 0, buf.size)
                            if (n > 0) {
                                if (rec.acceptWaveForm(buf, n)) scan(extract(rec.result, "text"))
                                else scan(extract(rec.partialResult, "partial"))
                            } else if (n < 0) {
                                Log.w(TAG, "$label read error ($n)"); faulted = true; break
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "$label loop error: ${e.message}"); faulted = true
                    }
                    // Ended while we still wanted to listen (a read failed, not a clean stop) -> re-arm.
                    if (faulted && capRunning) {
                        capRunning = false
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!running) return@postDelayed
                            if (inCall) startInCallListener() else startIdleListener()
                        }, RETRY_MS)
                    }
                }
                capThread = t
                t.start()
                Log.i(TAG, "$label listener active (source=$source)")
            } catch (e: Exception) {
                Log.w(TAG, "$label listener start failed: ${e.message} -> SR fallback")
                startSR()
            }
        }
    }

    private fun stopCapture() {
        capRunning = false
        try { capThread?.join(500) } catch (_: Exception) {}
        capThread = null
        try { capAudio?.stop() } catch (_: Exception) {}
        try { capAudio?.release() } catch (_: Exception) {}
        capAudio = null
        try { capRecognizer?.close() } catch (_: Exception) {}
        capRecognizer = null
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
            // The ringtone garbles the wake word, but READ_PHONE_STATE tells us a call is genuinely
            // RINGING. In that short, unambiguous window, act on the command alone — no wake word
            // required. Answer wins ties: a wrongly-answered call is recoverable, a wrongly-declined
            // one is not.
            if (ringing) {
                when {
                    answerActions.any { text.contains(it) } -> { Log.i(TAG, "ring-gate -> ANSWER: \"$text\""); maybeFire(ACTION_ANSWER); return }
                    voicemailActions.any { text.contains(it) } -> { Log.i(TAG, "ring-gate -> VOICEMAIL: \"$text\""); maybeFire(ACTION_VOICEMAIL); return }
                }
            }
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
        // Only act when the phone is actually in the matching call state. Friday is always listening,
        // so without this a command word in ordinary speech (e.g. dictating to another app) fires a
        // phantom action + spoken confirmation ("Connecting now, sir") with no call present.
        val active = inCall || isCallActive()
        val ringingNow = ringing || (audioManager?.mode == AudioManager.MODE_RINGTONE)
        val relevant = if (action == ACTION_HANGUP) active else ringingNow
        if (!relevant) {
            Log.i(TAG, "$action ignored — no call (ringing=$ringingNow active=$active)")
            return
        }
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
        const val COOLDOWN_MS = 6000L   // wide enough that one spoken command, heard repeatedly, fires once
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
