# Moda test checklist

## Build

```powershell
.\gradlew.bat assembleDebug --console=plain
```

## No-glasses flow

1. Grant requested Android permissions.
2. Enable Test mode, connect, and start a focus session.
3. Check start, pause, resume, end, break, and extension controls.
4. Point the phone camera at a phone, screen, or book and verify that the app remains responsive.

## Real glasses flow

1. Register in Meta AI, connect the worn and unfolded glasses, and grant glasses camera permission.
2. Confirm frames arrive and the IMU posture source becomes available.
3. Verify the timer and phone-distraction reminder during an active focus block.
4. If supported by the device, verify touch pause/end and one offline glasses-voice command.

Test mode confirms application flow only. Record real-device evidence before changing reminder thresholds or claiming a DAT capability works.
