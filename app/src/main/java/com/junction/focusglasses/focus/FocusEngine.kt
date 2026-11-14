package com.junction.focusglasses.focus

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.junction.focusglasses.ai.GeminiClient
import com.junction.focusglasses.audio.AmbientPlayer
import com.junction.focusglasses.audio.Voice
import com.junction.focusglasses.data.SettingsStore
import com.junction.focusglasses.data.StatsStore
import com.junction.focusglasses.glasses.GlassesLink
import com.junction.focusglasses.system.DndController
import com.junction.focusglasses.system.FocusService
import com.junction.focusglasses.system.PhonePitchSensor
import com.junction.focusglasses.vision.PhoneDetection
import com.junction.focusglasses.vision.PhoneDetector
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class FocusPhase { IDLE, FOCUS, BREAK }

enum class PitchSource { NONE, GLASSES, PHONE }

data class FocusUi(
    val phase: FocusPhase = FocusPhase.IDLE,
    val remainingSec: Int = 0,
    val phaseTotalSec: Int = 0,
    /** Completed focus rounds in this session. */
    val rounds: Int = 0,
    val sessionFocusedSec: Int = 0,
    /** Current tree: grows over one focus round, loses health on each distraction. */
    val treeGrowth: Float = 0f,
    val treeHealth: Int = 100,
    val treesGrown: Int = 0,
    val distractions: Int = 0,
    val postureAlerts: Int = 0,
    val phoneSeen: Boolean = false,
    val lastPhoneScore: Float = 0f,
    val pitchDeg: Double? = null,
    val baselineDeg: Double? = null,
    val calibrating: Boolean = false,
    val slouching: Boolean = false,
    val pitchSource: PitchSource = PitchSource.NONE,
    val taskLabel: String? = null,
    /** "先停一下" pause card is shown until this elapsedRealtime. */
    val pauseCardUntil: Long = 0,
    val pauseReason: String? = null,
    val dndActive: Boolean = false,
    val summary: String? = null,
    val detectorReady: Boolean? = null,
)

data class DebugFrame(val bitmap: Bitmap, val detection: PhoneDetection)

/**
 * The focus session: pomodoro timer, calm music, auto-DND, phone-distraction detection (camera +
 * unlock), posture reminders from head pitch, the growing tree, and end-of-session stats.
 * All state changes run on the main thread; only detection runs on a background dispatcher.
 */
class FocusEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: GlassesLink,
    private val settingsStore: SettingsStore,
    private val statsStore: StatsStore,
    private val ambient: AmbientPlayer,
    private val voice: Voice,
    private val dnd: DndController,
) {
  companion object {
    private const val TAG = "FocusGlasses:Engine"
    private const val PHONE_SCORE = 0.45f
    private const val NUDGE_COOLDOWN_MS = 60_000L
    private const val FOLLOW_UP_MS = 20_000L
    private const val TREE_DAMAGE = 15
    private const val CALIBRATION_MS = 5_000L
    private const val GLASSES_PITCH_FRESH_MS = 2_000L
    private const val PAUSE_CARD_MS = 8_000L
  }

  private val _ui = MutableStateFlow(FocusUi())
  val ui: StateFlow<FocusUi> = _ui.asStateFlow()

  private val _debug = MutableStateFlow<DebugFrame?>(null)
  /** Latest analysed frame + detection, for the on-screen preview. */
  val debug: StateFlow<DebugFrame?> = _debug.asStateFlow()

  private val detector by lazy { PhoneDetector(context) }
  private val phonePitch = PhonePitchSensor(context)

  private var tickJob: Job? = null
  private var followUpJob: Job? = null
  private var phaseEndAt = 0L
  private var lastTickAt = 0L
  private var unrecordedFocusMs = 0L
  private var phaseFocusMs = 0L
  private var lastNudgeAt = 0L

  // Phone detection smoothing: last 3 frame scores.
  private val recentScores = ArrayDeque<Float>()

  // Posture
  private var emaPitch: Double? = null
  private var calibrationEndsAt = 0L
  private val calibrationSamples = mutableListOf<Double>()
  private var slouchSince = 0L
  private var lastPostureAlertAt = 0L
  private var lastGlassesPitchAt = 0L

  init {
    // Frames: always analysed while the camera streams, so the preview works before a session.
    scope.launch {
      link.frames.collect { bitmap ->
        val detection = withContext(Dispatchers.Default) { detector.detect(bitmap) }
        if (_ui.value.detectorReady == null) _ui.update { it.copy(detectorReady = detector.isReady) }
        _debug.value = DebugFrame(bitmap, detection)
        onDetection(detection)
      }
    }
    scope.launch {
      link.pitch.collect { sample ->
        lastGlassesPitchAt = SystemClock.elapsedRealtime()
        onPitch(sample.degrees, PitchSource.GLASSES)
      }
    }
    scope.launch { settingsStore.settings.collect { s -> ambient.setVolume(s.ambientVolume) } }
  }

  val isRunning: Boolean get() = _ui.value.phase != FocusPhase.IDLE

  // region Session control

  fun start() {
    if (isRunning) return
    val s = settingsStore.current
    unrecordedFocusMs = 0
    completedFocusMs = 0
    phaseFocusMs = 0
    lastNudgeAt = 0
    lastPostureAlertAt = 0
    recentScores.clear()
    _ui.value =
        FocusUi(
            detectorReady = _ui.value.detectorReady,
            lastPhoneScore = _ui.value.lastPhoneScore,
        )
    FocusService.start(context)
    val dndOn = s.useDnd && dnd.enable()
    _ui.update { it.copy(dndActive = dndOn) }
    phonePitch.start { deg ->
      // Phone sensor is the fallback: ignore it while the glasses IMU is delivering.
      if (SystemClock.elapsedRealtime() - lastGlassesPitchAt > GLASSES_PITCH_FRESH_MS) onPitch(deg, PitchSource.PHONE)
    }
    recalibrate()
    enterPhase(FocusPhase.FOCUS)
    tickJob = scope.launch {
      while (true) {
        tick()
        delay(500)
      }
    }
    if (s.geminiKey.isNotBlank()) labelTaskWithGemini(s.geminiKey, s.geminiModel)
  }

  fun stop() {
    if (!isRunning) return
    tickJob?.cancel()
    followUpJob?.cancel()
    tick(allowPhaseChange = false) // account the last partial second
    val focusedSec = (unrecordedFocusMs / 1000).toInt()
    if (focusedSec >= 30) statsStore.recordSession(focusedSec)
    unrecordedFocusMs = 0
    ambient.stop()
    dnd.restore()
    phonePitch.stop()
    FocusService.stop(context)
    val ui = _ui.value
    val stats = statsStore.stats.value
    val minutes = (ui.sessionFocusedSec + 30) / 60
    val summary =
        "本次专注 $minutes 分钟，分心 ${ui.distractions} 次，坐姿提醒 ${ui.postureAlerts} 次。" +
            "今天累计 ${stats.todayMinutes} 分钟，连续第 ${maxOf(stats.streakDays, if (minutes > 0) 1 else 0)} 天。"
    voice.chimeEnd()
    voice.say(summary)
    _ui.update {
      it.copy(phase = FocusPhase.IDLE, remainingSec = 0, summary = summary, dndActive = false, slouching = false, calibrating = false)
    }
  }

  /** Skip to the next phase (focus → break → focus). */
  fun skipPhase() {
    if (!isRunning) return
    onPhaseFinished()
  }

  /** Take the current head angle as "sitting well" again. */
  fun recalibrate() {
    calibrationSamples.clear()
    calibrationEndsAt = SystemClock.elapsedRealtime() + CALIBRATION_MS
    slouchSince = 0
    _ui.update { it.copy(calibrating = true, baselineDeg = null, slouching = false) }
  }

  // endregion

  // region Timer

  private fun enterPhase(phase: FocusPhase) {
    val s = settingsStore.current
    val total = if (phase == FocusPhase.FOCUS) s.focusSeconds else s.breakSeconds
    val now = SystemClock.elapsedRealtime()
    phaseEndAt = now + total * 1000L
    lastTickAt = now
    if (phase == FocusPhase.FOCUS) {
      phaseFocusMs = 0
      if (s.ambientOn) ambient.start()
      voice.chimeStart()
      voice.say("专注开始，${total / 60}分钟。")
      _ui.update { it.copy(treeGrowth = 0f, treeHealth = 100) }
    } else {
      ambient.stop()
      voice.chimeEnd()
      voice.say("这一轮完成了，休息${total / 60}分钟，站起来活动一下。")
    }
    _ui.update { it.copy(phase = phase, phaseTotalSec = total, remainingSec = total) }
  }

  private fun tick(allowPhaseChange: Boolean = true) {
    val now = SystemClock.elapsedRealtime()
    val dt = now - lastTickAt
    lastTickAt = now
    val ui = _ui.value
    if (ui.phase == FocusPhase.FOCUS) {
      unrecordedFocusMs += dt
      phaseFocusMs += dt
      val growth = (phaseFocusMs / 1000f / ui.phaseTotalSec.coerceAtLeast(1)).coerceIn(0f, 1f)
      _ui.update { it.copy(treeGrowth = growth) }
    }
    val remaining = ceil((phaseEndAt - now) / 1000.0).toInt().coerceAtLeast(0)
    _ui.update {
      it.copy(
          remainingSec = remaining,
          sessionFocusedSec = (sessionFocusTotalMs() / 1000).toInt(),
      )
    }
    if (allowPhaseChange && ui.phase != FocusPhase.IDLE && remaining <= 0) onPhaseFinished()
  }

  private var completedFocusMs = 0L

  private fun sessionFocusTotalMs(): Long = completedFocusMs + if (_ui.value.phase == FocusPhase.FOCUS) phaseFocusMs else 0

  private fun onPhaseFinished() {
    val ui = _ui.value
    if (ui.phase == FocusPhase.FOCUS) {
      completedFocusMs += phaseFocusMs
      phaseFocusMs = 0
      val healthy = ui.treeHealth > 0
      _ui.update { it.copy(rounds = it.rounds + 1, treesGrown = it.treesGrown + if (healthy) 1 else 0, treeGrowth = 1f) }
      enterPhase(FocusPhase.BREAK)
    } else {
      enterPhase(FocusPhase.FOCUS)
    }
  }

  // endregion

  // region Distractions

  private fun onDetection(det: PhoneDetection) {
    recentScores.addLast(det.score)
    while (recentScores.size > 3) recentScores.removeFirst()
    val seen = recentScores.count { it >= PHONE_SCORE } >= 2
    val wasSeen = _ui.value.phoneSeen
    _ui.update { it.copy(phoneSeen = seen, lastPhoneScore = det.score) }
    if (seen && !wasSeen && _ui.value.phase == FocusPhase.FOCUS) onDistraction("看到你拿起了手机")
  }

  /** Called by the foreground service when the phone is unlocked. */
  fun onPhoneUnlocked() {
    if (_ui.value.phase == FocusPhase.FOCUS) onDistraction("手机被解锁了")
  }

  private fun onDistraction(reason: String) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastNudgeAt < NUDGE_COOLDOWN_MS) return // one event per minute at most
    lastNudgeAt = now
    Log.i(TAG, "distraction: $reason")
    _ui.update {
      it.copy(
          distractions = it.distractions + 1,
          treeHealth = (it.treeHealth - TREE_DAMAGE).coerceAtLeast(0),
          pauseCardUntil = now + PAUSE_CARD_MS,
          pauseReason = reason,
      )
    }
    voice.softTick()
    voice.say("先停一下。深呼吸，真的需要看手机吗？")
    followUpJob?.cancel()
    followUpJob = scope.launch {
      delay(FOLLOW_UP_MS)
      if (_ui.value.phase == FocusPhase.FOCUS && _ui.value.phoneSeen) {
        voice.say("小树还在等你。把手机放远一点，我们继续。")
      }
    }
  }

  // endregion

  // region Posture

  private fun onPitch(raw: Double, source: PitchSource) {
    val s = settingsStore.current
    val value = if (s.invertPosture) -raw else raw
    val ema = emaPitch?.let { it * 0.8 + value * 0.2 } ?: value
    emaPitch = ema
    val now = SystemClock.elapsedRealtime()
    var ui = _ui.value.copy(pitchDeg = ema, pitchSource = source)
    if (ui.calibrating) {
      calibrationSamples += value
      if (now >= calibrationEndsAt && calibrationSamples.isNotEmpty()) {
        ui = ui.copy(calibrating = false, baselineDeg = calibrationSamples.average())
        calibrationSamples.clear()
      }
    }
    val baseline = ui.baselineDeg
    if (baseline != null && ui.phase == FocusPhase.FOCUS) {
      val slouching = ema < baseline - s.postureThresholdDeg
      if (slouching) {
        if (slouchSince == 0L) slouchSince = now
        val held = now - slouchSince >= s.postureHoldSeconds * 1000L
        val cooled = now - lastPostureAlertAt >= s.postureCooldownSeconds * 1000L
        if (held && cooled) {
          lastPostureAlertAt = now
          ui = ui.copy(postureAlerts = ui.postureAlerts + 1)
          voice.say("头低太久了，抬头坐直一下。")
        }
      } else {
        slouchSince = 0
      }
      ui = ui.copy(slouching = slouching)
    } else {
      ui = ui.copy(slouching = false)
    }
    _ui.value = ui
  }

  // endregion

  private fun labelTaskWithGemini(key: String, model: String) {
    scope.launch {
      val frame: Bitmap = withTimeoutOrNull(10_000) { link.frames.first() } ?: return@launch
      val label = GeminiClient.describeTask(frame, key, model) ?: return@launch
      if (!isRunning) return@launch
      _ui.update { it.copy(taskLabel = label) }
      voice.say("好的，专心$label。")
    }
  }
}
