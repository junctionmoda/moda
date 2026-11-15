package com.junction.focusglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.junction.focusglasses.focus.FocusPhase
import com.junction.focusglasses.focus.FocusUi
import com.junction.focusglasses.focus.PitchSource
import com.junction.focusglasses.glasses.LinkPhase
import com.junction.focusglasses.glasses.LinkStatus
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
  private val graph get() = (application as FocusApp).graph
  private val requiredPermissions = buildList {
    add(Manifest.permission.BLUETOOTH_CONNECT)
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
  }.toTypedArray()
  private var permissionsGranted by mutableStateOf(false)
  private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updatePermissions() }
  private var pendingGlassesPermission: Permission? = null
  private val glassesPermissionLauncher = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
    if (result.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted) {
      when (pendingGlassesPermission) {
        Permission.CAMERA -> graph.link.onCameraPermissionGranted()
        Permission.MICROPHONE -> graph.link.enableModeSpeech()
        else -> Unit
      }
    }
    pendingGlassesPermission = null
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    updatePermissions()
    if (!permissionsGranted) permissionLauncher.launch(requiredPermissions)
    setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = Mint, secondary = Blue)) {
        Surface(Modifier.fillMaxSize(), color = Background, contentColor = TextMain) {
          ModaScreen(
              graph = graph,
              permissionsGranted = permissionsGranted,
              onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
              onRegister = { Wearables.startRegistration(this) },
              onUnregister = { Wearables.startUnregistration(this) },
              onRequestCamera = { pendingGlassesPermission = Permission.CAMERA; glassesPermissionLauncher.launch(Permission.CAMERA) },
              onRequestVoice = { pendingGlassesPermission = Permission.MICROPHONE; glassesPermissionLauncher.launch(Permission.MICROPHONE) },
          )
        }
      }
    }
  }

  private fun updatePermissions() {
    permissionsGranted = requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED && !graph.wearablesInitialized) {
      graph.wearablesInitialized = true
      Wearables.initialize(this)
      graph.link.onWearablesInitialized()
    }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) graph.commands.start()
  }
}

private val Background = Color(0xFF101614)
private val CardColor = Color(0xFF1A2420)
private val TextMain = Color(0xFFE6EEE9)
private val Muted = Color(0xFF9DB0A8)
private val Warn = Color(0xFFFFB86B)
private val Mint = Color(0xFF8BD3A8)
private val Blue = Color(0xFFB7C9FF)

@Composable
private fun ModaScreen(graph: AppGraph, permissionsGranted: Boolean, onRequestPermissions: () -> Unit, onRegister: () -> Unit, onUnregister: () -> Unit, onRequestCamera: () -> Unit, onRequestVoice: () -> Unit) {
  val link by graph.link.status.collectAsState()
  val focus by graph.engine.ui.collectAsState()
  val settings by graph.settings.settings.collectAsState()
  val stats by graph.stats.stats.collectAsState()
  val voiceStatus by graph.commands.status.collectAsState()
  val heard by graph.commands.heard.collectAsState()
  Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Moda", fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Text("A focused companion for Ray-Ban Meta Gen 2", color = Muted)
    if (!permissionsGranted) Section("Permissions") {
      Text("Bluetooth connects your glasses; camera powers test mode; microphone enables offline controls.", color = Muted)
      Button(onClick = onRequestPermissions) { Text("Grant permissions") }
    }
    ConnectionSection(graph, link, onRegister, onUnregister, onRequestCamera, onRequestVoice)
    Section("Voice controls") {
      Text(voiceStatus + heard.takeIf { it.isNotBlank() }?.let { " · heard \"$it\"" }.orEmpty(), color = Muted)
      Text("Try “start focus”, “pause”, “resume”, “end focus”, “yes”, or “not yet”.", color = Muted, fontSize = 12.sp)
    }
    FocusSection(graph, focus, link)
    Section("Today") {
      Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Stat("${stats.todayMinutes}", "minutes")
        Stat("${stats.todaySessions}", "sessions")
        Stat("${stats.streakDays}", "day streak")
      }
      focus.summary?.let { Text(it, color = Muted) }
    }
    SessionInfo(focus, link)
    SettingsSection(graph, settings, focus.phase != FocusPhase.IDLE)
    Spacer(Modifier.height(28.dp))
  }
}

