# Moda development workflow

## Environment

- Windows 11, PowerShell, JDK 17, Android SDK 36.
- Meta Wearables DAT 1.0.1.
- A compatible Ray-Ban Meta Gen 2 pair with Developer Mode is required to validate hardware behavior.

## Build and install

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug --console=plain
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

Use Test mode with the phone's back camera when glasses are unavailable. It exercises Moda's flow and on-device detector but does not validate hardware capabilities.

## Real-device checks

1. In Meta AI, pair the glasses and enable Developer Mode.
2. Register Moda, connect the glasses, and grant camera access.
3. Start a focus session, verify timer controls, camera frames, and a phone-distraction prompt.
4. Confirm that the IMU angle drops while looking down; recalibrate from a normal working posture.
5. Treat glasses touch and glasses speech as experimental. Verify any change on a real device before claiming it works.

## Guardrails

- Keep focus settings and stats local; no API key is needed.
- Do not change posture or distance thresholds without new logcat evidence.
- Build before each push and keep machine-local artifacts out of Git.
