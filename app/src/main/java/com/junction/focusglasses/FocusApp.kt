package com.junction.focusglasses

import android.app.Application
import com.junction.focusglasses.audio.AmbientPlayer
import com.junction.focusglasses.audio.Voice
import com.junction.focusglasses.audio.VoiceCommands
import com.junction.focusglasses.cooking.CookingEngine
import com.junction.focusglasses.data.SettingsStore
import com.junction.focusglasses.data.StatsStore
import com.junction.focusglasses.focus.FocusEngine
import com.junction.focusglasses.glasses.GlassesLink
import com.junction.focusglasses.modes.ModeController
import com.junction.focusglasses.system.DndController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide singletons: the session outlives the activity (screen off, app in background). */
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
  val cooking = CookingEngine(app, scope, link, settings, voice)
  val modes = ModeController(scope, engine, cooking, commands)
  init {
    link.bindModeController(modes)
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
