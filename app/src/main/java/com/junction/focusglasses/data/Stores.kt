package com.junction.focusglasses.data

import android.content.Context
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** Demo: the session clock runs [DEMO_SPEED]× faster (a 40 min session takes 2 min) and reminders fire sooner. */
    val demoMode: Boolean = false,
    /** Saved preference: length of one focus block. */
    val focusMinutes: Int = 40,
    val breakMinutes: Int = 5,
    /** Extra time when the user says "not yet" at the check-in. */
    val extendMinutes: Int = 10,
    val ambientOn: Boolean = true,
    val ambientVolume: Float = 0.6f,
    /** Degrees the head may drop below the calibrated posture before it counts as slouching. */
    val postureThresholdDeg: Float = 20f,
    /** "Looking down" reminders. Off by default: heads drop for too many good reasons (notes, keyboard, books). */
    val postureAlertsOn: Boolean = false,
    /** Flip if the angle readout moves the wrong way when you look down. */
    val invertPosture: Boolean = false,
    /** Screen / book looking this many times bigger than at session start counts as too close. */
    val closeRatio: Float = 1.25f,
    val useDnd: Boolean = true,
    val geminiKey: String = "",
    val geminiModel: String = "gemini-3.8-flash",
    /** TTS voice name; blank = automatic (a soft US voice when available). */
    val ttsVoice: String = "",
) {
  companion object {
    const val DEMO_SPEED = 20
  }

  /** How many session seconds pass per real second. */
  val timeScale: Int get() = if (demoMode) DEMO_SPEED else 1
  val focusSeconds: Int get() = focusMinutes * 60
  val breakSeconds: Int get() = breakMinutes * 60
  val extendSeconds: Int get() = extendMinutes * 60
  // Reminder timings are in real seconds.
  val postureHoldSeconds: Int get() = if (demoMode) 4 else 60
  val postureCooldownSeconds: Int get() = if (demoMode) 20 else 180
  /** Demo: a 10° drop is enough, so a clear nod down on camera triggers the reminder. */
  val effectivePostureThresholdDeg: Float get() = if (demoMode) minOf(postureThresholdDeg, 10f) else postureThresholdDeg
  val closeHoldSeconds: Int get() = if (demoMode) 5 else 15
  /** How long the end-of-block question waits for an answer before taking the break. */
  val checkInTimeoutSeconds: Int get() = 25
}

class SettingsStore(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val _settings = MutableStateFlow(load())
  val settings: StateFlow<Settings> = _settings.asStateFlow()
  val current: Settings get() = _settings.value

  private fun load(): Settings {
    val d = Settings()
    return Settings(
        demoMode = prefs.getBoolean("demoMode", d.demoMode),
        focusMinutes = prefs.getInt("focusMinutes", d.focusMinutes),
        breakMinutes = prefs.getInt("breakMinutes", d.breakMinutes),
        extendMinutes = prefs.getInt("extendMinutes", d.extendMinutes),
        ambientOn = prefs.getBoolean("ambientOn", d.ambientOn),
        ambientVolume = prefs.getFloat("ambientVolume", d.ambientVolume),
        postureThresholdDeg = prefs.getFloat("postureThresholdDeg", d.postureThresholdDeg),
        postureAlertsOn = prefs.getBoolean("postureAlertsOn", d.postureAlertsOn),
        invertPosture = prefs.getBoolean("invertPosture", d.invertPosture),
        closeRatio = prefs.getFloat("closeRatioV2", d.closeRatio),
        useDnd = prefs.getBoolean("useDnd", d.useDnd),
        ttsVoice = prefs.getString("ttsVoice", d.ttsVoice) ?: "",
        geminiKey = prefs.getString("geminiKey", d.geminiKey) ?: "",
        // gemini-2.5-flash is closed to new API users (HTTP 404 "no longer available").
        geminiModel = (prefs.getString("geminiModel", d.geminiModel) ?: d.geminiModel).let { if (it == "gemini-2.5-flash") d.geminiModel else it },
    )
  }

  fun update(transform: (Settings) -> Settings) {
    val s = transform(_settings.value)
    prefs
        .edit()
        .putBoolean("demoMode", s.demoMode)
        .putInt("focusMinutes", s.focusMinutes)
        .putInt("breakMinutes", s.breakMinutes)
        .putInt("extendMinutes", s.extendMinutes)
        .putBoolean("ambientOn", s.ambientOn)
        .putFloat("ambientVolume", s.ambientVolume)
        .putFloat("postureThresholdDeg", s.postureThresholdDeg)
        .putBoolean("postureAlertsOn", s.postureAlertsOn)
        .putBoolean("invertPosture", s.invertPosture)
        .putFloat("closeRatioV2", s.closeRatio)
        .putBoolean("useDnd", s.useDnd)
        .putString("ttsVoice", s.ttsVoice)
        .putString("geminiKey", s.geminiKey)
        .putString("geminiModel", s.geminiModel)
        .apply()
    _settings.value = s
  }
}

data class Stats(
    val todayMinutes: Int = 0,
    val todaySessions: Int = 0,
    val streakDays: Int = 0,
    /** Check-in feedback so far: how often the user took the break vs asked for more time. */
    val breaksTaken: Int = 0,
    val extensionsAsked: Int = 0,
)

/** Daily focus minutes and the streak of consecutive days with focus time. Stored locally only. */
class StatsStore(context: Context) {
  private val prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE)
  private val _stats = MutableStateFlow(compute())
  val stats: StateFlow<Stats> = _stats.asStateFlow()

  private fun minutesOn(day: LocalDate) = prefs.getInt("min_$day", 0)

  private fun compute(): Stats {
    val today = LocalDate.now()
    var streak = 0
    var day = if (minutesOn(today) > 0) today else today.minusDays(1)
    while (minutesOn(day) > 0) {
      streak++
      day = day.minusDays(1)
    }
    return Stats(
        minutesOn(today), prefs.getInt("sessions_$today", 0), streak,
        prefs.getInt("fb_break", 0), prefs.getInt("fb_extend", 0),
    )
  }

  fun recordSession(focusedSeconds: Int) {
    val today = LocalDate.now()
    val minutes = (focusedSeconds + 30) / 60
    prefs
        .edit()
        .putInt("min_$today", minutesOn(today) + minutes)
        .putInt("sessions_$today", prefs.getInt("sessions_$today", 0) + 1)
        .apply()
    _stats.value = compute()
  }

  /**
   * Records the answer to "Need a break?" ([extended] = the user wanted more time), with how far
   * into the block it came. Kept as a short log so later sessions can adapt their length.
   */
  fun recordCheckIn(extended: Boolean, focusedMinutes: Int) {
    val key = if (extended) "fb_extend" else "fb_break"
    val entry = "${java.time.LocalDateTime.now()},${if (extended) "extend" else "break"},$focusedMinutes"
    val log = (prefs.getString("fb_log", "").orEmpty().lines().filter { it.isNotBlank() } + entry).takeLast(50)
    prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).putString("fb_log", log.joinToString("\n")).apply()
    _stats.value = compute()
  }
}
