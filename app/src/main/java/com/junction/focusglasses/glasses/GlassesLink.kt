package com.junction.focusglasses.glasses

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.meta.wearable.dat.motion.Motion
import com.meta.wearable.dat.motion.addMotion
import com.meta.wearable.dat.motion.types.MotionConfiguration
import com.meta.wearable.dat.motion.types.MotionSamplingRate
import com.meta.wearable.dat.motion.types.MotionSource
import com.junction.focusglasses.vision.FrameConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch

enum class LinkPhase { IDLE, CONNECTING, WAITING_PERMISSION, STREAMING, PAUSED }

data class LinkStatus(
    val wearablesReady: Boolean = false,
    val registration: RegistrationState = RegistrationState.UNAVAILABLE,
    val deviceCount: Int = 0,
    val phase: LinkPhase = LinkPhase.IDLE,
    val mockEnabled: Boolean = false,
    /** null = not tried yet, true = IMU streaming, false = unavailable (see [motionMessage]). */
    val motionAvailable: Boolean? = null,
    val motionMessage: String? = null,
    val lastError: String? = null,
    val framesReceived: Long = 0,
    /** Raw glasses accelerometer (x, y, z) for checking the axis convention on a real pair. */
    val rawAccel: String? = null,
    /** Link / compatibility / worn / hinge / battery of the paired glasses, as the SDK reports. */
    val deviceInfo: String? = null,
)

/** Head-pitch sample in degrees, plus where it came from. */
data class PitchSample(val degrees: Double, val fromGlasses: Boolean)

/**
 * Everything that talks to the Meta glasses through the Device Access Toolkit: registration
 * state, the device session, the camera stream (decoded frames → small grayscale bitmaps) and
 * the experimental Motion (IMU) capability. Also hosts the MockDeviceKit "test mode", which
 * pretends to be a pair of Ray-Ban Meta glasses using the phone's back camera.
 */
class GlassesLink(private val context: Context, private val scope: CoroutineScope) {

  companion object {
    private const val TAG = "FocusGlasses:Link"
    /** Process at most this many frames per second for detection. */
    private const val FRAME_INTERVAL_MS = 450L
  }

  private val _status = MutableStateFlow(LinkStatus())
  val status: StateFlow<LinkStatus> = _status.asStateFlow()

  private val _frames =
      MutableSharedFlow<Bitmap>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  /** Downscaled grayscale frames from the glasses camera, throttled to ~2 per second. */
  val frames: SharedFlow<Bitmap> = _frames.asSharedFlow()

  private val _pitch =
      MutableSharedFlow<PitchSample>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  /** Head pitch from the glasses IMU (elevation of the camera's forward axis, degrees). */
  val pitch: SharedFlow<PitchSample> = _pitch.asSharedFlow()

  private var selector: DeviceSelector? = null
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var motion: Motion? = null
  private var mockGlasses: MockGlasses? = null
  private val sessionJobs = mutableListOf<Job>()
  private var lastFrameProcessedAt = 0L
  private var motionCount = 0L

  /** Call once, after Wearables.initialize() succeeded. */
  fun onWearablesInitialized() {
    if (_status.value.wearablesReady) return
    selector = AutoDeviceSelector()
    _status.update { it.copy(wearablesReady = true) }
    scope.launch {
      Wearables.registrationState.collect { reg -> _status.update { it.copy(registration = reg) } }
    }
    scope.launch {
      Wearables.devices.collectLatest { set ->
        _status.update { it.copy(deviceCount = set.size) }
        val flows = set.mapNotNull { Wearables.devicesMetadata[it] }
        if (flows.isEmpty()) {
          _status.update { it.copy(deviceInfo = null) }
          return@collectLatest
        }
        combine(flows) { it.toList() }.collect { devices ->
          val info =
              devices.joinToString(" | ") { d ->
                "${d.name.ifBlank { "眼镜" }}：连接 ${d.linkState} · 兼容 ${d.compatibility} · " +
                    "佩戴 ${d.donState} · 镜腿 ${d.hingeState} · 电量 ${d.batteryLevel}%"
              }
          Log.i(TAG, "device: $info")
          _status.update { it.copy(deviceInfo = info) }
        }
      }
    }
    scope.launch {
      Wearables.registrationErrorStream.collect { error ->
        Log.w(TAG, "registration error: ${error.description}")
        reportError("注册失败：${error.description}")
      }
    }
  }

