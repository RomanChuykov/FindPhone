package com.findphone.app

import android.content.Context
import android.content.SharedPreferences

/** Усі налаштування програми в одному місці. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("findphone", Context.MODE_PRIVATE)

    /** Користувач увімкнув фоновий пошук (щоб відновити його після перезапуску програми). */
    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) { sp.edit().putBoolean("service_enabled", v).apply() }

    // ---- Хлопок ----
    var clapEnabled: Boolean
        get() = sp.getBoolean("clap_enabled", true)
        set(v) { sp.edit().putBoolean("clap_enabled", v).apply() }

    /** Скільки хлопків потрібно: 1..3. */
    var clapCount: Int
        get() = sp.getInt("clap_count", 2).coerceIn(1, 3)
        set(v) { sp.edit().putInt("clap_count", v.coerceIn(1, 3)).apply() }

    /** Чутливість 0..100 (більше — реагує на тихіші хлопки). */
    var clapSensitivity: Int
        get() = sp.getInt("clap_sensitivity", 60).coerceIn(0, 100)
        set(v) { sp.edit().putInt("clap_sensitivity", v.coerceIn(0, 100)).apply() }

    // ---- Фраза ----
    var phraseEnabled: Boolean
        get() = sp.getBoolean("phrase_enabled", false)
        set(v) { sp.edit().putBoolean("phrase_enabled", v).apply() }

    var phrase: String
        get() = sp.getString("phrase", "де мій телефон") ?: ""
        set(v) { sp.edit().putString("phrase", v).apply() }

    var language: String
        get() = sp.getString("language", "uk-UA") ?: "uk-UA"
        set(v) { sp.edit().putString("language", v).apply() }

    // ---- Звук ----
    var soundType: Int
        get() = sp.getInt("sound_type", SOUND_SIREN)
        set(v) { sp.edit().putInt("sound_type", v).apply() }

    var customUri: String
        get() = sp.getString("custom_uri", "") ?: ""
        set(v) { sp.edit().putString("custom_uri", v).apply() }

    var customName: String
        get() = sp.getString("custom_name", "") ?: ""
        set(v) { sp.edit().putString("custom_name", v).apply() }

    /** Гучність сигналу у відсотках від максимуму будильника. */
    var volume: Int
        get() = sp.getInt("volume", 100).coerceIn(10, 100)
        set(v) { sp.edit().putInt("volume", v.coerceIn(10, 100)).apply() }

    /** Скільки секунд сигналити; 0 — поки користувач не вимкне. */
    var durationSec: Int
        get() = sp.getInt("duration_sec", 60)
        set(v) { sp.edit().putInt("duration_sec", v).apply() }

    fun modesDescription(context: Context): String {
        val parts = ArrayList<String>()
        if (clapEnabled) parts.add(context.getString(R.string.mode_clap, clapCount))
        if (phraseEnabled) parts.add(context.getString(R.string.mode_phrase, phrase))
        return parts.joinToString(", ")
    }

    companion object {
        const val SOUND_SIREN = 0
        const val SOUND_BEEP = 1
        const val SOUND_ALARM = 2
        const val SOUND_CUSTOM = 3

        val DURATIONS = intArrayOf(15, 30, 60, 120, 300, 0)
    }
}
