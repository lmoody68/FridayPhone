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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.picovoice.porcupine.PorcupineManager
import java.io.File

/**
 * Friday Phone — the "ears".
 *
 * A microphone foreground service that listens for two offline wake-words via Picovoice Porcupine:
 *   index 0 -> friday_answer.ppn  ("Friday answer the call")  -> broadcast ACTION_ANSWER
 *   index 1 -> friday_hangup.ppn  ("Friday hang up now")      -> broadcast ACTION_HANGUP
 * The broadcasts are caught by MacroDroid's "Intent Received" trigger, which runs the proven
 * answer+speaker / end-call macros. Keeping the telephony in MacroDroid means this app only has to
 * listen and poke — minimal surface, minimal bugs.
 *
 * If the Porcupine assets aren't present yet, it falls back to Android's on-device SpeechRecognizer
 * (battery-heavy, gappy) so the pipeline is still testable before the .ppn files are added.
 */
class ListenerService : Service() {

    private var porcupine: PorcupineManager? = null
    private var recognizer: SpeechRecognizer? = null
    private var running = false

    // Fallback phrase matching (used only when Porcupine isn't configured).
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
            .setContentText("Say your wake-word to answer or hang up a call.")
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
        if (startPorcupine()) {
            Log.i(TAG, "Porcupine wake-words active")
            return
        }
        Log.w(TAG, "Porcupine not configured — using SpeechRecognizer fallback")
        startSpeechRecognizer()
    }

    private fun stopListening() {
        try { porcupine?.stop(); porcupine?.delete() } catch (_: Exception) {}
        porcupine = null
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    // ── Porcupine (production, two keywords) ────────────────────────────────────────────────────
    private fun startPorcupine(): Boolean {
        return try {
            val key = readAsset("picovoice_key.txt")?.trim()
            if (key.isNullOrBlank()) return false
            if (!assetExists("friday_answer.ppn") || !assetExists("friday_hangup.ppn")) return false
            val answerPath = copyAsset("friday_answer.ppn")   // index 0
            val hangupPath = copyAsset("friday_hangup.ppn")   // index 1
            porcupine = PorcupineManager.Builder()
                .setAccessKey(key)
                .setKeywordPaths(arrayOf(answerPath, hangupPath))
                .setSensitivities(floatArrayOf(0.6f, 0.6f))
                .build(applicationContext) { keywordIndex ->
                    when (keywordIndex) {
                        0 -> fire(ACTION_ANSWER)
                        1 -> fire(ACTION_HANGUP)
                    }
                }
            porcupine?.start()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Porcupine init failed, falling back: ${e.message}")
            try { porcupine?.delete() } catch (_: Exception) {}
            porcupine = null
            false
        }
    }

    // ── SpeechRecognizer (beta fallback) ────────────────────────────────────────────────────────
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
                hits.any { h -> hangupPhrases.any { h.contains(it) } } -> fire(ACTION_HANGUP)
                hits.any { h -> answerPhrases.any { h.contains(it) } } -> fire(ACTION_ANSWER)
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
    private fun fire(action: String) {
        Log.i(TAG, "Wake-word -> $action")
        Handler(Looper.getMainLooper()).post {
            val label = if (action == ACTION_ANSWER) "ANSWER" else "HANG UP"
            Toast.makeText(applicationContext, "Friday heard: $label", Toast.LENGTH_SHORT).show()
        }
        sendCommand(applicationContext, action)
    }

    // ── Asset helpers ───────────────────────────────────────────────────────────────────────────
    private fun assetExists(name: String): Boolean =
        try { (assets.list("") ?: emptyArray()).contains(name) } catch (_: Exception) { false }

    private fun readAsset(name: String): String? =
        try { assets.open(name).bufferedReader().use { it.readText() } } catch (_: Exception) { null }

    private fun copyAsset(name: String): String {
        val out = File(filesDir, name)
        assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out.absolutePath
    }

    companion object {
        const val TAG = "FridayPhone"
        const val CHANNEL_ID = "friday_listener"
        const val NOTIF_ID = 1001
        const val MACRODROID_PKG = "com.arlosoft.macrodroid"
        const val ACTION_ANSWER = "com.macvision.fridayphone.ANSWER"
        const val ACTION_HANGUP = "com.macvision.fridayphone.HANGUP"

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