  // region Test mode (MockDeviceKit)

  /** Simulated Ray-Ban Meta glasses whose "camera" is the phone's back camera. */
  fun enableMock(): Boolean {
    val kit = MockDeviceKit.getInstance(context)
    if (!kit.isEnabled) {
      kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
    }
    if (mockGlasses == null) {
      var failed = false
      kit.pairGlasses(GlassesModel.RAYBAN_META).fold(
          onSuccess = { glasses ->
            glasses.powerOn()
            glasses.unfold()
            glasses.don()
            glasses.services.camera.setCameraFeed(CameraFacing.BACK)
            mockGlasses = glasses
          },
          onFailure = { error, _ ->
            reportError("测试模式启动失败：${error.description}")
            failed = true
          },
      )
      if (failed) return false
    }
    _status.update { it.copy(mockEnabled = true, lastError = null) }
    return true
  }

  fun disableMock() {
    disconnect()
    val kit = MockDeviceKit.getInstance(context)
    if (kit.isEnabled) kit.disable()
    mockGlasses = null
    _status.update { it.copy(mockEnabled = false) }
  }

  // endregion

  // region Session lifecycle

  fun connect() {
    val sel = selector
    if (sel == null) {
      reportError("SDK 还没初始化，请先授予蓝牙权限")
      return
    }
    if (session != null) return
    _status.update { it.copy(phase = LinkPhase.CONNECTING, lastError = null, motionAvailable = null, motionMessage = null) }
    Wearables.createSession(sel).fold(
        onSuccess = { created ->
          session = created
          // Subscribe before start() so no transition is missed.
          sessionJobs += scope.launch {
            created.state.collect { state -> onSessionState(created, state) }
          }
          sessionJobs += scope.launch {
            created.errors.collect { error ->
              Log.w(TAG, "session error: ${error.description}")
              reportError(describeSessionError(error))
            }
          }
          created.start()
        },
        onFailure = { error, _ ->
          reportError(describeSessionError(error))
          _status.update { it.copy(phase = LinkPhase.IDLE) }
        },
    )
  }

  fun disconnect() {
    val current = session ?: return
    current.stop()
  }

  private fun onSessionState(current: DeviceSession, state: DeviceSessionState) {
    Log.d(TAG, "session state $state")
    when (state) {
      DeviceSessionState.STARTED -> {
        if (camera == null) scope.launch { ensureCameraPermissionThenStream() }
        else _status.update { it.copy(phase = LinkPhase.STREAMING) }
      }
      DeviceSessionState.PAUSED -> _status.update { it.copy(phase = LinkPhase.PAUSED) }
      DeviceSessionState.STOPPED -> cleanup(current)
      else -> Unit
    }
  }

  private suspend fun ensureCameraPermissionThenStream() {
    Wearables.checkPermissionStatus(Permission.CAMERA).fold(
        onSuccess = { status ->
          if (status == PermissionStatus.Granted) startCamera()
          else _status.update { it.copy(phase = LinkPhase.WAITING_PERMISSION) }
        },
        onFailure = { error, _ ->
          reportError("检查摄像头权限失败：${error.description}")
          _status.update { it.copy(phase = LinkPhase.WAITING_PERMISSION) }
        },
    )
  }

  /** Called by the UI after the Meta AI permission flow returned Granted. */
  fun onCameraPermissionGranted() {
    if (session != null && camera == null) startCamera()
  }

