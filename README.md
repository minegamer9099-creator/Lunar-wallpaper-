# Luma Drift

A native Android live wallpaper built with Kotlin and Android Canvas. It works offline, uses no third-party runtime libraries, and keeps Android responsible for the launcher, lock screen, and wallpaper lifecycle.

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
- The built-in wallpaper uses a reusable pair of `Paint` objects and basic Canvas shapes; it does not load bitmaps, run network calls, use a background thread, or use a foreground service.
- Local videos can be selected through Android's system document picker. The app stores the selected content URI and its persistent read grant; it does not request broad gallery/storage permissions or copy the video.
- Selected videos are muted and looped with Android `MediaPlayer` directly on the wallpaper surface. The prepared player is paused and retained across screen-off and wallpaper-surface handoffs, then attached to the new surface on resume to avoid decoding the video again during unlock. If the wallpaper stays hidden while the device is unlocked, the player is released after a 5-second grace period; it is always released when the engine is destroyed.
- The app screen replaces its built-in drawing preview with a muted, looping preview of the selected video.
- Optional lock-screen splitting lets the user choose a duration in 0.1-second increments. The service derives the timer from the saved tenths value and uses one cancellable, generation-checked one-shot callback rather than polling. Screen-off/on pauses and continues the current lock segment; only a detected keyguard transition starts a new segment or resumes after unlock. The service checks screen interactivity and keyguard state separately, reuses the prepared player, and preserves the built-in animation phase across pauses. The chosen duration and animation phase are stored locally, and old whole-second settings are migrated. Android may not expose the lock-screen wallpaper surface on every version or manufacturer; the wallpaper picker and OEM keyguard behavior remain device-dependent.
- Any video resolution is accepted. Playback relies on the device's decoder; high-resolution or unsupported videos may stutter or fail on entry-level phones. On playback failure, the service logs the error and falls back to the built-in wallpaper.
- The built-in animation uses a single main-thread callback at 24 frames per second only while the surface is available, the engine is visible, the screen is interactive, and animation is enabled. It is removed when the wallpaper is hidden, the screen is off, the surface is destroyed, or the engine is destroyed.
- The built-in animation preview uses the same simple drawing approach; the selected video preview is muted. Both stop updating when the activity is not resumed.
- The built-in animation toggle, selected video metadata, and lock-screen split settings are stored locally and shared with the wallpaper service.
- The only requested permission is `SET_WALLPAPER`. The service is protected by Android’s required `BIND_WALLPAPER` service permission.

The 20–30 MB process-memory objective is not a guaranteed cap. Measure Java/Kotlin heap, native memory, graphics memory, and process PSS separately on target devices with Android Studio Profiler or `adb shell dumpsys meminfo`. Avoid treating device-wide memory as app memory.

## Test record

Environment checks performed for this update:

- Source-level checks cover the tenths-to-milliseconds mapping, lock transition state, one-shot timer generation/deadline, pause-safe animation clock, and surface/player handoff.
- Manifest, layout, and strings XML parse successfully.
- The Gradle wrapper downloaded Gradle 8.7. `assembleDebug` did not complete: the first attempt had no JDK; after installing Java, Gradle timed out during initialization. No `ANDROID_HOME`/`ANDROID_SDK_ROOT` or common Android SDK location was available here.

**NOT TESTED:** Android source compilation, APK installation, the requested 0.5/1.0/1.5-second timings, repeated lock/unlock cycles, screen-off/on while still locked, smoothness on a physical device, video selection and persistent URI access, video codec/decoder compatibility, in-app video preview, wallpaper picker/application, actual memory/PSS, long-duration memory growth, frame timing, CPU/battery use, memory pressure, launcher stability, rotation, reboot, and manufacturer-specific launchers. These require an Android SDK and/or physical devices. Do not treat source inspection or an emulator run as a substitute for low-RAM physical-device testing.