@Composable private fun Section(title: String, content: @Composable () -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = CardColor, contentColor = TextMain), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp); content() }
  }
}

@Composable private fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Switch(checked, onChange, enabled = enabled) }
}

@Composable
private fun ConnectionSection(graph: AppGraph, link: LinkStatus, onRegister: () -> Unit, onUnregister: () -> Unit, onRequestCamera: () -> Unit, onRequestVoice: () -> Unit) = Section("Glasses") {
  val registration = when (link.registration) {
    RegistrationState.REGISTERED -> "registered"
    RegistrationState.REGISTERING -> "registering…"
    RegistrationState.AVAILABLE -> "not registered"
    RegistrationState.UNAVAILABLE -> "unavailable"
    else -> link.registration.toString()
  }
  val connection = when (link.phase) {
    LinkPhase.IDLE -> "not connected"
    LinkPhase.CONNECTING -> "connecting…"
    LinkPhase.WAITING_PERMISSION -> "waiting for camera permission"
    LinkPhase.STREAMING -> "connected and streaming"
    LinkPhase.PAUSED -> "paused"
  }
  Text("Registration: $registration · $connection", color = Muted)
  link.deviceInfo?.let { Text(it, color = Muted, fontSize = 12.sp) }
  link.lastError?.let { Text("⚠ $it", color = Warn, fontSize = 12.sp) }
  Text(link.modeControlsStatus, color = Muted, fontSize = 12.sp)
  LabeledSwitch("Test mode (phone camera simulates glasses)", link.mockEnabled, link.wearablesReady && link.phase == LinkPhase.IDLE) { if (it) graph.link.enableMock() else graph.link.disableMock() }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (!link.mockEnabled) {
      if (link.registration == RegistrationState.REGISTERED) OutlinedButton(onClick = onUnregister) { Text("Unregister") }
      else OutlinedButton(onClick = onRegister, enabled = link.wearablesReady) { Text("Register") }
    }
    if (link.phase == LinkPhase.IDLE) Button(onClick = graph.link::connect, enabled = link.wearablesReady) { Text("Connect") }
    else OutlinedButton(onClick = graph.link::disconnect) { Text("Disconnect") }
  }
  if (link.phase == LinkPhase.WAITING_PERMISSION) Button(onClick = onRequestCamera) { Text("Grant glasses camera") }
  OutlinedButton(onClick = onRequestVoice, enabled = link.phase != LinkPhase.IDLE) { Text("Enable glasses voice") }
}

@Composable
private fun FocusSection(graph: AppGraph, focus: FocusUi, link: LinkStatus) = Section(when (focus.phase) {
  FocusPhase.IDLE -> "Focus session"; FocusPhase.FOCUS -> "Focusing"; FocusPhase.CHECK_IN -> "Need a break?"; FocusPhase.BREAK -> "On a break"; FocusPhase.PAUSED -> "Focus paused"
}) {
  val shown = if (focus.phase == FocusPhase.IDLE) graph.settings.current.focusSeconds else focus.remainingSec
  Text("%02d:%02d".format(shown / 60, shown % 60), fontSize = 48.sp, fontWeight = FontWeight.Light)
  Text("Round ${focus.rounds + if (focus.phase == FocusPhase.FOCUS || focus.phase == FocusPhase.CHECK_IN) 1 else 0} · tree health ${focus.treeHealth}%", color = Muted)
  Text("Distractions ${focus.distractions} · distance reminders ${focus.distanceAlerts}" + if (graph.settings.current.postureAlertsOn) " · posture reminders ${focus.postureAlerts}" else "", color = Muted, fontSize = 13.sp)
  if (link.phase != LinkPhase.STREAMING && focus.phase == FocusPhase.IDLE) Text("You can start without glasses; visual detection and glasses posture will be unavailable.", color = Muted, fontSize = 12.sp)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    when (focus.phase) {
      FocusPhase.IDLE -> Button(onClick = graph.engine::start) { Text("Start focus") }
      FocusPhase.CHECK_IN -> { Button(onClick = graph.engine::takeBreak) { Text("Take a break") }; OutlinedButton(onClick = graph.engine::extendFocus) { Text("Not yet, +${graph.settings.current.extendMinutes} min") } }
      FocusPhase.PAUSED -> { Button(onClick = graph.engine::resume) { Text("Resume") }; OutlinedButton(onClick = graph.engine::stop) { Text("End") } }
      else -> { Button(onClick = graph.engine::stop) { Text("End") }; OutlinedButton(onClick = graph.engine::skipPhase) { Text("Skip") }; OutlinedButton(onClick = graph.engine::recalibrate) { Text("Recalibrate") } }
    }
  }
}

