package com.junction.focusglasses.meeting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.junction.focusglasses.audio.Voice
import com.junction.focusglasses.ai.GeminiClient
import com.junction.focusglasses.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class MeetingPhase { IDLE, SCHEDULED, CHECK_IN, CONSENT, IN_PROGRESS, DELAYED, FINISHED }
data class CalendarMeeting(val id: Long, val title: String, val beginMs: Long, val endMs: Long)
data class MeetingUi(
    val phase: MeetingPhase = MeetingPhase.IDLE,
    val meeting: CalendarMeeting? = null,
    val transcriptConsent: Boolean = false,
    val notes: List<String> = emptyList(),
    val message: String = "Select Meeting Mode to check your local calendar.",
)

/** Local-calendar meeting check-in and local note store for the hackathon demo. */
class MeetingEngine(private val context: Context, private val scope: CoroutineScope, private val voice: Voice, private val settings: SettingsStore) {
  private val _ui = MutableStateFlow(MeetingUi())
  val ui: StateFlow<MeetingUi> = _ui.asStateFlow()
  private val prefs = context.getSharedPreferences("meeting_notes", Context.MODE_PRIVATE)
  private var remindedEventId: Long? = null

  fun start() { refresh(); if (_ui.value.meeting == null) _ui.update { it.copy(phase = MeetingPhase.SCHEDULED) } }
  fun pause() {}
  fun resume() {}
  fun stop() { _ui.update { it.copy(phase = MeetingPhase.IDLE) } }

  fun refresh() {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
      _ui.update { it.copy(message = "Allow Calendar permission to see your next meeting.") }
      return
    }
    scope.launch {
      val now = System.currentTimeMillis()
      val event = nextEvent(now)
      if (event == null) {
        _ui.update { it.copy(message = "No upcoming local calendar meeting found.") }
        return@launch
      }
      val due = now >= event.beginMs - 60_000 && now <= event.beginMs + 10 * 60_000
      _ui.update { it.copy(meeting = event, phase = if (due) MeetingPhase.CHECK_IN else MeetingPhase.SCHEDULED, message = if (due) "${event.title} is due. Has the meeting started?" else "Next meeting: ${event.title} at ${Instant.ofEpochMilli(event.beginMs)}") }
      if (due && remindedEventId != event.id) {
        remindedEventId = event.id
        voice.say("Your meeting ${event.title} is starting. Has it started?")
      }
    }
  }

  fun askNow() { _ui.update { it.copy(phase = MeetingPhase.CHECK_IN, message = "Has the meeting started?") }; voice.say("Has the meeting started?") }
  fun started() { _ui.update { it.copy(phase = MeetingPhase.CONSENT, message = "Would all participants agree to recording and transcription?") }; voice.say("Would all participants agree to recording and transcription?") }
  fun delayed() { _ui.update { it.copy(phase = MeetingPhase.DELAYED, message = "Meeting marked delayed. Check again when everyone is ready.") } }
  fun skip() { _ui.update { it.copy(phase = MeetingPhase.FINISHED, message = "Meeting skipped.") } }
  fun confirmConsent(consent: Boolean) {
    _ui.update { it.copy(phase = MeetingPhase.IN_PROGRESS, transcriptConsent = consent, message = if (consent) "Meeting notes are active. Record only with everyone’s consent." else "Meeting notes are active. Recording and transcription are off.") }
  }
  fun addActionItem(text: String) {
    if (text.isBlank()) return
    val entry = "${_ui.value.meeting?.title ?: "Meeting"}: $text"
    val all = (prefs.getString("notes", "").orEmpty().lines().filter { it.isNotBlank() } + entry).takeLast(50)
    prefs.edit().putString("notes", all.joinToString("\n")).apply()
    _ui.update { it.copy(notes = all, message = "Action item saved locally.") }
  }
  fun finish() { _ui.update { it.copy(phase = MeetingPhase.FINISHED, message = "Meeting saved locally. ${it.notes.size} action item(s) recorded.") } }

  /** Routes a final Speech transcript through Gemini when configured; falls back locally. */
  fun handleNaturalLanguage(text: String) {
    scope.launch {
      val s = settings.current
      val intent = GeminiClient.classifyMeetingIntent(text, s.geminiKey, s.geminiModel) ?: localIntent(text)
      when {
        intent.contains("started") -> started()
        intent.contains("delayed") -> delayed()
        intent.contains("skipped") -> skip()
        intent.contains("consent_recording") -> confirmConsent(true)
        intent.contains("notes_only") -> confirmConsent(false)
        intent.contains("action_item") -> addActionItem(text)
        else -> _ui.update { it.copy(message = "I did not understand that meeting reply. Try: started, delayed, record with consent, or notes only.") }
      }
    }
  }

  private fun localIntent(text: String): String {
    val t = text.lowercase()
    return when {
      "start" in t || "开始" in t -> "started"
      "delay" in t || "late" in t || "延" in t -> "delayed"
      "skip" in t || "取消" in t -> "skipped"
      "record" in t || "录音" in t || "同意" in t -> "consent_recording"
      "note" in t || "不录" in t -> "notes_only"
      "action" in t || "行动" in t || "决定" in t -> "action_item"
      else -> "unknown"
    }
  }

  private fun nextEvent(now: Long): CalendarMeeting? {
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath((now - 5 * 60_000).toString()).appendPath((now + 24 * 60 * 60_000).toString()).build()
    context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END), null, null, "begin ASC")?.use { c ->
      if (c.moveToFirst()) return CalendarMeeting(c.getLong(0), c.getString(1).orEmpty().ifBlank { "Untitled meeting" }, c.getLong(2), c.getLong(3))
    }
    return null
  }
}
