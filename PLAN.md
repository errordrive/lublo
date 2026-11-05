# Lublo — Strategy & Super Plan

## 1. Goal
A tiny, dependency-free Android agent. You say (or type) a task; an LLM looks at the screen
through the Accessibility API and drives the phone step by step until done.

- Backend: OpenAI-compatible `POST {base}/chat/completions` (default `https://api.apinex.bond/v1`, model `free/hy4`).
- Targets: Android 7.0+ (API 24), any device.
- Size target: ~1 MB release APK (R8 + resource shrinking, zero third-party libs).

## 2. Design principles
1. **Zero dependencies.** Framework Views, `HttpURLConnection`, `org.json`, Android's own STT/TTS. Nothing to bloat the APK.
2. **Text, not pixels.** Send a compact accessibility-tree text (≤120 elements) instead of screenshots: cheap, fast, works with any text model.
3. **Stateless prompts.** Each step sends task + last 10 action results + current screen. No growing chat history → low token cost, no context blowup.
4. **One JSON action per step.** Plain-JSON replies (not native tool-calling) because `free/hy4` support is unknown. Parser tolerates `<think>` blocks and stray text.
5. **Human stays in control.** Stop button, volume-combo toggle, step cap, confirmation on risky actions, passwords never read.
6. **v0.2+:** Plugin/tool system, autonomous background tasks, animated UI (Jervis-style AI bubble), free online APIs (weather/news/crypto/wiki/time/fetch), contacts/dial/SMS/shell/file plugins.

## 3. Architecture
```
 Voice / typed task
        │
        ▼
  AgentService (AccessibilityService) ── Overlay (animated AI bubble · mic · stop · yes/no · status)
        │  ▲
        ▼  │ speak / listen
     Agent loop (own thread)        Voice (TTS + STT, partial results, queueing)
    ┌─────────────────────────┐
    │ 1 ScreenReader  → text  │
    │ 2 LlmClient     → JSON  │
    │ 3 safety gate           │
    │ 4 execute action/plugin │
    │ 5 settle 0.9s, repeat   │
    └─────────────────────────┘
        │
        ▼
  PluginEngine ──► open_app, dial_phone, send_message, lookup_contact, add_contact,
                   shell (Termux), write_file, read_file, set_wallpaper, set_theme,
                   get_battery/wifi/cellular/current_app/clipboard/active_tasks
  ApiPlugin   ──► weather, news, crypto, time, wiki, fetch (free public APIs)
  TaskQueue   ──► background tasks run while voice stays available
```
| File | Role |
|---|---|
| `AgentService.kt` | Accessibility service: gestures, open app, key combo, voice glue, blocking `ask()`/`confirm()`, background task queue |
| `Agent.kt` | Loop, prompt, JSON parse, action execution, stuck detection, risky gate, plugin dispatch |
| `ScreenReader.kt` | Accessibility tree → compact text + node handles |
| `LlmClient.kt` | HTTPS chat-completions call, streaming, function/tool schema injection |
| `Voice.kt` | TTS + STT wrapper, partial results, urgent speech, language switching |
| `Overlay.kt` | Animated floating AI bubble (Jervis pulse), draggable, conversation panel, confirm controls |
| `MainActivity.kt` | Modern animated chat UI, settings, setup wizard |
| `TaskQueue.kt` | Background task queue for autonomous operation |
| `plugins/Plugin.kt` | Plugin definitions (schema for the model) |
| `plugins/PluginEngine.kt` | Dispatches plugin calls, permission checks, safety |
| `plugins/ApiPlugin.kt` | Free online APIs: weather, news, crypto, time, wiki, web fetch |

## 4. Action set
tap · long_press · type(+submit) · scroll · swipe · tap_xy · back · home · recents · notifications ·
open_app · wait · ask · finish. Tap tries `ACTION_CLICK` on the nearest clickable ancestor, then falls back to a gesture at the element centre.

