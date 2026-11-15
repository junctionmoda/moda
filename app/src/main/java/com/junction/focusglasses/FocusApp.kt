package com.junction.focusglasses

import android.app.Application
import com.junction.focusglasses.audio.AmbientPlayer
import com.junction.focusglasses.audio.Voice
import com.junction.focusglasses.audio.VoiceCommand
import com.junction.focusglasses.audio.VoiceCommands
import com.junction.focusglasses.data.SettingsStore
import com.junction.focusglasses.data.StatsStore
import com.junction.focusglasses.focus.FocusEngine
import com.junction.focusglasses.glasses.GlassesLink
import com.junction.focusglasses.system.DndController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide session graph. The focus session survives activity recreation and screen-off. */
class AppGraph(app: Application) {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  val settings = SettingsStore(app)
  val stats = StatsStore(app)
  val ambient = AmbientPlayer()
  val voice = Voice(app, ambient)
  val dnd = DndController(app)
  val commands = VoiceCommands(app, scope, voice)
  val link = GlassesLink(app, scope)
  val engine = FocusEngine(app, scope, link, settings, stats, ambient, voice, dnd)

  init {
    link.bindFocusControls(
        onTogglePause = { if (engine.isPaused) engine.resume() else engine.pause() },
        onEnd = engine::stop,
        onTranscript = { commands.submitFinalTranscript(it, source = "glasses") },
    )
    scope.launch {
      commands.commands.collect { command ->
        when (command) {
          VoiceCommand.START -> engine.start()
          VoiceCommand.PAUSE -> engine.pause()
          VoiceCommand.RESUME -> engine.resume()
          VoiceCommand.END -> engine.stop()
          VoiceCommand.YES -> engine.takeBreak()
          VoiceCommand.NOT_YET -> engine.extendFocus()
        }
      }
    }
    scope.launch { settings.settings.collect { voice.setPreferredVoice(it.ttsVoice) } }
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
