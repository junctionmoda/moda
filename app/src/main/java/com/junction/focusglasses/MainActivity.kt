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
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

  private val graph get() = (application as FocusApp).graph

  private val requiredPermissions: Array<String> =
      buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
          }
          .toTypedArray()

  private val permissionsGranted = mutableStateOf(false)

  private val permissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsChanged() }

  /** Camera permission on the glasses, granted through the Meta AI app. */
  private val glassesPermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        if (result.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted) {
          graph.link.onCameraPermissionGranted()
        }
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
              onRequestGlassesCamera = { glassesPermissionLauncher.launch(Permission.CAMERA) },
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
) {
  val link by graph.link.status.collectAsState()
  val ui by graph.engine.ui.collectAsState()
  val debug by graph.engine.debug.collectAsState()
  val settings by graph.settings.settings.collectAsState()
  val stats by graph.stats.stats.collectAsState()

  Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("专注眼镜", fontSize = 26.sp, fontWeight = FontWeight.Bold)
      Text("Ray-Ban Meta 陪你专注：番茄钟 · 分心提醒 · 坐姿 · 小树", color = Muted, fontSize = 13.sp)

      if (!permissionsGranted) {
        Section("需要权限") {
          Text("蓝牙（连接眼镜）、摄像头（测试模式）、通知（后台计时）。", color = Muted)
          Button(onClick = onRequestPermissions) { Text("授予权限") }
        }
      }

      ConnectionSection(graph, link, onRegister, onUnregister, onRequestGlassesCamera)
      FocusSection(graph, ui, link)
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
) {
  Section("眼镜连接") {
    val reg =
        when (link.registration) {
          RegistrationState.REGISTERED -> "已注册"
          RegistrationState.REGISTERING -> "注册中…"
          RegistrationState.AVAILABLE -> "未注册"
          RegistrationState.UNAVAILABLE -> "不可用（Meta AI app 未安装或未开开发者模式）"
          else -> link.registration.toString()
        }
    val phase =
        when (link.phase) {
          LinkPhase.IDLE -> "未连接"
          LinkPhase.CONNECTING -> "连接中…"
          LinkPhase.WAITING_PERMISSION -> "等待眼镜摄像头授权"
          LinkPhase.STREAMING -> "已连接，画面传输中"
          LinkPhase.PAUSED -> "已暂停（眼镜摘下或折叠）"
        }
    Text("SDK：${if (link.wearablesReady) "就绪" else "未初始化（需要蓝牙权限）"}", color = Muted)
    Text("注册：$reg · 设备 ${link.deviceCount} 台", color = Muted)
    Text("状态：$phase · 已收帧 ${link.framesReceived}", color = Muted)
    link.deviceInfo?.let { Text(it, color = Muted, fontSize = 12.sp) }
    link.motionMessage?.let { Text(it, color = Warn, fontSize = 13.sp) }
    if (link.motionAvailable == true) Text("眼镜 IMU：已连接", color = Muted, fontSize = 13.sp)
    link.lastError?.let { Text("⚠ $it", color = Warn, fontSize = 13.sp) }

    LabeledSwitch("测试模式（用手机后摄像头模拟眼镜）", link.mockEnabled, enabled = link.wearablesReady && link.phase == LinkPhase.IDLE) {
      if (it) graph.link.enableMock() else graph.link.disableMock()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (!link.mockEnabled) {
        if (link.registration == RegistrationState.REGISTERED) {
          OutlinedButton(onClick = onUnregister, enabled = link.wearablesReady) { Text("取消注册") }
        } else {
          OutlinedButton(onClick = onRegister, enabled = link.wearablesReady) { Text("在 Meta AI 中注册") }
        }
      }
      if (link.phase == LinkPhase.IDLE) {
        Button(onClick = { graph.link.connect() }, enabled = link.wearablesReady) { Text("连接眼镜") }
      } else {
        OutlinedButton(onClick = { graph.link.disconnect() }) { Text("断开") }
      }
    }
    if (link.phase == LinkPhase.WAITING_PERMISSION) {
      Button(onClick = onRequestGlassesCamera) { Text("授予眼镜摄像头权限") }
    }
  }
}

