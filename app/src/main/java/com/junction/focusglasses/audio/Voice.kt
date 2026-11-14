package com.junction.focusglasses.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spoken prompts and chimes. Uses the media stream so it reaches the glasses' open-ear speakers
 * over Bluetooth. Ducks the ambient music while speaking.
 */
class Voice(context: Context, private val ambient: AmbientPlayer) {
  private var ready = false
  private val ids = AtomicInteger()
  private val pending = mutableListOf<String>()
  private val _speaking = MutableStateFlow(false)
  /** True while a prompt is being spoken (the voice-command recognizer pauses then). */
  val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

  private val _voices = MutableStateFlow<List<String>>(emptyList())
  /** English voices of the TTS engine, best candidates first (US, natural "network" voices). */
  val voices: StateFlow<List<String>> = _voices.asStateFlow()
  private val _currentVoice = MutableStateFlow<String?>(null)
  val currentVoice: StateFlow<String?> = _currentVoice.asStateFlow()
  /** Voice chosen in Settings; blank = pick automatically. */
  private var preferredVoice = ""

  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          onTtsReady()
        } else {
          Log.w("FocusGlasses:Voice", "TTS init failed: $status")
        }
      }

  private val tone: ToneGenerator? =
      try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 70)
      } catch (t: Throwable) {
        null
      }

  @Synchronized
  private fun onTtsReady() {
    val en = tts.setLanguage(Locale.US)
    if (en == TextToSpeech.LANG_MISSING_DATA || en == TextToSpeech.LANG_NOT_SUPPORTED) {
      Log.w("FocusGlasses:Voice", "English TTS voice not installed; falling back to default")
    }
    val english =
        (tts.voices ?: emptySet())
            .filter { it.locale.language == "en" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(
                compareBy(
                    { if (it.locale.country == "US") 0 else 1 },
                    { if (it.name.contains("network")) 0 else 1 },
                    { -it.quality },
                    { it.name },
                ),
            )
    _voices.value = english.map { it.name }
    Log.i("FocusGlasses:Voice", "TTS engine ${tts.defaultEngine}, ${english.size} English voices: ${english.joinToString { it.name }}")
    applyVoice()
    tts.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
    )
    // Slightly slower and lower than default: calmer, less "announcement".
    tts.setSpeechRate(0.88f)
    tts.setPitch(0.95f)
    tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            _speaking.value = true
            ambient.setDucked(true)
          }
          override fun onDone(utteranceId: String?) {
            if (!tts.isSpeaking) {
              ambient.setDucked(false)
              _speaking.value = false
            }
          }
          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            ambient.setDucked(false)
            _speaking.value = false
          }
        },
    )
    ready = true
    pending.forEach { speakNow(it) }
    pending.clear()
  }

  /** Soft, natural-sounding US voices of Google's engine, in order of preference. */
  private val softDefaults =
      listOf("en-us-x-tpc-network", "en-us-x-sfg-network", "en-us-x-tpf-network", "en-us-x-tpc-local", "en-us-x-sfg-local")

  @Synchronized
  fun setPreferredVoice(name: String) {
    preferredVoice = name
    if (ready) applyVoice()
  }

  private fun applyVoice() {
    val all = tts.voices ?: return
    val byName = all.associateBy { it.name }
    val pick =
        byName[preferredVoice]
            ?: softDefaults.firstNotNullOfOrNull { byName[it] }
            ?: _voices.value.firstOrNull()?.let { byName[it] }
            ?: return
    tts.voice = pick
    _currentVoice.value = pick.name
    Log.i("FocusGlasses:Voice", "using voice ${pick.name}")
  }

  /** Speaks a sample line in [name] right away (Settings preview). */
  @Synchronized
  fun preview(name: String) {
    if (!ready) return
    tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it }
    tts.speak("Welcome back. Your forty-minute focus session is ready.", TextToSpeech.QUEUE_FLUSH, null, "preview")
    tts.voices?.firstOrNull { it.name == (preferredVoice.ifBlank { _currentVoice.value }) }?.let { tts.voice = it }
  }

  @Synchronized
  fun say(text: String) {
    if (!ready) {
      pending += text
      return
    }
    speakNow(text)
  }

  /** Gives the wearer the floor immediately (for example when glasses Speech sees a partial). */
  @Synchronized
  fun interrupt() {
    pending.clear()
    if (ready) tts.stop()
    ambient.setDucked(false)
    _speaking.value = false
  }

  private fun speakNow(text: String) {
    tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u${ids.incrementAndGet()}")
  }

  fun chimeStart() = tone?.startTone(ToneGenerator.TONE_PROP_ACK, 250)

  fun chimeEnd() = tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)

  fun softTick() = tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)

  fun shutdown() {
    tts.shutdown()
    tone?.release()
  }
}
