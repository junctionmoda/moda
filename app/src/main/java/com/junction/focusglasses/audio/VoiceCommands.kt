package com.junction.focusglasses.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

enum class VoiceCommand { START_WORK, END_WORK, YES, NOT_YET }

/**
 * Offline English voice commands (Vosk, small en-us model, phone microphone). The recognizer is
 * restricted to a short phrase list, so it only ever hears the commands below or "[unk]". It is
 * paused while our own TTS speaks so it does not hear itself.
 *
 * Phone mic on purpose: opening the glasses mic switches Bluetooth to the call profile, which
 * drops the music in the glasses to phone-call quality.
 */
class VoiceCommands(private val context: Context, private val scope: CoroutineScope, private val voice: Voice) {
  companion object {
    private const val TAG = "FocusGlasses:Commands"
    private const val SAMPLE_RATE = 16_000f
    private val PHRASES =
        listOf(
            "start work mode", "end work mode", "stop work mode", "finish work mode",
            "yes", "yes please", "sure", "okay", "take a break", "yes take a break", "i need a break",
            "no", "not yet", "no thanks", "not now", "keep going", "more time", "give me more time",
            "i want to finish this", "i want to finish this part", "not yet i want to finish this part",
            "[unk]",
        )

    /** Maps a recognized utterance to a command; null for anything else. */
    fun parse(text: String): VoiceCommand? {
      val t = " ${text.lowercase().trim()} "
      if (t.isBlank()) return null
      val workMode = "work mode" in t
      return when {
        workMode && (" start " in t) -> VoiceCommand.START_WORK
        workMode && (" end " in t || " stop " in t || " finish " in t) -> VoiceCommand.END_WORK
        " not " in t || " no " in t || " finish " in t || "more time" in t || "keep going" in t -> VoiceCommand.NOT_YET
        " yes " in t || " sure " in t || " okay " in t || " break " in t -> VoiceCommand.YES
        else -> null
      }
    }
  }

  private val _commands = MutableSharedFlow<VoiceCommand>(extraBufferCapacity = 4)
  val commands: SharedFlow<VoiceCommand> = _commands.asSharedFlow()

  private val _status = MutableStateFlow("Voice: off")
  val status: StateFlow<String> = _status.asStateFlow()

  /** Last thing the recognizer heard (for the debug readout). */
  private val _heard = MutableStateFlow("")
  val heard: StateFlow<String> = _heard.asStateFlow()

  private var service: SpeechService? = null
  private var starting = false

  /** Loads the model and starts listening. Needs RECORD_AUDIO; safe to call more than once. */
  fun start() {
    if (service != null || starting) return
    starting = true
    _status.value = "Voice: loading model…"
    StorageService.unpack(
        context,
        "model-en-us",
        "model",
        { model: Model -> onModel(model) },
        { e: java.io.IOException ->
          starting = false
          Log.e(TAG, "model unpack failed", e)
          _status.value = "Voice: model missing (${e.message})"
        },
    )
  }

  private fun onModel(model: Model) {
    try {
      val grammar = PHRASES.joinToString(",", "[", "]") { "\"$it\"" }
      val recognizer = Recognizer(model, SAMPLE_RATE, grammar)
      val s = SpeechService(recognizer, SAMPLE_RATE)
      s.startListening(listener)
      service = s
      _status.value = "Voice: listening"
      Log.i(TAG, "listening")
      // Don't listen to ourselves.
      scope.launch {
        voice.speaking.collect { speaking ->
          if (speaking) {
            s.setPause(true)
          } else {
            delay(400)
            if (!voice.speaking.value) s.setPause(false)
          }
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "could not start recognizer", t)
      _status.value = "Voice: error (${t.message})"
    } finally {
      starting = false
    }
  }

  private val listener =
      object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}

        override fun onResult(hypothesis: String?) = handle(hypothesis)

        override fun onFinalResult(hypothesis: String?) = handle(hypothesis)

        override fun onError(exception: Exception?) {
          Log.e(TAG, "recognizer error", exception)
          _status.value = "Voice: error (${exception?.message})"
        }

        override fun onTimeout() {}
      }

  private fun handle(hypothesis: String?) {
    val text = hypothesis?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }.orEmpty()
    if (text.isBlank() || text == "[unk]") return
    val cmd = parse(text.replace("[unk]", " "))
    Log.i(TAG, "heard \"$text\" -> $cmd")
    scope.launch {
      _heard.value = text
      cmd?.let { _commands.emit(it) }
    }
  }
}
