package com.findphone.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.findphone.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMicPermission()) startSearch() else toast(R.string.toast_need_mic)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        prefs = Prefs(this)

        binding.btnToggle.setOnClickListener { onToggle() }
        binding.btnStopAlarm.setOnClickListener { stopAlarm() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Якщо пошук був увімкнений, а сервіс не працює (перезапуск телефона чи програми) — відновлюємо.
        if (prefs.serviceEnabled && !ListenService.running && hasMicPermission()) {
            ListenService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun onToggle() {
        if (prefs.serviceEnabled) {
            stopSearch()
            return
        }
        if (!prefs.clapEnabled && !prefs.phraseEnabled) {
            toast(R.string.toast_no_mode)
            return
        }
        val missing = mutableListOf<String>()
        if (!hasMicPermission()) missing.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) startSearch() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startSearch() {
        prefs.serviceEnabled = true
        ListenService.start(this)
        updateUi()
    }

    private fun stopSearch() {
        prefs.serviceEnabled = false
        ListenService.send(this, ListenService.ACTION_STOP_SERVICE)
        AlarmPlayer.stop(this)
        updateUi()
    }

    private fun stopAlarm() {
        AlarmPlayer.stop(this)
        ListenService.send(this, ListenService.ACTION_STOP_ALARM)
        updateUi()
    }

    private fun updateUi() {
        val enabled = prefs.serviceEnabled
        binding.btnToggle.setText(if (enabled) R.string.btn_stop else R.string.btn_start)
        binding.tvStatus.text = when {
            enabled && ListenService.running ->
                getString(R.string.status_on, prefs.modesDescription(this))
            enabled -> getString(R.string.status_starting)
            else -> getString(R.string.status_off)
        }
        binding.btnStopAlarm.visibility = if (AlarmPlayer.isPlaying) View.VISIBLE else View.GONE
    }
}
