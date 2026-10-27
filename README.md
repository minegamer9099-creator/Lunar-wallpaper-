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
- Selected videos are muted and played with Android `MediaPlayer` directly on the wallpaper surface. The prepared player is paused and retained across visibility and surface handoffs, then attached to the current surface on resume. If the wallpaper stays hidden, the player is released after a 5-second grace period; its position is saved and restored when playback returns.
- The app screen replaces its built-in drawing preview with a muted, looping preview of the selected video.
- Video Loop Range stores Loop Start and Loop End in 0.1-second increments for the selected video and validates both against its duration. Playback runs from 0 to Loop End, then uses a cancellable, generation-checked one-shot callback to seek to Loop Start; no continuous position polling is used. Screen-off persists a pending restart, and the next visible screen-on cycle starts from 0. Ordinary unlock and surface/visibility handoffs resume the saved position instead of restarting. The range and playback position are stored locally.
- Any video resolution is accepted. Playback relies on the device's decoder; high-resolution or unsupported videos may stutter or fail on entry-level phones. On playback failure, the service logs the error and falls back to the built-in wallpaper.
- The built-in animation uses a single main-thread callback at 24 frames per second only while the surface is available, the engine is visible, the screen is interactive, and animation is enabled. It is removed when the wallpaper is hidden, the screen is off, the surface is destroyed, or the engine is destroyed.
- The built-in animation preview uses the same simple drawing approach; the selected video preview is muted. Both stop updating when the activity is not resumed.
- The built-in animation toggle, selected video metadata, and video loop range are stored locally and shared with the wallpaper service.
- The only requested permission is `SET_WALLPAPER`. The service is protected by Android’s required `BIND_WALLPAPER` service permission.

The 20–30 MB process-memory objective is not a guaranteed cap. Measure Java/Kotlin heap, native memory, graphics memory, and process PSS separately on target devices with Android Studio Profiler or `adb shell dumpsys meminfo`. Avoid treating device-wide memory as app memory.

## Test record

Environment checks performed for this update:

- Source-level checks cover the tenths-to-milliseconds mapping, loop boundary callback cancellation/generation, loop validation, screen-off restart state, visibility/surface resume, and pause-safe animation clock.
- Resource XML parses, the Kotlin-referenced layout IDs and strings exist, and the 10-second / 6–9-second range maps to 6000–9000 ms.
- `assembleDebug --offline --no-daemon` was attempted. Gradle 8.7 launched its daemon but the command timed out before any Android build task completed. `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset, and no `android.jar` was found in the checked SDK locations.

**NOT TESTED:** Android source compilation, APK installation, actual playback of the 10-second example (0–6, then 6–9 repeat), decimal loop ranges on a device, unlock continuity, screen-off/on restart at 0, repeated surface recreation, video codec behavior, memory/PSS, CPU/battery use, and manufacturer-specific wallpaper behavior. These require an Android SDK and/or physical devices.
