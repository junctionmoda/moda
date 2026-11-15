package com.junction.focusglasses.vision

import android.graphics.Bitmap
import java.nio.ByteBuffer

object FrameConverter {
  /**
   * Builds a half-resolution grayscale bitmap from the luma (Y) plane of a decoded YUV frame.
   *
   * The DAT SDK hands decoded frames as raw YUV. Every common 4:2:0 layout (I420, NV12, NV21)
   * starts with a full-size Y plane, so reading only luma is layout-independent; phone
   * detection works fine on grayscale.
   */
  fun toGrayBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val dup = buffer.duplicate()
    val start = dup.position()
    if (dup.remaining() < width * height) return null
    val outW = width / 2
    val outH = height / 2
    val pixels = IntArray(outW * outH)
    for (oy in 0 until outH) {
      val rowStart = start + (oy * 2) * width
      for (ox in 0 until outW) {
        val y = dup.get(rowStart + ox * 2).toInt() and 0xFF
        pixels[oy * outW + ox] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
      }
    }
    return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
  }
}
