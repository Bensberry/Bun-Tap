# Troubleshooting

## Building

**"Gradle requires JVM 17 or later" / "JAVA_HOME is set to an invalid directory"**
Gradle 9 needs Java 17+. Your `PATH` may point to an old Java 8. Either:

* run `build-debug-apk.bat` (it uses Android Studio's bundled Java automatically), or
* set `JAVA_HOME` for the current PowerShell window, then build:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

(On this PC Android Studio is installed in `C:\Program Files\Android\Android Studio1`.)

**"SDK location not found"** — open the project once in Android Studio (it creates
`local.properties`), or create `local.properties` with
`sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`.

**Android Studio cannot sync ("requires a newer version of Android Studio")** — AGP 9.1 needs
Android Studio Panda 3 (2025.3.3) or newer.

**"Dependency … requires … compileSdk 37"** — you upgraded a library past the versions listed in
`docs/DEPENDENCIES.md`. Either install SDK Platform 37 and set `compileSdk = 37`, or keep the
listed versions.

**The APK is large (~60 MB)** — OpenCV's native code. Set `buntap.abis=arm64-v8a` in
`gradle.properties` for a phone-only APK (measured: about 24 MB instead of about 58 MB).

## Installing and permissions

**"App not installed"** — uninstall any older BunTap built on another computer (different debug
signature), then install again.

**Accessibility switch is greyed out / "Restricted setting"** — Android 13+ restricts apps
installed from APK files. Open *Settings → Apps → BunTap → ⋮ (top right) → Allow restricted
settings*, then enable the service again. (BunTap's Permissions screen has an "App info" button.)

**The accessibility service is on, but BunTap says it is not running** — switch it off and on
again. Some phones stop accessibility services after an update or when battery saving is
aggressive; set BunTap's battery usage to *Unrestricted*.

**Floating panel doesn't appear** — allow *Display over other apps* (Permissions screen).
The panel hides itself while BunTap is open — switch to another app to see it.

## Tapping

**Taps happen but the app doesn't react**
* Some apps ignore automated taps or very short ones. Increase *Settings → Tap press duration*
  to 20–50 ms.
* Make sure nothing else covers the point (a dialog, the keyboard, BunTap's own panel — taps under
  the panel are skipped and logged).
* "Completed" in the Monitor means Android performed the gesture. It cannot tell whether the app
  reacted (see the test report: a launcher once ignored a tap while animating).

**Observed TPS is lower than requested** — Android delivers each gesture and reports back before
the next can start. If that takes longer than the interval, BunTap skips the slot ("missed
deadlines") instead of bursting. Lower the rate, close heavy apps, or accept the measured rate.
Above ~20 TPS, few devices keep up.

**"Screen size or orientation changed"** — points are tied to the screen they were picked on.
Rotate back, use *Scale* (same orientation, new resolution) or pick the points again.

**The run stopped by itself** — check Monitor → History for the reason: safety limits (Settings),
accessibility service turned off, capture ended, or a limit you set.

## Screen capture and visual targets

**Capture stops when the screen locks / after a while** — Android ends screen capture on lock and
when BunTap's process is killed. Start it again; consent cannot be restored silently.

**The captured screen is black** — the app protects its content (banking, DRM video, password
screens). BunTap cannot and will not bypass this.

**Target not found**
* Use *Test detection now* in the target editor to see the best score and what BunTap sees.
* Re-capture the target on the same device, resolution and theme it will be used on.
* Crop tightly around a distinctive part (text + icon works better than a plain shape).
* Lower the threshold a little (e.g. 0.85 → 0.80) — too low causes false matches.
* For small icons, raise *Settings → Processing resolution*.
* Template matching does not handle scaling, rotation, colour/theme changes or animations.

**"Ambiguous" detections** — the image appears more than once. Add a search region or enable
*Allow several matches* (BunTap then taps the best one).

**Battery use** — scanning costs CPU. Increase the scan interval, use a search region, or lower
the processing resolution.

## Routines

**"… can't run: …"** — the routine has a validation problem. Open it in the editor; problems are
listed at the bottom and on the affected actions.

**Imported routine asks for review** — imported files are untrusted until you have looked at
every action. Running it once confirms the review.

**A routine waits forever** — every wait has a timeout (default 10 s, max 1 hour) and the whole
run has the global maximum duration. Use the Monitor to see which action is running, and Stop.

## Testing on the emulator

* The APK includes x86_64 code, so it runs on the standard Android Studio emulator.
* Instrumented tests: `gradlew.bat connectedDebugAndroidTest`. The gesture tests need the
  accessibility service enabled on the emulator (see `docs/TEST_PLAN.md`).
