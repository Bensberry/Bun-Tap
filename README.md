# BunTap — Auto Clicker & Visual Automation

*Made by bensberry.*

BunTap is a native Android app (Kotlin + Jetpack Compose) that automates taps in other apps:

* **Auto clicker** — tap one or many screen positions at a precise rate (taps per second ⇄
  milliseconds), a fixed number of times, for a number of passes, or until you stop it.
* **Visual targets** — save a picture of a button; BunTap recognises it on screen with OpenCV
  template matching and taps it (once per appearance, repeatedly while visible, wait-and-tap, or
  just log it).
* **Routines** — step-by-step automations with waits, taps, target checks, if/else branches,
  repeat and retry groups, time limits, key presses, text input, notifications and logs.
* **Floating panel + on-screen markers** — appears automatically when taps start: numbered,
  draggable, editable tap-point markers; start, pause, stop, emergency stop, add tap points and
  picture targets without leaving the app; hide everything (bring back from the notification).
* **Monitor & history** — requested vs. observed taps-per-second, tap counters, detections,
  timeouts, retries and an event log for every session.

No root. No internet permission. Everything stays on the phone.

---

## What BunTap can and cannot do (please read)

* Taps are performed through Android's **accessibility gesture API**. You must switch BunTap's
  accessibility service on yourself. It is not a mouse: there are no left/right/middle clicks.
* The rate you choose is a **requested schedule**. Android finishes each gesture and reports back
  before the next one; if your device can't keep up, BunTap skips slots instead of bursting and
  shows the real, measured rate. Few devices sustain more than ~20 taps per second.
* "Completed" means Android performed the gesture — **not** that the app underneath reacted.
  Some apps ignore automated or very short taps.
* Visual recognition needs **screen capture**, which Android asks you to allow every time it
  starts. It stops when the screen locks or BunTap's process ends. Screens that apps protect
  (banking, DRM video, password fields) are captured black and can't be recognised.
* Template matching compares pixels: a button that changes size, colour, theme, language or
  animates may not be recognised. Use *Test detection now* to check.
* "Press key" supports system actions only (Back, Home, Recents, notifications, quick settings,
  lock screen, and Enter in a focused field on Android 11+). "Type text" sets the text of the
  focused field. Arbitrary key injection is not possible for normal apps.
* Battery savers and some phone makers stop background apps. BunTap cannot guarantee indefinite
  runs.
* Respect the rules of the apps and games you automate. BunTap does not hide itself or bypass any
  security, anti-cheat or protection.

---

## Build the APK (Windows)

You need **Android Studio Panda 3 (2025.3.3) or newer** (it provides the Android SDK and Java).
This project was built with Panda 4.

### Option A — double-click

Run **`build-debug-apk.bat`** in the project folder. It finds Android Studio's Java by itself and
prints where the APK is.

### Option B — command line

Open **PowerShell** in the project folder (`BunClick`) and run:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

(On this PC Android Studio lives in `C:\Program Files\Android\Android Studio1`. Gradle needs
Java 17+; the `java` on your `PATH` is Java 8, which is why `JAVA_HOME` must be set.)

In **Command Prompt** the same is:

```bat
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug
```

The APK is written to:

```
app\build\outputs\apk\debug\app-debug.apk
```

The first build downloads Gradle and the libraries (a few hundred MB) and takes a few minutes.

### Option C — Android Studio

*File → Open* the `BunClick` folder, wait for the Gradle sync, then *Build → Build App Bundle(s) /
APK(s) → Build APK(s)*, or press ▶ to install on a connected phone or emulator.

## Install on a phone

