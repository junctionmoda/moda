package com.junction.focusglasses.ai

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.junction.focusglasses.audio.AmbientPlayer
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

enum class LiveState { OFF, CONNECTING, READY, ERROR }

/**
 * "Focus Live": a Gemini Live session tuned for deep work. The phone microphone streams up,
 * Gemini's native-audio voice plays back into the glasses, local detections arrive as text
 * [event]s, and session actions come back as function calls handled by [toolHandler].
 *
 * Reconnects transparently (the server closes connections after ~10 min) using session
 * resumption, with context window compression so a session can outlast a long focus block.
 */
class FocusLive(private val scope: CoroutineScope, private val ambient: AmbientPlayer) {
  companion object {
    private const val TAG = "FocusGlasses:Live"
    private const val URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private const val IN_RATE = 16_000
    private const val OUT_RATE = 24_000
    private const val MAX_FAILED_CONNECTS = 3

    val SYSTEM_PROMPT =
        """
        You are Focus Live, a calm companion that speaks through the user's smart glasses.
        The app has modes, and you are the voice of all of them:
        - Work: focus blocks with breaks; the app watches for the phone and for leaning too close to the screen.
        - Cooking: hands-free kitchen help with timers and ideas from what the camera sees.
        - Study and Relax: quiet companionship; keep check-ins rare and gentle.
        - Meeting: calendar meeting reminders; the app handles them, so stay quiet unless asked.
        The app does the timing and watches through the glasses camera.

        Style: warm, calm and brief. One or two short sentences, under 20 words. Never lecture or guilt-trip.
        Always speak English, even if you hear another language: other languages are background chatter, ignore them.

        Messages that start with [EVENT] come from the app, not from the user. Always respond to an [EVENT] out loud,
        naturally, using its details (minutes in, the task, progress). Never read field names or brackets aloud.

        When the user speaks to you, answer briefly. If speech is background chatter or not meant for you, stay silent.

        Use the tools to act, only when the user clearly asks:
        - switch_mode / end_mode to change or finish a mode (start_work_mode and end_work_mode also work for Work).
        - take_break, extend_focus during Work. At a check-in (event block_finished) ask whether they need a break:
          if yes call take_break; if they want to keep going call extend_focus (10 minutes unless they name a number).
          They may also ask for a break or more time in the middle of a block: use the same tools.
        - cooking_timer and meal_finished in Cooking.
        - look when the user asks about what they are seeing; you then receive one camera image. Describe what matters
          for the current mode in one or two sentences.
        - get_status for questions about time left, progress, the active mode or stats; never invent numbers.
        In Cooking never claim food is cooked, safe or burnt: remind them to check heat and food safety themselves.
        After a tool call, confirm in a few words using what the tool returned.
        """
            .trimIndent()
  }

  /** Runs a function call from Gemini on the main thread; returns the JSON response. */
  var toolHandler: (name: String, args: JSONObject) -> JSONObject = { _, _ -> JSONObject().put("error", "not ready") }

  private val _state = MutableStateFlow(LiveState.OFF)
  val state: StateFlow<LiveState> = _state.asStateFlow()
  private val _status = MutableStateFlow("Focus Live: off")
  val status: StateFlow<String> = _status.asStateFlow()
  private val _speaking = MutableStateFlow(false)
  val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
  /** Last exchange, for the on-screen readout. */
  private val _transcript = MutableStateFlow("")
  val transcript: StateFlow<String> = _transcript.asStateFlow()

  val isReady: Boolean get() = _state.value == LiveState.READY

  /** True while a Gemini function call is being handled (main thread). */
  private var inTool = false

  // No ping interval: the server doesn't always answer pings in time, and OkHttp then drops a
  // healthy connection ("timeout"). The mic stream keeps the connection busy anyway.
  private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
  private var socket: WebSocket? = null
  private var wanted = false
  private var key = ""
  private var model = ""
  private var voiceName = ""
  private var resumeHandle: String? = null
  private var failedConnects = 0
  private var setupDone = false

  private var micThread: Thread? = null
  @Volatile private var micOn = false
  private var playThread: Thread? = null
  private val playQueue = LinkedBlockingQueue<ByteArray>()
  @Volatile private var flushPlayback = false

  private val userText = StringBuilder()
  private val modelText = StringBuilder()

  // region Connection

  fun connect(apiKey: String, model: String, voice: String) {
    if (wanted && apiKey == key && model == this.model && voice == voiceName) return
    disconnect()
    key = apiKey
    this.model = model
    voiceName = voice
    wanted = true
    failedConnects = 0
    resumeHandle = null
    openSocket()
  }

  fun disconnect() {
    wanted = false
    socket?.close(1000, "bye")
    socket = null
    stopAudio()
    setupDone = false
    _state.value = LiveState.OFF
    _status.value = "Focus Live: off"
  }

  private fun openSocket() {
    setupDone = false
    _state.value = LiveState.CONNECTING
    _status.value = if (resumeHandle == null) "Focus Live: connecting…" else "Focus Live: reconnecting…"
    Log.i(TAG, "connecting (model $model, attempt ${failedConnects + 1}, resume=${resumeHandle != null})")
    val request = Request.Builder().url("$URL?key=$key").build()
    socket = http.newWebSocket(request, listener)
  }

