package com.findphone.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.widget.doAfterTextChanged
import com.findphone.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val languages = listOf("uk-UA", "ru-RU", "en-US")
    private var previewing = false

    private val tick = object : Runnable {
        override fun run() {
            if (previewing && !AlarmPlayer.isPlaying) previewing = false
            updateTestButton()
            handler.postDelayed(this, 500)
        }
    }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) recordPhrase() else toast(R.string.toast_need_mic)
        }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onSpeechResult(result)
        }

    private val ringtoneLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val uri = if (data == null) null else IntentCompat.getParcelableExtra(
                    data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java
                )
                if (uri != null) {
                    val title = try {
                        RingtoneManager.getRingtone(this, uri)?.getTitle(this)
                    } catch (e: Exception) {
                        null
                    }
                    setCustomSound(uri, title)
                }
            }
        }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                }
                setCustomSound(uri, displayName(uri))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        prefs = Prefs(this)

        setupClap()
        setupPhrase()
        setupSound()

        binding.btnBattery.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        updateEnabledStates()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onStop() {
        super.onStop()
        if (previewing) {
            AlarmPlayer.stop(this)
            previewing = false
        }
        // Застосувати нові налаштування до працюючого сервісу.
        ListenService.send(this, ListenService.ACTION_RELOAD)
    }

    // ---------- Хлопок ----------

    private fun setupClap() {
        binding.swClap.isChecked = prefs.clapEnabled
        binding.swClap.setOnCheckedChangeListener { _, checked ->
            prefs.clapEnabled = checked
            updateEnabledStates()
        }

        binding.spClapCount.bind(
            resources.getStringArray(R.array.clap_counts).toList(),
            prefs.clapCount - 1
        ) { prefs.clapCount = it + 1 }

        binding.slSensitivity.value = (prefs.clapSensitivity / 5 * 5).toFloat().coerceIn(0f, 100f)
        binding.slSensitivity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.clapSensitivity = value.toInt()
        }
    }

    // ---------- Фраза ----------

    private fun setupPhrase() {
        binding.swPhrase.isChecked = prefs.phraseEnabled
        binding.swPhrase.setOnCheckedChangeListener { _, checked ->
            prefs.phraseEnabled = checked
            updateEnabledStates()
        }

        binding.etPhrase.setText(prefs.phrase)
        binding.etPhrase.doAfterTextChanged { prefs.phrase = it?.toString()?.trim().orEmpty() }

        binding.btnRecordPhrase.setOnClickListener { recordPhrase() }

        binding.spLanguage.bind(
            resources.getStringArray(R.array.language_names).toList(),
            languages.indexOf(prefs.language).coerceAtLeast(0)
        ) { prefs.language = languages[it] }

        binding.tvPhraseWarning.visibility =
            if (SpeechRecognizer.isRecognitionAvailable(this)) View.GONE else View.VISIBLE
    }

    private fun recordPhrase() {
        if (!hasMicPermission()) {
            micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        // Звільняємо мікрофон, поки користувач говорить.
        ListenService.send(this, ListenService.ACTION_PAUSE)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.say_phrase))
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            ListenService.send(this, ListenService.ACTION_RESUME)
            toast(R.string.toast_no_speech)
        }
    }

    private fun onSpeechResult(result: ActivityResult) {
        ListenService.send(this, ListenService.ACTION_RESUME)
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!text.isNullOrEmpty()) {
                binding.etPhrase.setText(text) // збережеться через doAfterTextChanged
            }
        }
    }

    // ---------- Звук ----------

    private fun setupSound() {
        binding.spSound.bind(
            resources.getStringArray(R.array.sound_names).toList(),
            prefs.soundType
        ) {
            prefs.soundType = it
            updateCustomLabel()
        }
        updateCustomLabel()

        binding.btnPickRingtone.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.pick_ringtone_title))
            }
            ringtoneLauncher.launch(intent)
        }
        binding.btnPickFile.setOnClickListener {
            filePicker.launch(arrayOf("audio/*"))
        }

        binding.slVolume.value = (prefs.volume / 10 * 10).toFloat().coerceIn(10f, 100f)
        binding.slVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.volume = value.toInt()
        }

        val durationIndex = Prefs.DURATIONS.indexOf(prefs.durationSec).let { if (it < 0) 2 else it }
        binding.spDuration.bind(
            resources.getStringArray(R.array.duration_names).toList(),
            durationIndex
        ) { prefs.durationSec = Prefs.DURATIONS[it] }

        binding.btnTest.setOnClickListener {
            if (AlarmPlayer.isPlaying) {
                AlarmPlayer.stop(this)
                previewing = false
            } else {
                AlarmPlayer.start(this, prefs)
                previewing = AlarmPlayer.isPlaying
            }
            updateTestButton()
        }
    }

    private fun setCustomSound(uri: Uri, name: String?) {
        prefs.customUri = uri.toString()
        prefs.customName = name ?: uri.lastPathSegment.orEmpty()
        prefs.soundType = Prefs.SOUND_CUSTOM
        binding.spSound.setSelection(Prefs.SOUND_CUSTOM)
        updateCustomLabel()
    }

    private fun updateCustomLabel() {
        binding.tvCustomSound.text =
            if (prefs.customUri.isEmpty()) {
                getString(R.string.custom_sound_none)
            } else {
                getString(R.string.custom_sound_current, prefs.customName)
            }
    }

    private fun updateTestButton() {
        binding.btnTest.setText(if (AlarmPlayer.isPlaying) R.string.test_stop else R.string.test_start)
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    // ---------- Інше ----------

    private fun updateEnabledStates() {
        val clap = binding.swClap.isChecked
        binding.spClapCount.isEnabled = clap
        binding.slSensitivity.isEnabled = clap

        val phrase = binding.swPhrase.isChecked
        binding.tilPhrase.isEnabled = phrase
        binding.etPhrase.isEnabled = phrase
        binding.btnRecordPhrase.isEnabled = phrase
        binding.spLanguage.isEnabled = phrase
    }
}
