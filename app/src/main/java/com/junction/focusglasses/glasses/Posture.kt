package com.junction.focusglasses.glasses

import kotlin.math.asin
import kotlin.math.sqrt

object Posture {
  /**
   * Elevation of the device's forward axis (0, 0, -1) above the horizon, in degrees, from an
   * accelerometer reading at rest (which points "up"). Looking down gives a negative value.
   * Same convention as Meta's BirdSpotter sample. Works for the glasses IMU and, as a fallback,
   * for a phone whose back camera faces forward.
   */
  fun elevationDegrees(x: Double, y: Double, z: Double): Double? {
    val len = sqrt(x * x + y * y + z * z)
    if (len < 1e-6) return null
    val upZ = z / len
    // forward · up = (0,0,-1)·(ux,uy,uz) = -uz
    return Math.toDegrees(asin((-upZ).coerceIn(-1.0, 1.0)))
  }
}
