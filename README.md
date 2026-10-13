# DOOMCOUNT — Prototype Setup & Build Guide

**Milestone 1 — Detection Spike**

---

## Prerequisites

| Tool | Required version |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17 (bundled with Android Studio) |
| Android SDK | API 35 (compileSdk), API 26+ minimum |
| Gradle | 8.9 (downloaded automatically via wrapper) |
| Physical Android device | Required — accessibility events are unreliable on emulators |

---

## Build steps

### 1. Open in Android Studio

```
File > Open > select d:\Doomcount
```

Android Studio will sync Gradle automatically. If it fails:
- Check that the Android SDK is installed at the expected location
- Run **File > Invalidate Caches / Restart**

### 2. Build from command line (optional)

```powershell
cd d:\Doomcount
.\gradlew assembleDebug
```

The APK will be at:
```
app\build\outputs\apk\debug\app-debug.apk
```

### 3. Install on device

```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## Required Manifest Declarations

All required declarations are already in [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml):

| Declaration | Why |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` permission | Required to declare an accessibility service |
| `<service android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">` | Ensures only the Android system can bind to our service |
| `<meta-data android:resource="@xml/accessibility_service_config">` | Points to the XML that declares event types and target packages |

The accessibility service configuration is at [`res/xml/accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml).

---

## Enabling the Accessibility Service

The Accessibility Service **cannot be enabled programmatically** — the user must do it manually. The app explains this and provides a direct link to Android Settings.

Manual steps:
1. Open **Android Settings**
2. Go to **Accessibility**
3. Under **Downloaded Apps** (label may vary by OEM), tap **DOOMCOUNT — Video Transition Detector**
4. Toggle **Use DOOMCOUNT** on
5. Tap **Allow** on the system confirmation dialog
6. Return to the DOOMCOUNT app — the status indicator will turn green

> **OEM note:** On Samsung (OneUI), the path may be Settings > Accessibility > Installed Services.
> On Xiaomi/MIUI, you may need to also disable battery optimization for DOOMCOUNT.

---

## Observing Events with ADB

During manual testing, run this in a terminal to see events in real time:

```bash
# All DOOMCOUNT service logs
adb logcat -s DoomEvent DoomService

# Filter to detection-only logs
adb logcat -s DoomService

# Save to file for later analysis
adb logcat -s DoomEvent DoomService > session_log.txt
```

Log format:
```
D DoomEvent: [TYPE_WINDOW_STATE_CHANGED] pkg=com.zhiliaoapp.musically t=1728808000123
I DoomService: Foreground changed: com.zhiliaoapp.musically -> com.android.launcher
```

---

## Project Structure

```
app/src/main/kotlin/com/doomcount/app/
├── MainActivity.kt                         Entry point
├── ui/
│   ├── MainScreen.kt                       Prototype UI — single screen
│   ├── MainViewModel.kt                    Bridges service StateFlows to Compose
│   └── theme/
│       ├── Color.kt                        DOOMCOUNT dark palette
│       ├── Type.kt                         Typography
│       └── Theme.kt                        MaterialTheme wrapper
├── service/
│   ├── DoomAccessibilityService.kt         Accessibility service — event routing
│   └── EventLogger.kt                      Minimal safe event recorder (prototype only)
└── detectors/
    ├── DetectionResult.kt                  Sealed result: NoSignal / PossibleTransition / LikelyTransition
    └── TikTokDetector.kt                   TikTok heuristic (UNVALIDATED — read before trusting)
```

---

## Manual Testing Checklist

Before drawing any conclusions, complete all tests on a **real device** with `adb logcat` open.

### Setup verification
- [ ] App installs without errors
- [ ] "Accessibility Service: DISABLED" shown on first launch
- [ ] Tapping "Enable in Settings" opens Android Accessibility settings
- [ ] After enabling, app shows green "Accessibility Service: ENABLED" (may require returning to app)
- [ ] TikTok is installed (check package: `adb shell pm list packages | grep -E "musically|trill"`)

### Feed transition tests
- [ ] Open TikTok — check that event counters start incrementing
- [ ] Swipe up once — note how many `TYPE_WINDOW_STATE_CHANGED` events fire
- [ ] Swipe up 5 times quickly — note event pattern
- [ ] Swipe down (reverse) — does it produce the same events?
- [ ] Let a video loop to completion and auto-advance — any different event pattern?

### Non-feed navigation tests (must NOT count as transitions)
- [ ] Tap comment icon — record events, note class names in logcat
- [ ] Close comments — record events
- [ ] Tap a creator's profile — record events
- [ ] Navigate to Search tab — record events
- [ ] Navigate back to feed — record events
- [ ] Tap the Inbox tab — record events

### App lifecycle tests
- [ ] Switch to another app and back to TikTok — note event burst on re-entry
- [ ] Lock screen while TikTok is open, unlock — any unexpected events?
- [ ] Force-stop DOOMCOUNT while service is enabled — service should stop gracefully
- [ ] Reboot device — service should reconnect after re-enabling in settings
- [ ] "Clear session" button — counters should reset to 0

### Fill in your results
For each test above, record in `DETECTION_NOTES.md`:
- TikTok version number
- Android version and device model
- Logcat output lines for each action
- Whether the heuristic `PossibleTransitions` counter in the app matched your actual swipes

---

## Known Limitations

- Launcher icon uses a vector placeholder (adaptive icon). Replace with final artwork.
- `gradle-wrapper.jar` is not included. Gradle will download it on first sync.
- No unit tests yet — the `TikTokDetector` is structured to be testable but tests are not written.
- Detection is entirely unvalidated — see `DETECTION_NOTES.md`.

