# FocusGlasses

Junction 2026 · Meta AI glasses focus companion. One Android app on the phone; Ray-Ban Meta (Gen 2) glasses connect through the Meta Wearables Device Access Toolkit (DAT) 1.0.1.

[中文说明](README_zh.md)

English UI and voice. This is a multi-mode Ray-Ban Meta companion; Work is the most complete mode.

## What works now

| Area | Current capability |
|---|---|
| Glasses link | Meta DAT registration, camera stream, IMU, MockDeviceKit test mode, experimental touch and Speech input |
| Mode control | Work, Cooking and Meeting lifecycle; start, pause, resume, end and switch modes |
| Work Mode | Focus timer, calm music, DND, temporally-smoothed phone/distraction nudges, distance/posture reminders, check-in, focus tree and local stats |
| Cooking Mode | Manual fridge prompt, one-frame Gemini suggestion, optional scene-change-gated proactive vision, timer and local dishes reminder |
| Meeting Mode | Reads the next local Android Calendar event, meeting start/delay/skip check-in, recording-consent prompt and local action items |

## Honest demo boundaries

- Cooking does **not** automatically recognise a fridge, flame, burnt food or food safety. The fridge cue is manual. Its opt-in proactive vision sends an occasional changed camera scene to Gemini (at most once per 45 seconds) and needs a configured API key; it stays off by default.
- Meeting Mode records consent and local notes. It does **not** continuously record or transcribe a meeting yet, and the calendar check is not a background all-day alarm.
- Glasses Inputs and Speech are experimental DAT capabilities and require developer approval plus real-device validation. Phone controls and MockDeviceKit validate the app flow, not hardware behaviour.

## Next

- Add explicit-consent meeting audio capture and continuous transcription.
- Add reliable calendar background reminders and confirmed follow-up tasks.
- Improve Cooking Mode with opt-in, evidence-based visual assistance; never treat it as a safety system.

## Work Mode detail

1. **"Start Work Mode"** (offline voice command): loads the saved focus length (default 40 min) and starts the timer, generated calm music and Do Not Disturb.
2. **Potential distraction**: the glasses camera sees a phone (on-device MediaPipe) and/or the phone is unlocked, which shows "Potential Distraction Detected" and gives a spoken nudge.
3. **Too close to the screen / book**: apparent size of the laptop or book in the glasses camera. Looking-down (IMU) reminders exist but are off by default.
4. **Adaptive check-in**: at the end of a block it asks "Need a break?". "Not yet, I want to finish this part" extends by 10 min; "yes / take a break" starts the break. Each answer is recorded (`StatsStore.recordCheckIn`).
5. **"End Work Mode"**: the focus tree and "40 MIN FOCUSED" summary.

**Demo mode** runs the session clock 20× faster (40 min in 2 min, +10 min in 30 s) and makes reminders fire sooner. Optional Gemini task labelling.

## Setup

- JDK 17, Android SDK 36 (Android Studio works out of the box)
- Create `local.properties` with `sdk.dir=...` (Android Studio does this automatically)
- Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- The first build downloads the Vosk speech model (`vosk-model-small-en-us-0.15`, ~40 MB) into `.cache/` (gitignored) and unpacks it into generated assets (`fetchVoskModel` in `app/build.gradle.kts`)
- For a natural voice, set **Speech Services by Google** as the phone's default TTS engine; pick a voice in Settings → Choose voice
- Phone: Android 12+ (minSdk 31), arm64 (MediaPipe ships arm64 natives only, so phone detection does not run on an x86 emulator)

## Running against real glasses

1. Meta AI app ≥ V292, glasses firmware ≥ V128
2. Meta AI → Settings → App Info → tap the version number 5× → enable **Developer Mode** (the app uses the Developer Mode placeholders `mwdat_application_id = 0`, `mwdat_client_token = 0`)
3. In the app: 在 Meta AI 中注册 → 连接眼镜 → grant the glasses camera permission
4. **Wear the glasses**: the session fails with `START_ERROR_DEVICE_UNAVAILABLE` while they report `DOFFED`

No glasses? Turn on 测试模式: MockDeviceKit simulates a pair using the phone's back camera.

See [TESTING.md](TESTING.md) for the full test checklist (Chinese).

## Code map

| Path | What |
|---|---|
| `glasses/GlassesLink.kt` | DAT: registration, session, camera stream, IMU, mock mode, device state |
| `focus/FocusEngine.kt` | Work Mode session: timer, check-in / extend, distractions, distance, posture, tree, summary |
| `modes/ModeController.kt` | Shared lifecycle and switching boundary for all modes |
| `cooking/CookingEngine.kt` | Cooking demo: advice, timer and dishes reminder |
| `meeting/MeetingEngine.kt` | Local Calendar meeting check-in, consent and local notes |
| `focus/DistanceTracker.kt` | Screen / book viewing distance from apparent size (vote window + absolute size) |
| `audio/VoiceCommands.kt` | Offline Vosk recognizer (phone mic) with a fixed phrase list |
| `vision/PhoneDetector.kt`, `FrameConverter.kt` | MediaPipe EfficientDet-Lite0: phone, laptop/tv, book |
| `audio/AmbientPlayer.kt`, `Voice.kt` | Generated calm music (random key / chords / melody), English TTS + voice picker |
| `system/FocusService.kt`, `DndController.kt`, `PhonePitchSensor.kt` | Foreground service + unlock receiver, DND, phone-sensor posture fallback |
| `data/Stores.kt` | Settings and stats (SharedPreferences) |
| `ai/GeminiClient.kt` | Optional Gemini REST call |
| `MainActivity.kt` | Compose UI |

## Known open issues

- **Posture axis** (measured on a real Ray-Ban Meta Gen 2): head pitch lives in the IMU's X–Z plane (Y ≈ 0) and the forward axis is +Z, so `GlassesLink` negates z before `Posture.elevationDegrees`. Level gaze ≈ -14°, looking down at a desk ≈ -50°. The posture baseline is calibrated in the first 5 s of a session, so start the session in your normal working posture: looking from a screen down to a desk is a 36° drop, more than the 20° default threshold.
- **Phone vs. other alerts**: a phone held up close is often boxed as a full-frame "laptop", and looking at it reads as head-down. Any phone hint (score ≥ 0.25) or unlock therefore mutes distance / posture reminders for 10 s.
- **Voice commands use the phone mic**, not the glasses: opening the glasses mic switches Bluetooth to the call profile and drops the music to call quality.
