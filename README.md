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

### Build in Termux on ARM64

Android Gradle Plugin may download a Linux x86_64 `aapt2`, which cannot run natively in Termux on ARM64. Install or provide a Termux-compatible native `aapt2`, then run:

```sh
bash termux-build.sh
```

The script checks that `aapt2` runs, then passes `android.aapt2FromMavenOverride` only to that Gradle invocation. If `aapt2` is not on `PATH`, provide its absolute path:

```sh
AAPT2_PATH=/absolute/path/to/aapt2 bash termux-build.sh
```

You can pass other Gradle tasks as arguments, for example `bash termux-build.sh assembleDebug`. This workaround only addresses the AAPT2 executable architecture; a compatible JDK, Android SDK/platforms, and Android Build Tools are still required. Standard Android Studio and GitHub Actions builds continue using their normal AAPT2 configuration.

## Build a release APK

```sh
./gradlew assembleRelease
```

The unsigned release APK is written to `app/build/outputs/apk/release/app-release-unsigned.apk`. Sign it with a key managed in Android Studio or your organization’s secure release process before distribution. Do not commit signing keys or passwords.

## Architecture and efficiency

- `DriftWallpaperService` is a standard `WallpaperService` with a custom `Engine`.
- The built-in wallpaper uses a reusable pair of `Paint` objects and basic Canvas shapes; it does not load bitmaps, run network calls, use a background thread, or use a foreground service.
- On Android 12+ the built-in scene responds to Android's `onZoomChanged` signal with a subtle, hardware-accelerated blur on a reused `RenderNode`; the blur radius is cached and bounded. Launchers that do not provide zoom updates simply keep the normal wallpaper. Selected videos remain on the direct `MediaPlayer` surface path and are not copied or blurred.
- Local videos can be selected through Android's system document picker. The app stores the selected content URI and its persistent read grant; it does not request broad gallery/storage permissions or copy the video.
- Selected videos are muted and played with Android `MediaPlayer` directly on the wallpaper surface. Muted audio tracks are deselected after preparation to avoid unnecessary audio decoding. The prepared player is paused on screen-off while a still-valid wallpaper surface remains attached; it is detached only when Android destroys that surface. Unlock resumes the prepared decoder without forcing a seek, avoiding unnecessary decoder teardown/re-attach. If the wallpaper stays hidden, the player is released after a 5-second grace period; its position is saved and restored when playback returns.
- The app screen replaces its built-in drawing preview with a muted, looping preview of the selected video.
- Video Loop Range stores Loop Start and Loop End in 0.1-second increments for the selected video and validates both against its duration. Playback runs from 0 to Loop End, then uses a cancellable, generation-checked one-shot callback to seek to Loop Start; no continuous position polling is used. Locking pauses playback and saves the current position; unlock and surface/visibility handoffs resume the already-prepared player directly without a screen-on seek. The range and playback position are stored locally.
- Any video resolution is accepted. Playback relies on the device's decoder; high-resolution or unsupported videos may stutter or fail on entry-level phones. On playback failure, the service logs the error and falls back to the built-in wallpaper.
- The built-in animation uses a single main-thread callback at 24 frames per second only while the surface is available, the engine is visible, the screen is interactive, and animation is enabled. It is removed when the wallpaper is hidden, the screen is off, the surface is destroyed, or the engine is destroyed.
- The built-in animation preview uses the same simple drawing approach; the selected video preview is muted. Both stop updating when the activity is not resumed.
- The built-in animation toggle, selected video metadata, and video loop range are stored locally and shared with the wallpaper service.
- The only requested permission is `SET_WALLPAPER`. The service is protected by Android’s required `BIND_WALLPAPER` service permission.

The 20–30 MB process-memory objective is not a guaranteed cap. Measure Java/Kotlin heap, native memory, graphics memory, and process PSS separately on target devices with Android Studio Profiler or `adb shell dumpsys meminfo`. Avoid treating device-wide memory as app memory.

## Test record

Environment checks performed for the transition update and Termux build workaround:

- Source-level checks cover loop state, the zoom-gated blur path, cached blur effect, and direct video-surface path.
- All 11 Android XML resources parse successfully.
- `bash -n termux-build.sh` passed, and a wrapper smoke test confirmed that the native AAPT2 path is passed only to that Gradle invocation.
- Source checks confirm screen-off does not force a seek or detach a still-valid surface; an already-prepared player resumes without a position seek unless a loop seek is already pending, and stale surface state is validated before reuse.
- The supplied wallpaper clip was inspected: H.264 High, 720×1280, 30 fps, about 0.38 Mbps, plus muted HE-AAC audio. This is source-file metadata, not a measurement of device decode performance.
- `assembleDebug --offline --no-daemon` could not start because this environment has no Java runtime. `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset, and no Android SDK is available here.

**NOT TESTED:** Android source compilation, APK installation, the app/home/recents transitions on a device, blur support in specific launchers, video loop continuity during transitions, surface recreation, video codec behavior, memory/PSS, CPU/battery use, and manufacturer-specific wallpaper behavior. These require an Android SDK and/or physical devices.