Plugins: dial_phone · send_message · lookup_contact · add_contact · shell · write_file · read_file ·
set_wallpaper · set_theme · get_battery · get_wifi · get_cellular · get_current_app · get_clipboard ·
set_clipboard · get_active_tasks.

## 5. Safety model (layered)
1. Model flags `"risky":true` (spend, send, delete, irreversible).
2. Local regex net on tapped labels (pay/buy/send/delete/transfer/uninstall… + Bengali/Hindi terms) — works even if the model forgets.
3. Risky → spoken prompt + on-screen ✔/✖; **default answer is NO** on timeout or unclear speech.
4. Password fields are replaced with `(password field)`; the prompt forbids typing passwords.
5. Hard step cap (default 25), repeat-action detection (warn at 3, abort at 6), stop anytime.
6. Shell commands blocked for destructive patterns (`rm -rf`, `su`, `dd`, force-stop, etc.).
7. Plugin permissions checked before execution (CALL_PHONE, SEND_SMS, READ/WRITE_CONTACTS).

## 6. Voice UX
- Trigger: mic button on the app, floating bubble (draggable), or **Volume Up + Down together** (press again to stop a running task).
- Spoken status, spoken questions (`ask`), spoken confirmations. Languages: en-US, bn-BD, hi-IN, en-GB, ar-SA.
- Voice "stop" is recognised whenever the app is listening (command prompt, questions, confirmations). During a plain run, use the ⏹ button or the volume combo.
- v0.2: partial recognition results streamed live, urgent speech interrupts normal queue.

## 7. Size & performance budget
- No AndroidX, no Compose, no coroutines, no HTTP/JSON libs. R8 full mode + shrinkResources, Kotlin intrinsics checks disabled.
- ≤120 nodes × ≤60 chars per step keeps prompts around 1–2k tokens.
- Play Services TTS optional (commented out by default).

## 8. Known risks & mitigations
| Risk | Mitigation |
|---|---|
| Android 13+ blocks sideloaded accessibility apps | App info → ⋮ → **Allow restricted settings**, then enable the service |
| Mic from background on Android 12+ | Overlay window stays visible (usually exempt); test on target phones |
| Starting apps from a service on Android 10+ | Same overlay exemption; fallback is the model using Home + launcher |
| Secure windows (banking, FLAG_SECURE) unreadable | Agent reports unreadable screen; user takes over |
| `free/hy4` ignores JSON-only instruction | Tolerant parser + "invalid JSON" feedback in history |
| Google Play policy on Accessibility API | Personal/sideload use only |
| Volume combo lets the first key change volume once | Acceptable; floating mic is the alternative |
| SMS/CALL requires runtime permission | Permission requested on first use; graceful error message |
| Termux not installed | Shell plugin returns clear message; guides user to install it |

## 9. Roadmap
- **v0.1:** core agent loop, accessibility, basic UI, voice, safety.
- **v0.2 (this drop):** plugin/tool system, animated Jervis-style AI bubble, modern chat UI,
  free online APIs (weather/news/crypto/wiki/time/fetch), contacts/dial/SMS/shell/file plugins,
  background task queue, autonomous mode, better TTS/STT.
- **v0.3:** saved routines ("good morning"), wake-word via on-device model (opt-in), notification-triggered tasks, screenshot fallback for vision models.

## 10. Test checklist (on a real phone)
1. Enable service, grant mic, save key. 2. "Open Settings" 3. "Turn on Wi-Fi" 4. Bengali command
5. Risky: "send hi to <contact> on WhatsApp" → must ask confirmation 6. Stop button mid-task
7. Volume combo from inside another app 8. Wrong API key → clear spoken error
9. "What's the weather in Dhaka?" → uses weather plugin, speaks result
10. "Call Mom" → lookup_contact → dial_phone with confirmation
11. "Install Free Fire" → opens Play Store, searches, taps install
12. "Write hello.txt on my phone" → write_file plugin
13. Background task: "check crypto prices every hour" → queued autonomously
