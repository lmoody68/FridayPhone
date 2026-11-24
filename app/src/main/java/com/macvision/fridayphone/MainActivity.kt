package com.macvision.fridayphone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.macvision.fridayphone.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            ListenerService.start(this)
            refresh()
        } else {
            Toast.makeText(this, "Microphone permission is required to listen.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnToggle.setOnClickListener {
            if (Prefs(this).listening) {
                ListenerService.stop(this)
            } else {
                requestPermsAndStart()
            }
            refresh()
        }

        b.btnBattery.setOnClickListener { requestBatteryExemption() }

        // Enable Friday's Accessibility Service (needed to tap Google Voice's "Hang up").
        b.btnA11y.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Turn ON \"Friday call control\".", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Open Settings → Accessibility → Friday call control.", Toast.LENGTH_LONG).show()
            }
        }

        // Set Friday as the phone's default digital assistant (grants in-call mic access).
        b.btnAssistant.setOnClickListener {
            val tries = listOf(
                "android.settings.VOICE_INPUT_SETTINGS",
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                Settings.ACTION_SETTINGS
            )
            for (a in tries) {
                try { startActivity(Intent(a)); break } catch (_: Exception) {}
            }
            Toast.makeText(this,
                "Pick \"Digital assistant app\" → Friday Phone.", Toast.LENGTH_LONG).show()
        }

        // Diagnostics: run each action directly, bypassing the recognizer, to prove the wiring.
        b.btnTestAnswer.setOnClickListener {
            ListenerService.sendCommand(this, ListenerService.ACTION_ANSWER)
            Toast.makeText(this, "Ran ANSWER", Toast.LENGTH_SHORT).show()
        }
        b.btnTestHangup.setOnClickListener {
            ListenerService.sendCommand(this, ListenerService.ACTION_HANGUP)
            Toast.makeText(this, "Ran HANG UP", Toast.LENGTH_SHORT).show()
        }
        b.btnTestVoicemail.setOnClickListener {
            ListenerService.sendCommand(this, ListenerService.ACTION_VOICEMAIL)
            Toast.makeText(this, "Ran SEND TO VOICEMAIL", Toast.LENGTH_SHORT).show()
        }

        b.btnProbe.setOnClickListener { startMicProbe() }
    }

    // ---- Mic probe: measure, source by source, whether Android feeds us real audio
    //      or silence during a live call. Results shown on screen (screenshot it). ----
    @Volatile private var probing = false

    private data class Src(val id: Int, val name: String)

    private val probeSources = listOf(
        Src(MediaRecorder.AudioSource.DEFAULT, "DEFAULT"),
        Src(MediaRecorder.AudioSource.MIC, "MIC"),
        Src(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
        Src(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"),
        Src(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER"),
        Src(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL"),
        Src(2 /*VOICE_UPLINK*/, "VOICE_UPLINK"),
        Src(3 /*VOICE_DOWNLINK*/, "VOICE_DOWNLINK")
    )

    private fun startMicProbe() {
        if (probing) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            Toast.makeText(this, "Grant mic, then tap Probe again.", Toast.LENGTH_LONG).show()
            return
        }
        // Free the mic from our own listener so the probe reads cleanly.
        ListenerService.stop(this)
        probing = true
        b.txtProbe.text = "Probing… TALK OUT LOUD now, nonstop, until it finishes.\n"
        Thread { runMicProbe() }.start()
    }

    @SuppressLint("MissingPermission")
    private fun runMicProbe() {
        val sb = StringBuilder("Probing… TALK OUT LOUD now, nonstop.\n\n")
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(rate) // ~0.5s floor
        var bestName: String? = null
        var bestPeak = 0

        for (s in probeSources) {
            var line: String
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(
                    s.id, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    line = "%-20s  —  init FAILED (blocked)".format(s.name)
                } else {
                    rec.startRecording()
                    val buf = ShortArray(minBuf)
                    var peak = 0
                    var sumSq = 0.0
                    var count = 0L
                    val until = System.currentTimeMillis() + 1500 // ~1.5s per source
                    while (System.currentTimeMillis() < until) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            for (i in 0 until n) {
                                val v = abs(buf[i].toInt())
                                if (v > peak) peak = v
                                sumSq += v.toDouble() * v
                                count++
                            }
                        }
                    }
                    rec.stop()
                    val rms = if (count > 0) sqrt(sumSq / count).toInt() else 0
                    val verdict = when {
                        peak >= 1500 -> "✅ LIVE (hears you!)"
                        peak >= 300  -> "~ faint"
                        else         -> "✖ silenced/zeros"
                    }
                    line = "%-20s peak=%-6d rms=%-5d %s".format(s.name, peak, rms, verdict)
                    if (peak > bestPeak) { bestPeak = peak; bestName = s.name }
                }
            } catch (e: Exception) {
                line = "%-20s  —  ERROR ${e.javaClass.simpleName}".format(s.name)
            } finally {
                try { rec?.release() } catch (_: Exception) {}
            }
            sb.append(line).append('\n')
            val snapshot = sb.toString()
            runOnUiThread { b.txtProbe.text = snapshot }
        }

        val summary = if (bestName != null && bestPeak >= 1500)
            "\n★ RESULT: \"$bestName\" gets your voice during the call (peak=$bestPeak).\n" +
            "→ On-phone voice hang-up IS possible. Send Friday this screenshot."
        else
            "\n★ RESULT: every source silenced (best peak=$bestPeak).\n" +
            "→ OS lock confirmed on your phone. Send Friday this screenshot."
        sb.append(summary)
        val finalText = sb.toString()
        runOnUiThread {
            b.txtProbe.text = finalText
            Toast.makeText(this, "Probe done — screenshot the green panel.", Toast.LENGTH_LONG).show()
        }
        probing = false
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestPermsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.ANSWER_PHONE_CALLS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.READ_PHONE_STATE

        if (needed.isEmpty()) {
            ListenerService.start(this)
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already unrestricted. 👍", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refresh() {
        val on = Prefs(this).listening
        b.txtStatus.text = if (on) "● Friday is listening" else "○ Not listening"
        b.btnToggle.text = if (on) "Stop listening" else "Start listening"
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        b.btnBattery.text = if (pm.isIgnoringBatteryOptimizations(packageName))
            "Battery: unrestricted ✓" else "Keep Friday alive (battery)"
    }
}
