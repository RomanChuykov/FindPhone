package com.findphone.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Слухає мікрофон і шукає різкі гучні піки (хлопки).
 * Звук нікуди не записується і не передається — аналізується на льоту.
 */
class ClapDetector(
    private val prefs: Prefs,
    private val onClaps: () -> Unit
) {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val t = Thread({ loop() }, "clap-detector")
        thread = t
        t.start()
    }

    @Synchronized
    fun stop() {
        running = false
        val t = thread
        thread = null
        if (t != null) {
            try { t.join(1000) } catch (e: InterruptedException) { }
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            running = false
            return
        }
        val frame = sampleRate / 50 // 20 мс
        val bufSize = maxOf(minBuf, frame * 2 * 4)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Exception) {
            running = false
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            running = false
            return
        }

        val buf = ShortArray(frame)
        val clapTimes = ArrayList<Long>()
        var background = 0.01
        var lastPeakTime = 0L
        var ignoreUntil = 0L

        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) {
                    Thread.sleep(10)
                    continue
                }

                var peak = 0
                var sum = 0.0
                for (i in 0 until n) {
                    val v = abs(buf[i].toInt())
                    if (v > peak) peak = v
                    sum += v.toDouble() * v
                }
                val peakF = peak / 32768.0
                val rms = sqrt(sum / n) / 32768.0
                val now = SystemClock.elapsedRealtime()

                // Поки грає сигнал (і трохи після) — не реагуємо, щоб не ловити власний звук.
                if (AlarmPlayer.isPlaying) {
                    ignoreUntil = now + 1500
                }
                if (now < ignoreUntil) {
                    clapTimes.clear()
                    continue
                }

                val threshold = 0.85 - 0.70 * prefs.clapSensitivity / 100.0
                val isSpike = peakF > threshold && peakF > background * 5

                if (isSpike) {
                    if (now - lastPeakTime > 150) { // відсікаємо відлуння одного хлопка
                        clapTimes.add(now)
                    }
                    lastPeakTime = now
                } else {
                    background = (background * 0.95 + rms * 0.05).coerceAtLeast(0.002)
                }

                clapTimes.removeAll { now - it > 1500 }
                if (clapTimes.size >= prefs.clapCount) {
                    clapTimes.clear()
                    ignoreUntil = now + 3000
                    onClaps()
                }
            }
        } catch (e: Exception) {
            // мікрофон зайнятий чи недоступний — просто завершуємо
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
            running = false
        }
    }
}
