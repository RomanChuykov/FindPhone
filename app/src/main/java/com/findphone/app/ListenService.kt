package com.findphone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Фоновий сервіс: слухає хлопок і/або фразу та вмикає сигнал.
 *
 * Мікрофон не можна ділити між AudioRecord (хлопок) і розпізнаванням мови одночасно,
 * тому коли ввімкнено обидва способи, вони чергуються: кілька секунд слухаємо хлопок,
 * потім одну сесію фрази, і знову по колу.
 */
class ListenService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var clapDetector: ClapDetector
    private lateinit var phraseListener: PhraseListener
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var paused = false
    private var foregroundStarted = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        clapDetector = ClapDetector(prefs) { handler.post { trigger() } }
        phraseListener = PhraseListener(
            this, prefs,
            onHeard = { trigger() },
            onSessionEnded = { delay -> onPhraseSessionEnded(delay) }
        )
        AlarmPlayer.onStopped = { handler.post { refreshNotification() } }
        running = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_SERVICE) {
            prefs.serviceEnabled = false
            shutdown()
            return START_NOT_STICKY
        }

        if (!foregroundStarted && !startAsForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_STOP_ALARM -> {
                AlarmPlayer.stop(this)
                refreshNotification()
            }
            ACTION_PAUSE -> {
                paused = true
                handler.removeCallbacksAndMessages(null)
                stopListeners()
            }
            ACTION_RESUME -> {
                paused = false
                begin()
            }
            else -> begin() // ACTION_START, ACTION_RELOAD або перезапуск системою
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        AlarmPlayer.onStopped = null
        handler.removeCallbacksAndMessages(null)
        stopListeners()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---------- Керування прослуховуванням ----------

    private fun begin() {
        handler.removeCallbacksAndMessages(null)
        stopListeners()
        refreshNotification()
        if (paused || destroyed) return

        val clap = prefs.clapEnabled
        val phrase = prefs.phraseEnabled
        if (!clap && !phrase) return

        acquireWakeLock()
        when {
            clap && phrase -> startClapSlice()
            clap -> clapDetector.start()
            else -> phraseListener.start(PHRASE_TIMEOUT_SOLO)
        }
    }

    private fun stopListeners() {
        clapDetector.stop()
        phraseListener.cancel()
    }

    /** Режим «обидва»: спершу слухаємо хлопок, потім одна сесія фрази. */
    private fun startClapSlice() {
        clapDetector.start()
        handler.postDelayed({
            if (!paused && !destroyed) {
                clapDetector.stop()
                phraseListener.start(PHRASE_TIMEOUT_BOTH)
            }
        }, CLAP_SLICE_MS)
    }

    private fun onPhraseSessionEnded(delayMs: Long) {
        if (paused || destroyed) return
        handler.postDelayed({
            if (!paused && !destroyed) {
                val clap = prefs.clapEnabled
                val phrase = prefs.phraseEnabled
                when {
                    clap && phrase -> startClapSlice()
                    phrase -> phraseListener.start(PHRASE_TIMEOUT_SOLO)
                    clap -> clapDetector.start()
                }
            }
        }, delayMs)
    }

    private fun trigger() {
        if (destroyed || paused || AlarmPlayer.isPlaying) return
        AlarmPlayer.start(this, prefs)
        refreshNotification()
    }

    private fun shutdown() {
        destroyed = true
        AlarmPlayer.onStopped = null
        handler.removeCallbacksAndMessages(null)
        stopListeners()
        AlarmPlayer.stop(this)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- Wake lock ----------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "findphone:listen").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
        wakeLock = null
    }

    // ---------- Сповіщення ----------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground(): Boolean {
        return try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            foregroundStarted = true
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun refreshNotification() {
        if (!foregroundStarted || destroyed) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (AlarmPlayer.isPlaying) {
            getString(R.string.notif_ringing)
        } else {
            getString(R.string.notif_text, prefs.modesDescription(this))
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (AlarmPlayer.isPlaying) {
            b.addAction(0, getString(R.string.btn_stop_alarm), servicePendingIntent(ACTION_STOP_ALARM, 1))
        }
        b.addAction(0, getString(R.string.btn_stop), servicePendingIntent(ACTION_STOP_SERVICE, 2))
        return b.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, ListenService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        const val ACTION_START = "com.findphone.app.START"
        const val ACTION_RELOAD = "com.findphone.app.RELOAD"
        const val ACTION_PAUSE = "com.findphone.app.PAUSE"
        const val ACTION_RESUME = "com.findphone.app.RESUME"
        const val ACTION_STOP_ALARM = "com.findphone.app.STOP_ALARM"
        const val ACTION_STOP_SERVICE = "com.findphone.app.STOP_SERVICE"

        private const val CHANNEL_ID = "listen"
        private const val NOTIF_ID = 1
        private const val CLAP_SLICE_MS = 3_000L
        private const val PHRASE_TIMEOUT_SOLO = 15_000L
        private const val PHRASE_TIMEOUT_BOTH = 6_000L

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ListenService::class.java).setAction(ACTION_START)
            )
        }

        /** Надсилає команду вже запущеному сервісу (якщо він не працює — нічого не робить). */
        fun send(context: Context, action: String) {
            if (!running) return
            try {
                context.startService(Intent(context, ListenService::class.java).setAction(action))
            } catch (e: Exception) {
                // система не дозволила команду з фону — сервіс уже працює, налаштування підхопить сам
            }
        }
    }
}
