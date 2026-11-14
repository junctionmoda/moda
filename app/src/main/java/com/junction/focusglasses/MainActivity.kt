package com.junction.focusglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.junction.focusglasses.focus.DebugFrame
import com.junction.focusglasses.focus.FocusPhase
import com.junction.focusglasses.focus.FocusUi
import com.junction.focusglasses.focus.PitchSource
import com.junction.focusglasses.glasses.LinkPhase
import com.junction.focusglasses.glasses.LinkStatus
import com.junction.focusglasses.modes.FocusMode
import com.junction.focusglasses.modes.ModeCommand
import com.junction.focusglasses.modes.ModeStatus
import com.junction.focusglasses.modes.ModeUi
import com.junction.focusglasses.cooking.CookingPhase
import com.junction.focusglasses.cooking.CookingUi
import com.junction.focusglasses.meeting.MeetingPhase
import com.junction.focusglasses.meeting.MeetingUi
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

  private val graph get() = (application as FocusApp).graph

  private val requiredPermissions: Array<String> =
      buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
          }
          .toTypedArray()

  private val permissionsGranted = mutableStateOf(false)

  private val permissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsChanged() }

  /** Camera permission on the glasses, granted through the Meta AI app. */
  private var pendingGlassesPermission: Permission? = null
  private val glassesPermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
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
    onPermissionsChanged()
    if (!permissionsGranted.value) permissionLauncher.launch(requiredPermissions)
    setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8BD3A8), secondary = Color(0xFFB7C9FF))) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101614), contentColor = TextMain) {
          AppScreen(
              graph = graph,
              permissionsGranted = permissionsGranted.value,
              onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
              onRegister = { Wearables.startRegistration(this) },
              onUnregister = { Wearables.startUnregistration(this) },
              onRequestGlassesCamera = {
                pendingGlassesPermission = Permission.CAMERA
                glassesPermissionLauncher.launch(Permission.CAMERA)
              },
              onRequestGlassesVoice = {
                pendingGlassesPermission = Permission.MICROPHONE
                glassesPermissionLauncher.launch(Permission.MICROPHONE)
              },
          )
        }
      }
    }
  }

  private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

  private fun onPermissionsChanged() {
    permissionsGranted.value = requiredPermissions.all(::granted)
    // The DAT SDK needs Bluetooth permission before initialize(); the rest are optional.
    if (granted(Manifest.permission.BLUETOOTH_CONNECT) && !graph.wearablesInitialized) {
      graph.wearablesInitialized = true
      Wearables.initialize(this)
      graph.link.onWearablesInitialized()
    }
    // Voice commands: offline recognizer on the phone mic.
    if (granted(Manifest.permission.RECORD_AUDIO)) {
      graph.commands.start()
      graph.micGranted = true // also connects Focus Live when enabled
    }
  }
}

// region Screen

private val CardColor = Color(0xFF1A2420)
private val TextMain = Color(0xFFE6EEE9)
private val Muted = Color(0xFF9DB0A8)
private val Warn = Color(0xFFFFB86B)

@Composable
private fun AppScreen(
    graph: AppGraph,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onRequestGlassesCamera: () -> Unit,
    onRequestGlassesVoice: () -> Unit,
) {
  val link by graph.link.status.collectAsState()
  val ui by graph.engine.ui.collectAsState()
  val debug by graph.engine.debug.collectAsState()
  val settings by graph.settings.settings.collectAsState()
  val stats by graph.stats.stats.collectAsState()
  val modes by graph.modes.ui.collectAsState()
  val cooking by graph.cooking.ui.collectAsState()
  val meeting by graph.meeting.ui.collectAsState()

  Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("FocusGlasses", fontSize = 26.sp, fontWeight = FontWeight.Bold)
      Text("Ray-Ban Meta focus companion: Work Mode · distraction nudges · posture · focus tree", color = Muted, fontSize = 13.sp)

      if (!permissionsGranted) {
        Section("Permissions needed") {
          Text("Bluetooth (glasses), microphone (voice commands), camera (test mode), notifications (timer in background).", color = Muted)
          Button(onClick = onRequestPermissions) { Text("Grant permissions") }
        }
      }

      ConnectionSection(graph, link, onRegister, onUnregister, onRequestGlassesCamera, onRequestGlassesVoice)
      ModeSection(graph, modes)
      VoiceSection(graph, settings.liveOn)
      if (modes.activeMode == FocusMode.COOKING) CookingSection(graph, cooking, link)
      if (modes.activeMode == FocusMode.MEETING) MeetingSection(graph, meeting)
      FocusSection(graph, ui, link, modes)
      StatsSection(stats.todayMinutes, stats.todaySessions, stats.streakDays, ui.summary)
      SensorsSection(ui, debug, link)
      SettingsSection(graph, settings, ui.phase != FocusPhase.IDLE)
      Spacer(Modifier.height(40.dp))
    }
    PauseOverlay(ui)
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = CardColor, contentColor = TextMain), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
      content()
    }
  }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text(label, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
  }
}

