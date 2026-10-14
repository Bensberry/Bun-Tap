# BunTap architecture

This document explains how BunTap is put together and *why*. Read it alongside the code — every
package mentioned here is under `app/src/main/java/com/buntap/app/`.

## 1. The big picture

```
            ┌───────────── UI (Jetpack Compose, ui/*) ─────────────┐
            │ Home · Clicker · Targets · Routines · Monitor · …     │
            └──────────┬───────────────────────────┬───────────────┘
                       │ ViewModels                │ observe state
                       ▼                           │
              AutomationLauncher  ──validates──►  repositories (data/*)
                       │ PreparedTask                 ▲
                       ▼                              │ history
   ┌──────────────── AutomationEngine (automation/*) ─┴───────────────┐
   │  one session at a time · countdown · pause/step/stop/e-stop      │
   │  limits (TPS, taps, duration) · requirement watchers · stats     │
   │                                                                  │
   │  AutomationTask ── ClickerTask | VisualWatchTask | RoutineTask   │
   │        │ ctx.tapBatch()                     │ detector.detect()   │
   │        ▼                                    ▼                     │
   │   TapScheduler (only place taps happen)   TargetDetector          │
   └────────┬────────────────────────────────────┬────────────────────┘
            ▼                                    ▼
   GestureDispatcher                       VisionEngine (vision/*)
   = AccessibilityGestureDispatcher        OpenCV template matching
   → BunTapAccessibilityService            ← CaptureManager / ScreenCaptureService
     dispatchGesture()                       (MediaProjection → ImageReader)
```

**One engine, one scheduler.** The auto clicker, visual automation and routines are all
`AutomationTask`s. They never call Android gesture APIs themselves; they call
`ExecutionContext.tapBatch(...)`, which is implemented by the single `TapScheduler` inside the
single `AutomationEngine`. `AutomationEngine.start()` refuses a second task while one is
running, so two features can never run competing tap loops.

## 2. Packages

| Package | Responsibility |
|---|---|
| `domain/` | Pure Kotlin models and rules: `TapRate` (TPS ⇄ interval), `ClickerConfig`, `VisualTarget`, `ScreenGeometry`, validation, `ClickerPlanner`. No Android code — fully unit tested. |
| `automation/` | `AutomationEngine`, `TapScheduler`, timing (`PauseGate`, `ActiveTimer`, `RateMeter`), stats, `ClickerTask`, `VisualWatchTask`, engine contracts. |
| `routines/` | Routine action model (sealed, JSON-serialisable), `RoutineTask` (interpreter), `RoutineValidator`, `RoutineTree` (editor operations), import/export (`RoutineExchange`), `ExampleRoutine`. |
| `vision/` | `CandidateSelector` + `NonMaxSuppression` + `DetectionTracker` (pure Kotlin) and the OpenCV parts: `OpenCvMatcher`, `TemplateCache`, `VisionEngine`. |
| `capture/` | MediaProjection lifecycle: `CaptureManager`, `ScreenCaptureService` (foreground service type `mediaProjection`), `LatestImageHolder`, frame conversion, `DisplayMonitor`. |
| `accessibility/` | `BunTapAccessibilityService`, `AccessibilityGestureDispatcher`, key/text actions. |
| `floating/` | Floating control panel and on-screen point picker (overlay windows that host Compose). |
| `data/` | Room database, DAOs, repositories, `TargetImageStore` (app-private PNGs), `RoutineLibrary`. |
| `settings/` | `AppSettings` + DataStore persistence. |
| `notifications/` | Channels, the ongoing automation notification with Pause/Stop, alerts. |
| `permissions/` | Permission/diagnostic snapshot and system-settings intents. |
| `ui/` | Theme, icons, shared components (incl. `RateEditor`), navigation and one package per screen. |
| `AppContainer.kt` | Manual dependency injection: builds every long-lived object once. |
| `AutomationLauncher.kt` | Validates configurations and creates tasks; every start path goes through it. |

## 3. Timing model (the core of the clicker)

* A `TapRate` is stored as an **interval in microseconds**. `interval ms = 1000 / TPS`.
  Converting back and forth never drifts (`3 TPS ⇄ 333.333 ms` stays exact).
* `TapScheduler` uses **fixed-rate deadlines** on a **monotonic clock**
  (`SystemClock.elapsedRealtimeNanos`): tap *n* is due at `start + n × interval`. It waits with
  coroutine `delay` (no busy-waiting).
* It **waits for Android's gesture callback** (`onCompleted`/`onCancelled`) before the next tap,
  because dispatching a new gesture cancels one in progress.
* If gestures are slower than the interval, **missed deadlines are skipped and counted**, never
  sent in a catch-up burst — the requested rate is a ceiling.
* In addition, two taps are never dispatched closer together than *interval − min(5 %, 2 ms)*.
  The small allowance absorbs timer jitter; without this rule, one slow gesture callback could make
  the following tap arrive too early (this was found by the on-device gesture test and fixed).
* Counters distinguish *scheduled → dispatched → completed* (plus cancelled, rejected, timed out,
  skipped). "Completed" means Android finished injecting the gesture, **not** that the target app
  reacted.
* **Observed TPS** comes from real completion timestamps (`RateMeter`). Deliberate gaps (pauses,
  waits, delays, scans) break continuity so they don't distort the average.
