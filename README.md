# Moda for Ray-Ban Meta Gen 2

Moda is a focused Android companion for Ray-Ban Meta Gen 2 glasses. It deliberately does one job well: support a distraction-aware focus session without requiring a cloud account or API key.

[中文说明](README_zh.md)

## Project demo

[Open the browser-only Moda demo](https://htmlpreview.github.io/?https://github.com/xingzhuonan-blip/moda/blob/main/demo/index.html). It simulates glasses connection, camera detection, posture, voice controls, and the full focus session in the browser—no phone app or glasses required.

## What it does

- Connects to compatible glasses through Meta Wearables Device Access Toolkit (DAT) 1.0.1.
- Runs a focus timer with breaks, an optional calm soundtrack, local stats, and optional Do Not Disturb.
- Uses the glasses camera to give gentle, rate-limited phone-distraction and viewing-distance reminders.
- Uses glasses IMU posture data when available, with a phone-sensor fallback and an opt-in posture reminder.
- Supports fixed offline voice commands on the phone, plus experimental glasses touch and speech controls.
- Includes MockDeviceKit test mode, which uses the phone back camera to simulate glasses.

## Product boundary

Moda is a single-purpose focus companion. Cooking, calendar, meeting capture, cloud AI, and multi-mode navigation were removed: they were not sufficiently reliable to be part of the core product. All session data stays on the device.

Glasses touch and speech are experimental DAT capabilities. They are helpful controls when available, but Moda remains fully usable with its on-screen controls and phone-mic commands.

## Build

Requirements: JDK 17, Android SDK 36, Android Studio Narwhal or newer.

```powershell
.\gradlew.bat assembleDebug --console=plain
```

The first build downloads the offline Vosk English voice model (~40 MB) into the ignored `.cache/` directory. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Use with glasses

1. Pair compatible Ray-Ban Meta Gen 2 glasses in the Meta AI app and enable Developer Mode.
2. In Moda, register with Meta AI, connect the glasses, and grant camera access.
3. Start a focus session. Say “start focus”, “pause”, “resume”, “end focus”, “yes”, or “not yet”, or use the on-screen controls.

Without glasses, enable Test mode to develop against the phone's back camera. It validates the app flow, not physical glasses behavior.

## Code map

| Path | Responsibility |
|---|---|
| `glasses/GlassesLink.kt` | DAT registration, session, camera, IMU, MockDeviceKit, experimental controls |
| `focus/FocusEngine.kt` | Timer, break check-ins, reminder state, focus tree, and session lifecycle |
| `vision/PhoneDetector.kt` | On-device phone, screen, and book detection |
| `audio/VoiceCommands.kt` | Offline fixed-phrase voice controls |
| `system/FocusService.kt` | Foreground session and unlock signal |
| `data/Stores.kt` | Local settings and daily stats |

## Privacy and safety

Camera frames are processed on-device for the focus experience. Moda is not a posture, eye-health, or safety system; reminders are optional prompts, not medical advice.
