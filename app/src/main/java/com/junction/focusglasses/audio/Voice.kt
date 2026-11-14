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

/**
 * Spoken prompts and chimes. Uses the media stream so it reaches the glasses' open-ear speakers
 * over Bluetooth. Ducks the ambient music while speaking.
 */
class Voice(context: Context, private val ambient: AmbientPlayer) {
  private var ready = false
  private val ids = AtomicInteger()
  private val pending = mutableListOf<String>()

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
    val zh = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
    if (zh == TextToSpeech.LANG_MISSING_DATA || zh == TextToSpeech.LANG_NOT_SUPPORTED) {
      Log.w("FocusGlasses:Voice", "Chinese TTS voice not installed; falling back to default")
    }
    tts.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
    )
    tts.setSpeechRate(0.95f)
    tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) = ambient.setDucked(true)
          override fun onDone(utteranceId: String?) {
            if (!tts.isSpeaking) ambient.setDucked(false)
          }
          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) = ambient.setDucked(false)
        },
    )
    ready = true
    pending.forEach { speakNow(it) }
    pending.clear()
  }

  @Synchronized
  fun say(text: String) {
    if (!ready) {
      pending += text
      return
    }
    speakNow(text)
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