@Composable
private fun FocusSection(graph: AppGraph, ui: FocusUi, link: LinkStatus) {
  Section(
      when (ui.phase) {
        FocusPhase.IDLE -> "专注"
        FocusPhase.FOCUS -> "专注中" + (ui.taskLabel?.let { " · $it" } ?: "")
        FocusPhase.BREAK -> "休息中"
      },
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Tree(growth = if (ui.phase == FocusPhase.IDLE) 0.15f else ui.treeGrowth, health = ui.treeHealth, modifier = Modifier.size(140.dp))
      Spacer(Modifier.width(16.dp))
      Column {
        val shown = if (ui.phase == FocusPhase.IDLE) graph.settings.current.focusSeconds else ui.remainingSec
        Text("%02d:%02d".format(shown / 60, shown % 60), fontSize = 48.sp, fontWeight = FontWeight.Light)
        Text("第 ${ui.rounds + if (ui.phase == FocusPhase.FOCUS) 1 else 0} 轮 · 已种 ${ui.treesGrown} 棵树", color = Muted)
        Text("小树生命 ${ui.treeHealth}%", color = if (ui.treeHealth < 50) Warn else Muted)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AssistChip(onClick = {}, label = { Text("分心 ${ui.distractions}") })
      AssistChip(onClick = {}, label = { Text("坐姿提醒 ${ui.postureAlerts}") })
      if (ui.dndActive) AssistChip(onClick = {}, label = { Text("勿扰已开") })
    }
    if (ui.phase != FocusPhase.IDLE) {
      Text(
          when {
            ui.calibrating -> "正在记录你的正常坐姿，请保持 5 秒…"
            ui.slouching -> "低头中…"
            else -> "坐姿正常"
          },
          color = if (ui.slouching) Warn else Muted,
          fontSize = 13.sp,
      )
    }
    if (link.phase != LinkPhase.STREAMING && ui.phase == FocusPhase.IDLE) {
      Text("提示：未连接眼镜也能开始，只是没有手机检测和眼镜坐姿。", color = Muted, fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (ui.phase == FocusPhase.IDLE) {
        Button(onClick = { graph.engine.start() }) { Text("开始专注") }
      } else {
        Button(onClick = { graph.engine.stop() }) { Text("结束") }
        OutlinedButton(onClick = { graph.engine.skipPhase() }) { Text("跳过") }
        OutlinedButton(onClick = { graph.engine.recalibrate() }) { Text("校准坐姿") }
      }
    }
  }
}

@Composable
private fun StatsSection(todayMinutes: Int, todaySessions: Int, streak: Int, summary: String?) {
  Section("统计") {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      Stat("$todayMinutes", "今日分钟")
      Stat("$todaySessions", "今日次数")
      Stat("$streak", "连续天数")
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
  Section("传感器（调试）") {
    val src =
        when (ui.pitchSource) {
          PitchSource.GLASSES -> "眼镜 IMU"
          PitchSource.PHONE -> "手机传感器"
          PitchSource.NONE -> "无（开始专注后启用）"
        }
    Text(
        "头部角度：${ui.pitchDeg?.let { "%.1f°".format(it) } ?: "—"}  基准：${ui.baselineDeg?.let { "%.1f°".format(it) } ?: "—"}  来源：$src",
        color = Muted,
        fontSize = 13.sp,
    )
    link.rawAccel?.let { Text("眼镜加速度 (x, y, z)：$it", color = Muted, fontSize = 12.sp) }
    Text("低头时角度应变小（负方向）。如果反了，在设置里打开「反转坐姿方向」。", color = Muted, fontSize = 12.sp)
    Text(
        "手机检测：${"%.2f".format(ui.lastPhoneScore)}${if (ui.phoneSeen) "  ← 看到手机" else ""}" +
            if (ui.detectorReady == false) "  （检测模型加载失败）" else "",
        color = if (ui.phoneSeen) Warn else Muted,
        fontSize = 13.sp,
    )
    if (debug != null) {
      DebugPreview(debug)
    } else if (link.phase == LinkPhase.STREAMING) {
      Text("等待第一帧…（如果一直没有，可能是视频帧是压缩格式）", color = Muted, fontSize = 12.sp)
    }
  }
}

@Composable
private fun DebugPreview(frame: DebugFrame) {
  val bmp = frame.bitmap
  val det = frame.detection
  Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))) {
    Image(bmp.asImageBitmap(), contentDescription = "眼镜画面", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    val box = det.box
    if (box != null && det.score >= 0.3f) {
      Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / det.frameWidth
        val sy = size.height / det.frameHeight
        drawRect(
            color = if (det.score >= 0.45f) Color(0xFFFF6B6B) else Color(0xFFFFD36B),
            topLeft = Offset(box.left * sx, box.top * sy),
            size = Size(box.width() * sx, box.height() * sy),
            style = Stroke(width = 3.dp.toPx()),
        )
      }
    }
  }
}

