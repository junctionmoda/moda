# Moda development notes

Read `DEVELOPMENT.md` before changing runtime behavior.

- Build before reporting code work complete: `./gradlew.bat assembleDebug --console=plain` with JDK 17.
- Keep the product single-purpose: focus sessions, on-device detection, and local data. Do not add cloud AI or unrelated modes without a verified product requirement.
- User-facing text and TTS are English.
- Thresholds in `FocusEngine` and `DistanceTracker` come from real-device measurements; change them only with new device evidence.
- Before pushing: `git pull --rebase origin main`, build, then push to `main`. Never commit `local.properties`, `.cache/`, API keys, or APKs.
