package com.junction.focusglasses.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional Gemini call: looks at one frame from the glasses when a session starts and names the
 * task in a few words ("看书", "写代码"). Only used when an API key is set in Settings. The frame
 * is sent once and not stored.
 */
object GeminiClient {
  private const val TAG = "FocusGlasses:Gemini"

  suspend fun describeTask(bitmap: Bitmap, apiKey: String, model: String): String? =
      withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
          val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
          val prompt =
              "这是一个人戴着眼镜看到的第一人称画面，他准备开始专注工作或学习。" +
                  "用不超过6个汉字说出他正在做的任务，例如：看书、写代码、做数学题、写论文。只输出任务名。"
          val body =
              JSONObject()
                  .put(
                      "contents",
                      JSONArray().put(
                          JSONObject()
                              .put(
                                  "parts",
                                  JSONArray()
                                      .put(JSONObject().put("text", prompt))
                                      .put(
                                          JSONObject()
                                              .put(
                                                  "inline_data",
                                                  JSONObject()
                                                      .put("mime_type", "image/jpeg")
                                                      .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                                              ),
                                      ),
                              ),
                      ),
                  )
          val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
          val conn = (url.openConnection() as HttpURLConnection)
          conn.requestMethod = "POST"
          conn.connectTimeout = 8000
          conn.readTimeout = 15000
          conn.doOutput = true
          conn.setRequestProperty("Content-Type", "application/json")
          conn.setRequestProperty("x-goog-api-key", apiKey)
          conn.outputStream.use { it.write(body.toString().toByteArray()) }
          val code = conn.responseCode
          val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText()
          if (code !in 200..299) {
            Log.w(TAG, "HTTP $code: $text")
            return@withContext null
          }
          JSONObject(text ?: return@withContext null)
              .getJSONArray("candidates")
              .getJSONObject(0)
              .getJSONObject("content")
              .getJSONArray("parts")
              .getJSONObject(0)
              .getString("text")
              .trim()
              .take(12)
        } catch (t: Throwable) {
          Log.w(TAG, "describeTask failed", t)
          null
        }
      }
}
