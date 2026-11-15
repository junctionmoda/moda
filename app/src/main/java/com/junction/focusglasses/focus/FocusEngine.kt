package com.junction.focusglasses.focus

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.junction.focusglasses.audio.AmbientPlayer
import com.junction.focusglasses.audio.Voice
import com.junction.focusglasses.data.SettingsStore
import com.junction.focusglasses.data.StatsStore
import com.junction.focusglasses.glasses.GlassesLink
import com.junction.focusglasses.system.DndController
import com.junction.focusglasses.system.FocusService
import com.junction.focusglasses.system.PhonePitchSensor
import com.junction.focusglasses.vision.FrameDetections
import com.junction.focusglasses.vision.WorkObject
import com.junction.focusglasses.vision.PhoneDetector
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** CHECK_IN: a focus block just ended and we asked "Need a break?"; waiting for the answer. */
enum class FocusPhase { IDLE, FOCUS, CHECK_IN, BREAK, PAUSED }

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
    /** "Potential distraction detected" card is shown until this elapsedRealtime. */
    val pauseCardUntil: Long = 0,
    val pauseReason: String? = null,
    val dndActive: Boolean = false,
    val summary: String? = null,
    val detectorReady: Boolean? = null,
    /** Apparent screen / book size relative to the calibrated baseline (1.0 = normal distance). */
    val screenRatio: Float? = null,
    val bookRatio: Float? = null,
    val screenCalibrating: Boolean = false,
    val bookCalibrating: Boolean = false,
    val tooClose: Boolean = false,
    val distanceAlerts: Int = 0,
    /** Times the user asked for more time at the check-in this session. */
    val extensions: Int = 0,
)

