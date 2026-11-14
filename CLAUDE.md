# FocusGlasses

Android app (Kotlin, Compose) for Ray-Ban Meta glasses via Meta Wearables DAT 1.0.1. Features and code map: README.md.

@DEVELOPMENT.md

## Working rules for AI assistants

- Build before claiming done: `.\gradlew.bat assembleDebug --console=plain` (JAVA_HOME = JDK 17). There are no unit tests; behaviour is verified on a real arm64 phone through logcat (`FocusGlasses:*` tags).
- Shell is Windows PowerShell 5.1: no `&&`, use `;`; prefix exe paths with `&`. Never rewrite source files with Get-Content/Set-Content (breaks UTF-8).
- All user-facing text and TTS lines are English.
- Thresholds in `FocusEngine` / `DistanceTracker` come from real-device measurements (see DEVELOPMENT.md §5); change them only with new logcat evidence.
- Before pushing: `git pull --rebase origin main`, build, then push to `main`. Never commit API keys, `local.properties`, `.cache/` or APKs.
