package com.junction.focusglasses.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Calm focus music generated in code, never the same twice (no audio files, no licensing):
 * a random key, a slowly wandering chord progression on a soft pad, and a sparse pentatonic
 * melody in an electric-piano-like tone, through a gentle echo. Plays on the media stream, which
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

  // region Music

  /** One sounding note. Times in samples; amplitude envelope = attack, hold, then release/decay. */
  private class Note(
      freq: Double,
      val amp: Double,
      val start: Long,
      val attack: Int,
      /** Pad: sample where the release starts. Bell: unused (decays from the start). */
      val releaseAt: Long,
      val release: Int,
      val bell: Boolean,
      sampleRate: Int,
  ) {
    val step = 2 * PI * freq / sampleRate
    // A slightly detuned twin for the pad gives a slow, warm chorus.
    val step2 = step * 1.004
    var phase = 0.0
    var phase2 = 0.0
    /** Bell decay per sample (about 1.8 s to -40 dB). */
    val decay = exp(-4.6 / (1.8 * sampleRate))
    var level = 1.0

    private val bellLength = 4L * sampleRate

    fun done(t: Long) = if (bell) t - start > bellLength else t > releaseAt + release

    fun sample(t: Long): Double {
      val age = t - start
      if (age < 0) return 0.0
      val env =
          if (bell) {
            level *= decay
            (age.toDouble() / attack).coerceAtMost(1.0) * level
          } else {
            val a = (age.toDouble() / attack).coerceAtMost(1.0)
            val r = if (t < releaseAt) 1.0 else (1.0 - (t - releaseAt).toDouble() / release).coerceAtLeast(0.0)
            a * r
          }
      phase += step
      val v =
          if (bell) {
            sin(phase) + 0.22 * sin(2 * phase) + 0.08 * sin(3 * phase)
          } else {
            phase2 += step2
            0.5 * (sin(phase) + sin(phase2)) + 0.12 * sin(2 * phase)
          }
      return v * env * amp
    }
  }

  private fun midiToHz(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)

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

    val rnd = Random(System.nanoTime())
    // Random key: tonic between G3 and D4.
    val tonic = listOf(55, 57, 58, 60, 62).random(rnd)
    // Diatonic chords as scale-step offsets from the tonic (major key): I, ii, iii, IV, V, vi.
    val chords =
        mapOf(
            "I" to intArrayOf(0, 4, 7, 11), "ii" to intArrayOf(2, 5, 9, 12), "iii" to intArrayOf(4, 7, 11, 14),
            "IV" to intArrayOf(5, 9, 12, 16), "V" to intArrayOf(7, 11, 14, 17), "vi" to intArrayOf(9, 12, 16, 19),
        )
    // Gentle progressions only (no V→… tension resolutions needed for background music).
    val next =
        mapOf(
            "I" to listOf("IV", "vi", "ii", "iii"), "ii" to listOf("IV", "V", "vi"), "iii" to listOf("vi", "IV"),
            "IV" to listOf("I", "vi", "ii", "V"), "V" to listOf("vi", "IV", "I"), "vi" to listOf("IV", "ii", "I", "iii"),
        )
    val pentatonic = intArrayOf(0, 2, 4, 7, 9)

    val notes = ArrayList<Note>()
    var chord = "I"
    var nextChordAt = 0L
    var nextMelodyAt = (sampleRate * 2.5).toLong()
    var lastMelody = tonic + 12

    // Echo: 0.42 s feedback delay.
    val delay = DoubleArray((sampleRate * 0.42).toInt())
    var delayPos = 0
    var lowpass = 0.0
    var gain = 0f
    var t = 0L
    val chunk = ShortArray(1024)

    try {
      while (running) {
        for (i in chunk.indices) {
          if (t >= nextChordAt) {
            val len = sampleRate * (7 + rnd.nextInt(4)) // 7–10 s per chord
            val tones = chords.getValue(chord)
            // Bass an octave below, then the four chord tones, quietly.
            notes += Note(midiToHz(tonic + tones[0] - 12), 0.10, t, sampleRate * 2, t + len, sampleRate * 3, false, sampleRate)
            for ((k, iv) in tones.withIndex()) {
              notes += Note(midiToHz(tonic + iv), 0.055 - k * 0.006, t, sampleRate * 2, t + len, sampleRate * 3, false, sampleRate)
            }
            nextChordAt = t + len
            chord = next.getValue(chord).random(rnd)
          }
          if (t >= nextMelodyAt) {
            // Sparse melody: small steps around the last note, on the pentatonic scale an octave up.
            if (rnd.nextFloat() < 0.7f) {
              val candidates =
                  (0..2).flatMap { oct -> pentatonic.map { tonic + 12 * oct + it } }.filter { kotlin.math.abs(it - lastMelody) in 1..5 }
              val m = candidates.randomOrNull(rnd) ?: (tonic + 12)
              lastMelody = m.coerceIn(tonic + 7, tonic + 26)
              notes += Note(midiToHz(lastMelody), 0.13, t, (sampleRate * 0.01).toInt(), 0, 0, true, sampleRate)
            }
            nextMelodyAt = t + (sampleRate * listOf(0.6, 0.9, 1.2, 1.8, 2.4).random(rnd)).toLong()
          }

          var dry = 0.0
          for (n in notes) dry += n.sample(t)
          val echo = delay[delayPos]
          delay[delayPos] = dry + echo * 0.38
          delayPos = (delayPos + 1) % delay.size
          // Soft one-pole low-pass keeps it warm and takes the edge off.
          lowpass += (dry + echo * 0.35 - lowpass) * 0.35
          val wanted = targetGain * duck
          gain += (wanted - gain) * 0.0005f // smooth fades, no clicks
          chunk[i] = ((lowpass * gain).coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.85).toInt().toShort()
          t++
        }
        notes.removeAll { it.done(t) }
        track.write(chunk, 0, chunk.size)
      }
    } finally {
      track.stop()
      track.release()
    }
  }

  // endregion
}