@Composable
private fun ConnectionSection(
    graph: AppGraph,
    link: LinkStatus,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onRequestGlassesCamera: () -> Unit,
    onRequestGlassesVoice: () -> Unit,
) {
  Section("Glasses") {
    val reg =
        when (link.registration) {
          RegistrationState.REGISTERED -> "registered"
          RegistrationState.REGISTERING -> "registering…"
          RegistrationState.AVAILABLE -> "not registered"
          RegistrationState.UNAVAILABLE -> "unavailable (Meta AI app missing or Developer Mode off)"
          else -> link.registration.toString()
        }
    val phase =
        when (link.phase) {
          LinkPhase.IDLE -> "not connected"
          LinkPhase.CONNECTING -> "connecting…"
          LinkPhase.WAITING_PERMISSION -> "waiting for glasses camera permission"
          LinkPhase.STREAMING -> "connected, streaming"
          LinkPhase.PAUSED -> "paused (glasses off or folded)"
        }
    Text("SDK: ${if (link.wearablesReady) "ready" else "not initialized (needs Bluetooth permission)"}", color = Muted)
    Text("Registration: $reg · ${link.deviceCount} device(s)", color = Muted)
    Text("Status: $phase · ${link.framesReceived} frames", color = Muted)
    link.deviceInfo?.let { Text(it, color = Muted, fontSize = 12.sp) }
    link.motionMessage?.let { Text(it, color = Warn, fontSize = 13.sp) }
    Text(link.modeControlsStatus, color = Muted, fontSize = 13.sp)
    if (link.motionAvailable == true) Text("Glasses IMU: connected", color = Muted, fontSize = 13.sp)
    link.lastError?.let { Text("⚠ $it", color = Warn, fontSize = 13.sp) }

    LabeledSwitch("Test mode (phone back camera simulates the glasses)", link.mockEnabled, enabled = link.wearablesReady && link.phase == LinkPhase.IDLE) {
      if (it) graph.link.enableMock() else graph.link.disableMock()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (!link.mockEnabled) {
        if (link.registration == RegistrationState.REGISTERED) {
          OutlinedButton(onClick = onUnregister, enabled = link.wearablesReady) { Text("Unregister") }
        } else {
          OutlinedButton(onClick = onRegister, enabled = link.wearablesReady) { Text("Register in Meta AI") }
        }
      }
      if (link.phase == LinkPhase.IDLE) {
        Button(onClick = { graph.link.connect() }, enabled = link.wearablesReady) { Text("Connect glasses") }
      } else {
        OutlinedButton(onClick = { graph.link.disconnect() }) { Text("Disconnect") }
      }
    }
    if (link.phase == LinkPhase.WAITING_PERMISSION) {
      Button(onClick = onRequestGlassesCamera) { Text("Grant glasses camera permission") }
    }
    OutlinedButton(onClick = onRequestGlassesVoice, enabled = link.phase != LinkPhase.IDLE) {
      Text("Enable glasses mode voice")
    }
  }
}