@Composable
private fun SettingsSection(graph: AppGraph, s: com.junction.focusglasses.data.Settings, running: Boolean) {
  val store = graph.settings
  Section("设置") {
    LabeledSwitch("演示模式（专注 2 分钟 / 休息 1 分钟）", s.demoMode, enabled = !running) { v -> store.update { it.copy(demoMode = v) } }
    LabeledSwitch("舒缓背景音", s.ambientOn) { v ->
      store.update { it.copy(ambientOn = v) }
      if (!v) graph.ambient.stop() else if (graph.engine.ui.value.phase == FocusPhase.FOCUS) graph.ambient.start()
    }
    Text("音量 ${(s.ambientVolume * 100).toInt()}%", color = Muted, fontSize = 13.sp)
    Slider(value = s.ambientVolume, onValueChange = { v -> store.update { it.copy(ambientVolume = v) } })

    var dndAccess by remember { mutableStateOf(graph.dnd.hasAccess) }
    LaunchedEffect(Unit) {
      while (true) {
        dndAccess = graph.dnd.hasAccess
        delay(1500)
      }
    }
    LabeledSwitch("专注时自动开启勿扰", s.useDnd) { v -> store.update { it.copy(useDnd = v) } }
    if (s.useDnd && !dndAccess) {
      TextButton(onClick = { graph.dnd.openAccessSettings() }) { Text("去授予「勿扰权限」→ 找到 专注眼镜 打开") }
    }

    Text("坐姿阈值：低头超过 ${s.postureThresholdDeg.toInt()}° 持续 ${s.postureHoldSeconds} 秒提醒", color = Muted, fontSize = 13.sp)
    Slider(value = s.postureThresholdDeg, onValueChange = { v -> store.update { it.copy(postureThresholdDeg = v) } }, valueRange = 10f..35f)
    LabeledSwitch("反转坐姿方向", s.invertPosture) { v -> store.update { it.copy(invertPosture = v) } }

    HorizontalDivider(color = Color(0xFF2B3833))
    Text("Gemini（可选）：开始时识别你在做什么任务", color = Muted, fontSize = 13.sp)
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
    OutlinedTextField(value = model, onValueChange = { model = it.trim() }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { store.update { it.copy(geminiKey = key, geminiModel = model.ifBlank { "gemini-2.5-flash" }) } }) {
      Text(if (s.geminiKey.isBlank()) "保存" else "已保存 · 更新")
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
      Text("先停一下", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TextMain)
      ui.pauseReason?.let { Text(it, color = Muted) }
      Canvas(Modifier.size(180.dp)) {
        drawCircle(Color(0x558BD3A8), radius = size.minDimension / 2 * breath)
        drawCircle(Color(0xFF8BD3A8), radius = size.minDimension / 2 * breath, style = Stroke(2.dp.toPx()))
      }
      Text("跟着圆圈呼吸。真的需要看手机吗？", color = Muted)
      Text("小树生命 ${ui.treeHealth}%", color = Warn)
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
