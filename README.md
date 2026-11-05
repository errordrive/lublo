# Lublo

**Lublo — Your AI assistant for Android.** Voice-controlled autonomous agent that reads your screen,
controls your phone, browses the web, makes calls, manages contacts, runs shell commands, and
helps you with daily tasks. Modern animated UI (Jervis-style AI bubble) with live conversations.

## Features
- 🎙️ **Voice-first**: tap mic, floating bubble, or **Volume Up + Down** to talk from any app
- 🤖 **Autonomous agent**: sees your screen via Accessibility API, thinks, acts, and reports
- 🌐 **Web & free APIs**: weather, news, crypto, wiki, time, web page fetching — all free
- 🔌 **Plugins**: dial calls, SMS, contacts, Termux shell, file读写, wallpaper, theme, clipboard
- 🚀 **App control**: open apps, install from Play Store, automate Chrome/YouTube/WhatsApp
- 🎨 **Animated UI**: glassmorphism, bubble pulse, typing indicators, smooth chat
- 🛡️ **Safety**: confirmations on risky actions (calls, sends, deletes), step caps, stop anytime
- 🔋 **Background tasks**: queue tasks that run while you keep talking
- 🧠 **Context aware**: remembers your task across turns, breaks big jobs into steps

## Build
**Android Studio:** File → Open → this folder → sync Gradle → Build → Build APK(s).
Release APK: `gradle assembleRelease` (debug key so it installs directly).
Output: `app/build/outputs/apk/release/app-release.apk`.

**GitHub Actions:** push to `main` or tag `v*` — CI builds and uploads the APK, tags publish a Release.

## First run
1. Install the APK. Android 13+: Settings → Apps → Lublo → ⋮ → **Allow restricted settings**.
2. Open Lublo → *Enable accessibility service* → turn on.
3. Allow microphone + notifications.
4. Enter your **API key** (base URL + model pre-filled for `free/hy4`).
5. Talk: tap 🎤, drag the floating bubble, or press **Volume Up + Down**.

## Example commands
- "Open YouTube and search Lo-fi beats"
- "What's the weather in Dhaka?"
- "Call Mom" (looks up her number first)
- "Install Free Fire from Play Store"
- "Search Chrome for the best budget laptop 2026 and read the first result"
- "Write a to-do list for tomorrow in my notes"
- "Set my wallpaper to a nature image"
- "Read my clipboard"

The API key is stored only on the device (never in source).