/** Focus Live and offline voice status, shown in every mode. */
@Composable
private fun VoiceSection(graph: AppGraph, liveOn: Boolean) {
  val state by graph.live.state.collectAsState()
  val status by graph.live.status.collectAsState()
  val transcript by graph.live.transcript.collectAsState()
  val voiceStatus by graph.commands.status.collectAsState()
  val heard by graph.commands.heard.collectAsState()
  Section("Voice") {
    if (liveOn) Text(status, color = if (state == com.junction.focusglasses.ai.LiveState.ERROR) Warn else Muted, fontSize = 13.sp)
    if (transcript.isNotBlank()) Text(transcript, color = TextMain, fontSize = 13.sp)
    if (state != com.junction.focusglasses.ai.LiveState.READY) {
      Text(voiceStatus + (if (heard.isNotBlank()) " · heard \"$heard\"" else ""), color = Muted, fontSize = 12.sp)
    }
    if (liveOn && (state == com.junction.focusglasses.ai.LiveState.ERROR || state == com.junction.focusglasses.ai.LiveState.OFF)) {
      OutlinedButton(onClick = { graph.reconnectLive() }) { Text("Reconnect Focus Live") }
    }
  }
}

@Composable
private fun ModeSection(graph: AppGraph, modes: ModeUi) {
  Section("Focus Modes") {
    Text("Use voice, glasses touch, or these development controls. Work and Cooking have feature engines.", color = Muted, fontSize = 13.sp)
    FocusMode.entries.forEach { mode ->
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("${mode.title}: ${modes.statuses.getValue(mode).name.lowercase()}", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { graph.modes.dispatch(ModeCommand.Activate(mode)) }) { Text("Select") }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { graph.modes.dispatch(ModeCommand.Previous) }) { Text("Previous") }
      OutlinedButton(onClick = { graph.modes.dispatch(ModeCommand.TogglePause) }) { Text("Pause / resume") }
      OutlinedButton(onClick = { graph.modes.dispatch(ModeCommand.Next) }) { Text("Next") }
      OutlinedButton(onClick = { graph.modes.dispatch(ModeCommand.End) }) { Text("End") }
    }
    Text(modes.lastAction, color = Muted, fontSize = 12.sp)
  }
}

@Composable
private fun MeetingSection(graph: AppGraph, meeting: MeetingUi) {
  var actionItem by remember { mutableStateOf("") }
  Section("Meeting Mode") {
    meeting.meeting?.let { Text(it.title, fontWeight = FontWeight.SemiBold) }
    Text(meeting.message, color = Muted)
    when (meeting.phase) {
      MeetingPhase.SCHEDULED -> Button(onClick = graph.meeting::askNow) { Text("Check meeting now") }
      MeetingPhase.CHECK_IN -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = graph.meeting::started) { Text("Started") }
        OutlinedButton(onClick = graph.meeting::delayed) { Text("Delayed") }
        OutlinedButton(onClick = graph.meeting::skip) { Text("Skip") }
      }
      MeetingPhase.CONSENT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { graph.meeting.confirmConsent(true) }) { Text("Everyone agreed") }
        OutlinedButton(onClick = { graph.meeting.confirmConsent(false) }) { Text("Notes only") }
      }
      MeetingPhase.IN_PROGRESS -> {
        Text(if (meeting.transcriptConsent) "Consent recorded. Transcript capture is a demo consent state; add action items below." else "Notes-only meeting.", color = Muted, fontSize = 12.sp)
        OutlinedTextField(value = actionItem, onValueChange = { actionItem = it }, label = { Text("Action item or decision") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { graph.meeting.addActionItem(actionItem); actionItem = "" }) { Text("Save item") }
          OutlinedButton(onClick = graph.meeting::finish) { Text("Finish meeting") }
        }
      }
      else -> Unit
    }
  }
}

@Composable
private fun CookingSection(graph: AppGraph, cooking: CookingUi, link: LinkStatus) {
  Section("Cooking Mode") {
    Text(
        when (cooking.phase) {
          CookingPhase.PLANNING -> "Plan your meal"
          CookingPhase.COOKING -> "Cooking in progress"
          CookingPhase.PAUSED -> "Cooking Mode paused"
          CookingPhase.FINISHED -> "Meal finished"
          CookingPhase.IDLE -> "Ready"
        },
        fontWeight = FontWeight.SemiBold,
    )
    Text(cooking.advice, color = Muted)
    cooking.timerRemainingSec?.let { Text("Timer %02d:%02d".format(it / 60, it % 60), fontSize = 28.sp) }
    if (link.phase != LinkPhase.STREAMING) Text("Connect glasses and grant camera access before analyzing a view.", color = Warn, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = graph.cooking::fridgeOpened) { Text("Fridge opened") }
      Button(onClick = graph.cooking::analyzeCurrentView, enabled = !cooking.analyzing && link.phase == LinkPhase.STREAMING) { Text(if (cooking.analyzing) "Analyzing…" else "Analyze current view") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = graph.cooking::startTenMinuteTimer) { Text("Start 10 min timer") }
      OutlinedButton(onClick = graph.cooking::mealFinished) { Text("Meal finished") }
    }
    Text("Camera advice is optional and not a food-safety, fire, or burn detector.", color = Warn, fontSize = 12.sp)
  }
}

