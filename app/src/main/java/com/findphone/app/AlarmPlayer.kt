package com.findphone.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Програє гучний сигнал на каналі будильника (чутно навіть у беззвучному режимі).
 * Викликати лише з головного потоку.
 */
object AlarmPlayer {

    private const val SAMPLE_RATE = 22050

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var autoStop: Runnable? = null

    @Volatile
    var isPlaying = false
        private set

    /** Викликається, коли сигнал закінчився (вручну чи за таймером). */
    @Volatile
    var onStopped: (() -> Unit)? = null

    fun start(context: Context, prefs: Prefs) {
        val app = context.applicationContext
        stopInternal(app, notify = false)

        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (max * prefs.volume / 100f).roundToInt().coerceIn(1, max)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: Exception) {
            // Режим «Не турбувати» може заборонити змінювати гучність — граємо як є.
        }

        val mp = prepare { setSource(app, it, prefs) }
            ?: prepare { it.setDataSource(builtInFile(app, "siren").absolutePath) }
        if (mp == null) {
            restoreVolume(app)
            return
        }

        player = mp
        mp.start()
        isPlaying = true

        val seconds = prefs.durationSec
        if (seconds > 0) {
            val r = Runnable { stop(app) }
            autoStop = r
            handler.postDelayed(r, seconds * 1000L)
        }
    }

    fun stop(context: Context) = stopInternal(context.applicationContext, notify = true)

    private fun stopInternal(app: Context, notify: Boolean) {
        autoStop?.let { handler.removeCallbacks(it) }
        autoStop = null

        val p = player
        player = null
        if (p != null) {
            try { p.stop() } catch (e: Exception) { }
            try { p.release() } catch (e: Exception) { }
        }

        restoreVolume(app)

        val wasPlaying = isPlaying
        isPlaying = false
        if (wasPlaying && notify) onStopped?.invoke()
    }

    private fun restoreVolume(app: Context) {
        if (savedVolume < 0) return
        try {
            val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        } catch (e: Exception) {
        }
        savedVolume = -1
    }

    private fun prepare(source: (MediaPlayer) -> Unit): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            source(mp)
            mp.isLooping = true
            mp.prepare()
            mp
        } catch (e: Exception) {
            try { mp.release() } catch (e2: Exception) { }
            null
        }
    }

    private fun setSource(app: Context, mp: MediaPlayer, prefs: Prefs) {
        when (prefs.soundType) {
            Prefs.SOUND_BEEP -> mp.setDataSource(builtInFile(app, "beep").absolutePath)
            Prefs.SOUND_ALARM -> {
                val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                mp.setDataSource(app, uri)
            }
            Prefs.SOUND_CUSTOM -> mp.setDataSource(app, Uri.parse(prefs.customUri))
            else -> mp.setDataSource(builtInFile(app, "siren").absolutePath)
        }
    }

    // ---------- Вбудовані звуки (генеруються один раз у кеш) ----------

    private fun builtInFile(context: Context, name: String): File {
        val file = File(context.cacheDir, "${name}_v1.wav")
        if (!file.exists() || file.length() < 1000) {
            val samples = if (name == "beep") generateBeep() else generateSiren()
            writeWav(file, samples)
        }
        return file
    }

    /** Сирена: тон плавно гойдається між 650 і 1500 Гц. */
    private fun generateSiren(): ShortArray {
        val n = SAMPLE_RATE * 2
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val tri = if (p < 0.5) p * 2 else (1 - p) * 2
            val freq = 650.0 + 850.0 * tri
            phase += 2 * PI * freq / SAMPLE_RATE
            val v = (sin(phase) + 0.35 * sin(2 * phase)) / 1.35
            out[i] = (v * 0.95 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Гудок: два різких сигнали по 0,28 с. */
    private fun generateBeep(): ShortArray {
        val n = (SAMPLE_RATE * 0.8).toInt()
        val out = ShortArray(n)
        val period = (SAMPLE_RATE * 0.4).toInt()
        val on = (SAMPLE_RATE * 0.28).toInt()
        val fade = (SAMPLE_RATE * 0.005).toInt()
        for (i in 0 until n) {
            val pos = i % period
            if (pos < on) {
                val env = minOf(1.0, pos / fade.toDouble(), (on - pos) / fade.toDouble())
                val v = sin(2 * PI * 2000.0 * i / SAMPLE_RATE) * env
                out[i] = (v * 0.95 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    private fun writeWav(file: File, samples: ShortArray) {
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1.toShort())            // PCM
        buf.putShort(1.toShort())            // моно
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2)
        buf.putShort(2.toShort())
        buf.putShort(16.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        for (s in samples) buf.putShort(s)
        file.writeBytes(buf.array())
    }
}
