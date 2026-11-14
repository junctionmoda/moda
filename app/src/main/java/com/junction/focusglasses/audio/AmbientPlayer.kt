package com.junction.focusglasses.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Calm focus soundscape generated in code (no audio files, no licensing questions): soft brown
 * noise like distant rain, plus a quiet slowly-breathing chord. Plays on the media stream, which
 * Android routes to the glasses' speakers when they are the connected Bluetooth audio device.
 */
class AmbientPlayer {
  private val sampleRate = 22050
  @Volatile private var running = false
  @Volatile private var targetGain = 0.6f
  @Volatile private var duck = 1f
  private var thread: Thread? = null

  val isPlaying: Boolean get() = running

  fun setVolume(volume: Float) {
    targetGain = volume.coerceIn(0f, 1f)
  }

  /** Temporarily lower the music (e.g. while a voice prompt plays). */
  fun setDucked(ducked: Boolean) {
    duck = if (ducked) 0.25f else 1f
  }

  @Synchronized
  fun start() {
    if (running) return
    running = true
    thread = Thread({ loop() }, "ambient").apply { start() }
  }

  @Synchronized
  fun stop() {
    running = false
    thread?.join(500)
    thread = null
  }

  private fun loop() {
    val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val track =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    track.play()
    val chunk = ShortArray(1024)
    val rnd = Random(42)
    var brown = 0.0
    var t = 0L
    var gain = 0f
    // A soft major-seventh-ish pad: A2, E3, C#4, G#4 (Hz)
    val freqs = doubleArrayOf(110.0, 164.81, 277.18, 415.30)
    val amps = doubleArrayOf(0.09, 0.06, 0.035, 0.02)
    try {
      while (running) {
        for (i in chunk.indices) {
          // Brown noise: integrated white noise with a gentle leak.
          brown = (brown + (rnd.nextDouble() * 2 - 1) * 0.02) * 0.997
          val time = t.toDouble() / sampleRate
          // 10-second "breathing" envelope on the pad.
          val breath = 0.55 + 0.45 * sin(2 * PI * time / 10.0)
          var pad = 0.0
          for (k in freqs.indices) pad += amps[k] * sin(2 * PI * freqs[k] * time + k)
          val wanted = targetGain * duck
          gain += (wanted - gain) * 0.0005f // smooth fades, no clicks
          val s = (brown * 1.6 + pad * breath) * gain
          chunk[i] = (s.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.8).toInt().toShort()
          t++
        }
        track.write(chunk, 0, chunk.size)
      }
    } finally {
      track.stop()
      track.release()
    }
  }
}