@Composable
private fun FocusSection(graph: AppGraph, ui: FocusUi, link: LinkStatus, modes: ModeUi) {
  if (modes.activeMode != null && modes.activeMode != FocusMode.WORK) {
    Section("Work Mode") {
      Text("Work Mode is paused while ${modes.activeMode.title} Mode is active.", color = Muted)
    }
    return
  }
  Section(
      when (ui.phase) {
        FocusPhase.IDLE -> "Work Mode"
        FocusPhase.FOCUS -> "Focusing" + (ui.taskLabel?.let { " · $it" } ?: "")
        FocusPhase.CHECK_IN -> "Need a break?"
        FocusPhase.BREAK -> "On a break"
        FocusPhase.PAUSED -> "Work Mode Paused"
      },
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Tree(growth = if (ui.phase == FocusPhase.IDLE) 0.15f else ui.treeGrowth, health = ui.treeHealth, modifier = Modifier.size(140.dp))
      Spacer(Modifier.width(16.dp))
      Column {
        val shown = if (ui.phase == FocusPhase.IDLE) graph.settings.current.focusSeconds else ui.remainingSec
        Text("%02d:%02d".format(shown / 60, shown % 60), fontSize = 48.sp, fontWeight = FontWeight.Light)
        Text("Round ${ui.rounds + if (ui.phase == FocusPhase.FOCUS || ui.phase == FocusPhase.CHECK_IN) 1 else 0} · ${ui.treesGrown} trees grown" + if (ui.extensions > 0) " · +${ui.extensions} extended" else "", color = Muted)
        Text("Tree health ${ui.treeHealth}%", color = if (ui.treeHealth < 50) Warn else Muted)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AssistChip(onClick = {}, label = { Text("Distractions ${ui.distractions}") })
      if (graph.settings.current.postureAlertsOn) AssistChip(onClick = {}, label = { Text("Posture ${ui.postureAlerts}") })
      AssistChip(onClick = {}, label = { Text("Distance ${ui.distanceAlerts}") })
      if (ui.dndActive) AssistChip(onClick = {}, label = { Text("Do Not Disturb on") })
    }
    if (ui.phase != FocusPhase.IDLE) {
      if (graph.settings.current.postureAlertsOn) Text(
          when {
            ui.calibrating -> "Learning your normal posture, hold still for 5 s…"
            ui.slouching -> "Head down…"
            else -> "Posture OK"
          },
          color = if (ui.slouching) Warn else Muted,
          fontSize = 13.sp,
      )
      Text(
          when {
            ui.tooClose -> "Too close…"
            ui.screenRatio != null || ui.bookRatio != null -> "Distance OK"
            else -> "Distance: learned automatically when you look at a screen or book"
          },
          color = if (ui.tooClose) Warn else Muted,
          fontSize = 13.sp,
      )
    }
    if (link.phase != LinkPhase.STREAMING && ui.phase == FocusPhase.IDLE) {
      Text("Tip: you can start without the glasses, just without phone detection and glasses posture.", color = Muted, fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (ui.phase == FocusPhase.IDLE) {
        Button(onClick = { graph.modes.dispatch(ModeCommand.Activate(FocusMode.WORK)) }) { Text("Start Work Mode") }
      } else if (ui.phase == FocusPhase.CHECK_IN) {
        Button(onClick = { graph.engine.takeBreak() }) { Text("Take a break") }
        OutlinedButton(onClick = { graph.engine.extendFocus() }) { Text("Not yet, +${graph.settings.current.extendMinutes} min") }
      } else if (ui.phase == FocusPhase.PAUSED) {
        Button(onClick = { graph.modes.dispatch(ModeCommand.Resume) }) { Text("Resume Work Mode") }
        OutlinedButton(onClick = { graph.modes.dispatch(ModeCommand.End) }) { Text("End Work Mode") }
      } else {
        Button(onClick = { graph.modes.dispatch(ModeCommand.End) }) { Text("End Work Mode") }
        OutlinedButton(onClick = { graph.engine.skipPhase() }) { Text("Skip") }
        OutlinedButton(onClick = { graph.engine.recalibrate() }) { Text("Recalibrate") }
      }
    }
  }
}

@Composable
private fun StatsSection(todayMinutes: Int, todaySessions: Int, streak: Int, summary: String?) {
  Section("Stats") {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      Stat("$todayMinutes", "min today")
      Stat("$todaySessions", "sessions today")
      Stat("$streak", "day streak")
    }
    summary?.let {
      HorizontalDivider(color = Color(0xFF2B3833))
      Text(it, color = Muted, fontSize = 14.sp)
    }
  }
}

