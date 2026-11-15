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
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.removeSpeech
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.types.SpeechError
import com.meta.wearable.dat.speech.types.SpeechState
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
    /** Experimental DAT Inputs / Speech status; independent from camera and IMU state. */
    val modeControlsStatus: String = "Controls: connect glasses to enable",
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

  /** Keeps optional glasses controls outside the camera and motion pipelines. */
  fun bindFocusControls(onTogglePause: () -> Unit, onEnd: () -> Unit, onTranscript: (String) -> Unit) {
    toggleFocus = onTogglePause
    endFocus = onEnd
    transcriptHandler = onTranscript
  }

  /** A partial transcription means the wearer has started talking and should take priority. */
  fun bindUserSpeechListener(listener: () -> Unit) {
    onUserSpeech = listener
  }

  /** Must be called only after DAT microphone permission has been granted. */
  fun enableModeSpeech() {
    speechRequested = true
    val current = session
    if (current == null || !inputsAttached) {
      _status.update { it.copy(modeControlsStatus = "Mode voice: connect glasses first") }
    } else {
      attachSpeech(current)
    }
  }

  private var selector: DeviceSelector? = null
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var motion: Motion? = null
  private var mockGlasses: MockGlasses? = null
  private var toggleFocus: (() -> Unit)? = null
  private var endFocus: (() -> Unit)? = null
  private var transcriptHandler: ((String) -> Unit)? = null
  private var inputsAttached = false
  private var speechRequested = false
  private var speechAttached = false
  private var activeSpeech: Speech? = null
  private var speechRestartJob: Job? = null
  private var speechHasStarted = false
  private var onUserSpeech: (() -> Unit)? = null
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
                "${d.name.ifBlank { "Glasses" }}: link ${d.linkState} · compat ${d.compatibility} · " +
                    "worn ${d.donState} · hinge ${d.hingeState} · battery ${d.batteryLevel}%"
              }
          Log.i(TAG, "device: $info")
          _status.update { it.copy(deviceInfo = info) }
        }
      }
    }
    scope.launch {
      Wearables.registrationErrorStream.collect { error ->
        Log.w(TAG, "registration error: ${error.description}")
        reportError("Registration failed: ${error.description}")
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
            reportError("Test mode failed to start: ${error.description}")
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
      reportError("SDK not initialized yet, grant Bluetooth permission first")
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
        attachInputs(current)
        if (speechRequested) attachSpeech(current)
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
          reportError("Camera permission check failed: ${error.description}")
          _status.update { it.copy(phase = LinkPhase.WAITING_PERMISSION) }
        },
    )
  }

  /** Called by the UI after the Meta AI permission flow returned Granted. */
  fun onCameraPermissionGranted() {
    if (session != null && camera == null) startCamera()
  }

  private fun attachInputs(current: DeviceSession) {
    if (inputsAttached) return
    current.addInputs(InputsConfiguration(consumeBack = true)).fold(
        onSuccess = { inputs ->
          inputsAttached = true
          _status.update { it.copy(modeControlsStatus = "Controls: touch ready") }
          sessionJobs += scope.launch {
            inputs.events.collect { event ->
              when (event) {
                is InputEvent.Select, is InputEvent.Button -> toggleFocus?.invoke()
                is InputEvent.Back -> endFocus?.invoke()
                else -> Unit
              }
            }
          }
        },
        onFailure = { error, _ ->
          _status.update { it.copy(modeControlsStatus = "Controls unavailable: ${error.description}") }
          Log.w(TAG, "controls unavailable: ${error.description}")
        },
    )
  }

  private fun attachSpeech(current: DeviceSession) {
    if (speechAttached) return
    current.addSpeech().fold(
        onSuccess = { speech ->
          speechAttached = true
          activeSpeech = speech
          speechHasStarted = false
          _status.update { it.copy(modeControlsStatus = "Glasses voice: starting") }
          sessionJobs += scope.launch {
            speech.transcriptions.collect { transcript ->
              val text = transcript?.text?.trim().orEmpty()
              if (text.isNotEmpty()) {
                // Do this for partials too: it stops output before the final command arrives.
                onUserSpeech?.invoke()
                _status.update { it.copy(modeControlsStatus = if (transcript?.isFinal == true) "Glasses voice: heard command" else "Glasses voice: hearing…") }
              }
              if (transcript?.isFinal == true && text.isNotEmpty()) {
                transcriptHandler?.invoke(text)
              }
            }
          }
          sessionJobs += scope.launch {
            speech.state.collect { state ->
              Log.i(TAG, "speech state $state")
              when (state) {
                SpeechState.STARTED -> {
                  speechHasStarted = true
                  _status.update { it.copy(modeControlsStatus = "Glasses voice: listening") }
                }
                // The initial STOPPED is normal. A later STOPPED commonly follows a capability
                // crossing, so restart the existing recognizer instead of silently going deaf.
                SpeechState.STOPPED -> if (speechHasStarted) restartSpeech(current)
                else -> Unit
              }
            }
          }
          sessionJobs += scope.launch {
            speech.errors.collect { error ->
              if (error == null) return@collect
              Log.w(TAG, "mode speech error: ${error.description}")
              if (error == SpeechError.UNAVAILABLE) {
                _status.update { it.copy(modeControlsStatus = "Glasses voice unavailable on these glasses") }
              } else {
                _status.update { it.copy(modeControlsStatus = "Glasses voice reconnecting…") }
                restartSpeech(current)
              }
            }
          }
          speech.start().onFailure { error, _ ->
            _status.update { it.copy(modeControlsStatus = "Glasses voice unavailable: ${error.description}") }
            restartSpeech(current)
          }
        },
        onFailure = { error, _ ->
          _status.update { it.copy(modeControlsStatus = "Glasses voice unavailable: ${error.description}") }
          Log.w(TAG, "glasses speech unavailable: ${error.description}")
        },
    )
  }

  /** Restarts the same DAT Speech capability after a transient stop; never retries unsupported hardware. */
  private fun restartSpeech(current: DeviceSession) {
    if (speechRestartJob?.isActive == true || !speechRequested || session !== current) return
    speechRestartJob =
        scope.launch {
          repeat(3) { attempt ->
            delay(700L * (attempt + 1))
            val speech = activeSpeech ?: return@launch
            if (session !== current || speech.state.value != SpeechState.STOPPED) return@launch
            Log.i(TAG, "restarting glasses speech (${attempt + 1}/3)")
            speech.start().onFailure { error, _ -> Log.w(TAG, "speech restart rejected: ${error.description}") }
            delay(500)
            if (speech.state.value != SpeechState.STOPPED) return@launch
          }
          if (session === current && activeSpeech?.state?.value == SpeechState.STOPPED) {
            _status.update { it.copy(modeControlsStatus = "Glasses voice stopped — tap Enable glasses voice to retry") }
          }
        }
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
                stream.errorStream.collect { error -> reportError("Video stream: ${error.description}") }
              }
              stream.start().onFailure { error, _ -> reportError("Video stream failed to start: ${error.description}") }
            },
            onFailure = { error, _ -> reportError("Could not add the camera: ${describeSessionError(error)}") },
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
              // Measured on a real Ray-Ban Meta Gen 2 (2026-11-14): pitch lives in the X–Z plane
              // (Y ≈ 0). Level gaze read (-9.5, 0.5, -2.4), looking down at a desk (-6.3, -0.4, -7.5),
              // so the camera's forward axis is +Z here, the opposite of the phone's -Z: negate z.
              // Gives ≈ -14° level (natural tilt on the face) and ≈ -50° at the desk.
              Posture.elevationDegrees(accel.x.toDouble(), accel.y.toDouble(), -accel.z.toDouble())
                  ?.let { _pitch.tryEmit(PitchSample(it, fromGlasses = true)) }
            }
          }
          sessionJobs += scope.launch {
            added.errors.collect { error ->
              if (error != null) {
                _status.update { it.copy(motionMessage = "IMU: ${error.description}") }
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
                motionMessage = "Glasses IMU unavailable (${error?.description}), using the phone sensor for posture",
            )
          }
          return@launch
        }
        delay(2000)
      }
      _status.update { it.copy(motionAvailable = false, motionMessage = "Glasses IMU timed out, using the phone sensor for posture") }
    }
  }

  private fun cleanup(stopped: DeviceSession) {
    if (session !== stopped) return
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    camera = null
    motion = null
    speechRestartJob?.cancel()
    speechRestartJob = null
    activeSpeech?.stop()
    stopped.removeSpeech()
    activeSpeech = null
    speechHasStarted = false
    inputsAttached = false
    speechAttached = false
    session = null
    _status.update {
      it.copy(
          phase = LinkPhase.IDLE,
          motionAvailable = null,
          modeControlsStatus = "Controls: glasses disconnected",
      )
    }
  }

  // endregion

  private fun reportError(message: String) {
    Log.w(TAG, message)
    _status.update { it.copy(lastError = message) }
  }

  private fun describeSessionError(error: DeviceSessionError): String =
      when (error) {
        DeviceSessionError.NO_ELIGIBLE_DEVICE -> "No eligible glasses: make sure they are on, paired in the Meta AI app, and this app is registered"
        DeviceSessionError.SESSION_ALREADY_EXISTS -> "Another app already has a session on the glasses"
        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED -> "The glasses need an update, update the firmware in the Meta AI app"
        DeviceSessionError.THERMAL_CRITICAL, DeviceSessionError.THERMAL_EMERGENCY -> "The glasses are too hot, try again later"
        DeviceSessionError.BATTERY_CRITICAL -> "Glasses battery too low"
        else -> error.description
      }
}
