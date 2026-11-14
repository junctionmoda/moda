package com.junction.focusglasses.system

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.junction.focusglasses.glasses.Posture

/**
 * Fallback head-pitch source when the glasses IMU is unavailable (e.g. test mode, or Motion not
 * enabled for the app): the phone's own gravity sensor, using the same "forward = back camera"
 * convention. Useful when the phone is worn/held like the glasses for testing.
 */
class PhonePitchSensor(context: Context) : SensorEventListener {
  private val sm = context.getSystemService(SensorManager::class.java)
  private var listener: ((Double) -> Unit)? = null

  fun start(onPitch: (Double) -> Unit) {
    if (listener != null) return
    listener = onPitch
    val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
    sm.registerListener(this, sensor, 200_000)
  }

  fun stop() {
    sm.unregisterListener(this)
    listener = null
  }

  override fun onSensorChanged(event: SensorEvent) {
    val v = event.values
    Posture.elevationDegrees(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())?.let { listener?.invoke(it) }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