@Composable
private fun Stat(value: String, label: String) {
  Column {
    Text(value, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    Text(label, color = Muted, fontSize = 12.sp)
  }
}

@Composable
private fun SensorsSection(ui: FocusUi, debug: DebugFrame?, link: LinkStatus) {
  Section("Sensors (debug)") {
    val src =
        when (ui.pitchSource) {
          PitchSource.GLASSES -> "glasses IMU"
          PitchSource.PHONE -> "phone sensor"
          PitchSource.NONE -> "none (starts with Work Mode)"
        }
    Text(
        "Head angle: ${ui.pitchDeg?.let { "%.1f°".format(it) } ?: "—"}  baseline: ${ui.baselineDeg?.let { "%.1f°".format(it) } ?: "—"}  source: $src",
        color = Muted,
        fontSize = 13.sp,
    )
    link.rawAccel?.let { Text("Glasses accel (x, y, z): $it", color = Muted, fontSize = 12.sp) }
    Text("The angle should drop (go negative) when you look down. If it goes the other way, turn on Invert posture in Settings.", color = Muted, fontSize = 12.sp)
    Text(
        "Phone detection: ${"%.2f".format(ui.lastPhoneScore)}${if (ui.phoneSeen) "  ← phone seen" else ""}" +
            if (ui.detectorReady == false) "  (detector failed to load)" else "",
        color = if (ui.phoneSeen) Warn else Muted,
        fontSize = 13.sp,
    )
    fun ratioText(r: Float?, calibrating: Boolean) =
        when {
          r != null -> "%.2f×".format(r)
          calibrating && ui.phase != FocusPhase.IDLE -> "calibrating"
          else -> "—"
        }
    val det = debug?.detection
    Text(
        "Distance (size vs. start): screen ${ratioText(ui.screenRatio, ui.screenCalibrating)}" +
            "  book ${ratioText(ui.bookRatio, ui.bookCalibrating)}" +
            (det?.screen?.let { "  · ${if (it.label == "tv") "monitor" else "laptop"} %.2f".format(it.size) } ?: "") +
            (det?.book?.let { "  · book %.2f".format(it.size) } ?: ""),
        color = if (ui.tooClose) Warn else Muted,
        fontSize = 13.sp,
    )
    if (debug != null) {
      DebugPreview(debug)
    } else if (link.phase == LinkPhase.STREAMING) {
      Text("Waiting for the first frame… (if it never comes, frames may be compressed)", color = Muted, fontSize = 12.sp)
    }
  }
}

@Composable
private fun DebugPreview(frame: DebugFrame) {
  val bmp = frame.bitmap
  val frameDet = frame.detection
  val det = frameDet.phone
  Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))) {
    Image(bmp.asImageBitmap(), contentDescription = "Glasses view", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    Canvas(Modifier.fillMaxSize()) {
      val sx = size.width / det.frameWidth
      val sy = size.height / det.frameHeight
      fun box(r: android.graphics.RectF, color: Color) =
          drawRect(
              color = color,
              topLeft = Offset(r.left * sx, r.top * sy),
              size = Size(r.width() * sx, r.height() * sy),
              style = Stroke(width = 3.dp.toPx()),
          )
      // Screen = blue, book = green, phone = red (yellow when unsure).
      frameDet.screen?.let { box(it.box, Color(0xFF6BB8FF)) }
      frameDet.book?.let { box(it.box, Color(0xFF8BD3A8)) }
      val phoneBox = det.box
      if (phoneBox != null && det.score >= 0.3f) {
        box(phoneBox, if (det.score >= 0.45f) Color(0xFFFF6B6B) else Color(0xFFFFD36B))
      }
    }
  }
}