  private val listener =
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          webSocket.send(setupMessage().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          Log.i(TAG, "closing $code $reason")
          webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDropped(webSocket, "closed $code $reason")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            onDropped(webSocket, "failure ${t.message} ${response?.code ?: ""}")
      }

  private fun onDropped(webSocket: WebSocket, why: String) {
    if (webSocket != socket) return
    Log.w(TAG, "connection $why")
    stopAudio()
    scope.launch(Dispatchers.Main) {
      if (!wanted) return@launch
      if (!setupDone) failedConnects++
      if (failedConnects >= MAX_FAILED_CONNECTS) {
        wanted = false
        _state.value = LiveState.ERROR
        _status.value = "Focus Live: unavailable ($why), using offline voice"
        return@launch
      }
      delay(if (setupDone) 200 else 1500)
      if (wanted) openSocket()
    }
  }

  private fun setupMessage(): JSONObject {
    fun fn(name: String, description: String, params: JSONObject? = null) =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("behavior", "BLOCKING")
            .apply { if (params != null) put("parameters", params) }
    val minutesParam =
        JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject().put("minutes", JSONObject().put("type", "INTEGER").put("description", "Extra focus minutes, default 10")),
            )
    val modeParam =
        JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject().put("mode", JSONObject().put("type", "STRING").put("enum", JSONArray().put("work").put("study").put("cooking").put("relax").put("meeting"))),
            )
            .put("required", JSONArray().put("mode"))
    val timerParam =
        JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject().put("minutes", JSONObject().put("type", "INTEGER").put("description", "Timer length in minutes")))
            .put("required", JSONArray().put("minutes"))
    val tools =
        JSONArray()
            .put(fn("start_work_mode", "Start a focus session with the user's saved preferences."))
            .put(fn("end_work_mode", "End the focus session and get the summary (minutes focused, distractions, trees)."))
            .put(fn("take_break", "The user wants a break now (at a check-in, or ending the current block early)."))
            .put(fn("extend_focus", "The user wants more focus time: at a check-in, or added to the current block.", minutesParam))
            .put(fn("get_status", "Active mode, phase, minutes left, minutes focused, distractions, trees grown, task, cooking timer."))
            .put(fn("switch_mode", "Switch to a mode (the current one is paused).", modeParam))
            .put(fn("end_mode", "Finish the active mode (for Work: returns the session summary)."))
            .put(fn("cooking_timer", "Start a cooking timer.", timerParam))
            .put(fn("meal_finished", "The user finished eating; schedules a gentle dishes reminder."))
            .put(fn("look", "Get one image of what the user is looking at right now."))
    val setup =
        JSONObject()
            .put("model", "models/$model")
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject()
                            .put("languageCode", "en-US")
                            .put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName))),
                    ),
            )
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", JSONObject().apply { resumeHandle?.let { put("handle", it) } })
    return JSONObject().put("setup", setup)
  }

  // endregion

  // region Server messages

  private fun handle(webSocket: WebSocket, text: String) {
    if (webSocket != socket) return
    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
    when {
      msg.has("setupComplete") -> {
        setupDone = true
        failedConnects = 0
        Log.i(TAG, "setup complete (model $model, voice $voiceName, resumed=${resumeHandle != null})")
        startAudio()
        scope.launch(Dispatchers.Main) {
          _state.value = LiveState.READY
          _status.value = "Focus Live: listening ($voiceName)"
        }
      }
      msg.has("serverContent") -> onServerContent(msg.getJSONObject("serverContent"))
      msg.has("toolCall") -> onToolCall(webSocket, msg.getJSONObject("toolCall"))
      msg.has("toolCallCancellation") -> Log.i(TAG, "tool call cancelled ${msg.getJSONObject("toolCallCancellation")}")
      msg.has("sessionResumptionUpdate") -> {
        val u = msg.getJSONObject("sessionResumptionUpdate")
        if (u.optBoolean("resumable") && u.optString("newHandle").isNotEmpty()) resumeHandle = u.getString("newHandle")
      }
      msg.has("goAway") -> {
        Log.i(TAG, "goAway ${msg.getJSONObject("goAway")}, reconnecting")
        webSocket.close(1000, "goAway")
      }
      msg.has("error") -> Log.w(TAG, "server error $text")
    }
  }

  private fun onServerContent(c: JSONObject) {
    c.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
      for (i in 0 until parts.length()) {
        val data = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
        if (data.optString("mimeType").startsWith("audio/pcm")) {
          flushPlayback = false
          playQueue.put(Base64.decode(data.getString("data"), Base64.DEFAULT))
        }
      }
    }
    c.optJSONObject("inputTranscription")?.optString("text")?.let { userText.append(it) }
    c.optJSONObject("outputTranscription")?.optString("text")?.let { modelText.append(it) }
    if (c.optBoolean("interrupted")) {
      flushPlayback = true
      playQueue.clear()
    }
    if (c.optBoolean("turnComplete")) {
      val heard = userText.toString().trim()
      val said = modelText.toString().trim()
      userText.clear()
      modelText.clear()
      if (heard.isNotEmpty() || said.isNotEmpty()) {
        Log.i(TAG, "turn: user=\"$heard\" model=\"$said\"")
        scope.launch(Dispatchers.Main) {
          _transcript.value = listOfNotNull(heard.takeIf { it.isNotEmpty() }?.let { "You: $it" }, said.takeIf { it.isNotEmpty() }?.let { "Live: $it" }).joinToString("\n")
        }
      }
    }
  }

  private fun onToolCall(webSocket: WebSocket, call: JSONObject) {
    val calls = call.optJSONArray("functionCalls") ?: return
    scope.launch(Dispatchers.Main) {
      val responses = JSONArray()
      for (i in 0 until calls.length()) {
        val fc = calls.getJSONObject(i)
        val name = fc.optString("name")
        val args = fc.optJSONObject("args") ?: JSONObject()
        // Announcements triggered by the action itself are muted: Gemini confirms from the result.
        inTool = true
        val result = runCatching { toolHandler(name, args) }.getOrElse { JSONObject().put("error", it.message) }
        inTool = false
        Log.i(TAG, "tool $name($args) -> $result")
        responses.put(JSONObject().put("id", fc.optString("id")).put("name", name).put("response", result))
      }
      webSocket.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
    }
  }

  // endregion

  // region Client messages

  /** Tells Gemini something happened; it answers out loud. Interrupts whatever it was saying. */
  fun event(text: String) {
    val s = socket ?: return
    if (inTool) {
      Log.i(TAG, "event muted during tool call: $text")
      return
    }
    Log.i(TAG, "event: $text")
    val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "[EVENT] $text")))
    s.send(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true)).toString())
  }

  /**
   * One glasses frame (sent only at key moments, to keep context and cost small). Encoded on the
   * calling thread so it is queued before anything sent after it (e.g. the `look` tool response).
   */
  fun image(bitmap: Bitmap) {
    val s = socket ?: return
    val scale = 640f / maxOf(bitmap.width, bitmap.height)
    val small =
        if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        else bitmap
    val out = ByteArrayOutputStream()
    small.compress(Bitmap.CompressFormat.JPEG, 75, out)
    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    s.send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject().put("data", b64).put("mimeType", "image/jpeg"))).toString())
    Log.i(TAG, "image ${small.width}x${small.height} sent")
  }

  // endregion

  // region Audio

  @SuppressLint("MissingPermission") // only connected once RECORD_AUDIO is granted
  private fun startAudio() {
    if (micThread != null) return
    micOn = true
    playThread = Thread({ playLoop() }, "live-play").apply { start() }
    micThread =
        Thread(
                {
                  val minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                  val rec =
                      AudioRecord(
                          MediaRecorder.AudioSource.VOICE_RECOGNITION,
                          IN_RATE,
                          AudioFormat.CHANNEL_IN_MONO,
                          AudioFormat.ENCODING_PCM_16BIT,
                          maxOf(minBuf, IN_RATE),
                      )
                  val chunk = ByteArray(IN_RATE / 10 * 2) // 100 ms
                  var paused = false
                  try {
                    rec.startRecording()
                    while (micOn) {
                      val n = rec.read(chunk, 0, chunk.size)
                      if (n <= 0) continue
                      val s = socket ?: continue
                      // Don't let Gemini hear itself through the phone mic while it talks.
                      if (_speaking.value) {
                        if (!paused) {
                          paused = true
                          s.send("""{"realtimeInput":{"audioStreamEnd":true}}""")
                        }
                        continue
                      }
                      paused = false
                      val b64 = Base64.encodeToString(chunk, 0, n, Base64.NO_WRAP)
                      s.send("""{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=$IN_RATE"}}}""")
                    }
                  } catch (t: Throwable) {
                    Log.e(TAG, "mic failed", t)
                  } finally {
                    runCatching { rec.stop() }
                    rec.release()
                  }
                },
                "live-mic",
            )
            .apply { start() }
  }

  private fun stopAudio() {
    micOn = false
    micThread = null
    playThread?.interrupt()
    playThread = null
    playQueue.clear()
    if (_speaking.value) {
      _speaking.value = false
      ambient.setDucked(false)
    }
  }

  private fun playLoop() {
    val minBuf = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val track =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(OUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    track.play()
    var framesWritten = 0L
    try {
      while (!Thread.currentThread().isInterrupted) {
        val pcm = playQueue.poll(50, TimeUnit.MILLISECONDS)
        if (flushPlayback) {
          track.pause()
          track.flush()
          track.play()
          framesWritten = 0
          flushPlayback = false
        }
        if (pcm != null) {
          setSpeaking(true)
          track.write(pcm, 0, pcm.size)
          framesWritten += pcm.size / 2
        } else {
          val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
          if (head >= framesWritten) setSpeaking(false)
        }
      }
    } catch (_: InterruptedException) {
    } finally {
      track.stop()
      track.release()
    }
  }

  private fun setSpeaking(v: Boolean) {
    if (_speaking.value == v) return
    _speaking.value = v
    ambient.setDucked(v)
  }

  // endregion
}
