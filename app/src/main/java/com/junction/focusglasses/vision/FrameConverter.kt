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

  /**
   * Half-resolution color bitmap from a YUV 4:2:0 frame, for Gemini (detection stays on luma).
   * Planar (I420) and semi-planar (NV12) chroma are told apart from the data: in NV12 neighbouring
   * chroma bytes alternate U and V, so they differ more than bytes two apart.
   */
  fun toColorBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val ySize = width * height
    if (data.size < ySize * 3 / 2) return null
    val cw = width / 2
    val ch = height / 2
    val semiPlanar = isSemiPlanar(data, ySize, cw * ch)
    val pixels = IntArray(cw * ch)
    for (oy in 0 until ch) {
      for (ox in 0 until cw) {
        val y = data[(oy * 2) * width + ox * 2].toInt() and 0xFF
        val u: Int
        val v: Int
        if (semiPlanar) {
          val i = ySize + oy * width + ox * 2
          u = data[i].toInt() and 0xFF
          v = data[i + 1].toInt() and 0xFF
        } else {
          val i = oy * cw + ox
          u = data[ySize + i].toInt() and 0xFF
          v = data[ySize + cw * ch + i].toInt() and 0xFF
        }
        pixels[oy * cw + ox] = yuvToArgb(y, u, v)
      }
    }
    return Bitmap.createBitmap(pixels, cw, ch, Bitmap.Config.ARGB_8888)
  }

  fun isSemiPlanar(data: ByteArray, ySize: Int, chromaPlane: Int): Boolean {
    var adjacent = 0L
    var twoApart = 0L
    val from = ySize + chromaPlane / 4
    val to = minOf(ySize + chromaPlane * 3 / 4, data.size - 3)
    var i = from - (from - ySize) % 2
    while (i < to) {
      adjacent += kotlin.math.abs((data[i].toInt() and 0xFF) - (data[i + 1].toInt() and 0xFF))
      twoApart += kotlin.math.abs((data[i].toInt() and 0xFF) - (data[i + 2].toInt() and 0xFF))
      i += 16
    }
    return adjacent > twoApart * 13 / 10
  }

  /** BT.601 limited range. */
  private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
    val c = 298 * (y - 16)
    val d = u - 128
    val e = v - 128
    val r = ((c + 409 * e + 128) shr 8).coerceIn(0, 255)
    val g = ((c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
    val b = ((c + 516 * d + 128) shr 8).coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
  }
}
