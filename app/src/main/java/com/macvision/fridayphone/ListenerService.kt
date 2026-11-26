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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.media.AudioDeviceInfo
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
import org.vosk.SpeakerModel
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

    // Shake-to-hang-up: the mic is silenced mid-call but the accelerometer is NOT. A firm shake ends it.
    private var sensorManager: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private var shakeCount = 0
    private var lastShakeAt = 0L
    private var lastShakeHangup = 0L

    // Capture engine: our own AudioRecord feeding a Vosk recognizer (idle = MIC, in-call = VOICE_COMMUNICATION).
    @Volatile private var capRunning = false
    private var capThread: Thread? = null
    private var capAudio: AudioRecord? = null
    private var capRecognizer: Recognizer? = null
    @Volatile private var capSessionId = -1   // Friday's own mic session, to tell it from a dictation app's

    // ── VOICEPRINT LOCK: only Les's enrolled voice acts (Vosk speaker x-vectors). Falls open until enrolled. ──
    private var voskSpeakerModel: SpeakerModel? = null
    @Volatile private var lastSpk: FloatArray? = null     // the last final utterance's voiceprint vector
    @Volatile private var enrollMode = false
    private val enrollSamples = mutableListOf<FloatArray>()
    private var enrollStartMs = 0L                         // enroll auto-cancels if it stalls (never gets stuck)

    // ── IN-CALL RELAY: "Friday, tell Claude/Jarvis <message>" → open dictation → POST to the bridge. ──
    @Volatile private var relayMode = false
    private var relayRec: Recognizer? = null
    private var relayTarget = "claude"
    private var relayStartMs = 0L

    private var lastAnswer = 0L
    private var lastHangup = 0L
    private var lastVoicemail = 0L

    // Require the wake word "friday" + one of these, so the TV / ambient speech can't trip it.
    private val answerActions = listOf("answer", "pick up", "take the call", "connect", "connect call", "connect the call")
    // NOTE: bare "drop" removed 2026-11-25 — it false-fired a hang-up from far-end/overlapping JARVIS audio
    // on speaker ("friday drop"). Require distinctive phrases; the durable fix is the voiceprint lock below.
    private val hangupActions = listOf("hang up", "hangup", "end call", "end the call", "disconnect", "disconnect the call", "drop the call")
    private val voicemailActions = listOf("voicemail", "send to voice", "send it to voice", "send them to voice", "voice mail")
    private val cancelActions = listOf("stop", "cancel", "never mind", "nevermind", "no")
    // "Friday, who's calling?" — read the caller ID aloud (read-only; ring-gated). Both the contracted
    // and spelled-out forms, so Vosk matches whichever it emits.
    private val whoActions = listOf("who's calling", "whos calling", "who is calling",
        "who is it", "who's it", "who's this", "whos this", "who is this", "caller id")
    private val confirmActions = listOf("yes", "yeah", "yep", "yup")   // NOT "confirm" (it's in Friday's prompt)

    // Outbound calling by voice: name -> number from the phone's own contacts (on-device only).
    @Volatile private var contactMap: Map<String, String> = emptyMap()
    // Reverse map (last-10-digits -> best display name) so "who's calling?" can name a known number
    // even when the ring screen shows only digits. On-device only, never leaves the phone.
    @Volatile private var reverseContactMap: Map<String, String> = emptyMap()
    private val callHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingCallNumber: String? = null
    @Volatile private var pendingCallName: String? = null
    private var pendingDialRunnable: Runnable? = null

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
        VoiceLock.load(this)
        Thread { ensureSpeakerModel() }.start()   // load the voiceprint speaker model off the main thread
        contactMap = Contacts.load(this)   // load phone contacts so "Friday call <name>" works
        // Build number -> name so "Friday, who's calling?" can name a known caller. Prefer the longest
        // name key per number (so we say "Justin Moody", not just "Justin").
        val byNum = HashMap<String, String>()
        for ((name, num) in contactMap) {
            val k = last10(num); if (k.length < 7) continue
            val cur = byNum[k]; if (cur == null || name.length > cur.length) byNum[k] = name
        }
        reverseContactMap = byNum.mapValues { e ->
            e.value.split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
        }
        registerCallDetector()
        registerRingDetector()
        if (isCallActive()) { inCall = true; startInCallListener() } else { inCall = false; startIdleListener() }
    }

    private fun stopAll() {
        stopCapture()
        stopShakeDetector()
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
                        val wasRinging = ringing
                        ringing = (state == TelephonyManager.CALL_STATE_RINGING)
                        if (ringing && !wasRinging) reclaimMicForRing()   // win the mic from Claude Code etc.
                    }
                }
                telephonyCb = cb
                telephony?.registerTelephonyCallback(mainExecutor, cb)
            } else {
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        val wasRinging = ringing
                        ringing = (state == TelephonyManager.CALL_STATE_RINGING)
                        if (ringing && !wasRinging) reclaimMicForRing()   // win the mic from Claude Code etc.
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

    // ── Shake-to-hang-up: a firm shake during a call ends it (the accelerometer is not blocked in-call) ──
    private fun startShakeDetector() {
        if (shakeListener != null) return
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        shakeCount = 0
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val gForce = Math.sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH
                if (Math.abs(gForce) > SHAKE_THRESHOLD) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastShakeAt > SHAKE_WINDOW_MS) shakeCount = 0   // peaks must cluster
                    lastShakeAt = now
                    shakeCount++
                    if (shakeCount >= SHAKE_COUNT && now - lastShakeHangup > 5000) {
                        lastShakeHangup = now
                        shakeCount = 0
                        Log.i(TAG, "SHAKE -> hang up (in-call)")
                        Handler(Looper.getMainLooper()).post { CallController.hangup(applicationContext) }
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        shakeListener = l
        try {
            sensorManager?.registerListener(l, accel, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "shake-to-hang-up detector active")
        } catch (e: Exception) { Log.w(TAG, "shake detector failed: ${e.message}"); shakeListener = null }
    }

    private fun stopShakeDetector() {
        shakeListener?.let { try { sensorManager?.unregisterListener(it) } catch (_: Exception) {} }
        shakeListener = null
        shakeCount = 0
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
            startInCallListener()   // listen on VOICE_RECOGNITION (the one channel the OS leaves live mid-call)
            // Turn the speaker on NOW — the call is active and the in-call control bar is rendered, so the
            // Speaker button actually exists to tap. This is the RELIABLE trigger; answer()/callOut() fire
            // too early on a slow outbound dial. CRITICAL: the in-call ear only hears Les on speaker, so a
            // missed speaker = Friday goes deaf mid-call = she can't hear "disconnect". speakerOn() latches
            // on the first success, so this won't toggle off an already-on speaker from the early kick.
            CallController.speakerOn(applicationContext)
            updateNotif("On a call — say \"Friday disconnect the call\" to hang up.")
            Log.i(TAG, "Call active -> IN-CALL LISTENER (VOICE_RECOGNITION) + speaker")
        } else if (!active && inCall) {
            inCall = false
            stopCapture()
            stopSR()
            CallController.resetSpeakerForNewCall()   // arm a fresh speaker sequence for the next call
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

    // ── Voiceprint helpers ──────────────────────────────────────────────────
    /** Load Vosk's speaker model (copied assets→internal storage) so results carry an "spk" voiceprint. */
    private fun ensureSpeakerModel() {
        if (voskSpeakerModel != null) return
        try {
            if (assets.list(SPK_ASSET).isNullOrEmpty()) { Log.w(TAG, "no speaker model asset -> voiceprint off"); return }
            val dest = java.io.File(filesDir, "model-spk")
            if (!dest.exists() || (dest.list()?.isEmpty() != false)) copyAssetDir(SPK_ASSET, dest)
            voskSpeakerModel = SpeakerModel(dest.absolutePath)
            Log.i(TAG, "voiceprint speaker model loaded (enrolled=${VoiceLock.isEnrolled()})")
        } catch (e: Exception) { Log.w(TAG, "speaker model load failed: ${e.message}") }
    }

    private fun copyAssetDir(assetPath: String, dest: java.io.File) {
        val items = try { assets.list(assetPath) } catch (_: Exception) { null }
        if (items != null && items.isNotEmpty()) {
            dest.mkdirs()
            for (item in items) copyAssetDir("$assetPath/$item", java.io.File(dest, item))
        } else {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
        }
    }

    /** Parse the voiceprint vector from a Vosk final-result JSON ("spk"), or null. */
    private fun extractSpk(json: String?): FloatArray? {
        if (json == null) return null
        return try {
            val arr = org.json.JSONObject(json).optJSONArray("spk") ?: return null
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        } catch (_: Exception) { null }
    }

    /** "Friday enroll my voice" → collect the next few utterances' voiceprints and lock to Les. */
    private fun handleEnrollCommand() {
        if (inCall) { Voice.say("Let's set up your voice when you're off a call, sir."); return }
        if (voskSpeakerModel == null) ensureSpeakerModel()
        enrollMode = true
        enrollStartMs = System.currentTimeMillis()
        enrollSamples.clear()
        Voice.say("Enrolling your voice, sir. Say, hey Friday this is Les, ${VoiceLock.ENROLL_SAMPLES} times, once after each reply.")
        Log.i(TAG, "voiceprint enrollment started")
    }

    private fun collectEnroll(spk: FloatArray?) {
        // Safety: if enrollment stalls (e.g. it was triggered by mistake and nobody completes it), bail out
        // instead of looping "say it again" forever.
        if (System.currentTimeMillis() - enrollStartMs > 25000L) {
            enrollMode = false; enrollSamples.clear()
            Voice.say("Never mind, sir — we can set up your voice another time.")
            Log.i(TAG, "enroll auto-cancelled (timed out)")
            return
        }
        if (spk == null || spk.isEmpty()) { Voice.say("I didn't catch your voice, sir — say it again."); return }
        enrollSamples.add(spk)
        if (enrollSamples.size >= VoiceLock.ENROLL_SAMPLES) {
            VoiceLock.save(applicationContext, VoiceLock.average(enrollSamples))
            enrollMode = false; enrollSamples.clear()
            Voice.say("Your voiceprint is locked in, sir. I'll only answer to your voice now.")
            Log.i(TAG, "voiceprint enrolled")
        } else {
            Voice.say("Got it. ${VoiceLock.ENROLL_SAMPLES - enrollSamples.size} more.")
        }
    }

    // ── In-call relay helpers ───────────────────────────────────────────────
    /** "Friday, tell Claude/Jarvis …" → open an un-grammared recognizer and capture the next utterance. */
    private fun handleRelayCommand(target: String) {
        val model = voskModel
        if (model == null) { Voice.say("My ears aren't ready yet, sir."); return }
        try { relayRec = Recognizer(model, SAMPLE_RATE) }           // no grammar = open dictation
        catch (e: Exception) { Log.w(TAG, "relayRec init: ${e.message}"); Voice.say("I couldn't set that up, sir."); return }
        relayTarget = target
        relayStartMs = System.currentTimeMillis()
        relayMode = true
        Voice.say("Go ahead, sir — what should I tell ${if (target == "jarvis") "Jarvis" else "Claude"}?")
        Log.i(TAG, "relay mode started -> $target")
    }

    /** Feed audio to the open recognizer; send on a final result, or bail after the window. */
    private fun handleRelayAudio(buf: ShortArray, n: Int) {
        val rr = relayRec ?: run { relayMode = false; return }
        if (System.currentTimeMillis() - relayStartMs > RELAY_WINDOW_MS) { endRelay(null); return }
        try {
            if (rr.acceptWaveForm(buf, n)) {
                val msg = extract(rr.result, "text").trim()
                if (msg.isNotEmpty() && msg != "the" && msg != "[unk]") endRelay(msg)
            }
        } catch (e: Exception) { Log.w(TAG, "relay audio: ${e.message}"); endRelay(null) }
    }

    private fun endRelay(msg: String?) {
        relayMode = false
        try { relayRec?.close() } catch (_: Exception) {}
        relayRec = null
        val t = (msg ?: "").trim()
        if (t.isEmpty()) { Voice.say("I didn't catch a message, sir."); Log.i(TAG, "relay: empty/timeout"); return }
        Log.i(TAG, "relay -> $relayTarget: \"$t\"")
        Voice.say("Sending to ${if (relayTarget == "jarvis") "Jarvis" else "Claude"}, sir.")
        postRelay(t, relayTarget)
    }

    /** POST the message to the bridge. Cloudflare blocks the default UA → send a browser one. */
    private fun postRelay(text: String, target: String) {
        val key = BuildConfig.RELAY_KEY
        if (key.isEmpty()) { Log.w(TAG, "relay: no RELAY_KEY in build"); return }
        Thread {
            try {
                val url = java.net.URL(BuildConfig.RELAY_URL + "?k=" + java.net.URLEncoder.encode(key, "UTF-8"))
                val c = url.openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000; c.readTimeout = 12000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                val payload = org.json.JSONObject()
                    .put("text", text).put("source", "friday-incall").put("from", target).toString()
                c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                Log.i(TAG, "relay POST -> HTTP $code")
                c.disconnect()
                if (code !in 200..299)
                    Handler(Looper.getMainLooper()).post { Voice.say("The message didn't go through, sir.") }
            } catch (e: Exception) {
                Log.w(TAG, "relay POST failed: ${e.message}")
                Handler(Looper.getMainLooper()).post { Voice.say("I couldn't reach the server, sir.") }
            }
        }.start()
    }

    /** Idle wake-word listener (not in a connected call): MIC source + full grammar (incl. contacts). */
    private fun startIdleListener() {
        if (!running || inCall) return
        startCapture(MediaRecorder.AudioSource.MIC, idleGrammarJson(), "idle")
    }

    /**
     * Idle grammar = the base commands + "friday call <name>" for each phone contact (single-word
     * first names only, which Vosk can recognize) + cancel words for the dial confirmation window.
     */
    private fun idleGrammarJson(): String {
        val phrases = GRAMMAR.filter { it != "[unk]" }.toMutableList()
        // Only the curated, callable names (pins) go in the grammar — a tiny reliable set so the
        // recognizer can't confuse one name for another out of a 250-contact phonebook.
        for (name in Contacts.callable.filter { !it.contains(" ") && it != "friday" && it != "hey" }) {
            phrases.add("friday call $name")
            phrases.add("hey friday call $name")
        }
        phrases.add("friday stop"); phrases.add("friday cancel")
        phrases.add("stop"); phrases.add("cancel"); phrases.add("never mind")
        // confirmation words for the "Call X? say Friday yes" gate
        phrases.add("friday yes"); phrases.add("yes"); phrases.add("yeah"); phrases.add("yep")
        phrases.add("confirm"); phrases.add("do it"); phrases.add("no")
        phrases.add("[unk]")
        return phrases.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    }

    /**
     * In-call listener (hears DURING a connected call). MEASURED 2026-11-24 on this A15: with Friday set as
     * the default assistant, during a connected cellular call on speaker ONLY the VOICE_RECOGNITION source
     * stays live (peaks ~23k-28k) while MIC / CAMCORDER / DEFAULT / VOICE_COMMUNICATION are all fed zeros.
     * So we capture VOICE_RECOGNITION mid-call and run the hang-up grammar on it.
     */
    private fun startInCallListener() {
        startCapture(MediaRecorder.AudioSource.VOICE_RECOGNITION, inCallGrammarJson(), "in-call")
    }

    /**
     * DIAGNOSTIC (v0.10.1): during a call, cycle every mic source and log its peak level, to find any
     * channel the OS does NOT silence mid-call. If one leaks, a loud clap / "disconnect" can trigger
     * hang-up with no touch. Read the INCALL_PROBE lines while the user claps/talks on a live call.
     */
    @SuppressLint("MissingPermission")
    private fun startInCallProbe() {
        stopCapture()
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION"
        )
        capRunning = true
        val t = Thread {
            val rate = SAMPLE_RATE.toInt()
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(rate)
            while (capRunning) {
                for ((src, name) in sources) {
                    if (!capRunning) break
                    var rec: AudioRecord? = null
                    try {
                        rec = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                        if (rec.state != AudioRecord.STATE_INITIALIZED) { Log.i(TAG, "INCALL_PROBE $name INIT_FAIL"); rec.release(); continue }
                        rec.startRecording()
                        val buf = ShortArray(minBuf)
                        var peak = 0
                        val until = SystemClock.elapsedRealtime() + 2200
                        while (SystemClock.elapsedRealtime() < until && capRunning) {
                            val n = rec.read(buf, 0, buf.size)
                            for (i in 0 until n) { val v = Math.abs(buf[i].toInt()); if (v > peak) peak = v }
                        }
                        rec.stop()
                        val verdict = if (peak >= 1500) "LIVE ***" else if (peak >= 300) "faint" else "silenced"
                        Log.i(TAG, "INCALL_PROBE $name peak=$peak $verdict")
                    } catch (e: Exception) {
                        Log.i(TAG, "INCALL_PROBE $name ERR ${e.javaClass.simpleName}")
                    } finally { try { rec?.release() } catch (_: Exception) {} }
                }
            }
        }
        capThread = t
        t.start()
        Log.i(TAG, "In-call SOURCE PROBE active — CLAP / talk loudly now")
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
                voskSpeakerModel?.let { try { rec.setSpeakerModel(it) } catch (_: Exception) {} }   // enable voiceprint per-utterance
                capAudio = audio
                capSessionId = try { audio.audioSessionId } catch (_: Exception) { -1 }
                capRecognizer = rec
                capRunning = true
                routeMic(audio)                 // follow the mic onto Bluetooth in the car; else force built-in
                audio.startRecording()
                val t = Thread {
                    val buf = ShortArray(2048)
                    var faulted = false
                    try {
                        var mutedForSpeech = false
                        while (capRunning) {
                            val n = audio.read(buf, 0, buf.size)
                            if (n > 0) {
                                // While Friday is speaking (+ echo tail), DRAIN the mic but DON'T feed
                                // her own voice into the recognizer — otherwise she hears her own prompt
                                // ("...say Friday yes...") and self-confirms / self-cancels the command.
                                if (Voice.isSpeaking()) { mutedForSpeech = true; continue }
                                if (mutedForSpeech) {        // she just finished — flush her prompt audio
                                    try { rec.reset() } catch (_: Exception) {}
                                    if (relayMode) { try { relayRec?.reset() } catch (_: Exception) {}; relayStartMs = System.currentTimeMillis() }
                                    mutedForSpeech = false
                                }
                                // Relay mode: feed open dictation, not the wake-word grammar.
                                if (relayMode) { handleRelayAudio(buf, n); continue }
                                if (rec.acceptWaveForm(buf, n)) {
                                    val json = rec.result
                                    val spk = extractSpk(json)                 // this utterance's voiceprint (if spk model on)
                                    if (enrollMode) { collectEnroll(spk) }     // enrolling → collect, don't act
                                    else { lastSpk = spk; scan(extract(json, "text")) }
                                } else {
                                    // partials carry NO voiceprint → when locked, only act on finals (which do)
                                    if (!enrollMode && !VoiceLock.isEnrolled()) scan(extract(rec.partialResult, "partial"))
                                }
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

    /**
     * Keep Friday's mic working in the CAR. When the phone is on Bluetooth, Android moves the live mic to
     * the car/headset, so Friday's recorder on the phone's own mic gets silence (this is why nothing worked
     * in the car). This routes the recorder: if a Bluetooth link is already live (a call routed to the car),
     * capture the CAR's mic; otherwise force the phone's BUILT-IN mic so a Bluetooth music connection can't
     * silently steal it. Best-effort — never throws into the capture path.
     */
    @SuppressLint("MissingPermission")
    private fun routeMic(audio: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val am = audioManager ?: return
        try {
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val btLive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                am.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            val target = if (btLive)
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            else
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (target != null) {
                audio.setPreferredDevice(target)
                Log.i(TAG, "mic routed -> ${if (btLive) "Bluetooth(car)" else "built-in"} (${target.productName})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "routeMic failed: ${e.message}")
        }
    }

    /**
     * On an incoming RING, aggressively re-grab the microphone. Android gives the live mic to the NEWEST
     * requester, so if another app holds it — notably the Claude Code app (also named "Friday") while Les
     * dictates into it — Friday's idle listener gets silence and can't hear "Friday answer / connect call".
     * Restarting our recorder makes Friday the newest requester and wins the mic back. Two attempts (the
     * other app may re-grab once), on a background thread so stopCapture's join never blocks the main thread.
     */
    private fun reclaimMicForRing() {
        if (!running || inCall) return
        Thread {
            try {
                repeat(2) { attempt ->
                    if (!running || inCall || !ringing) return@Thread
                    Log.i(TAG, "RING → reclaiming mic (attempt ${attempt + 1}) to win it from a foreground app")
                    stopCapture()
                    Thread.sleep(120)
                    Handler(Looper.getMainLooper()).post {
                        if (running && !inCall && ringing) startIdleListener()
                    }
                    Thread.sleep(900)
                }
            } catch (_: Exception) {}
        }.start()
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
        // Half-duplex: never react to audio heard while Friday herself is speaking (+ echo tail),
        // or she acts on her own confirmation ("…say Friday stop to cancel") — the self-cancel bug.
        if (Voice.isSpeaking()) { Log.i(TAG, "ignored (Friday speaking): \"$text\""); return }
        // ── VOICEPRINT LOCK ── "Friday enroll my voice" (re)locks to Les; until enrolled this falls open.
        // A voice enroll/forget command must be CLEAN and SHORT — no "[unk]" garbage, few words. In a noisy
        // room Vosk snaps random speech onto grammar phrases; requiring a clean match stops that noise from
        // nagging Les to enroll (or from clearing his print). Deliberate "Friday enroll my voice" still works.
        val cleanCmd = !text.contains("[unk]") && text.trim().split(Regex("\\s+")).size <= 5
        // Escape hatch: recover from a corrupted print. Requires wake word + clean phrase so the TV
        // can't clear the lock, AND — once enrolled — only Les's own voice may clear it. Ambient speech
        // snapping to "forget my voice" was silently WIPING the enrollment, dropping the lock OPEN.
        if (cleanCmd && text.contains("friday") &&
            (text.contains("forget my voice") || text.contains("reset my voice"))) {
            if (VoiceLock.isEnrolled() && !VoiceLock.isLes(lastSpk)) {
                Log.i(TAG, "clear blocked — enrolled and the voice isn't Les (score=${VoiceLock.score(lastSpk)})"); return
            }
            VoiceLock.clear(applicationContext)
            enrollMode = false; enrollSamples.clear()
            Voice.say("Done, sir — I've cleared your voiceprint. Say, Friday enroll my voice, to set it up again.")
            Log.i(TAG, "voiceprint cleared by command"); return
        }
        // Enroll ONLY on a CLEAN, explicit "Friday enroll my voice"; once enrolled, only Les's own voice
        // may re-enroll. The wake word is required so ambient "enroll my voice" can't nag Les to re-enroll.
        if (cleanCmd && text.contains("friday") && text.contains("enroll my voice")) {
            if (VoiceLock.isEnrolled() && !VoiceLock.isLes(lastSpk)) {
                Log.i(TAG, "re-enroll blocked — already enrolled and the voice isn't Les (score=${VoiceLock.score(lastSpk)})")
                return
            }
            handleEnrollCommand(); return
        }
        // ── FAIL CLOSED when not enrolled ── An unenrolled lock falls OPEN (isLes == true for anyone),
        // so ambient speech Vosk snaps to a command would FIRE — phantom calls, endless enroll nags.
        // Refuse to ACT until Les enrolls: only "Friday enroll my voice" (handled above) works unenrolled.
        // Exception: a genuinely RINGING call — answering is safe/recoverable and must work pre-enrollment.
        if (!VoiceLock.isEnrolled() && !ringing) {
            Log.i(TAG, "ignored (not enrolled — lock open, refusing to act): \"$text\""); return
        }
        // Once enrolled, ONLY Les's voice acts — the TV, a bystander, or JARVIS on speaker are rejected.
        // EXCEPTION (matches line 810): hands-free CALL CONTROL must work even when the ring/call audio
        // bleeding into the mic garbles the voiceprint sample below the 0.32 threshold — otherwise Les
        // can neither answer nor hang up by voice (the exact failures MEASURED 2026-11-26: answer rejected
        // at score 0.25 over the ringtone; "Friday disconnect the call" rejected at 0.15 / 0.28 mid-call
        // while JARVIS was talking on speaker, forcing Les to tap End / making JARVIS relay the hang-up).
        //  • RINGING: answer / decline / voicemail bypass the voiceprint — a wrongly-answered call is
        //    recoverable (line 841's rule) and only Les is talking to his phone while it rings.
        //  • IN A CALL: HANG UP bypasses the voiceprint too, but ONLY with the wake word "friday" + a CLEAN
        //    phrase (no [unk]) — so the far end's garbled speech can't drop the line; it takes Les clearly
        //    saying "Friday, disconnect the call". Relay / outbound / enroll still require his confirmed voice.
        val cleanPhrase = !text.contains("[unk]")
        val ringCallControl = ringing && (answerActions.any { text.contains(it) } ||
            voicemailActions.any { text.contains(it) } || hangupActions.any { text.contains(it) })
        val inCallHangup = inCall && !ringing && text.contains("friday") && cleanPhrase &&
            hangupActions.any { text.contains(it) }
        val voiceGateBypass = ringCallControl || inCallHangup
        if (VoiceLock.isEnrolled() && !VoiceLock.isLes(lastSpk) && !voiceGateBypass) {
            Log.i(TAG, "ignored (voiceprint: not Les, score=${VoiceLock.score(lastSpk)}): \"$text\""); return
        }
        if (voiceGateBypass && !VoiceLock.isLes(lastSpk))
            Log.i(TAG, "voiceprint-bypass (${if (ringCallControl) "ring" else "in-call hangup"}): acting despite score=${VoiceLock.score(lastSpk)} (call-audio garble): \"$text\"")
        if (VoiceLock.isEnrolled()) Log.i(TAG, "accepted (voiceprint: Les, score=${VoiceLock.score(lastSpk)}): \"$text\"")
        // "Friday, tell Claude/Jarvis <message>" → open-dictation relay (only Les reaches here when enrolled).
        if (!relayMode && text.contains("friday") &&
            (text.contains("tell claude") || text.contains("message claude") || text.contains("tell cloud") ||
             text.contains("tell jarvis") || text.contains("message jarvis"))) {
            handleRelayCommand(if (text.contains("jarvis")) "jarvis" else "claude"); return
        }
        // Awaiting confirmation of an outbound call → ONLY a clean, SHORT yes/no matters. A salad
        // (Friday's own prompt echoed, or ambient) must never confirm. Never auto-dial.
        if (pendingCallNumber != null) {
            val clean = !text.contains("[unk]") && text.trim().split(Regex("\\s+")).size <= 3
            if (clean && confirmActions.any { text.contains(it) }) { confirmPendingCall(); return }
            if (cancelActions.any { text.contains(it) }) { cancelPendingCall(); return }
            return
        }
        // "Friday, who's calling?" — read the caller ID aloud. Read-only and ring-gated, so it's safe
        // to act on the command alone even when the ringtone garbled the wake word (like answer/decline).
        if (ringing && whoActions.any { text.contains(it) }) { readCallerAloud(); return }
        if (!text.contains("friday")) {
            // A bare "stop"/"cancel" during the dial confirmation window aborts the pending call.
            if (pendingCallNumber != null && cancelActions.any { text.contains(it) }) { cancelPendingCall(); return }
            // The ringtone garbles the wake word, but READ_PHONE_STATE tells us a call is genuinely
            // RINGING. In that short, unambiguous window, act on the command alone — no wake word
            // required. Answer wins ties: a wrongly-answered call is recoverable, a wrongly-declined
            // one is not.
            if (ringing) {
                when {
                    answerActions.any { text.contains(it) } -> { Log.i(TAG, "ring-gate -> ANSWER: \"$text\""); maybeFire(ACTION_ANSWER); return }
                    voicemailActions.any { text.contains(it) } -> { Log.i(TAG, "ring-gate -> VOICEMAIL: \"$text\""); maybeFire(ACTION_VOICEMAIL); return }
                    // On a RINGING call "disconnect"/"decline" == reject -> it goes to voicemail anyway.
                    hangupActions.any { text.contains(it) } -> { Log.i(TAG, "ring-gate -> DECLINE(voicemail): \"$text\""); maybeFire(ACTION_VOICEMAIL); return }
                }
            }
            if (answerActions.any { text.contains(it) } || hangupActions.any { text.contains(it) } ||
                voicemailActions.any { text.contains(it) })
                Log.i(TAG, "ignored (no wake word): \"$text\"")
            return
        }
        Log.i(TAG, "heard: \"$text\"")
        // Dictation guard: a real command is short ("friday call chris" = 3 words). When Les dictates
        // to ANOTHER app — including Claude Code, also named "Friday" — Vosk snaps his natural speech
        // onto a long command "salad". Real phrases are <= 6 words; reject anything longer.
        if (text.trim().split(Regex("\\s+")).size > 6) {
            Log.i(TAG, "ignored (too long — dictation, not a command): \"$text\"")
            return
        }
        if (inCall) {
            // During a call only HANG UP is meaningful; ignore "answer" so garbled call audio
            // can't fire the wrong command (and the in-call grammar holds hang-up phrases only).
            // On speaker, Friday's in-call ear also hears the OTHER end (e.g. a voicemail greeting),
            // and the recognizer snaps that onto "friday disconnect" surrounded by [unk] noise ->
            // a false hang-up. Require a CLEAN phrase (no [unk]): your clear command has none, the
            // far-end garble always does. (The full fix for other voices is the voiceprint lock.)
            if (text.contains("[unk]")) {
                if (hangupActions.any { text.contains(it) }) Log.i(TAG, "in-call hangup IGNORED (far-end/garbled audio): \"$text\"")
                return
            }
            if (hangupActions.any { text.contains(it) }) maybeFire(ACTION_HANGUP)
            return
        }
        // Start a new outbound call → ASK FOR CONFIRMATION, never auto-dial (pending handled at top).
        matchCallTarget(text)?.let { startPendingCall(it); return }
        // Not in a call (idle or a call ringing): answer / send-to-voicemail / hang up.
        // Check voicemail first — "send to voicemail" must not be swallowed by another match.
        when {
            voicemailActions.any { text.contains(it) } -> maybeFire(ACTION_VOICEMAIL)
            // While ringing, "disconnect" = reject the ringing call -> voicemail (endCall decline).
            hangupActions.any { text.contains(it) } -> maybeFire(if (ringing) ACTION_VOICEMAIL else ACTION_HANGUP)
            answerActions.any { text.contains(it) } -> maybeFire(ACTION_ANSWER)
        }
    }

    /**
     * "Friday call <name>" → the matched contact key, or null. The name is the FIRST known contact that
     * appears AFTER the word "call" (skipping the wake word + fillers), so the wake word "friday" and
     * garbled trailing audio can't be dialed. Won't fire on answer/hang-up/voicemail phrases.
     */
    private val callSkip = setOf("friday", "hey", "my", "the", "a", "to", "call")
    private fun matchCallTarget(text: String): String? {
        if (ringing || inCall || contactMap.isEmpty()) return null
        if (text.contains("[unk]")) return null   // garbled/ambient audio — never dial off it
        val words = text.split(Regex("\\s+"))
        val idx = words.indexOf("call")
        if (idx < 0) return null
        if (answerActions.any { text.contains(it) } || hangupActions.any { text.contains(it) } ||
            voicemailActions.any { text.contains(it) } || cancelActions.any { text.contains(it) }) return null
        if (words.size > 5) return null   // "friday call <name>" is short; longer = dictation salad
        for (i in idx + 1 until words.size) {
            val w = words[i]
            if (w in callSkip) continue
            if (contactMap.containsKey(w)) return w
        }
        return null
    }

    // ── "Friday, who's calling?" — read the caller ID aloud (read-only) ──────
    private fun last10(n: String): String {
        val d = n.filter { it.isDigit() }
        return if (d.length >= 10) d.takeLast(10) else d
    }

    /** Read the ring screen via a11y, name the caller from contacts if known, and speak it. */
    private fun readCallerAloud() {
        val a11y = FridayA11yService.instance
        if (a11y == null) { Voice.say("I need accessibility access to see the caller, sir."); return }
        val raw = try { a11y.readCallerId() } catch (_: Exception) { null }
        val spoken = callerSpoken(raw)
        Log.i(TAG, "who's calling -> raw='$raw' -> '$spoken'")
        Voice.say(spoken)
    }

    /** Turn the raw on-screen caller label into a natural spoken line. */
    private fun callerSpoken(raw: String?): String {
        if (raw.isNullOrBlank()) return "I can't tell who's calling, sir."
        val low = raw.lowercase()
        if (low.contains("unknown") || low.contains("no caller") || low.contains("private") ||
            low.contains("blocked") || low.contains("restricted"))
            return "It's a private or unknown caller, sir."
        val digits = raw.filter { it.isDigit() }
        val letters = raw.count { it.isLetter() }
        // Mostly digits -> a number. Name it from contacts if we know it, else read the digits.
        if (digits.length >= 7 && letters <= 2) {
            reverseContactMap[last10(raw)]?.let { return "It's $it, sir." }
            return "It's a number I don't recognize: ${spokenDigits(digits)}."
        }
        // Otherwise it's the name the dialer already resolved on screen.
        return "It's $raw, sir."
    }

    /** Space the digits so TTS reads them one at a time, grouped like a phone number. */
    private fun spokenDigits(d: String): String {
        val x = if (d.length > 10) d.takeLast(10) else d
        return if (x.length == 10)
            "${x.substring(0, 3).toCharArray().joinToString(" ")}, " +
                "${x.substring(3, 6).toCharArray().joinToString(" ")}, " +
                x.substring(6).toCharArray().joinToString(" ")
        else x.toCharArray().joinToString(" ")
    }

    /**
     * True when an app OTHER than Friday is recording the mic — i.e. Les is dictating (to Claude Code
     * or anything else). Used to suppress ONLY the outbound-call command: answer/decline/hang-up stay
     * live because they're already gated to a real ringing/active call, so a call that arrives mid-
     * dictation can still be answered by voice.
     */
    private fun externalRecorderActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val cfgs = audioManager?.activeRecordingConfigurations ?: return false
            cfgs.count { it.clientAudioSessionId != capSessionId } > 0
        } catch (_: Exception) { false }
    }

    /**
     * Suppress ONLY the outbound call (answer/decline/hang-up stay live, they're call-gated) when the
     * command almost certainly isn't Les deliberately addressing the phone:
     *  - media is playing (TikTok / YouTube / TV / music) → the "voice" is the video, not Les;
     *  - the Claude Code app (also "Friday") is in front → he's dictating to the CLI;
     *  - a standard editable field is focused, or another app is recording.
     * (The real general fix for other PEOPLE talking is the voiceprint lock — still to build.)
     */
    private fun isDictating(): Boolean {
        // (Dropped the old "media playing → suppress" guard: it was blocking legitimate hands-free calls in
        //  the CAR whenever the stereo/Bluetooth audio was on. The VOICEPRINT LOCK now confirms it's Les
        //  BEFORE any command, so a radio/TV/video can no longer trigger a call on its own.)
        val a11y = FridayA11yService.instance
        if (a11y?.isDictationAppForeground() == true) return true
        val focused = try { a11y?.isTextInputFocused() == true } catch (_: Exception) { false }
        if (focused) { Log.i(TAG, "dictation detected (textField) — outbound call suppressed"); return true }
        return externalRecorderActive()
    }

    /** Announce, then dial after a short cancel window (so a misheard name doesn't call the wrong person). */
    private fun startPendingCall(name: String) {
        // Les's rule (2026-11-26): the phone must obey a dial command REGARDLESS of what app is in the
        // foreground — the Claude app, a game, a website, anything. The old foreground/dictation
        // suppression (isDictating()) is REMOVED. Safety is still fully covered by three gates that
        // already passed to get here: wake word "friday" + VOICEPRINT LOCK (only Les's voice) + the
        // CONFIRM-TO-DIAL below ("Call X? say Friday yes") — nothing dials without Les's explicit confirm.
        val number = contactMap[name] ?: return
        pendingDialRunnable?.let { callHandler.removeCallbacks(it) }
        pendingCallNumber = number; pendingCallName = name
        val spoken = name.replaceFirstChar { it.uppercase() }
        // ASK, don't dial. Nothing is called until Les says "yes". This is the hard guarantee that
        // ambient audio / a video / a mishear can never place a call on its own.
        Voice.say("Call $spoken? Say Friday yes to confirm.")
        Log.i(TAG, "awaiting confirmation -> $spoken (say \"Friday yes\")")
        val r = Runnable {
            if (pendingCallNumber != null) Log.i(TAG, "confirmation timed out — NOT calling $pendingCallName")
            pendingCallNumber = null; pendingCallName = null; pendingDialRunnable = null
        }
        pendingDialRunnable = r
        callHandler.postDelayed(r, 12000L)   // no confirmation within 12s → drop it, no call
    }

    /** Les said "yes" → place the call that was awaiting confirmation. */
    private fun confirmPendingCall() {
        pendingDialRunnable?.let { callHandler.removeCallbacks(it) }
        pendingDialRunnable = null
        val num = pendingCallNumber; val nm = pendingCallName ?: ""
        pendingCallNumber = null; pendingCallName = null
        if (num != null) { Log.i(TAG, "call CONFIRMED -> dialing $nm"); CallController.callOut(applicationContext, num, nm) }
    }

    private fun cancelPendingCall() {
        pendingDialRunnable?.let { callHandler.removeCallbacks(it) }
        pendingDialRunnable = null
        val nm = pendingCallName ?: "?"
        pendingCallNumber = null; pendingCallName = null
        Voice.say("Okay, cancelled.")
        Log.i(TAG, "pending call cancelled ($nm)")
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
        const val SPK_ASSET = "model-spk"        // Vosk speaker model for the voiceprint lock
        const val RELAY_WINDOW_MS = 12000L       // after "tell Claude", how long to capture the message
        const val SAMPLE_RATE = 16000.0f
        const val COOLDOWN_MS = 6000L   // wide enough that one spoken command, heard repeatedly, fires once
        // Shake-to-hang-up: a DELIBERATE shake (high threshold, several clustered peaks) so road bumps
        // and normal handling don't end a call. ~13 m/s² above gravity, 3 peaks within 1.2s.
        const val SHAKE_THRESHOLD = 13.0
        const val SHAKE_COUNT = 3
        const val SHAKE_WINDOW_MS = 1200L
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
            // "Friday, who's calling?" — read the caller ID aloud.
            "friday who's calling", "friday whos calling", "friday who is calling",
            "friday who is it", "friday who's this", "friday who is this",
            "hey friday who's calling", "hey friday who is calling", "hey friday who is it",
            // "connect" as an answer synonym (what Les says after hearing the caller ID).
            "friday connect the call", "friday connect call", "friday connect",
            "hey friday connect the call", "hey friday connect call",
            // voiceprint enrollment
            "friday enroll my voice", "hey friday enroll my voice",
            "friday forget my voice", "friday reset my voice", "hey friday forget my voice",
            // in-call relay to Claude / JARVIS
            "friday tell claude", "friday tell jarvis", "friday message claude", "friday message jarvis",
            "hey friday tell claude", "hey friday tell jarvis",
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
