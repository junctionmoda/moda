package com.junction.focusglasses.data

import android.content.Context
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** Short phases for demos: 2 min focus / 1 min break, faster posture reminders. */
    val demoMode: Boolean = false,
    val ambientOn: Boolean = true,
    val ambientVolume: Float = 0.6f,
    /** Degrees the head may drop below the calibrated posture before it counts as slouching. */
    val postureThresholdDeg: Float = 20f,
    /** Flip if the angle readout moves the wrong way when you look down. */
    val invertPosture: Boolean = false,
    val useDnd: Boolean = true,
    val geminiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
) {
  val focusSeconds: Int get() = if (demoMode) 120 else 25 * 60
  val breakSeconds: Int get() = if (demoMode) 60 else 5 * 60
  val postureHoldSeconds: Int get() = if (demoMode) 15 else 60
  val postureCooldownSeconds: Int get() = if (demoMode) 30 else 180
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
        ambientOn = prefs.getBoolean("ambientOn", d.ambientOn),
        ambientVolume = prefs.getFloat("ambientVolume", d.ambientVolume),
        postureThresholdDeg = prefs.getFloat("postureThresholdDeg", d.postureThresholdDeg),
        invertPosture = prefs.getBoolean("invertPosture", d.invertPosture),
        useDnd = prefs.getBoolean("useDnd", d.useDnd),
        geminiKey = prefs.getString("geminiKey", d.geminiKey) ?: "",
        geminiModel = prefs.getString("geminiModel", d.geminiModel) ?: d.geminiModel,
    )
  }

  fun update(transform: (Settings) -> Settings) {
    val s = transform(_settings.value)
    prefs
        .edit()
        .putBoolean("demoMode", s.demoMode)
        .putBoolean("ambientOn", s.ambientOn)
        .putFloat("ambientVolume", s.ambientVolume)
        .putFloat("postureThresholdDeg", s.postureThresholdDeg)
        .putBoolean("invertPosture", s.invertPosture)
        .putBoolean("useDnd", s.useDnd)
        .putString("geminiKey", s.geminiKey)
        .putString("geminiModel", s.geminiModel)
        .apply()
    _settings.value = s
  }
}

data class Stats(val todayMinutes: Int = 0, val todaySessions: Int = 0, val streakDays: Int = 0)

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
    return Stats(minutesOn(today), prefs.getInt("sessions_$today", 0), streak)
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
}
