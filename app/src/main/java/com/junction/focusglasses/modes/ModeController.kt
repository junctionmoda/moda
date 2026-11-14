package com.junction.focusglasses.modes

import android.util.Log
import com.junction.focusglasses.audio.VoiceCommand
import com.junction.focusglasses.audio.VoiceCommands
import com.junction.focusglasses.cooking.CookingEngine
import com.junction.focusglasses.meeting.MeetingEngine
import com.junction.focusglasses.focus.FocusEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The stable app-level boundary: feature teams implement a mode, this class owns switching. */
enum class FocusMode(val title: String) {
  WORK("Work"),
  STUDY("Study"),
  COOKING("Cooking"),
  RELAX("Relax"),
  MEETING("Meeting"),
}

enum class ModeStatus { INACTIVE, ACTIVE, PAUSED, COMPLETED }

data class ModeUi(
    val activeMode: FocusMode? = null,
    val statuses: Map<FocusMode, ModeStatus> = FocusMode.entries.associateWith { ModeStatus.INACTIVE },
    val lastAction: String = "Choose a focus mode to begin.",
)

sealed interface ModeCommand {
  data class Activate(val mode: FocusMode) : ModeCommand
  data object Pause : ModeCommand
  data object Resume : ModeCommand
  data object TogglePause : ModeCommand
  data object End : ModeCommand
  data object Next : ModeCommand
  data object Previous : ModeCommand
}

/**
 * Work is the first real feature-mode. The remaining modes deliberately expose only lifecycle
 * placeholders until their owning teams attach their own engines here.
 */
