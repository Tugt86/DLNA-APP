package com.mycast.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * خدمة أمامية: تُبقي الخادم والواي فاي نشطين، وتعرض إشعار تحكم (مثل مشغّل الموسيقى) في الشريط
 * وشاشة القفل، وتستمع لهزّ الهاتف لإيقاف البث.
 */
class CastService : Service(), CastSession.Listener {

    companion object {
        const val ACTION_TOGGLE = "com.mycast.dlna.TOGGLE"
        const val ACTION_STOP = "com.mycast.dlna.STOP"
        const val ACTION_BACK = "com.mycast.dlna.BACK"
        const val ACTION_FWD = "com.mycast.dlna.FWD"
        const val ACTION_EXIT = "com.mycast.dlna.EXIT"
        private const val CHANNEL = "cast_v2"
        private const val NOTIF_ID = 1
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var session: MediaSessionCompat? = null
    private var sensorManager: SensorManager? = null
    private var shake: ShakeDetector? = null
    private var shakeOn = false
    private lateinit var prefs: SharedPreferences

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "shake_exit") refreshShake()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CastSession.init(this)
        prefs = CastSession.prefs

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "التحكم في البث", NotificationManager.IMPORTANCE_LOW)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }

        session = MediaSessionCompat(this, "DlnaCaster").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = CastSession.play()
                override fun onPause() = CastSession.pause()
                override fun onStop() = CastSession.stop()
                override fun onSeekTo(pos: Long) = CastSession.seek((pos / 1000).toInt())
                override fun onRewind() = CastSession.seekBy(-10)
                override fun onFastForward() = CastSession.seekBy(10)
            })
            isActive = true
        }
        updateSession()

        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(
            if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "dlnacaster:wifi"
        ).also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dlnacaster:cast").also {
            it.setReferenceCounted(false)
            it.acquire(6 * 60 * 60 * 1000L)
        }

        CastSession.addListener(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshShake()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> CastSession.togglePlay()
            ACTION_STOP -> CastSession.stop()
            ACTION_BACK -> CastSession.seekBy(-10)
            ACTION_FWD -> CastSession.seekBy(10)
            ACTION_EXIT -> exitApp(applicationContext)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CastSession.removeListener(this)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        stopShake()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ───────────── الهزّ ─────────────

    private fun refreshShake() {
        if (CastSession.shakeExit) startShake() else stopShake()
    }

    private fun startShake() {
        if (shakeOn) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val d = ShakeDetector(prefs) {
            if (CastSession.casting) {
                buzz()
                CastSession.stop()
            }
        }
        sm.registerListener(d, acc, SensorManager.SENSOR_DELAY_GAME)
        sensorManager = sm
        shake = d
        shakeOn = true
    }

    private fun stopShake() {
        shake?.let { sensorManager?.unregisterListener(it) }
        shake = null
        shakeOn = false
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 180, 90, 180) // نبضتان واضحتان
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else v.vibrate(pattern, -1)
        } catch (e: Exception) {
        }
    }

    // ───────────── الإشعار وجلسة الوسائط ─────────────

    override fun onState() {
        updateSession()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    override fun onProgress() {
        if (CastSession.casting && CastSession.position % 5 == 0) updateSession(refreshMeta = false)
    }

    private fun updateSession(refreshMeta: Boolean = true) {
        val s = session ?: return
        val cs = CastSession
        val state = when {
            !cs.casting -> PlaybackStateCompat.STATE_BUFFERING
            cs.playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_FAST_FORWARD
                )
                .setState(state, cs.position * 1000L, if (cs.playing) 1f else 0f, SystemClock.elapsedRealtime())
                .build()
        )
        if (refreshMeta) {
            s.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, cs.title.ifEmpty { "بث DLNA" })
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "يُعرض على التلفاز")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, cs.duration * 1000L)
                    .build()
            )
        }
    }

    private fun action(code: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req, Intent(this, CastService::class.java).setAction(code),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val cs = CastSession
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            !cs.casting -> if (cs.preparing) cs.prepText else "جارٍ التجهيز…"
            cs.playing -> if (cs.preparing) "قيد التشغيل · ${cs.prepText}" else "قيد التشغيل على التلفاز"
            else -> "متوقف مؤقتًا"
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(cs.title.ifEmpty { "بث DLNA" })
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(R.drawable.ic_replay, "−10", action(ACTION_BACK, 1))
            .addAction(
                if (cs.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (cs.playing) "إيقاف مؤقت" else "تشغيل",
                action(ACTION_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_forward, "+10", action(ACTION_FWD, 3))
            .addAction(R.drawable.ic_stop, "إيقاف", action(ACTION_STOP, 4))
            .addAction(R.drawable.ic_power, "خروج", action(ACTION_EXIT, 5))
        val style = MediaStyle().setShowActionsInCompactView(1, 3)
        session?.let { style.setMediaSession(it.sessionToken) }
        b.setStyle(style)
        return b.build()
    }
}