data class DebugFrame(val bitmap: Bitmap, val detection: FrameDetections)

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
    /**
     * Any hint of a phone (well below [PHONE_SCORE]) or an unlock means the user is busy with the
     * phone: looking down at it reads as bad posture, and a phone held up close is often boxed as a
     * full-frame "laptop". So posture / distance reminders stay quiet for [PHONE_QUIET_MS] after it;
     * the distraction nudge is the right message then.
     */
    private const val PHONE_HINT_SCORE = 0.25f
    private const val PHONE_QUIET_MS = 10_000L
    private const val PHONE_SUSTAINED_MEAN = 0.33
    private const val NUDGE_COOLDOWN_MS = 60_000L
    private const val FOLLOW_UP_MS = 20_000L
    /** No hint of the phone for this long after a nudge = it was put away. */
    private const val PHONE_GONE_MS = 5_000L
    private const val TREE_DAMAGE = 15
    private const val CALIBRATION_MS = 5_000L
    private const val GLASSES_PITCH_FRESH_MS = 2_000L
    private const val PAUSE_CARD_MS = 8_000L
    /** Laptop apparent size that is too close on its own (normal sitting distance measured 0.52–0.68). */
    private const val SCREEN_ABS_CLOSE = 0.78f
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
  private var phoneEvidenceAt = 0L
  private var pausedPhase: FocusPhase? = null
  private var pausedRemainingSec = 0

  private fun phoneBusy(now: Long) = now - phoneEvidenceAt < PHONE_QUIET_MS

  // Phone detection smoothing: recent scores plus a short exit hysteresis. Entering needs
  // corroboration; leaving needs three weak frames, so a hand, glare, or one bad camera frame
  // cannot repeatedly flip the state and create duplicate distraction cards.
  private val recentScores = ArrayDeque<Float>()
  private var phoneExitFrames = 0

  // Posture
  private var emaPitch: Double? = null
  private var calibrationEndsAt = 0L
  private val calibrationSamples = mutableListOf<Double>()
  private var slouchSince = 0L
  private var lastPostureAlertAt = 0L
  private var lastGlassesPitchAt = 0L

  // Viewing distance
  private val screenDistance = DistanceTracker(absCloseSize = SCREEN_ABS_CLOSE)
  private val bookDistance = DistanceTracker()

  init {
    // Frames: always analysed while the camera streams, so the preview works before a session.
    scope.launch {
      link.frames.collect { bitmap ->
        val detection = withContext(Dispatchers.Default) { detector.detect(bitmap) }
        if (_ui.value.detectorReady == null) _ui.update { it.copy(detectorReady = detector.isReady) }
        _debug.value = DebugFrame(bitmap, detection)
        Log.d(
            TAG,
            "det phone=%.2f screen=%s book=%s".format(
                detection.phone.score,
                detection.screen?.let { "%s/%.2f/%.3f".format(it.label, it.score, it.size) },
                detection.book?.let { "%.2f/%.3f".format(it.score, it.size) },
            ),
        )
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
  val isPaused: Boolean get() = _ui.value.phase == FocusPhase.PAUSED

  // region Session control

  fun start(announce: Boolean = true) {
    if (isRunning) return
    val s = settingsStore.current
    unrecordedFocusMs = 0
    completedFocusMs = 0
    phaseFocusMs = 0
    lastNudgeAt = 0
    phoneEvidenceAt = 0
    phoneExitFrames = 0
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
    voice.chimeStart()
    if (announce) {
      speak(
          "session_started: a ${s.focusMinutes}-minute focus block just started. Welcome the user back in one sentence, " +
              "mention the length, and the task if the image makes it obvious.",
          "Welcome back. Your ${s.focusMinutes}-minute focus session is ready.",
      )
    }
    enterPhase(FocusPhase.FOCUS, announce = false)
    tickJob = scope.launch {
      while (true) {
        tick()
        delay(500)
      }
    }
  }

  fun stop(announce: Boolean = true) {
    if (!isRunning) return
    if (isPaused) {
      _ui.update { it.copy(phase = pausedPhase ?: FocusPhase.FOCUS) }
      lastTickAt = SystemClock.elapsedRealtime()
      pausedPhase = null
    }
    tickJob?.cancel()
    followUpJob?.cancel()
    checkInJob?.cancel()
    tick(allowPhaseChange = false) // account the last partial second
    val focusedSec = (unrecordedFocusMs / 1000).toInt()
    if (focusedSec >= 30) statsStore.recordSession(focusedSec)
    unrecordedFocusMs = 0
    ambient.stop()
    dnd.restore()
    phonePitch.stop()
    FocusService.stop(context)
    // A block that reached full growth (e.g. stopped at the check-in) still counts as a grown tree.
    val before = _ui.value
    val finishedTree = before.phase != FocusPhase.BREAK && before.treeGrowth >= 1f && before.treeHealth > 0
    if (finishedTree) _ui.update { it.copy(treesGrown = it.treesGrown + 1) }
    val ui = _ui.value
    val stats = statsStore.stats.value
    val minutes = (ui.sessionFocusedSec + 30) / 60
    val streak = maxOf(stats.streakDays, if (minutes > 0) 1 else 0)
    val summary =
        listOf(
                "$minutes MIN FOCUSED",
                "${plural(ui.distractions, "distraction")} · " +
                    (if (ui.postureAlerts > 0) "${plural(ui.postureAlerts, "posture reminder")} · " else "") +
                    "${plural(ui.distanceAlerts, "distance reminder")} · ${plural(ui.treesGrown, "tree")} grown",
                "Today ${stats.todayMinutes} min · ${plural(streak, "day")} streak",
            )
            .joinToString("\n")
    val treeLine =
        when {
          ui.treesGrown > 0 -> "Your tree has grown."
          ui.treeHealth <= 0 -> "Your tree needs a little more care next time."
          else -> "Your tree is still growing."
        }
    voice.chimeEnd()
    if (announce) {
      speak(
          "session_ended: $minutes minutes focused, ${ui.distractions} distractions, ${ui.treesGrown} trees grown" +
              "${if (ui.treeHealth <= 0) " (the tree lost all its health)" else ""}. Give a warm one or two sentence wrap-up.",
          "Work mode ended. $minutes minutes focused. $treeLine",
      )
    }
    _ui.update {
      it.copy(phase = FocusPhase.IDLE, remainingSec = 0, summary = summary, dndActive = false, slouching = false, calibrating = false, tooClose = false)
    }
  }

  private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

  /** Suspend the Work engine while another top-level mode owns the user's attention. */
  fun pause() {
    val phase = _ui.value.phase
    if (phase != FocusPhase.FOCUS && phase != FocusPhase.BREAK && phase != FocusPhase.CHECK_IN) return
    tick(allowPhaseChange = false)
    pausedPhase = phase
    pausedRemainingSec = _ui.value.remainingSec
    tickJob?.cancel()
    followUpJob?.cancel()
    checkInJob?.cancel()
    ambient.stop()
    dnd.restore()
    phonePitch.stop()
    FocusService.stop(context)
    _ui.update { it.copy(phase = FocusPhase.PAUSED, dndActive = false, slouching = false) }
    speak("work_paused: Work mode is paused while another mode runs. One short sentence.", "Work mode paused.")
    Log.i(TAG, "work paused from $phase with $pausedRemainingSec seconds remaining")
  }

  /** Resume the exact Work focus or break phase that was suspended by the mode controller. */
  fun resume() {
    val phase = pausedPhase ?: return
    if (!isPaused) return
    val settings = settingsStore.current
    val now = SystemClock.elapsedRealtime()
    phaseEndAt = now + pausedRemainingSec * 1000L / timeScale
    lastTickAt = now
    FocusService.start(context)
    val dndOn = settings.useDnd && dnd.enable()
    phonePitch.start { deg ->
      if (SystemClock.elapsedRealtime() - lastGlassesPitchAt > GLASSES_PITCH_FRESH_MS) onPitch(deg, PitchSource.PHONE)
    }
    if (phase == FocusPhase.FOCUS && settings.ambientOn) ambient.start()
    _ui.update { it.copy(phase = phase, dndActive = dndOn) }
    pausedPhase = null
    tickJob = scope.launch {
      while (true) {
        tick()
        delay(500)
      }
    }
    if (phase == FocusPhase.CHECK_IN) {
      checkInJob = scope.launch {
        delay(settings.checkInTimeoutSeconds * 1000L)
        if (_ui.value.phase == FocusPhase.CHECK_IN) takeBreak(recordFeedback = false)
      }
    }
    speak("work_resumed: back to the focus block. One short sentence.", "Work mode resumed.")
    Log.i(TAG, "work resumed into $phase")
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
    screenDistance.reset()
    bookDistance.reset()
    _ui.update {
      it.copy(
          calibrating = true, baselineDeg = null, slouching = false,
          screenRatio = null, bookRatio = null, screenCalibrating = true, bookCalibrating = true, tooClose = false,
      )
    }
  }

  // endregion

  // region Timer

  /** Starts a phase of [totalSec] session seconds (the demo clock runs [Settings.timeScale]× faster). */
  private fun enterPhase(phase: FocusPhase, announce: Boolean = true) {
    val s = settingsStore.current
    val total = if (phase == FocusPhase.FOCUS) s.focusSeconds else s.breakSeconds
    val now = SystemClock.elapsedRealtime()
    timeScale = s.timeScale
    phaseEndAt = now + total * 1000L / timeScale
    lastTickAt = now
    // Update the phase first so the status line sent with the announcement is current.
    _ui.update { it.copy(phase = phase, phaseTotalSec = total, remainingSec = total) }
    if (phase == FocusPhase.FOCUS) {
      phaseFocusMs = 0
      if (s.ambientOn) ambient.start()
      if (announce) {
        voice.chimeStart()
        speak(
            "break_over: the next ${s.focusMinutes}-minute focus block starts now. One short sentence.",
            "Break's over. Your next ${s.focusMinutes}-minute focus block starts now.",
        )
      }
      _ui.update { it.copy(treeGrowth = 0f, treeHealth = 100) }
    } else {
      ambient.stop()
      voice.chimeEnd()
      if (announce) {
        speak(
            "break_started: a ${s.breakMinutes}-minute break starts now. Suggest one gentle thing (stretch, water, look far away).",
            "Enjoy your ${s.breakMinutes}-minute break. Stand up and stretch a little.",
        )
      }
    }
  }

  private var timeScale = 1

  private fun tick(allowPhaseChange: Boolean = true) {
    val now = SystemClock.elapsedRealtime()
    val dt = (now - lastTickAt) * timeScale
    lastTickAt = now
    val ui = _ui.value
    if (ui.phase == FocusPhase.FOCUS) {
      unrecordedFocusMs += dt
      phaseFocusMs += dt
      // Growth is measured against the planned block, so extra time keeps the tree fully grown.
      val growth = (phaseFocusMs / 1000f / settingsStore.current.focusSeconds.coerceAtLeast(1)).coerceIn(0f, 1f)
      _ui.update { it.copy(treeGrowth = growth) }
    }
    val remaining =
        if (ui.phase == FocusPhase.CHECK_IN) 0 else ceil((phaseEndAt - now) * timeScale / 1000.0).toInt().coerceAtLeast(0)
    _ui.update {
      it.copy(
          remainingSec = remaining,
          sessionFocusedSec = (sessionFocusTotalMs() / 1000).toInt(),
      )
    }
    if (allowPhaseChange && (ui.phase == FocusPhase.FOCUS || ui.phase == FocusPhase.BREAK) && remaining <= 0) onPhaseFinished()
  }

  private var completedFocusMs = 0L
  private var checkInJob: Job? = null

  private fun sessionFocusTotalMs(): Long =
      completedFocusMs + if (_ui.value.phase == FocusPhase.FOCUS || _ui.value.phase == FocusPhase.CHECK_IN) phaseFocusMs else 0

  private fun onPhaseFinished() {
    when (_ui.value.phase) {
      FocusPhase.FOCUS -> askForBreak()
      FocusPhase.BREAK -> enterPhase(FocusPhase.FOCUS)
      else -> {}
    }
  }

  /** End of a focus block: ask instead of forcing the break; the answer comes by voice or button. */
  private fun askForBreak() {
    ambient.setDucked(true)
    _ui.update { it.copy(phase = FocusPhase.CHECK_IN, remainingSec = 0, treeGrowth = 1f) }
    voice.chimeEnd()
    speak(
        "block_finished: they just finished ${(phaseFocusMs / 60_000)} minutes of focus. Praise briefly and ask if they need a break.",
        "Nice work, that's ${(phaseFocusMs / 60_000)} minutes. Need a break?",
    )
    checkInJob?.cancel()
    checkInJob = scope.launch {
      delay(settingsStore.current.checkInTimeoutSeconds * 1000L)
      if (_ui.value.phase == FocusPhase.CHECK_IN) {
        Log.i(TAG, "check-in: no answer, taking the break")
        takeBreak(recordFeedback = false)
      }
    }
  }

  /** Adds minutes to the running focus block (asked for mid-block); returns the minutes added. */
  private fun addFocusTime(minutes: Int): Int {
    val extra = minutes.coerceIn(1, 60)
    phaseEndAt += extra * 60_000L / timeScale
    _ui.update { it.copy(phaseTotalSec = it.phaseTotalSec + extra * 60, extensions = it.extensions + 1) }
    Log.i(TAG, "added $extra min to the current block")
    return extra
  }

  /** Check-in answer "yes" (or a break asked for mid-block): finish the block and start the break. */
  fun takeBreak(recordFeedback: Boolean = true, announce: Boolean = true) {
    if (_ui.value.phase != FocusPhase.CHECK_IN && _ui.value.phase != FocusPhase.FOCUS) return
    checkInJob?.cancel()
    ambient.setDucked(false)
    if (recordFeedback) statsStore.recordCheckIn(extended = false, focusedMinutes = (phaseFocusMs / 60_000).toInt())
    completedFocusMs += phaseFocusMs
    phaseFocusMs = 0
    // A block ended early only grows its tree if it had already reached full growth.
    val grown = _ui.value.treeHealth > 0 && _ui.value.treeGrowth >= 1f
    _ui.update { it.copy(rounds = it.rounds + 1, treesGrown = it.treesGrown + if (grown) 1 else 0) }
    enterPhase(FocusPhase.BREAK, announce)
  }

  /** Check-in answer "not yet": keep the block going for [Settings.extendMinutes] more and remember it. */
  fun extendFocus(minutes: Int? = null, announce: Boolean = true) {
    if (_ui.value.phase != FocusPhase.CHECK_IN) return
    checkInJob?.cancel()
    ambient.setDucked(false)
    val s = settingsStore.current
    val extra = (minutes ?: s.extendMinutes).coerceIn(1, 60)
    statsStore.recordCheckIn(extended = true, focusedMinutes = (phaseFocusMs / 60_000).toInt())
    val now = SystemClock.elapsedRealtime()
    timeScale = s.timeScale
    phaseEndAt = now + extra * 60_000L / timeScale
    lastTickAt = now
    Log.i(TAG, "check-in: extended by $extra min")
    if (announce) speak("focus_extended: $extra more minutes added to the block.", "Okay, I'll give you $extra more minutes.")
    _ui.update {
      it.copy(
          phase = FocusPhase.FOCUS,
          phaseTotalSec = extra * 60,
          remainingSec = extra * 60,
          extensions = it.extensions + 1,
      )
    }
  }

  // endregion

  // region Distractions

  private fun onDetection(frame: FrameDetections) {
    val phoneInFrame = frame.phone.score >= PHONE_HINT_SCORE
    if (phoneInFrame) phoneEvidenceAt = SystemClock.elapsedRealtime()
    onDistance(frame, phoneInFrame)
    val det = frame.phone
    recentScores.addLast(det.score)
    while (recentScores.size > 4) recentScores.removeFirst()
    // Either a confident sighting (2 of the last 3 frames), or a phone that keeps showing up at
    // mid confidence (all of the last 4 frames), which is how a phone held in front of a screen looks.
    val confident = recentScores.toList().takeLast(3).count { it >= PHONE_SCORE } >= 2
    val sustained = recentScores.size == 4 && recentScores.all { it >= PHONE_HINT_SCORE } && recentScores.average() >= PHONE_SUSTAINED_MEAN
    val wasSeen = _ui.value.phoneSeen
    val seen =
        if (wasSeen) {
          if (det.score < PHONE_HINT_SCORE) phoneExitFrames++ else phoneExitFrames = 0
          phoneExitFrames < 3
        } else {
          phoneExitFrames = 0
          confident || sustained
        }
    if (seen != wasSeen) {
      Log.i(TAG, "phone ${if (seen) "entered" else "left"} view: score=%.2f recent=%s".format(det.score, recentScores.joinToString { "%.2f".format(it) }))
    }
    _ui.update { it.copy(phoneSeen = seen, lastPhoneScore = det.score) }
    if (seen && !wasSeen && _ui.value.phase == FocusPhase.FOCUS) onDistraction("Phone in view")
  }

  /**
   * Too close to the screen / homework: the object looks [Settings.closeRatio]× bigger than at the
   * start of the session, held for a while. Only during focus; baselines are taken in focus too.
   */
  private fun onDistance(frame: FrameDetections, phoneInFrame: Boolean) {
    if (_ui.value.phase != FocusPhase.FOCUS) return
    val s = settingsStore.current
    val now = SystemClock.elapsedRealtime()
    fun track(obj: WorkObject?, tracker: DistanceTracker, message: String): Float? {
      // A frame with a phone in it doesn't vote: the "screen" may be the phone held up close.
      val ratio = obj?.takeUnless { phoneInFrame }?.let { tracker.add(it.size, now, s.closeRatio) }
      tracker.update(now)
      if (phoneBusy(now)) {
        tracker.closeSince = 0 // restart the hold once the phone is gone
        return ratio
      }
      if (obj != null) {
        Log.d(TAG, "dist ${obj.label} size=%.2f ratio=%s close=${tracker.close}".format(obj.size, ratio?.let { "%.2f".format(it) }))
      }
      if (tracker.close) {
        val held = now - tracker.closeSince >= s.closeHoldSeconds * 1000L
        val cooled = now - tracker.lastAlertAt >= s.postureCooldownSeconds * 1000L
        if (held && cooled) {
          tracker.lastAlertAt = now
          Log.i(TAG, "too close: $message ratio=$ratio")
          _ui.update { it.copy(distanceAlerts = it.distanceAlerts + 1) }
          val what = if (obj?.label == "book") "book" else "screen"
          speak("too_close: they have been leaning in close to the $what for a while. Suggest sitting back, kindly.", message)
        }
      }
      return ratio
    }
    val screenRatio = track(frame.screen, screenDistance, "You're quite close to the screen. Try sitting back a little.")
    val bookRatio = track(frame.book, bookDistance, "You're quite close to your book. Lift your head up a bit.")
    _ui.update {
      it.copy(
          screenRatio = screenRatio ?: it.screenRatio,
          bookRatio = bookRatio ?: it.bookRatio,
          screenCalibrating = screenDistance.calibrating,
          bookCalibrating = bookDistance.calibrating,
          tooClose = screenDistance.close || bookDistance.close,
      )
    }
  }

  /** Called by the foreground service when the phone is unlocked. */
  fun onPhoneUnlocked() {
    phoneEvidenceAt = SystemClock.elapsedRealtime()
    if (_ui.value.phase == FocusPhase.FOCUS) onDistraction("Phone unlocked")
  }

  /**
   * Phone in view and/or phone unlocked. Both usually happen within seconds of each other, so a
   * second signal inside the cooldown only adds to the reason shown on the card.
   */
  private fun onDistraction(reason: String) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastNudgeAt < NUDGE_COOLDOWN_MS) {
      val ui = _ui.value
      if (now < ui.pauseCardUntil && ui.pauseReason?.contains(reason) == false) {
        _ui.update { it.copy(pauseReason = "${it.pauseReason} + $reason") }
      }
      return
    }
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
    speak(
        "phone_distraction: ${reason.lowercase()} (distraction number ${_ui.value.distractions} this session). " +
            "Gently notice it and invite them back to work. No guilt.",
        "Getting a little distracted? Let's get back to work.",
    )
    followUpJob?.cancel()
    followUpJob = scope.launch {
      // Wait for the phone to go away (no hint of it for a few seconds) and welcome them back;
      // if it is still out after FOLLOW_UP_MS, nudge once more.
      val startedAt = SystemClock.elapsedRealtime()
      var nudged = false
      while (_ui.value.phase == FocusPhase.FOCUS) {
        delay(1_000)
        val t = SystemClock.elapsedRealtime()
        if (t - phoneEvidenceAt >= PHONE_GONE_MS && t - startedAt >= PHONE_GONE_MS) {
          speak("phone_put_down: the phone is away again. A very short welcome back, mention the minutes left.", "Welcome back. Let's keep going.")
          break
        }
        if (!nudged && t - startedAt >= FOLLOW_UP_MS && _ui.value.phoneSeen) {
          nudged = true
          speak(
              "phone_still_out: the phone is still out ${FOLLOW_UP_MS / 1000} seconds later. One more gentle nudge, mention the tree.",
              "Your tree is still waiting. Put the phone away and let's keep going.",
          )
        }
        if (t - startedAt >= 90_000) break
      }
    }
  }

  // endregion

  // region Posture

  private fun onPitch(raw: Double, source: PitchSource) {
    val s = settingsStore.current
    val value = if (s.invertPosture) -raw else raw
    val ema = emaPitch?.let { it * 0.6 + value * 0.4 } ?: value
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
      val slouching = ema < baseline - s.effectivePostureThresholdDeg
      if (!s.postureAlertsOn || phoneBusy(now)) {
        slouchSince = 0 // reminders off, or looking down at the phone: the distraction nudge covers it
      } else if (slouching) {
        if (slouchSince == 0L) slouchSince = now
        val held = now - slouchSince >= s.postureHoldSeconds * 1000L
        val cooled = now - lastPostureAlertAt >= s.postureCooldownSeconds * 1000L
        if (held && cooled) {
          lastPostureAlertAt = now
          ui = ui.copy(postureAlerts = ui.postureAlerts + 1)
          Log.i(TAG, "posture alert: pitch=%.1f baseline=%.1f".format(ema, baseline))
          speak(
              "posture: they have been looking down for a while. Suggest sitting up straight, kindly.",
              "You've been looking down for a while. Try sitting up straight.",
          )
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

  private fun speak(@Suppress("UNUSED_PARAMETER") event: String, fallback: String) = voice.say(fallback)

}
