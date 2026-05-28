# Long Press Bot — Android App

A fully functional Android app that simulates continuous long-press gestures
at a configurable screen position, using Android's Accessibility Service API.

---

## Features

| Feature | Description |
|---|---|
| 🎯 Draggable overlay button | Floating button you drag to any screen position |
| 📐 Percentage-based positioning | Position stored as % of screen size — works on any device resolution |
| ⏱ Hold Duration slider | 50 ms – 4 550 ms configurable press length |
| 🔁 Repeat Interval slider | 50 ms – 3 000 ms gap between each gesture cycle |
| ✅ Permission guards | Guides user through overlay + accessibility setup |
| 💾 Persistent settings | Position and timing survive app restarts |
| 🔒 Safe stop | Gestures stop cleanly; no dangling callbacks |

---

## Project Structure

```
LongPressApp/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/longpress/app/
│       │   ├── AppPreferences.kt          # SharedPreferences wrapper
│       │   ├── LongPressAccessibilityService.kt  # Gesture engine
│       │   ├── OverlayService.kt          # Draggable floating button
│       │   └── MainActivity.kt            # UI + permission flow
│       └── res/
│           ├── drawable/                  # Vector icons + button backgrounds
│           ├── layout/                    # activity_main.xml, overlay_button.xml
│           ├── values/                    # strings, colors, themes
│           └── xml/
│               └── accessibility_service_config.xml
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew / gradlew.bat
```

---

## Build Requirements

- **Android Studio** Hedgehog (2023.1.1) or newer  
  *or* JDK 17 + Android SDK command-line tools
- **Gradle** 8.6 (downloaded automatically by the wrapper)
- **Android SDK** with `compileSdk 34` / `Build-Tools 34.x`
- **minSdk 26** (Android 8.0 Oreo) — required for `GestureDescription`

---

## How to Build

### Option A — Android Studio (recommended)

1. Open Android Studio → **File → Open** → select the `LongPressApp` folder.
2. Let Gradle sync finish.
3. Edit `local.properties` and set `sdk.dir` to your Android SDK path if it
   isn't set automatically.
4. **Build → Make Project** (`Ctrl+F9` / `⌘F9`).
5. **Run → Run 'app'** to install on a connected device / emulator.

### Option B — Command Line

```bash
# macOS / Linux
export ANDROID_HOME=/path/to/your/Android/Sdk
cd LongPressApp
./gradlew assembleDebug

# Windows
set ANDROID_HOME=C:\Users\You\AppData\Local\Android\Sdk
cd LongPressApp
gradlew.bat assembleDebug
```

Output APK:
```
app/build/outputs/apk/debug/app-debug.apk
```

Install directly:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## First-Run Setup (on device)

Two one-time permissions are required:

### 1. Overlay Permission
- Tap **"Grant Overlay Permission"**
- The system Settings page opens — find **Long Press Bot** and toggle it on
- Return to the app

### 2. Accessibility Service
- Tap **"Enable Accessibility Service"**
- Navigate to **Long Press Bot** in the Accessibility list and enable it
- Return to the app

Both status lines will turn **teal** (✓) when ready.

---

## Usage

1. Tap **START** — a purple ⊕ button appears floating on screen.
2. **Drag the ⊕ button** to the exact spot you want to long-press.
3. Adjust **Hold Duration** and **Repeat Interval** sliders as needed.
4. The Accessibility Service will continuously perform long-press gestures
   at the saved position until you tap **STOP**.

> **Tip:** The floating button turns green while the service is actively
> performing gestures, and purple when idle.

---

## Architecture Notes

### Coordinate system
The overlay button converts its pixel position to `(xPercent, yPercent)` on
drag-release and writes them to `AppPreferences`. The Accessibility Service
reads these percentages each cycle and multiplies by the current screen
dimensions obtained from `WindowManager`. This makes the coordinates
resolution-independent and safe to use on any device.

### Gesture loop
```
startGestureLoop()
  └─ scheduleNextGesture()
       └─ performLongPressGesture()
            ├─ builds a GestureDescription (single Path stroke, duration = holdMs)
            ├─ calls dispatchGesture()
            └─ on callback onCompleted → postDelayed(scheduleNextGesture, pauseMs)
```
Stopping sets `isPerforming = false` and calls
`handler.removeCallbacksAndMessages(null)` — no gesture can leak after stop.

### IPC
- `MainActivity` → `LongPressAccessibilityService`: local broadcasts
  (`ACTION_START` / `ACTION_STOP`)
- `OverlayService` → `MainActivity`: local broadcast
  (`ACTION_POSITION_CHANGED`) when the user releases the drag

---

## Permissions Explained

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the draggable button above other apps |
| `FOREGROUND_SERVICE` | Keep `OverlayService` alive while the overlay is visible |
| `BIND_ACCESSIBILITY_SERVICE` | System-only; grants gesture dispatch capability |

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Gesture cancelled" in logs | Another app may be consuming touch events; try a different position |
| Overlay button not appearing | Re-grant overlay permission in system settings |
| Service stops after screen off | Disable battery optimisation for the app in system settings |
| App crashes on install | Ensure `minSdk 26`; gestures API requires Android 8.0+ |