* **USB:** enable Developer options → USB debugging, connect the phone, then run
  `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
* **File:** copy `app-debug.apk` to the phone and open it (allow installing from that app).

The debug APK is about 58 MB because it contains OpenCV for arm64, armv7 and x86_64 (emulator).
For an approximately 24 MB phone-only APK, set `buntap.abis=arm64-v8a` in `gradle.properties`.

## First start

1. Read the short introduction.
2. **Permissions & diagnostics** (shield icon on Home):
   * **Accessibility service** — required. Tap *Accessibility settings*, open “BunTap automation”,
     switch it on. On Android 13+ with an APK installed from a file, the switch may be locked:
     *App info → ⋮ → Allow restricted settings*, then try again.
   * **Display over other apps** — optional, for the floating panel and on-screen point picker.
   * **Notifications** — optional, for the status notification with Pause/Stop.
   * **Battery** — set to *Unrestricted* if long runs stop in the background.
3. Screen capture is started from the **Targets** tab when you need it (choose “Entire screen”).

## Using BunTap

### Auto clicker (Clicker tab)

1. Add points: **Pick a point on screen** (BunTap steps aside; drag the crosshair onto the spot in
   the other app and tap *Save point*), **Enter coordinates**, or **Pick on a screenshot** (when
   capture is running).
2. Choose **Same for all points** (one rate + tap count) or **Per-point settings** (edit a point to
   give it its own rate, count, delays, timeout and visits per pass).
3. Set the rate in **TPS or milliseconds** (they update each other), the taps per point, and the
   repeat mode: one pass, N passes, or until stopped (asks for confirmation). Optional session tap
   limit and maximum duration. The *Planned* box explains the total, e.g. “3 points × 10 taps =
   30 taps per pass”.
4. **Start** gives you a countdown to switch apps. Stop from the app, the notification, the floating
   panel, or the red **Emergency stop**.

Points remember the screen they were picked on. After rotating or changing resolution, BunTap
refuses to tap (and offers to scale or re-pick) instead of tapping the wrong place.

### Visual targets (Targets tab)

1. **Start screen capture.**
2. Get a screenshot: the floating panel's **camera** button (best: captures the app you are in),
   **Capture in 3 s**, or **Import an image file**.
3. **Crop** the button tightly and save. In the target editor choose what happens when it is
   detected, the tap rate and count, cooldown, tap position (centre or a custom point + offset),
   threshold, scan interval and optional search region.
4. **Test detection now** shows the current screen with the match, score and tap point.
5. Enable the targets you want and press **Start watching** (or arm the floating panel).

### Routines (Routines tab)

* **New routine** → **Add action** → pick from 19 action types. Group actions (Repeat, Retry, Time
  limit, If target exists/does not exist) get their own “+ Add here” sections.
* Every action can be disabled, duplicated, moved and given an *On failure* policy (stop or
  continue). The editor lists problems that must be fixed before running.
* **Run**, **Step debug** (pauses before every action), or **Arm floating panel**.
* **Create example routine** builds the specification's example from two of your targets.
* **Export** saves a `.buntap.json` file with the routine and its target images; **Import**
  validates a file and marks the routine for review before it can run.

### Floating panel and tap markers

Shown automatically when an automation starts (Settings → Floating panel; needs “Display over
other apps”). It hides while BunTap itself is open and appears over the app you automate,
together with **numbered markers** for the auto clicker's tap points.

* **Markers:** drag a marker to move its point; tap it to set its taps and speed, enable/disable
  it or delete it. While taps run, markers turn see-through to touches so they never block a tap.
* ▶ / ⏸ — start (re-runs whatever you last started; the auto clicker if nothing yet), pause, resume.
* ■ — stop. Red ⚡ — emergency stop.
* **+** — add a tap point: a new marker appears in the middle of the screen; drag it into place.
* 🖼 — add a picture target: drag a box around a button on the live screen (needs screen capture;
  if it is off, BunTap opens to start it).
* 👁 — hide the panel and all markers. A notification (“BunTap controls hidden”) has **Show** and
  **Close panel**; opening BunTap also brings them back.
* ✕ — close the floating panel (it returns the next time taps start).
* Tap the name to open BunTap; drag ☰ to move the panel.

Adding points or targets pauses running taps first; press ▶ to continue.

### Monitor tab

Live status, requested and observed TPS, planned/completed/remaining taps, dispatched/failed/
skipped gestures, missed deadlines, detections, timeouts, retries, the last detection and the
event log. **History** keeps finished sessions (no screenshots, never typed text).

### Settings

Default rate and tap count, tap press duration, start countdown, safety limits (max TPS, max taps
per session, max duration), floating panel, recognition resolution/threshold/scan interval, theme,
detailed logging, storage cleanup.

---

## Project structure

```
BunClick/
├─ app/
│  ├─ build.gradle.kts            module build (AGP 9 built-in Kotlin, Compose, KSP/Room, OpenCV)
│  └─ src/
│     ├─ main/java/com/buntap/app/
│     │  ├─ domain/               models, TPS⇄interval, validation, clicker planning (pure Kotlin)
│     │  ├─ automation/           AutomationEngine, TapScheduler, timing, clicker & visual tasks
│     │  ├─ routines/             routine model, executor, validator, editor tree ops, import/export
│     │  ├─ vision/               OpenCV matcher, template cache, candidate selection, tracker
│     │  ├─ capture/              MediaProjection service, frame holder/conversion, display monitor
│     │  ├─ accessibility/        accessibility service, gesture dispatcher, key/text actions
│     │  ├─ floating/             floating panel and on-screen point picker
│     │  ├─ data/                 Room database, repositories, target image storage
│     │  ├─ settings/ notifications/ permissions/
│     │  ├─ ui/                   theme, icons, components, navigation, one package per screen
│     │  ├─ AppContainer.kt       creates and wires everything (manual dependency injection)
│     │  ├─ AutomationLauncher.kt validates and starts automations
│     │  ├─ BunTapApplication.kt, MainActivity.kt
│     ├─ main/res/                strings, theme, icons, accessibility service config
│     ├─ test/                    149 JVM unit tests
│     └─ androidTest/             21 instrumented tests
├─ gradle/libs.versions.toml      all dependency versions
├─ build-debug-apk.bat            one-click debug build
└─ docs/
   ├─ ARCHITECTURE.md             how it works and why
   ├─ DEPENDENCIES.md             versions and compatibility decisions
   ├─ TEST_PLAN.md                tests, real results, manual test plan
   ├─ TROUBLESHOOTING.md          common problems
   └─ test-results/               raw test output and gesture measurements
