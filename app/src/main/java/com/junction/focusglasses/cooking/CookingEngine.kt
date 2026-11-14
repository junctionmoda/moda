package com.junction.focusglasses.cooking

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.junction.focusglasses.ai.FocusLive
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
 * A deliberately cautious Cooking Mode. In addition to a manual look command, it can make
 * infrequent, opt-in scene-change-gated camera checks. Gemini may suggest a next step, but is
 * never treated as a food-safety, fire, or burn detector.
 */
class CookingEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: GlassesLink,
    private val settings: SettingsStore,
    private val voice: Voice,
    private val live: FocusLive,
) {
  private companion object {
    const val TAG = "FocusGlasses:Cooking"
    private const val PROACTIVE_WARMUP_MS = 8_000L
    private const val PROACTIVE_INTERVAL_MS = 45_000L
    private const val SCENE_CHANGE_THRESHOLD = 18
  }

  private val _ui = MutableStateFlow(CookingUi())
  val ui: StateFlow<CookingUi> = _ui.asStateFlow()
  private var timerJob: Job? = null
  private var pausedPhase: CookingPhase? = null
  private var modeStartedAt = 0L
  private var lastProactiveAt = 0L
  private var lastScene: IntArray? = null

  init {
    scope.launch {
      link.frames.collect { frame -> maybeInspectChangedScene(frame) }
    }
  }

  fun start() {
    if (_ui.value.phase == CookingPhase.PAUSED) { resume(); return }
    if (_ui.value.phase != CookingPhase.IDLE) return
    modeStartedAt = SystemClock.elapsedRealtime()
    lastProactiveAt = 0L
    lastScene = null
    _ui.value = CookingUi(phase = CookingPhase.PLANNING, advice = if (settings.current.cookingProactiveVision) "Cooking Mode is watching for meaningful scene changes. It will offer occasional camera-based suggestions." else "Cooking Mode ready. Ask for a current-view suggestion, or enable proactive camera prompts.")
    speak(
        "cooking_started: Cooking mode is on. Offer hands-free timers and ideas from what they see. One sentence.",
        "Cooking mode is on. Say what can I cook, or set a ten minute timer.",
    )
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
    lastScene = null
    _ui.update { it.copy(phase = CookingPhase.IDLE, timerRemainingSec = null, analyzing = false) }
  }

  /** Manual cue for the current demo; automatic fridge-door recognition is intentionally not claimed. */
  fun fridgeOpened() {
    _ui.update {
      it.copy(advice = "Fridge check: choose one protein, two vegetables, and a staple. Analyze the current view for a tailored idea; add low items to your shopping list manually.")
    }
    speak(
        "fridge_opened: suggest picking a protein, two vegetables and a staple; offer to look at the fridge.",
        "Choose a protein, two vegetables, and a staple. You can analyze the current view for an idea.",
    )
  }

  fun analyzeCurrentView() {
    if (_ui.value.analyzing) return
    val currentSettings = settings.current
    val snapshot = link.colorSnapshot()
    if (useLive && snapshot != null) {
      // Focus Live sees the color frame and answers out loud; no separate request needed.
      live.image(snapshot)
      live.event("cooking_look: the user wants an idea from this view. Name the visible ingredients and give one short meal idea.")
      _ui.update { it.copy(advice = "Focus Live is looking at the current view…") }
      return
    }
    if (currentSettings.geminiKey.isBlank()) {
      _ui.update { it.copy(advice = "Add a Gemini API key in Settings before sending a one-time camera frame for analysis.") }
      return
    }
    _ui.update { it.copy(analyzing = true, advice = "Analyzing one current glasses frame…") }
    voice.say("Let me take a look.")
    scope.launch {
      val frame = snapshot ?: withTimeoutOrNull(8_000) { link.frames.first() }
      val suggestion = frame?.let { GeminiClient.describeCookingFrame(it, currentSettings.geminiKey, currentSettings.geminiModel) }
      _ui.update {
        it.copy(
            analyzing = false,
            advice = suggestion ?: "No useful frame suggestion yet. Check the food and heat yourself, then try again with a clearer view.",
        )
      }
      Log.i(TAG, "view suggestion: ${suggestion ?: "none"}")
      voice.say(suggestion ?: "Sorry, I couldn't get a suggestion. Try again with a clearer view.")
    }
  }

  /** Only requests vision after a meaningful low-resolution scene change and a long cooldown. */
  private fun maybeInspectChangedScene(frame: Bitmap) {
    val phase = _ui.value.phase
    if (!settings.current.cookingProactiveVision || (phase != CookingPhase.PLANNING && phase != CookingPhase.COOKING) || _ui.value.analyzing) return
    val now = SystemClock.elapsedRealtime()
    if (now - modeStartedAt < PROACTIVE_WARMUP_MS || now - lastProactiveAt < PROACTIVE_INTERVAL_MS) return
    val signature = sceneSignature(frame)
    val previous = lastScene
    if (previous != null && sceneDifference(previous, signature) < SCENE_CHANGE_THRESHOLD) return
    lastScene = signature
    lastProactiveAt = now
    inspectSceneProactively()
  }

  private fun inspectSceneProactively() {
    val currentSettings = settings.current
    if (currentSettings.geminiKey.isBlank()) {
      _ui.update { it.copy(advice = "Proactive camera prompts need a Gemini API key. No frame was sent.") }
      return
    }
    val snapshot = link.colorSnapshot() ?: return
    _ui.update { it.copy(analyzing = true, advice = "Checking a changed kitchen scene…") }
    scope.launch {
      val suggestion = GeminiClient.describeCookingScene(snapshot, currentSettings.geminiKey, currentSettings.geminiModel)
      val useful = suggestion?.takeUnless { it.equals("NO_ACTION", ignoreCase = true) }
      _ui.update { current ->
        current.copy(analyzing = false, advice = useful ?: "No useful camera prompt for this scene; staying quiet.")
      }
      if (useful != null) {
        Log.i(TAG, "proactive scene suggestion: $useful")
        speak("cooking_camera_observation: $useful Speak only this short, cautious suggestion.", useful)
      }
    }
  }

  /** 8×8 luma signature: a cheap local gate, not a claim to recognize kitchen objects. */
  private fun sceneSignature(bitmap: Bitmap): IntArray {
    val values = IntArray(64)
    var i = 0
    for (y in 0 until 8) for (x in 0 until 8) {
      val color = bitmap.getPixel((x + 1) * bitmap.width / 9, (y + 1) * bitmap.height / 9)
      values[i++] = (android.graphics.Color.red(color) * 30 + android.graphics.Color.green(color) * 59 + android.graphics.Color.blue(color) * 11) / 100
    }
    return values
  }

  private fun sceneDifference(before: IntArray, after: IntArray): Int =
      before.indices.sumOf { kotlin.math.abs(before[it] - after[it]) } / before.size

  fun startTenMinuteTimer() = startTimer(10)

  /** Starts (or restarts) the cooking timer; returns the minutes set. */
  fun startTimer(minutes: Int, announce: Boolean = false): Int {
    val m = minutes.coerceIn(1, 180)
    timerMinutes = m
    _ui.update { it.copy(phase = CookingPhase.COOKING, advice = "$m-minute cooking timer started. Check heat and food safety yourself.") }
    runTimer(m * 60)
    Log.i(TAG, "timer $m min")
    if (announce) speak("cooking_timer_started: a $m-minute timer is running. One short sentence.", "Okay, $m minute timer started.")
    return m
  }

  private var timerMinutes = 10

  private fun speak(event: String, fallback: String) {
    if (useLive) live.event(event) else voice.say(fallback)
  }

  private val useLive: Boolean get() = settings.current.cookingLive && live.isReady

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
        speak(
            "cooking_timer_done: the $timerMinutes-minute timer finished. Remind them to check the food.",
            "Your cooking timer is finished. Please check the food before serving.",
        )
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
    speak(
        "meal_finished: wish them a nice meal; a dishes reminder is set for later.",
        "Enjoy your meal. I will remind you about the dishes later.",
    )
  }
}
