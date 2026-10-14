# BunTap test plan and results

Everything below says **what was actually run**, where, and with what result. "Verified" means
the result was observed; anything not observed is listed under *Not verified*.

Test environment used (2026-10-10):

* Windows 11, Android Studio Panda 4 (JBR 21), Gradle 9.3.1, AGP 9.1.1.
* Android emulator **Small_Phone**: Android 17 (API 37) Google Play image, x86_64, 16 KB pages,
  720×1280 @ 320 dpi, software rendering (SwiftShader). Started **read-only** (no changes persist).

## 1. How to run the tests

```bat
rem JVM unit tests (no device needed)
gradlew.bat testDebugUnitTest

rem Instrumented tests (emulator or phone connected; see note on accessibility below)
gradlew.bat connectedDebugAndroidTest
```

The 4 tests in `AccessibilityGestureTest` need the BunTap accessibility service enabled on the
test device; otherwise they are *skipped* (not failed). On a test device you can enable it with:

```bat
adb shell settings put secure enabled_accessibility_services com.buntap.app/com.buntap.app.accessibility.BunTapAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

## 2. JVM unit tests — 149 tests, 0 failures

Run on the build machine with virtual time (`kotlinx-coroutines-test`) and fakes for Android
APIs. They verify logic and scheduling; they are **not** proof of device behaviour.

| Class | Tests | Covers |
|---|---|---|
| `TapRateTest` | 9 | TPS⇄interval examples from the spec, no drift, invalid values, limits |
| `ValidationTest` | 9 | number parsing (incl. comma decimals), min/max TPS, press-duration bound, coordinates, screen-geometry rules |
| `ClickerPlannerTest` | 7 | 3×10 = 30 planned taps, global vs per-point, repeat/passes, unlimited, session limit, validation |
| `RateMeterTest` | 4 | observed TPS from completion times, continuity breaks, recent window |
| `TapSchedulerTest` | 18 | exact intervals, counters (scheduled/dispatched/completed/cancelled/rejected/timed out), consecutive failures, accessibility loss, missed deadlines without bursts, **no catch-up burst after one slow gesture**, max-TPS clamp, pause/resume, stop, emergency stop, global & task tap limits, active-time duration limit, batch timeout, delays, own-overlay skip, off-screen skip, spacing across batches |
| `AutomationEngineTest` | 12 | one session at a time, missing accessibility/capture, countdown, stop during countdown, accessibility/capture loss mid-run, prompt cancellation of long waits, exception handling, history record, armed start, active elapsed time |
| `ClickerTaskTest` | 8 | point order, per-point overrides, passes, until-stopped + limit, user stop, point timeout, rotation before/while running |
| `DetectionTrackerTest` | 12 | once-per-appearance, re-arming after confirmed disappearance, unknown frames, cooldown, min visible time, repeat-while-visible chunks/limits, wait-and-tap timeouts and retries, notify-only |
| `CandidateSelectorTest` | 10 | threshold, NMS, ambiguity, allow-multiple, NaN scores, tap anchor/offset, IoU |
| `VisualWatchTaskTest` | 10 | detector→tracker→scheduler integration: one tap per appearance despite many scans, batch never restarted, detected positions, repeat stops when gone, wait-and-tap retries/success, notify-only, ambiguity skip, blank frames, two targets fairly served |
| `RoutineExecutorTest` | 20 | sequence, if/else, if-not, repeat, retry success/exhaustion, time limit, stop, continue-if-target skip/stop, failure policy, disabled actions, prompt cancellation, routine & global tap limits, step mode, typed text never logged, **the specification's example routine** (success and failure) |
| `RoutineSerializationTest` | 5 | every action type round-trips, readable `"type"` names, unknown types rejected, unknown fields ignored, invalid rate rejected |
| `RoutineExchangeTest` | 14 | export→import with new IDs, schema/format/JSON errors, unknown actions, missing targets, bad Base64/images/sizes, loop bounds, duplicate IDs, warnings |
| `RoutineTreeTest` | 11 | editor operations (find/update/remove/move/insert/duplicate/flatten/remap) and validator bounds |

## 3. Instrumented tests on the emulator — 21 tests, 0 failures

After this final instrumented run, only Android Lint cleanups were made (an obsolete SDK check and
an unused string removed, a lint suppression added, backup-exclusion rules added). The unit tests
and the APK build were re-run after them; the instrumented suite was not.

| Class | Tests | What it proves on a real Android runtime |
|---|---|---|
| `VisionPipelineTest` | 6 | OpenCV native library loads (x86_64, 16 KB pages); exact template location; absent target not found; two identical buttons ⇒ ambiguous unless a search region separates them; blank/flat images rejected; full `VisionEngine` path (saved PNG → template cache → 50 % downscaled frame → screen coordinates ±2 px → diagnostic preview); blank frame reported |
| `StorageIntegrationTest` | 5 | Room + image files: target create/rename/duplicate/delete keeps files in sync; routines persist and duplicate with new IDs; clicker config persists; history with events and clearing; export→import round trip with real PNG decoding; corrupted import creates nothing |
| `RateEditorUiTest` | 4 | Compose UI: typing TPS updates ms; typing ms updates TPS; invalid values show an error and are not reported; presets update both fields |
| `AppNavigationTest` | 2 | the real app starts (real DI, database, settings); onboarding can be skipped; Home, Clicker, Targets, Routines, Monitor, Settings and Permissions screens all render; routine editor and "Add action" picker open |
| `AccessibilityGestureTest` | 4 | **real gestures from the real engine arrive as touches at a real view**; rate never exceeds the request; BunTap's reported TPS matches what the view received (±10 %); callback latency probe |

### Measured gesture delivery (emulator, final build)

From gesture event timestamps at the receiving view:

| Requested | Taps received / completed | Rate delivered at the view | BunTap reported | Gaps min / median / max |
|---|---|---|---|---|
| 2 TPS × 10 | 10 / 10 | 2.000 TPS | 2.03 TPS | 462 / 500 / 544 ms |
| 10 TPS × 30 | 30 / 30 | 9.93 TPS | 10.13 TPS | 98 / 100 / 104 ms |
| 20 TPS × 40 | 40 / 40 | 19.45 TPS | 20.00 TPS | 44 / 51 / 72 ms |

Completion callback for a 10 ms tap: median 18 ms, occasional spikes up to ~460 ms on this
software-rendered emulator. Individual gaps below the interval at 20 TPS come from Android's
input-event timing; BunTap never dispatches two taps closer than the interval minus ≤ 2 ms.

**Bug found and fixed by this test:** the first device run showed a catch-up burst (a tap
following the previous one by much less than the interval after one slow callback). The scheduler
now enforces a minimum gap; `TapSchedulerTest.oneSlowGestureDoesNotCauseCatchUpBurst` guards it.

### Physical phone: Samsung Galaxy A26 5G (SM-A266B), Android 16 (API 36), arm64 — 2026-10-14

All **21/21 instrumented tests passed**. The accessibility service was switched on by the user
through BunTap's Permissions screen.

| Requested | Taps received / completed | Rate delivered at the view | BunTap reported | Gaps min / median / max | Missed |
|---|---|---|---|---|---|
| 2 TPS × 10 | 10 / 10 | 1.99 TPS | 1.99 TPS | 497 / 502 / 512 ms | 0 |
| 10 TPS × 30 | 30 / 30 | 9.71 TPS | 9.72 TPS | 88 / 103 / 108 ms | 0 |
| 20 TPS × 40 | 40 / 40 | 19.24 TPS | 19.20 TPS | 49 / 51 / 57 ms | 0 |

Completion callback for a 10 ms tap: 11–14 ms.

## 4. Manual end-to-end checks performed on the emulator

Driven through the real UI with `adb` (taps, text, screenshots). All results observed.

| # | Scenario | Result |
|---|---|---|
| 1 | Clicker: add a point via "Enter coordinates", Start, 3 s countdown, 10 taps at 5 TPS | ✅ Completed, **observed 4.99 TPS**, history entry recorded |
| 2 | Force-stop and relaunch | ✅ tap point and history persisted |
| 3 | Start screen capture from Targets | ✅ Android 17 consent dialog offers only "Share entire screen" (we request the default display); `ScreenCaptureService` runs as foreground service type `mediaProjection` with notification |
| 4 | Floating panel | ✅ appears over other apps, hidden inside BunTap, fits a 360 dp screen, drag handle, Pause → "Paused", Emergency stop → "Stopped / Emergency stop: all pending automation was cancelled", Open BunTap |
| 5 | Panel camera on the launcher → crop screen → crop the Photos icon → save target | ✅ panel hidden in the capture; target saved (90×90 px) |
| 6 | **Visual automation: watch "Photos", go to launcher** | ✅ BunTap detected the icon (score **0.974**, matched `[400,764 90×90]`, 146 ms after start) and tapped (445, 809); **Google Photos opened by itself 5.2 s after Start** (incl. 3 s countdown) |
| 7 | Routine built in the editor: Wait for target "Photos" → Tap target, Run, go to launcher | ⚠️ 2 runs: run 2 opened Photos. Run 1: target found (0.974) and the gesture **completed**, but the launcher did not open Photos — it was re-arranging its "predicted apps" row at that moment. This is why BunTap reports "completed" and never claims the app reacted. |
| 8 | On-screen point picker: drag crosshair onto a button in another app, Save | ✅ point added at the crosshair's position (360, 963); BunTap returned to the front |
| 9 | Reinstalling the app while capturing | ✅ capture session ends and must be granted again (consent is never restored silently) |
| 10 | Unsaved routine → Back | ✅ "Unsaved changes" dialog with Save / Discard / Keep editing |

## 5. Not verified (be aware)

* **Other phones.** Automated tests ran on one emulator (API 37) and one Samsung Galaxy A26
  (Android 16). Older Android versions (8–15), other vendors and the manual checklist below have
  not yet been run on a physical phone.
* Rotation and display-size changes during capture/clicking (code handles them; unit tests cover
  the clicker logic; not exercised on a device).
* Screen off / lock during automation, low-memory kills, battery-optimisation behaviour, sessions
  longer than a couple of minutes.
* Notification-shade Pause/Stop buttons, "Capture in 3 s" from inside the app, import/export
  through the system file picker UI (the underlying logic is covered by `StorageIntegrationTest`),
  Press-key and Type-text actions on a device, the example routine on a device, light theme
  visuals, tablet/landscape layout, TalkBack.

## 6. Manual test plan for your phone

Do these on a real phone after installing the APK. Tick each one off.

1. **Install & onboarding** — install `app-debug.apk`, open BunTap, read the introduction.
2. **Accessibility** — Permissions → Accessibility settings → enable "BunTap automation". On
   Android 13+, if it is greyed out: App info → ⋮ → *Allow restricted settings*. Home banner disappears.
3. **Clicker basics** — open a notes/drawing app. In BunTap add a point with *Pick a point on screen*
   (grant "Display over other apps"), set 10 taps at 2 TPS, Start, switch to the app. Expect 10 marks,
   Monitor shows ~2 TPS observed.
4. **Rates** — repeat at 5, 10, 20 TPS. Note observed TPS and missed deadlines.
5. **Unlimited + stop** — Repeat "Until stopped": confirm dialog; stop via app, notification and floating panel.
6. **Emergency stop** — start an unlimited run at 10 TPS, press the panel's red button: taps stop at once.
7. **Pause/resume** — pause mid-run for 10 s; no taps while paused; resumes without a burst.
8. **Rotation** — pick points in portrait, rotate to landscape, Start: BunTap refuses with an explanation.
9. **Screen capture** — Targets → Start screen capture → "Entire screen". Lock the phone: capture ends,
   BunTap explains why.
10. **Visual target** — capture a button in another app with the panel camera, crop it, *Test detection now*
    (green box on the button). Start watching: BunTap taps it once per appearance.
11. **Routine** — Routines → Create example routine with two targets, Run, watch the Monitor's action statuses.
12. **Step debug** — run a routine with *Step debug*; press Step to advance.
13. **Import/export** — export a routine, delete it, import the file: it is marked "needs review".
14. **Background** — run a 5-minute clicker with the screen on and BunTap in the background; check it is
    not stopped. If it is, set battery to *Unrestricted* and retry.
15. **Permission loss** — while running, disable the accessibility service: the session stops with
    "Accessibility service unavailable".
