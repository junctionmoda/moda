package com.junction.focusglasses

import android.app.Application
import com.junction.focusglasses.ai.FocusLive
import com.junction.focusglasses.ai.LiveState
import com.junction.focusglasses.audio.AmbientPlayer
import com.junction.focusglasses.audio.Voice
import com.junction.focusglasses.audio.VoiceCommands
import com.junction.focusglasses.cooking.CookingEngine
import com.junction.focusglasses.data.SettingsStore
import com.junction.focusglasses.data.StatsStore
import com.junction.focusglasses.focus.FocusEngine
import com.junction.focusglasses.glasses.GlassesLink
import com.junction.focusglasses.modes.FocusMode
import com.junction.focusglasses.modes.ModeCommand
import com.junction.focusglasses.modes.ModeController
import com.junction.focusglasses.meeting.MeetingEngine
import com.junction.focusglasses.system.DndController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Process-wide singletons: the session outlives the activity (screen off, app in background). */
class AppGraph(app: Application) {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  val settings = SettingsStore(app)
  val stats = StatsStore(app)
  val ambient = AmbientPlayer()
  val voice = Voice(app, ambient)
  val dnd = DndController(app)
  val commands = VoiceCommands(app, scope, voice)
  val live = FocusLive(scope, ambient)
  val link = GlassesLink(app, scope)
  val engine = FocusEngine(app, scope, link, settings, stats, ambient, voice, dnd, live)
  val cooking = CookingEngine(app, scope, link, settings, voice, live)
  val meeting = MeetingEngine(app, scope, voice, settings)
  val modes = ModeController(scope, engine, cooking, meeting, commands)

  /** Set by the activity once RECORD_AUDIO is granted (Live streams the microphone). */
  var micGranted = false
    set(value) {
      field = value
      applyLive()
    }

  init {
    link.bindModeController(modes)
    // Any partial from glasses Speech is a strong signal that the wearer is taking a turn.
    // Stop both output paths before waiting for the final command/transcript.
    link.bindUserSpeechListener {
      live.interruptForUserSpeech()
      voice.interrupt()
    }
    live.toolHandler = ::onLiveTool
    scope.launch {
      settings.settings.collect {
        voice.setPreferredVoice(it.ttsVoice)
        applyLive()
      }
    }
    // Only one recognizer may own the microphone: the offline one steps aside while Live is up.
    scope.launch { live.state.collect { commands.setSuspended(it == LiveState.CONNECTING || it == LiveState.READY) } }
    // A flaky network can exhaust the quick retries; keep trying quietly in the background.
    scope.launch {
      live.state.collectLatest {
        if (it == LiveState.ERROR) {
          delay(45_000)
          applyLive()
        }
      }
    }
  }

  /** Manual retry from the UI. */
  fun reconnectLive() {
    live.disconnect()
    applyLive()
  }

  /**
   * Gemini function calls. Mode changes go through the [ModeController] so the UI, glasses touch
   * controls and voice all agree; Work actions are handled by the [FocusEngine].
   */
  private fun onLiveTool(name: String, args: JSONObject): JSONObject {
    val active = modes.ui.value.activeMode
    return when (name) {
      "switch_mode" -> {
        val mode = FocusMode.entries.firstOrNull { it.name.equals(args.optString("mode"), ignoreCase = true) }
        if (mode == null) JSONObject().put("result", "unknown mode").put("modes", "work, cooking, meeting")
        else {
          modes.dispatch(ModeCommand.Activate(mode))
          JSONObject().put("result", "${mode.title} mode is active").put("paused", active?.takeIf { it != mode }?.title ?: JSONObject.NULL)
        }
      }
      "start_work_mode" ->
          if (active != null && active != FocusMode.WORK || engine.isPaused) onLiveTool("switch_mode", JSONObject().put("mode", "work"))
          else engine.onLiveTool(name, args).also { if (engine.isRunning) modes.dispatch(ModeCommand.Activate(FocusMode.WORK)) }
      "end_mode", "end_work_mode" ->
          when {
            active == null && !engine.isRunning -> JSONObject().put("result", "no mode is active")
            active == FocusMode.WORK || active == null ->
                engine.onLiveTool("end_work_mode", args).also { if (modes.ui.value.activeMode == FocusMode.WORK) modes.dispatch(ModeCommand.End) }
            name == "end_work_mode" -> JSONObject().put("result", "work mode is not the active mode").put("active_mode", active.title)
            else -> {
              modes.dispatch(ModeCommand.End)
              JSONObject().put("result", "${active.title} mode ended")
            }
          }
      "cooking_timer" ->
          if (active != FocusMode.COOKING) JSONObject().put("result", "cooking mode is not active")
          else JSONObject().put("result", "timer started").put("minutes", cooking.startTimer(args.optInt("minutes", 10)))
      "meal_finished" ->
          if (active != FocusMode.COOKING) JSONObject().put("result", "cooking mode is not active")
          else {
            cooking.mealFinished()
            JSONObject().put("result", "dishes reminder scheduled")
          }
      "look" -> {
        val frame = link.colorSnapshot()
        if (frame == null) JSONObject().put("result", "no camera view: the glasses are not streaming")
        else {
          live.image(frame)
          JSONObject().put("result", "image sent").put("active_mode", active?.title ?: "none")
        }
      }
      "get_status" ->
          engine.onLiveTool(name, args).apply {
            put("active_mode", active?.title ?: "none")
            if (active == FocusMode.COOKING) {
              val c = cooking.ui.value
              put("cooking_phase", c.phase.name.lowercase())
              c.timerRemainingSec?.let { put("cooking_timer_seconds_left", it) }
            }
          }
      "take_break", "extend_focus" ->
          if (active != null && active != FocusMode.WORK) JSONObject().put("result", "only available in work mode").put("active_mode", active.title)
          else engine.onLiveTool(name, args)
      else -> engine.onLiveTool(name, args)
    }
  }

  /** Connects Focus Live when it is on, a Gemini key is set and the mic is granted. */
  fun applyLive() {
    val s = settings.current
    if (s.liveOn && s.geminiKey.isNotBlank() && micGranted) live.connect(s.geminiKey, s.liveModel, s.liveVoice) else live.disconnect()
  }

  /** Wearables.initialize() must run once, after BLUETOOTH_CONNECT is granted. */
  var wearablesInitialized = false
}

class FocusApp : Application() {
  lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)
  }
}
