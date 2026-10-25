# Luma Drift

A native Android live wallpaper built with Kotlin and Android Canvas. Version 1 is offline, uses no third-party runtime libraries, and keeps Android responsible for the launcher, lock screen, and wallpaper lifecycle.

## Requirements

- Android Studio with Android SDK Platform 35 and Build Tools installed
- JDK 17 or newer
- Android 6.0 (API 23) or newer

The minimum SDK is API 23. This is a declared project target, not a claim that every Android version or manufacturer launcher has been tested.

## Build a debug APK

Open this `native-android` directory in Android Studio and allow Gradle sync to finish. Then run:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To install on a connected device:

```sh
./gradlew installDebug
```

## Build a release APK

```sh
./gradlew assembleRelease
```

The unsigned release APK is written to `app/build/outputs/apk/release/app-release-unsigned.apk`. Sign it with a key managed in Android Studio or your organization’s secure release process before distribution. Do not commit signing keys or passwords.

## Architecture and efficiency

- `DriftWallpaperService` is a standard `WallpaperService` with a custom `Engine`.
- The wallpaper uses a reusable pair of `Paint` objects and basic Canvas shapes; it does not load bitmaps, run network calls, use a background thread, or use a foreground service.
- A single main-thread callback schedules at 24 frames per second only while the surface is available, the engine is visible, and animation is enabled. It is removed when the wallpaper is hidden, the surface is destroyed, or the engine is destroyed.
- The app preview uses the same simple drawing approach and pauses when the activity is not resumed.
- The animation toggle is stored locally and shared with the wallpaper service.
- The only requested permission is `SET_WALLPAPER`. The service is protected by Android’s required `BIND_WALLPAPER` service permission.

The 20–30 MB process-memory objective is not a guaranteed cap. Measure Java/Kotlin heap, native memory, graphics memory, and process PSS separately on target devices with Android Studio Profiler or `adb shell dumpsys meminfo`. Avoid treating device-wide memory as app memory.

## Test record

Environment checks performed for this delivery:

- Source and resource files reviewed for the required wallpaper declaration, offline operation, and visibility-based frame scheduling.
- The Gradle wrapper was generated and downloaded Gradle 8.7 successfully.
- `assembleDebug` was attempted but stopped before compilation because no Android SDK is installed in the build environment.

**NOT TESTED:** Android source compilation, APK installation, wallpaper picker/application, device lifecycle behavior, actual memory/PSS, long-duration memory growth, frame timing, CPU/battery use, memory pressure, launcher stability, lock/unlock, rotation, reboot, and manufacturer-specific launchers. These require an Android SDK and/or physical devices. Do not treat source inspection or an emulator run as a substitute for low-RAM physical-device testing.