class ModeController(
    private val scope: CoroutineScope,
    private val work: FocusEngine,
    private val cooking: CookingEngine,
    private val meeting: MeetingEngine,
    private val commands: VoiceCommands,
) {
  private companion object {
    const val TAG = "FocusGlasses:Modes"
  }

  private val _ui = MutableStateFlow(ModeUi())
  val ui: StateFlow<ModeUi> = _ui.asStateFlow()

  init {
    scope.launch { commands.commands.collect(::onVoiceCommand) }
  }

  /** DAT Speech forwards only final transcripts here; partial text never changes mode state. */
  fun submitGlassesTranscript(transcript: String) {
    if (_ui.value.activeMode == FocusMode.MEETING) meeting.handleNaturalLanguage(transcript)
    else commands.submitFinalTranscript(transcript, source = "glasses")
  }

  fun dispatch(command: ModeCommand) {
    when (command) {
      is ModeCommand.Activate -> activate(command.mode)
      ModeCommand.Pause -> pause()
      ModeCommand.Resume -> resume()
      ModeCommand.TogglePause -> if (currentStatus() == ModeStatus.ACTIVE) pause() else resume()
      ModeCommand.End -> end()
      ModeCommand.Next -> move(1)
      ModeCommand.Previous -> move(-1)
    }
  }

  private fun activate(mode: FocusMode) {
    val previous = _ui.value.activeMode
    if (previous == mode && currentStatus() == ModeStatus.PAUSED) {
      resume()
      return
    }
    if (previous == mode && currentStatus() == ModeStatus.ACTIVE) return
    if (previous != null && currentStatus() == ModeStatus.ACTIVE) suspendMode(previous)
    startMode(mode)
    _ui.update {
      it.copy(
          activeMode = mode,
          statuses = it.statuses + (mode to ModeStatus.ACTIVE),
          lastAction = "${mode.title} mode is active.",
      )
    }
    Log.i(TAG, "activated $mode")
  }

  private fun pause() {
    val mode = _ui.value.activeMode ?: return
    if (currentStatus() != ModeStatus.ACTIVE) return
    suspendMode(mode)
    _ui.update { it.copy(lastAction = "${mode.title} mode paused.") }
    Log.i(TAG, "paused $mode")
  }

  private fun resume() {
    val mode = _ui.value.activeMode ?: return
    if (currentStatus() != ModeStatus.PAUSED) return
    when (mode) {
      FocusMode.WORK -> work.resume()
      FocusMode.COOKING -> cooking.resume()
      FocusMode.MEETING -> meeting.resume()
      else -> Unit
    }
    _ui.update { it.copy(statuses = it.statuses + (mode to ModeStatus.ACTIVE), lastAction = "${mode.title} mode resumed.") }
    Log.i(TAG, "resumed $mode")
  }

  private fun end() {
    val mode = _ui.value.activeMode ?: return
    when (mode) {
      FocusMode.WORK -> work.stop()
      FocusMode.COOKING -> cooking.stop()
      FocusMode.MEETING -> meeting.stop()
      else -> Unit
    }
    _ui.update {
      it.copy(
          activeMode = null,
          statuses = it.statuses + (mode to ModeStatus.COMPLETED),
          lastAction = "${mode.title} mode ended.",
      )
    }
    Log.i(TAG, "ended $mode")
  }

  private fun move(delta: Int) {
    val current = _ui.value.activeMode ?: FocusMode.WORK
    val index = FocusMode.entries.indexOf(current)
    activate(FocusMode.entries[(index + delta).mod(FocusMode.entries.size)])
  }

  private fun suspendMode(mode: FocusMode) {
    when (mode) {
      FocusMode.WORK -> work.pause()
      FocusMode.COOKING -> cooking.pause()
      FocusMode.MEETING -> meeting.pause()
      else -> Unit
    }
    _ui.update { it.copy(statuses = it.statuses + (mode to ModeStatus.PAUSED)) }
  }

  private fun currentStatus(): ModeStatus =
      _ui.value.activeMode?.let { _ui.value.statuses.getValue(it) } ?: ModeStatus.INACTIVE

  private fun startMode(mode: FocusMode) {
    when (mode) {
      FocusMode.WORK -> if (work.isPaused) work.resume() else if (!work.isRunning) work.start()
      FocusMode.COOKING -> cooking.start()
      FocusMode.MEETING -> meeting.start()
      else -> Unit
    }
  }

  private fun onVoiceCommand(command: VoiceCommand) {
    when (command) {
      VoiceCommand.START_WORK -> dispatch(ModeCommand.Activate(FocusMode.WORK))
      VoiceCommand.START_STUDY -> dispatch(ModeCommand.Activate(FocusMode.STUDY))
      VoiceCommand.START_COOKING -> dispatch(ModeCommand.Activate(FocusMode.COOKING))
      VoiceCommand.START_RELAX -> dispatch(ModeCommand.Activate(FocusMode.RELAX))
      VoiceCommand.PAUSE_MODE -> dispatch(ModeCommand.Pause)
      VoiceCommand.RESUME_MODE -> dispatch(ModeCommand.Resume)
      VoiceCommand.NEXT_MODE -> dispatch(ModeCommand.Next)
      VoiceCommand.PREVIOUS_MODE -> dispatch(ModeCommand.Previous)
      VoiceCommand.END_WORK, VoiceCommand.END_MODE -> dispatch(ModeCommand.End)
      VoiceCommand.YES -> if (_ui.value.activeMode == FocusMode.WORK) work.takeBreak()
      VoiceCommand.NOT_YET -> if (_ui.value.activeMode == FocusMode.WORK) work.extendFocus()
      VoiceCommand.COOK_IDEA -> if (_ui.value.activeMode == FocusMode.COOKING) cooking.analyzeCurrentView()
      VoiceCommand.COOK_TIMER ->
          if (_ui.value.activeMode == FocusMode.COOKING) cooking.startTimer(VoiceCommands.parseMinutes(commands.heard.value) ?: 10, announce = true)
      VoiceCommand.MEAL_DONE -> if (_ui.value.activeMode == FocusMode.COOKING) cooking.mealFinished()
    }
  }
}