```

## Tests

```bat
gradlew.bat testDebugUnitTest            :: 149 JVM unit tests
gradlew.bat connectedDebugAndroidTest    :: 21 instrumented tests (emulator or phone)
```

Last results (2026-10-10): **149/149 unit tests passed**, **21/21 instrumented tests passed** on
an Android 17 x86_64 emulator, including real accessibility gestures measured at a real view
(10 TPS requested → 9.93 TPS delivered, 100 ms median gap). Manual end-to-end checks on the same
emulator include the clicker (4.99 TPS observed for 5 TPS), screen capture, the floating panel,
and visual automation that found and tapped the Google Photos icon on the launcher by itself.
On a Samsung Galaxy A26 (Android 16) all 21 instrumented tests also pass (10 TPS requested →
9.71 TPS delivered, 0 missed deadlines). See [docs/TEST_PLAN.md](docs/TEST_PLAN.md) for
the full record, what is *not* verified, and a checklist to run on your phone.

## Privacy

* No `INTERNET` permission, no accounts, ads or analytics.
* Screen frames are processed in memory and discarded; only the cropped target images you save
  are stored (app-private). Temporary full screenshots are deleted when cropping ends and at
  start-up.
* History stores counts and text events only — never screenshots or typed text.
* App data is excluded from Android backups. Delete targets, routines and history in Settings.

## More documentation

* [Architecture](docs/ARCHITECTURE.md)
* [Dependencies and versions](docs/DEPENDENCIES.md)
* [Test plan and results](docs/TEST_PLAN.md)
* [Troubleshooting](docs/TROUBLESHOOTING.md)