@Composable
private fun SettingsSection(graph: AppGraph, s: com.junction.focusglasses.data.Settings, running: Boolean) {
  val store = graph.settings
  Section("Settings") {
    LabeledSwitch("Demo mode (clock runs 20× faster: 40 min = 2 min)", s.demoMode, enabled = !running) { v -> store.update { it.copy(demoMode = v) } }
    Text("Focus block ${s.focusMinutes} min · extend by ${s.extendMinutes} min · break ${s.breakMinutes} min", color = Muted, fontSize = 13.sp)
    Slider(
        value = s.focusMinutes.toFloat(),
        onValueChange = { v -> store.update { it.copy(focusMinutes = (v / 5).roundToInt() * 5) } },
        valueRange = 10f..60f,
        steps = 9,
        enabled = !running,
    )
    LabeledSwitch("Calm background music", s.ambientOn) { v ->
      store.update { it.copy(ambientOn = v) }
      if (!v) graph.ambient.stop() else if (graph.engine.ui.value.phase == FocusPhase.FOCUS) graph.ambient.start()
    }
    Text("Volume ${(s.ambientVolume * 100).toInt()}%", color = Muted, fontSize = 13.sp)
    Slider(value = s.ambientVolume, onValueChange = { v -> store.update { it.copy(ambientVolume = v) } })

    var dndAccess by remember { mutableStateOf(graph.dnd.hasAccess) }
    LaunchedEffect(Unit) {
      while (true) {
        dndAccess = graph.dnd.hasAccess
        delay(1500)
      }
    }
    LabeledSwitch("Turn on Do Not Disturb in Work Mode", s.useDnd) { v -> store.update { it.copy(useDnd = v) } }
    if (s.useDnd && !dndAccess) {
      TextButton(onClick = { graph.dnd.openAccessSettings() }) { Text("Grant Do Not Disturb access → find FocusGlasses and turn it on") }
    }

    Text("Posture: remind after the head drops more than ${s.effectivePostureThresholdDeg.toInt()}° for ${s.postureHoldSeconds} s", color = Muted, fontSize = 13.sp)
    Slider(value = s.postureThresholdDeg, onValueChange = { v -> store.update { it.copy(postureThresholdDeg = v) } }, valueRange = 10f..35f)
    LabeledSwitch("Looking-down reminders", s.postureAlertsOn) { v -> store.update { it.copy(postureAlertsOn = v) } }
    LabeledSwitch("Invert posture", s.invertPosture) { v -> store.update { it.copy(invertPosture = v) } }

    Text(
        "Distance: remind when the screen/book looks ${"%.2f".format(s.closeRatio)}× bigger than at the start (~${((1 - 1 / s.closeRatio) * 100).toInt()}% closer) for ${s.closeHoldSeconds} s",
        color = Muted,
        fontSize = 13.sp,
    )
    Slider(value = s.closeRatio, onValueChange = { v -> store.update { it.copy(closeRatio = v) } }, valueRange = 1.1f..1.8f)

    HorizontalDivider(color = Color(0xFF2B3833))
    LabeledSwitch("Focus Live (Gemini voice coach, needs the Gemini key below)", s.liveOn) { v -> store.update { it.copy(liveOn = v) } }
    if (s.liveOn) {
      Text("Live voice: ${s.liveVoice}", color = Muted, fontSize = 13.sp)
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Achernar" to "soft", "Sulafat" to "warm", "Vindemiatrix" to "gentle", "Aoede" to "breezy").forEach { (name, feel) ->
          TextButton(onClick = { store.update { it.copy(liveVoice = name) } }) {
            Text("$name\n$feel", color = if (name == s.liveVoice) TextMain else Muted, fontSize = 12.sp)
          }
        }
      }
    }
    VoicePicker(graph, s)
    Text("Gemini (optional): recognizes your task at the start", color = Muted, fontSize = 13.sp)
    var key by remember { mutableStateOf(s.geminiKey) }
    var model by remember { mutableStateOf(s.geminiModel) }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it.trim() },
        label = { Text("API Key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(value = model, onValueChange = { model = it.trim() }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { store.update { it.copy(geminiKey = key, geminiModel = model.ifBlank { "gemini-3.8-flash" }) } }) {
      Text(if (s.geminiKey.isBlank()) "Save" else "Saved · update")
    }
  }
}

