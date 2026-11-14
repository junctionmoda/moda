package com.junction.focusglasses.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

data class PhoneDetection(val score: Float, val box: RectF?, val frameWidth: Int, val frameHeight: Int)

/**
 * On-device phone detector: MediaPipe ObjectDetector with EfficientDet-Lite0 (COCO), restricted
 * to the "cell phone" class. No network, no API key.
 */
class PhoneDetector(context: Context) {
  private val detector: ObjectDetector? =
      try {
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                .setRunningMode(RunningMode.IMAGE)
                .setCategoryAllowlist(listOf("cell phone"))
                .setScoreThreshold(0.25f)
                .setMaxResults(3)
                .build(),
        )
      } catch (t: Throwable) {
        Log.e("FocusGlasses:Detector", "failed to create detector", t)
        null
      }

  val isReady: Boolean get() = detector != null

  /** Best "cell phone" detection in the frame, or score 0 if none. */
  @Synchronized
  fun detect(bitmap: Bitmap): PhoneDetection {
    val d = detector ?: return PhoneDetection(0f, null, bitmap.width, bitmap.height)
    return try {
      val result = d.detect(BitmapImageBuilder(bitmap).build())
      val best =
          result.detections().maxByOrNull { det -> det.categories().maxOfOrNull { it.score() } ?: 0f }
      val score = best?.categories()?.maxOfOrNull { it.score() } ?: 0f
      PhoneDetection(score, best?.boundingBox(), bitmap.width, bitmap.height)
    } catch (t: Throwable) {
      Log.e("FocusGlasses:Detector", "detect failed", t)
      PhoneDetection(0f, null, bitmap.width, bitmap.height)
    }
  }
}
