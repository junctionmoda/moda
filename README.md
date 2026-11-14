# 专注眼镜 FocusGlasses

Junction 2026 · Meta AI glasses focus companion. One Android app on the phone; Ray-Ban Meta (Gen 2) glasses connect through the Meta Wearables Device Access Toolkit (DAT) 1.0.1.

Features: pomodoro + generated calm music, distraction detection (glasses camera sees a phone via on-device MediaPipe, plus phone unlock), "先停一下" pause nudge, posture reminders from the glasses IMU, grow-a-tree, auto DND, end-of-session stats and streak. Optional Gemini task labelling.

## Setup

- JDK 17, Android SDK 36 (Android Studio works out of the box)
- Create `local.properties` with `sdk.dir=...` (Android Studio does this automatically)
- Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
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
| `focus/FocusEngine.kt` | Pomodoro, distractions, posture, tree, summary |
| `vision/PhoneDetector.kt`, `FrameConverter.kt` | MediaPipe EfficientDet-Lite0 "cell phone" detection on the luma plane |
| `audio/AmbientPlayer.kt`, `Voice.kt` | Generated ambient sound, Chinese TTS + chimes |
| `system/FocusService.kt`, `DndController.kt`, `PhonePitchSensor.kt` | Foreground service + unlock receiver, DND, phone-sensor posture fallback |
| `data/Stores.kt` | Settings and stats (SharedPreferences) |
| `ai/GeminiClient.kt` | Optional Gemini REST call |
| `MainActivity.kt` | Compose UI |

## Known open issues

- **Posture axis**: on a real Ray-Ban Meta Gen 2 the head pitch shows up in the IMU's X–Z plane (Y ≈ 0). `glasses/Posture.kt` still assumes forward = −Z and needs recalibrating.
