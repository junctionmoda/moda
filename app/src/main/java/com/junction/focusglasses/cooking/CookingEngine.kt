package com.junction.focusglasses.cooking

import android.content.Context
import android.util.Log
import com.junction.focusglasses.ai.GeminiClient
import com.junction.focusglasses.audio.Voice
import com.junction.focusglasses.data.SettingsStore
import com.junction.focusglasses.glasses.GlassesLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class CookingPhase { IDLE, PLANNING, COOKING, PAUSED, FINISHED }

data class CookingUi(
    val phase: CookingPhase = CookingPhase.IDLE,
    val advice: String = "Start Cooking Mode, then use the glasses view when you want advice.",
    val timerRemainingSec: Int? = null,
    val analyzing: Boolean = false,
    val dishesReminderScheduled: Boolean = false,
)

/**
 * A deliberately cautious Cooking Mode. It can show the existing glasses camera feed and submit
 * one user-requested frame to the optional Gemini setting; it does not claim to detect a fridge,
 * flame, or food safety state automatically.
 */
class CookingEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: GlassesLink,
    private val settings: SettingsStore,
    private val voice: Voice,
) {
  private companion object { const val TAG = "FocusGlasses:Cooking" }

  private val _ui = MutableStateFlow(CookingUi())
  val ui: StateFlow<CookingUi> = _ui.asStateFlow()
  private var timerJob: Job? = null
  private var pausedPhase: CookingPhase? = null

  fun start() {
    if (_ui.value.phase == CookingPhase.PAUSED) { resume(); return }
    if (_ui.value.phase != CookingPhase.IDLE) return
    _ui.value = CookingUi(phase = CookingPhase.PLANNING, advice = "Cooking Mode ready. Open the fridge, then ask for a current-view suggestion.")
    voice.say("Cooking mode is ready. I can help with a timer and a meal reminder.")
    Log.i(TAG, "started")
  }

  fun pause() {
    val phase = _ui.value.phase
    if (phase == CookingPhase.IDLE || phase == CookingPhase.FINISHED || phase == CookingPhase.PAUSED) return
    pausedPhase = phase
    timerJob?.cancel()
    _ui.update { it.copy(phase = CookingPhase.PAUSED) }
  }

  fun resume() {
    val phase = pausedPhase ?: return
    _ui.update { it.copy(phase = phase) }
    pausedPhase = null
    _ui.value.timerRemainingSec?.let(::runTimer)
  }

  fun stop() {
    timerJob?.cancel()
    timerJob = null
    pausedPhase = null
    _ui.update { it.copy(phase = CookingPhase.IDLE, timerRemainingSec = null, analyzing = false) }
  }

  /** Manual cue for the current demo; automatic fridge-door recognition is intentionally not claimed. */
  fun fridgeOpened() {
    _ui.update {
      it.copy(advice = "Fridge check: choose one protein, two vegetables, and a staple. Analyze the current view for a tailored idea; add low items to your shopping list manually.")
    }
    voice.say("Choose a protein, two vegetables, and a staple. You can analyze the current view for an idea.")
  }

  fun analyzeCurrentView() {
    if (_ui.value.analyzing) return
    val currentSettings = settings.current
    if (currentSettings.geminiKey.isBlank()) {
      _ui.update { it.copy(advice = "Add a Gemini API key in Settings before sending a one-time camera frame for analysis.") }
      return
    }
    _ui.update { it.copy(analyzing = true, advice = "Analyzing one current glasses frame…") }
    scope.launch {
      val frame = withTimeoutOrNull(8_000) { link.frames.first() }
      val suggestion = frame?.let { GeminiClient.describeCookingFrame(it, currentSettings.geminiKey, currentSettings.geminiModel) }
      _ui.update {
        it.copy(
            analyzing = false,
            advice = suggestion ?: "No useful frame suggestion yet. Check the food and heat yourself, then try again with a clearer view.",
        )
      }
    }
  }

  fun startTenMinuteTimer() {
    _ui.update { it.copy(phase = CookingPhase.COOKING, advice = "Ten-minute cooking timer started. Check heat and food safety yourself.") }
    runTimer(10 * 60)
  }

  private fun runTimer(initialSeconds: Int) {
    timerJob?.cancel()
    timerJob = scope.launch {
      var left = initialSeconds
      val scale = settings.current.timeScale
      while (left > 0 && _ui.value.phase != CookingPhase.PAUSED) {
        _ui.update { it.copy(timerRemainingSec = left) }
        delay(1000L / scale)
        left--
      }
      if (left == 0) {
        _ui.update { it.copy(timerRemainingSec = null, advice = "Timer finished. Check the food visually and with a food thermometer when appropriate.") }
        voice.say("Your cooking timer is finished. Please check the food before serving.")
      }
    }
  }

  fun mealFinished() {
    timerJob?.cancel()
    val delayMs = if (settings.current.demoMode) 60_000L else 60 * 60 * 1000L
    CookingDishReminder.schedule(context, delayMs)
    _ui.update {
      it.copy(
          phase = CookingPhase.FINISHED,
          timerRemainingSec = null,
          dishesReminderScheduled = true,
          advice = "Meal finished. A dishes reminder is scheduled in ${if (settings.current.demoMode) "one minute (demo)" else "about one hour"}.",
        )
    }
    voice.say("Enjoy your meal. I will remind you about the dishes later.")
  }
}