@Composable private fun Stat(value: String, label: String) = Column { Text(value, fontSize = 28.sp, fontWeight = FontWeight.SemiBold); Text(label, color = Muted, fontSize = 12.sp) }

@Composable
private fun SessionInfo(focus: FocusUi, link: LinkStatus) = Section("Session status") {
  val source = when (focus.pitchSource) { PitchSource.GLASSES -> "glasses IMU"; PitchSource.PHONE -> "phone sensor"; PitchSource.NONE -> "not started" }
  Text("Posture source: $source · angle ${focus.pitchDeg?.let { "%.1f°".format(it) } ?: "—"}", color = Muted)
  Text("Phone detection ${"%.2f".format(focus.lastPhoneScore)}${if (focus.phoneSeen) " · phone seen" else ""}", color = if (focus.phoneSeen) Warn else Muted)
  link.motionMessage?.let { Text(it, color = Warn, fontSize = 12.sp) }
}

@Composable
private fun SettingsSection(graph: AppGraph, settings: com.junction.focusglasses.data.Settings, running: Boolean) = Section("Settings") {
  val store = graph.settings
  LabeledSwitch("Demo mode (40 min in 2 min)", settings.demoMode, !running) { store.update { it.copy(demoMode = it.demoMode.not()) } }
  Text("Focus block ${settings.focusMinutes} min · break ${settings.breakMinutes} min · extension ${settings.extendMinutes} min", color = Muted, fontSize = 13.sp)
  Slider(settings.focusMinutes.toFloat(), { value -> store.update { it.copy(focusMinutes = (value / 5).roundToInt() * 5) } }, valueRange = 10f..60f, steps = 9, enabled = !running)
  LabeledSwitch("Calm background music", settings.ambientOn) { enabled -> store.update { it.copy(ambientOn = enabled) }; if (!enabled) graph.ambient.stop() }
  LabeledSwitch("Do Not Disturb during focus", settings.useDnd) { store.update { it.copy(useDnd = it.useDnd.not()) } }
  if (settings.useDnd && !graph.dnd.hasAccess) TextButton(onClick = graph.dnd::openAccessSettings) { Text("Grant Do Not Disturb access") }
  LabeledSwitch("Looking-down reminders", settings.postureAlertsOn) { enabled -> store.update { it.copy(postureAlertsOn = enabled) } }
  Text("Posture threshold ${settings.effectivePostureThresholdDeg.toInt()}° · distance threshold ${"%.2f".format(settings.closeRatio)}×", color = Muted, fontSize = 13.sp)
  Slider(settings.postureThresholdDeg, { value -> store.update { it.copy(postureThresholdDeg = value) } }, valueRange = 10f..35f)
  Slider(settings.closeRatio, { value -> store.update { it.copy(closeRatio = value) } }, valueRange = 1.1f..1.8f)
  LabeledSwitch("Invert posture direction", settings.invertPosture) { enabled -> store.update { it.copy(invertPosture = enabled) } }
}
