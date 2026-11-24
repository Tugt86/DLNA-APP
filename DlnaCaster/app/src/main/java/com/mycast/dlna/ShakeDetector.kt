package com.mycast.dlna

import android.content.SharedPreferences
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** يكتشف هزّ الهاتف: هزّة قوية واحدة. الحساسية (0..100) تُقرأ من الإعدادات مباشرة. */
class ShakeDetector(
    private val prefs: SharedPreferences,
    private val onShake: () -> Unit
) : SensorEventListener {

    private var last = 0L

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val sens = prefs.getInt("shake_sens", 50).coerceIn(0, 100)
        val threshold = 5.0f - sens / 100f * 2.4f // 5.0g (أقل حساسية) … 2.6g (أعلى حساسية)
        if (g < threshold) return
        val now = SystemClock.elapsedRealtime()
        if (now - last < 1000) return // نفس الهزّة
        last = now
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