/** TTS voice choice: tap a name to use it, ▶ to hear a sample. */
@Composable
private fun VoicePicker(graph: AppGraph, s: com.junction.focusglasses.data.Settings) {
  val voices by graph.voice.voices.collectAsState()
  val current by graph.voice.currentVoice.collectAsState()
  var expanded by remember { mutableStateOf(false) }
  Text("Voice: ${current ?: "default"}${if (s.ttsVoice.isBlank()) " (auto)" else ""}", color = Muted, fontSize = 13.sp)
  TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide voices" else "Choose voice (${voices.size})") }
  if (expanded) {
    voices.take(24).forEach { name ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { graph.voice.preview(name) }) { Text("▶") }
        TextButton(onClick = { graph.settings.update { it.copy(ttsVoice = name) } }) {
          Text(name + if (name == current) "  ✓" else "", color = if (name == current) TextMain else Muted, fontSize = 13.sp)
        }
      }
    }
  }
}

@Composable
private fun PauseOverlay(ui: FocusUi) {
  var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
  LaunchedEffect(ui.pauseCardUntil) {
    while (SystemClock.elapsedRealtime() < ui.pauseCardUntil) {
      now = SystemClock.elapsedRealtime()
      delay(200)
    }
    now = SystemClock.elapsedRealtime()
  }
  if (now >= ui.pauseCardUntil || ui.phase != FocusPhase.FOCUS) return
  val breath by
      rememberInfiniteTransition(label = "breath")
          .animateFloat(0.55f, 1f, infiniteRepeatable(tween(4000), RepeatMode.Reverse), label = "breathScale")
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Surface(color = Color(0xF0101614), contentColor = TextMain, modifier = Modifier.fillMaxSize()) {}
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text("Potential Distraction Detected", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextMain)
      ui.pauseReason?.let { Text(it, color = Muted) }
      Canvas(Modifier.size(180.dp)) {
        drawCircle(Color(0x558BD3A8), radius = size.minDimension / 2 * breath)
        drawCircle(Color(0xFF8BD3A8), radius = size.minDimension / 2 * breath, style = Stroke(2.dp.toPx()))
      }
      Text("Breathe with the circle. Getting a little distracted?", color = Muted)
      Text("Tree health ${ui.treeHealth}%", color = Warn)
    }
  }
}

/** A small stylised tree: trunk and crown scale with growth; leaves fade brown as health drops. */
@Composable
private fun Tree(growth: Float, health: Int, modifier: Modifier = Modifier) {
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val g = growth.coerceIn(0f, 1f)
    // ground
    drawRect(Color(0xFF3A2E24), topLeft = Offset(w * 0.1f, h * 0.9f), size = Size(w * 0.8f, h * 0.05f))
    val trunkH = h * (0.1f + 0.35f * g)
    val trunkW = w * (0.03f + 0.05f * g)
    drawRect(Color(0xFF7A5232), topLeft = Offset(w / 2 - trunkW / 2, h * 0.9f - trunkH), size = Size(trunkW, trunkH))
    val healthy = Color(0xFF5FBF7A)
    val sick = Color(0xFF9C7A3C)
    val t = (health / 100f).coerceIn(0f, 1f)
    val leaf =
        Color(
            red = sick.red + (healthy.red - sick.red) * t,
            green = sick.green + (healthy.green - sick.green) * t,
            blue = sick.blue + (healthy.blue - sick.blue) * t,
        )
    val r = w * (0.08f + 0.27f * g)
    val top = h * 0.9f - trunkH
    drawCircle(leaf, radius = r, center = Offset(w / 2, top - r * 0.5f))
    if (g > 0.4f) {
      drawCircle(leaf.copy(alpha = 0.9f), radius = r * 0.7f, center = Offset(w / 2 - r * 0.75f, top - r * 0.1f))
      drawCircle(leaf.copy(alpha = 0.9f), radius = r * 0.7f, center = Offset(w / 2 + r * 0.75f, top - r * 0.1f))
    }
    if (g >= 1f) drawCircle(Color(0xFFFF8A8A), radius = r * 0.12f, center = Offset(w / 2 + r * 0.3f, top - r * 0.7f))
  }
}

// endregion