* **Pause** works at safe points: before every tap and inside every wait. Waits and timeouts are
  measured in *active* time (`ActiveTimer`), so a paused routine doesn't time out.
* **Stop** cancels the session coroutine; **emergency stop** additionally sets a kill switch checked
  immediately before each gesture. A gesture already handed to Android (≈ the press duration,
  10 ms by default) cannot be recalled.

## 4. Safety rails (all enforced in the engine, not the UI)

* Max TPS (rates above are clamped and logged), max dispatched taps per session, max active
  session duration — from Settings, applied to every task.
* Task limits (clicker session tap limit, routine max taps/duration).
* Before every tap: kill switch, limits, **screen geometry check** (coordinates picked on another
  screen size/orientation end the session instead of tapping wrong places), on-screen bounds, and
  "is this point under BunTap's own floating panel?" (skipped and logged).
* Watchers stop the session if the accessibility service or screen capture disappears.
* Five consecutive failed/skipped gestures fail a batch.
* Unlimited clicker runs and imported routines require explicit confirmation in the UI.

## 5. Visual recognition pipeline

1. `ScreenCaptureService` mirrors the display into an `ImageReader` (RGBA_8888, 3 buffers).
   `LatestImageHolder` keeps **only the newest frame** and closes older ones immediately.
2. On each scan, `VisionEngine` (single background thread) asks for a grayscale frame at the
   processing scale (default 50 %, raised automatically for small targets).
3. Blank frames (protected content, screen off) are rejected.
4. Each target's PNG is converted once to a grayscale, equally scaled template (`TemplateCache`,
   keyed by target version and scale). Low-detail templates are rejected.
5. `Imgproc.matchTemplate(TM_CCOEFF_NORMED)` runs on the whole frame or the search region. Up to 5
   peaks are extracted, suppressing each peak's neighbourhood.
6. `CandidateSelector`: threshold → non-maximum suppression → if two distinct matches score within
   0.02 of each other the result is **Ambiguous** (skipped and logged) unless "allow several
   matches" is on.
7. Coordinates are scaled back to screen pixels; the tap point comes from the target's anchor and
   offset.
8. `DetectionTracker` (per target) turns per-frame results into decisions: appeared/disappeared
   (2 consecutive misses), minimum visible time, cooldown, re-arm rules, per-appearance and
   per-session limits, wait-and-tap timeouts with retries. Unknown frames never count as
   "disappeared", which prevents duplicate triggers.
9. `VisualWatchTask` runs the tap batch for a decision as one operation, then rescans (it never
   acts on a frame captured before its own taps).

## 6. Routines

* Actions are a sealed hierarchy (`RoutineModels.kt`) serialised with kotlinx.serialization using a
  `"type"` discriminator. Groups (`repeat`, `retry`, `time_limit`, `if_target`) contain nested lists.
* `RoutineTask` interprets the tree. Each action returns `Success`, `Failure`, `Stop` or
  `SkipRest`. `onFailure = CONTINUE|STOP_ROUTINE` decides what a failure does; `Retry` groups catch
  failures; `Stop` cannot be caught.
* Bounds (validated before saving/running/importing): nesting ≤ 6, ≤ 500 actions, repeat ≤ 100 000,
  retry ≤ 20 attempts, waits/timeouts ≤ 1 h, taps per action ≤ 100 000.
* Step-by-step mode pauses before every action.
* Export format: `{"format":"buntap.routine","schemaVersion":1, "routine":{…}, "targets":[{…,"pngBase64":"…"}]}`.
  Import checks format, version, action types, all numbers, target references, Base64, image
  decoding and declared sizes, then assigns **new IDs** to every action and target. Imported
  routines are marked "needs review".

## 7. Overlays

* The floating panel (`FloatingPanelService`) and point picker (`PointPickerService`) are
  `TYPE_APPLICATION_OVERLAY` windows that host Compose via `OverlayLifecycleOwner`. They are
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`, so only their own small area receives touches.
* The panel hides itself while BunTap's own screen is visible (the in-app screens have full
  controls) and while the point picker is open. Its screen bounds are published to
  `OverlayRegistry`; taps that would land under it are skipped and logged.
* The panel's camera button hides the panel, waits for a fresh frame, saves a temporary screenshot
  and opens the crop screen.

## 8. Threads and lifecycles

* Automation runs in `AppContainer.appScope` (`Dispatchers.Default`), independent of screens.
  The process stays alive while the accessibility service is bound and/or the capture foreground
  service runs; Android can still stop it (battery savers, low memory).
* OpenCV work: one dedicated single-thread dispatcher. Gesture callbacks: main thread. Frame
  delivery: a `HandlerThread`. Room/DataStore: their own IO threads.
* UI state: ViewModels expose `StateFlow`s; edits are applied in memory immediately and saved with a
  short debounce on the app scope (so leaving a screen never loses the last change).

## 9. Persistence

* Room (`buntap.db`, version 1): `targets`, `routines` (body JSON), `clicker_profiles`,
  `sessions`, `session_events` (cascade delete; trimmed to 500 sessions).
* Target images: `files/targets/<id>.png`. Temporary full screenshots: `cache/screens/`, deleted
  when the crop screen closes and at app start.
* Settings: DataStore preferences. `android:allowBackup="false"`.
* Future schema changes must add a Room `Migration`.
