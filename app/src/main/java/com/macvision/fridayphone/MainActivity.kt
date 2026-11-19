package com.macvision.fridayphone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
