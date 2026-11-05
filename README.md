# Lublo

Lightweight voice-controlled Android agent (Accessibility API + OpenAI-compatible LLM). See `PLAN.md`.

## Build
**Android Studio (easiest):** File → Open → this folder → let Gradle sync → Build → Build APK(s).
Release APK: `Build > Generate Signed…` or run `gradle assembleRelease` (uses the debug key so it installs directly).
Output: `app/build/outputs/apk/release/app-release.apk`.

**GitHub Actions:** push to GitHub; `.github/workflows/build.yml` builds the APK and uploads it as an artifact.

## First run
1. Install the APK. On Android 13+: Settings → Apps → Lublo → ⋮ → **Allow restricted settings**.
2. Open Lublo → *Enable accessibility service* → turn Lublo on.
3. *Allow microphone*.
4. Enter your **API key** (base URL and model are pre-filled) → Save.
5. Talk: tap 🎤, use the floating bubble, or press **Volume Up + Down** together.

The API key is stored only on the device (never in source). Rotate any key you pasted into a chat.