  private fun startCamera() {
    val current = session ?: return
    if (camera != null) return
    current
        .addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 7))
        .fold(
            onSuccess = { added ->
              camera = added
              val stream = added.stream
              sessionJobs += scope.launch(Dispatchers.Default) {
                stream.videoStream.collect { frame ->
                  val now = System.currentTimeMillis()
                  _status.update { it.copy(framesReceived = it.framesReceived + 1) }
                  if (now - lastFrameProcessedAt < FRAME_INTERVAL_MS) return@collect
                  lastFrameProcessedAt = now
                  if (frame.isCompressed) return@collect
                  FrameConverter.toGrayBitmap(frame.buffer, frame.width, frame.height)?.let { _frames.tryEmit(it) }
                }
              }
              sessionJobs += scope.launch {
                var motionTried = false
                stream.state.collect { state ->
                  Log.d(TAG, "stream state $state")
                  when (state) {
                    StreamState.STREAMING -> {
                      _status.update { it.copy(phase = LinkPhase.STREAMING) }
                      // The device is reachable once frames flow: a good moment to attach the IMU.
                      if (!motionTried) {
                        motionTried = true
                        attachMotion(current)
                      }
                    }
                    StreamState.PAUSED -> _status.update { it.copy(phase = LinkPhase.PAUSED) }
                    else -> Unit
                  }
                }
              }
              sessionJobs += scope.launch {
                stream.errorStream.collect { error -> reportError("视频流：${error.description}") }
              }
              stream.start().onFailure { error, _ -> reportError("视频流启动失败：${error.description}") }
            },
            onFailure = { error, _ -> reportError("添加摄像头失败：${describeSessionError(error)}") },
        )
  }

  private fun attachMotion(current: DeviceSession) {
    if (motion != null) return
    scope.launch {
      repeat(3) { attempt ->
        val result = current.addMotion(MotionConfiguration(samplingRate = MotionSamplingRate.HZ_10))
        val added = result.getOrNull()
        if (added != null) {
          motion = added
          sessionJobs += scope.launch {
            added.samples.collect { sample ->
              // Only the head-worn IMU says anything about posture (UNKNOWN = firmware left it unset).
              if (sample.source != MotionSource.GLASSES && sample.source != MotionSource.UNKNOWN) return@collect
              val accel = sample.accelerometer ?: return@collect
              if (++motionCount % 5 == 0L) {
                Log.i(TAG, "imu %.2f %.2f %.2f".format(accel.x, accel.y, accel.z))
                _status.update { it.copy(rawAccel = "%.1f, %.1f, %.1f".format(accel.x, accel.y, accel.z)) }
              }
              Posture.elevationDegrees(accel.x.toDouble(), accel.y.toDouble(), accel.z.toDouble())
                  ?.let { _pitch.tryEmit(PitchSample(it, fromGlasses = true)) }
            }
          }
          sessionJobs += scope.launch {
            added.errors.collect { error ->
              if (error != null) {
                _status.update { it.copy(motionMessage = "IMU：${error.description}") }
              }
            }
          }
          added.start()
          _status.update { it.copy(motionAvailable = true, motionMessage = null) }
          return@launch
        }
        val error = result.errorOrNull()
        Log.w(TAG, "addMotion failed (attempt ${attempt + 1}): ${error?.description}")
        if (error != DeviceSessionError.DEVICE_DISCONNECTED) {
          _status.update {
            it.copy(
                motionAvailable = false,
                motionMessage = "眼镜 IMU 不可用（${error?.description}），坐姿改用手机传感器",
            )
          }
          return@launch
        }
        delay(2000)
      }
      _status.update { it.copy(motionAvailable = false, motionMessage = "眼镜 IMU 连接超时，坐姿改用手机传感器") }
    }
  }

  private fun cleanup(stopped: DeviceSession) {
    if (session !== stopped) return
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    camera = null
    motion = null
    session = null
    _status.update { it.copy(phase = LinkPhase.IDLE, motionAvailable = null) }
  }

  // endregion

  private fun reportError(message: String) {
    Log.w(TAG, message)
    _status.update { it.copy(lastError = message) }
  }

  private fun describeSessionError(error: DeviceSessionError): String =
      when (error) {
        DeviceSessionError.NO_ELIGIBLE_DEVICE -> "找不到可用的眼镜：请确认眼镜已开机、已在 Meta AI app 里配对，并且已注册本 app"
        DeviceSessionError.SESSION_ALREADY_EXISTS -> "眼镜上已有其他 app 的会话在运行"
        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED -> "眼镜需要更新，请在 Meta AI app 里更新固件"
        DeviceSessionError.THERMAL_CRITICAL, DeviceSessionError.THERMAL_EMERGENCY -> "眼镜过热，请稍后再试"
        DeviceSessionError.BATTERY_CRITICAL -> "眼镜电量过低"
        else -> error.description
      }
}
