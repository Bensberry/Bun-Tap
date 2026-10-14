# Dependencies and versions

All versions live in `gradle/libs.versions.toml`. Each was checked against this machine's Android
Studio and installed SDK (October 2026), not guessed.

| Component | Version | Why this version |
|---|---|---|
| Android Gradle Plugin | 9.1.1 | Android Studio Panda 4 (2025.3.4) supports AGP up to 9.2. AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the `org.jetbrains.kotlin.android` plugin is **not** applied. |
| Gradle (wrapper) | 9.3.1 | Minimum required by AGP 9.1. The wrapper pins the distribution SHA-256. |
| JDK to run Gradle | 17+ (Android Studio's bundled JBR 21 works) | Gradle 9 requirement. Java 8 on `PATH` is not enough — see the README. |
| Kotlin Gradle plugin | 2.3.21 | Put on the build classpath in the root `build.gradle.kts` (the AGP 9 documented way to use a newer Kotlin than AGP's default 2.2.10). |
| Compose compiler plugin | 2.3.21 | Must match Kotlin. |
| KSP | 2.3.12 | Room's annotation processor. ≥ 2.3.10 fixes R-class resolution with AGP 9 built-in Kotlin. |
| compileSdk / targetSdk | 36 | Installed platforms are 35, 36, 36.1. |
| minSdk | 26 (Android 8.0) | Required by the specification; also the minimum for `TYPE_APPLICATION_OVERLAY`. |
| Compose BOM | 2026.06.01 (UI 1.11.4, Material3 1.4.0) | 2026.08+ (UI 1.12) requires compileSdk 37. |
| androidx.core | 1.18.0 | 1.19 requires compileSdk 37. |
| activity-compose | 1.13.0 | |
| lifecycle | 2.10.0 | 2.11 Compose artifacts require compileSdk 37. |
| navigation-compose | 2.9.7 | 2.10 requires compileSdk 37. Type-safe routes with kotlinx.serialization. |
| Room | 2.8.5 | Structured storage. |
| DataStore preferences | 1.2.1 | Settings. |
| kotlinx.coroutines | 1.10.2 | |
| kotlinx.serialization-json | 1.9.0 | Routine/target/clicker JSON and the export format. |
| OpenCV (`org.opencv:opencv`) | 4.13.0 | Official Android AAR on Maven Central. Java APIs used were verified with `javap`. |
| material-icons-core | from BOM (1.7.8) | Extra icons are small vector paths in `ui/icons/BunIcons.kt` instead of the very large `material-icons-extended`. |

Test-only: JUnit 4.13.2, kotlinx-coroutines-test, AndroidX Test (core/runner/rules 1.7.0,
ext-junit 1.3.0), Compose UI test.

## Upgrading to compileSdk 37 later

Install "Android SDK Platform 37" in Android Studio's SDK Manager, set `compileSdk = 37` in
`app/build.gradle.kts`, then you may raise core to 1.19, lifecycle to 2.11, navigation to 2.10 and
the Compose BOM to 2026.08+. AGP 9.1.1 supports API 37.

## APK size

OpenCV ships a native library per CPU type (arm64-v8a ≈ 24 MB, armeabi-v7a ≈ 16 MB,
x86_64 ≈ 59 MB uncompressed). `gradle.properties` → `buntap.abis` selects which are packaged
(default: all three, so the APK runs on phones and the emulator). Native libraries are stored
compressed (`useLegacyPackaging = true`). Measured debug APK sizes: **about 58 MB** with all three
ABIs, **about 24 MB** with `arm64-v8a` only. For a phone-only build:

```properties
buntap.abis=arm64-v8a
```
