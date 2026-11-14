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

enum class VoiceCommand {
  START_WORK,
  START_STUDY,
  START_COOKING,
  START_RELAX,
  PAUSE_MODE,
  RESUME_MODE,
  NEXT_MODE,
  PREVIOUS_MODE,
  END_WORK,
  END_MODE,
  YES,
  NOT_YET,
  /** Cooking: "what can I cook" / "what do you see". */
  COOK_IDEA,
  /** Cooking: "set a ten minute timer"; minutes come from [VoiceCommands.parseMinutes]. */
  COOK_TIMER,
  MEAL_DONE,
}

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
    private val MODES = listOf("work", "study", "cooking", "relax")
    private val NUMBERS =
        mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
            "nine" to 9, "ten" to 10, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "twenty five" to 25,
            "thirty" to 30, "forty" to 40, "forty five" to 45, "sixty" to 60,
        )
    private val PHRASES =
        MODES.flatMap { m ->
          listOf("$m mode", "start $m mode", "open $m mode", "switch to $m mode", "end $m mode", "stop $m mode", "exit $m mode")
        } +
            listOf(
                "finish work mode", "end mode", "stop mode",
                "pause mode", "resume mode", "next mode", "previous mode",
                "yes", "yes please", "sure", "okay", "take a break", "yes take a break", "i need a break",
                "no", "not yet", "no thanks", "not now", "keep going", "more time", "give me more time",
                "i want to finish this", "i want to finish this part", "not yet i want to finish this part",
                "what can i cook", "what can i make", "what do you see", "look at this",
                "start a timer", "set a timer", "start timer",
                "meal finished", "i finished eating", "i am done eating", "done eating",
                "[unk]",
            ) +
            NUMBERS.keys.flatMap { n -> listOf("set a $n minute timer", "$n minute timer", "$n minutes") }

    /** Minutes named in a timer request ("set a ten minute timer"), or null. */
    fun parseMinutes(text: String): Int? {
      val t = " ${text.lowercase()} "
      return NUMBERS.entries.sortedByDescending { it.key.length }.firstOrNull { " ${it.key} " in t }?.value
          ?: Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Maps a recognized utterance to a command; null for anything else. */
    fun parse(text: String): VoiceCommand? {
      val t = " ${text.lowercase().trim()} "
      if (t.isBlank()) return null
      val t2 = t.replace(".", " ").replace(",", " ")
      val mode = MODES.firstOrNull { "$it mode" in t2 }
      val ending = listOf(" end ", " stop ", " finish ", " exit ", " close ", " quit ", " and ").any { it in t2 }
      return when {
        // Glasses Speech often hears "end" as "and": "And cooking mode."
        mode != null && ending -> if (mode == "work") VoiceCommand.END_WORK else VoiceCommand.END_MODE
        mode == "work" -> VoiceCommand.START_WORK
        mode == "study" -> VoiceCommand.START_STUDY
        mode == "cooking" -> VoiceCommand.START_COOKING
        mode == "relax" -> VoiceCommand.START_RELAX
        "end mode" in t2 || "stop mode" in t2 -> VoiceCommand.END_MODE
        "pause" in t2 -> VoiceCommand.PAUSE_MODE
        "resume" in t2 || "continue" in t2 -> VoiceCommand.RESUME_MODE
        "next mode" in t2 -> VoiceCommand.NEXT_MODE
        "previous mode" in t2 -> VoiceCommand.PREVIOUS_MODE
        " timer " in t2 -> VoiceCommand.COOK_TIMER
        " cook " in t2 || "can i make" in t2 || "do you see" in t2 || "look at this" in t2 -> VoiceCommand.COOK_IDEA
        "meal finished" in t2 || "done eating" in t2 || "finished eating" in t2 -> VoiceCommand.MEAL_DONE
        " not " in t2 || " no " in t2 || " finish " in t2 || "more time" in t2 || "keep going" in t2 -> VoiceCommand.NOT_YET
        " yes " in t2 || " sure " in t2 || " okay " in t2 || " break " in t2 -> VoiceCommand.YES
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
  private var model: Model? = null
  /** While suspended (Focus Live owns the microphone) the recognizer is released. */
  private var suspended = false
  private var collectingSpeech = false

  /** Loads the model and starts listening. Needs RECORD_AUDIO; safe to call more than once. */
  fun start() {
    if (suspended || service != null || starting) return
    model?.let {
      onModel(it)
      return
    }
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

  /** Releases the microphone for Focus Live, or takes it back. */
  fun setSuspended(value: Boolean) {
    if (suspended == value) return
    suspended = value
    if (value) {
      service?.let {
        it.stop()
        it.shutdown()
      }
      service = null
      _status.value = "Voice: handled by Focus Live"
      Log.i(TAG, "suspended")
    } else {
      start()
    }
  }

  private fun onModel(model: Model) {
    this.model = model
    if (suspended) {
      starting = false
      return
    }
    try {
      val grammar = PHRASES.joinToString(",", "[", "]") { "\"$it\"" }
      val recognizer = Recognizer(model, SAMPLE_RATE, grammar)
      val s = SpeechService(recognizer, SAMPLE_RATE)
      s.startListening(listener)
      service = s
      _status.value = "Voice: listening"
      Log.i(TAG, "listening")
      // Don't listen to ourselves.
      if (!collectingSpeech) {
        collectingSpeech = true
        scope.launch {
          voice.speaking.collect { speaking ->
            if (speaking) {
              service?.setPause(true)
            } else {
              delay(400)
              if (!voice.speaking.value) service?.setPause(false)
            }
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

  /** Accepts a final transcript from either the phone recognizer or DAT glasses Speech. */
  fun submitFinalTranscript(text: String, source: String) {
    if (text.isBlank() || text == "[unk]") return
    val cmd = parse(text.replace("[unk]", " "))
    Log.i(TAG, "$source heard \"$text\" -> $cmd")
    scope.launch {
      _heard.value = text
      cmd?.let { _commands.emit(it) }
    }
  }

  private fun handle(hypothesis: String?) {
    val text = hypothesis?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }.orEmpty()
    submitFinalTranscript(text, source = "phone")
  }
}
