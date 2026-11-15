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

enum class VoiceCommand { START, PAUSE, RESUME, END, YES, NOT_YET }

/** Offline, fixed-phrase controls. The phone mic avoids lowering the glasses audio profile. */
class VoiceCommands(private val context: Context, private val scope: CoroutineScope, private val voice: Voice) {
  companion object {
    private const val TAG = "Moda:Commands"
    private const val SAMPLE_RATE = 16_000f
    private val PHRASES =
        listOf(
            "start focus", "start focus mode", "start work", "start work mode",
            "end focus", "end focus mode", "end work", "end work mode", "stop focus", "stop work",
            "pause", "pause focus", "pause work", "resume", "resume focus", "resume work", "continue",
            "yes", "yes please", "sure", "okay", "take a break", "yes take a break", "i need a break",
            "no", "not yet", "no thanks", "not now", "keep going", "more time", "give me more time",
            "i want to finish this", "i want to finish this part", "not yet i want to finish this part", "[unk]",
        )

    fun parse(text: String): VoiceCommand? {
      val phrase = " ${text.lowercase().replace(".", " ").replace(",", " ").trim()} "
      if (phrase.isBlank()) return null
      return when {
        listOf(" end ", " stop ", " finish ", " exit ").any { it in phrase } -> VoiceCommand.END
        "start" in phrase || "focus mode" in phrase || "work mode" in phrase -> VoiceCommand.START
        "pause" in phrase -> VoiceCommand.PAUSE
        "resume" in phrase || "continue" in phrase -> VoiceCommand.RESUME
        " not " in phrase || " no " in phrase || "more time" in phrase || "keep going" in phrase -> VoiceCommand.NOT_YET
        " yes " in phrase || " sure " in phrase || " okay " in phrase || " break " in phrase -> VoiceCommand.YES
        else -> null
      }
    }
  }

  private val _commands = MutableSharedFlow<VoiceCommand>(extraBufferCapacity = 4)
  val commands: SharedFlow<VoiceCommand> = _commands.asSharedFlow()
  private val _status = MutableStateFlow("Voice: off")
  val status: StateFlow<String> = _status.asStateFlow()
  private val _heard = MutableStateFlow("")
  val heard: StateFlow<String> = _heard.asStateFlow()
  private var service: SpeechService? = null
  private var starting = false
  private var model: Model? = null
  private var collectingSpeech = false

  fun start() {
    if (service != null || starting) return
    model?.let { onModel(it); return }
    starting = true
    _status.value = "Voice: loading model…"
    StorageService.unpack(context, "model-en-us", "model", ::onModel) { error ->
      starting = false
      Log.e(TAG, "model unpack failed", error)
      _status.value = "Voice: model missing (${error.message})"
    }
  }

  private fun onModel(model: Model) {
    this.model = model
    try {
      val grammar = PHRASES.joinToString(",", "[", "]") { "\"$it\"" }
      service = SpeechService(Recognizer(model, SAMPLE_RATE, grammar), SAMPLE_RATE).also { it.startListening(listener) }
      _status.value = "Voice: listening"
      if (!collectingSpeech) {
        collectingSpeech = true
        scope.launch {
          voice.speaking.collect { speaking ->
            if (speaking) service?.setPause(true) else {
              delay(400)
              if (!voice.speaking.value) service?.setPause(false)
            }
          }
        }
      }
    } catch (error: Throwable) {
      Log.e(TAG, "could not start recognizer", error)
      _status.value = "Voice: error (${error.message})"
    } finally {
      starting = false
    }
  }

  private val listener = object : RecognitionListener {
    override fun onPartialResult(hypothesis: String?) = Unit
    override fun onResult(hypothesis: String?) = handle(hypothesis)
    override fun onFinalResult(hypothesis: String?) = handle(hypothesis)
    override fun onError(exception: Exception?) { _status.value = "Voice: error (${exception?.message})" }
    override fun onTimeout() = Unit
  }

  fun submitFinalTranscript(text: String, source: String) {
    if (text.isBlank() || text == "[unk]") return
    val command = parse(text.replace("[unk]", " "))
    Log.i(TAG, "$source heard \"$text\" -> $command")
    scope.launch {
      _heard.value = text
      command?.let { _commands.emit(it) }
    }
  }

  private fun handle(hypothesis: String?) {
    val text = hypothesis?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }.orEmpty()
    submitFinalTranscript(text, source = "phone")
  }
}
