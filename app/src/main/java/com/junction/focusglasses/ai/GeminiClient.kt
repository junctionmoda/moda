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
 * task in a few words ("reading", "writing code"). Only used when an API key is set in Settings. The frame
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
              "This is a first-person photo from smart glasses worn by someone about to start focused work or study. " +
                  "Name the task they are doing in at most 4 words, e.g. reading, writing code, math homework, writing an essay. Output only the task, lowercase."
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

  /** User-triggered only: one frame is sent and never stored by this app. */
  suspend fun describeCookingFrame(bitmap: Bitmap, apiKey: String, model: String): String? =
      withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
          val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
          val body =
              JSONObject().put(
                  "contents",
                  JSONArray().put(
                      JSONObject().put(
                          "parts",
                          JSONArray()
                              .put(JSONObject().put("text", "You are a cautious cooking helper. Describe only visible ingredients or cookware in this first-person kitchen image, then give one short meal idea and one optional shopping-list item. Never claim that food is cooked, safe, burnt, or unsafe; tell the cook to check heat and food safety themselves. Keep under 45 words."))
                              .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))),
                      ),
                  ),
              )
          val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection)
          conn.requestMethod = "POST"
          conn.connectTimeout = 8000
          conn.readTimeout = 15000
          conn.doOutput = true
          conn.setRequestProperty("Content-Type", "application/json")
          conn.setRequestProperty("x-goog-api-key", apiKey)
          conn.outputStream.use { it.write(body.toString().toByteArray()) }
          if (conn.responseCode !in 200..299) return@withContext null
          JSONObject(conn.inputStream.bufferedReader().readText())
              .getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
              .getJSONArray("parts").getJSONObject(0).getString("text").trim().take(300)
        } catch (t: Throwable) {
          Log.w(TAG, "describeCookingFrame failed", t)
          null
        }
      }
}
