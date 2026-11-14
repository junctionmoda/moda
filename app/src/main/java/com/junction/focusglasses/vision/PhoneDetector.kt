package com.junction.focusglasses.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.sqrt

data class PhoneDetection(val score: Float, val box: RectF?, val frameWidth: Int, val frameHeight: Int)

/** A work object (screen or book) seen by the glasses camera. */
data class WorkObject(val label: String, val score: Float, val box: RectF, val frameWidth: Int, val frameHeight: Int) {
  /**
   * Apparent size: sqrt(box area / frame area). Proportional to 1 / distance, so the ratio to a
   * calibrated baseline says how much closer the wearer has moved.
   */
  val size: Float
    get() = sqrt((box.width() * box.height()) / (frameWidth.toFloat() * frameHeight).coerceAtLeast(1f))
}

data class FrameDetections(val phone: PhoneDetection, val screen: WorkObject?, val book: WorkObject?)

/**
 * On-device detector: MediaPipe ObjectDetector with EfficientDet-Lite0 (COCO). Finds phones
 * (distraction) and screens / books (viewing distance). No network, no API key.
 */
class PhoneDetector(context: Context) {
  companion object {
    private val SCREEN_LABELS = setOf("laptop", "tv")
    private const val SCREEN_MIN_SCORE = 0.4f
    private const val BOOK_MIN_SCORE = 0.35f
  }

  private val detector: ObjectDetector? =
      try {
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                .setRunningMode(RunningMode.IMAGE)
                .setCategoryAllowlist(listOf("cell phone", "laptop", "tv", "book"))
                .setScoreThreshold(0.25f)
                .setMaxResults(6)
                .build(),
        )
      } catch (t: Throwable) {
        Log.e("FocusGlasses:Detector", "failed to create detector", t)
        null
      }

  val isReady: Boolean get() = detector != null

  @Synchronized
  fun detect(bitmap: Bitmap): FrameDetections {
    val w = bitmap.width
    val h = bitmap.height
    val none = FrameDetections(PhoneDetection(0f, null, w, h), null, null)
    val d = detector ?: return none
    return try {
      val result = d.detect(BitmapImageBuilder(bitmap).build())
      var phone = PhoneDetection(0f, null, w, h)
      var screen: WorkObject? = null
      var book: WorkObject? = null
      for (det in result.detections()) {
        val cat = det.categories().maxByOrNull { it.score() } ?: continue
        val label = cat.categoryName()
        val score = cat.score()
        val box = det.boundingBox()
        when {
          label == "cell phone" -> if (score > phone.score) phone = PhoneDetection(score, box, w, h)
          label in SCREEN_LABELS && score >= SCREEN_MIN_SCORE -> {
            val o = WorkObject(label, score, box, w, h)
            if (screen == null || o.size > screen.size) screen = o
          }
          label == "book" && score >= BOOK_MIN_SCORE -> {
            val o = WorkObject(label, score, box, w, h)
            if (book == null || o.size > book.size) book = o
          }
        }
      }
      FrameDetections(phone, screen, book)
    } catch (t: Throwable) {
      Log.e("FocusGlasses:Detector", "detect failed", t)
      none
    }
  }
}
