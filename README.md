# KineWall — Video Wallpaper for Android

> [!WARNING]
> KineWall is still in an early stage of development and may contain bugs or device-specific behavior. It has been tested primarily on a Xiaomi Redmi Note 13.

KineWall is a native Android application for using local videos as live wallpapers. It is built with Kotlin, Jetpack Compose, `WallpaperService`, `MediaPlayer`, and an OpenGL ES rendering pipeline.

The project is focused on keeping video wallpaper playback native, lightweight, and integrated with Android instead of relying on a cross-platform runtime.

[Español](README.es.md)

## Features

- Native Android application written in Kotlin.
- Jetpack Compose and Material 3 interface.
- Responsive layouts for phones and tablets.
- Android 11+ support (`minSdk 30`).
- Local video selection through Android's Storage Access Framework.
- Persisted access to the selected video URI.
- Infinite video loop.
- Wallpaper audio is muted and configured to prevent audio capture where supported by Android.
- Playback pauses while the wallpaper is not visible.
- Two display modes:
  - **Stretch** — fills the wallpaper surface and may alter the video's aspect ratio.
  - **Fill & crop** — preserves aspect ratio and crops overflow to fill the surface.
- Drag-to-position support in the Android wallpaper preview when using **Fill & crop**.
- Saved crop position for the selected video.
- Direct access to Android's native live wallpaper preview/apply screen.
- Automatic playback and rendering recovery when a stalled wallpaper is detected.
- Optional diagnostic logging, disabled by default.
- Daily diagnostic log files with view, download, share, and delete actions.
- In-app GitHub release checks.
- In-app APK download and installation flow for newer releases.
- No broad media or external-storage permission required for selecting videos.

## Architecture

KineWall uses Android's live wallpaper system and a small native media/rendering pipeline.

```text
ComposeMainActivity
 ├─ Video picker (OpenDocument)
 ├─ Video/display preferences
 ├─ Wallpaper configuration
 ├─ Settings and diagnostics navigation
 └─ GitHub release update checks

VideoWallpaperService
 └─ WallpaperService.Engine
     ├─ MediaPlayer
     │   └─ Decodes the selected video into a SurfaceTexture input surface
     ├─ VideoFrameRenderer
     │   ├─ EGL / OpenGL ES 2.0
     │   ├─ Stretch rendering
     │   ├─ Fill & crop rendering
     │   └─ Crop positioning
     └─ Android wallpaper Surface
```

The video path is:

```text
Local video
   ↓
MediaPlayer / Android media stack
   ↓
SurfaceTexture
   ↓
VideoFrameRenderer (OpenGL ES)
   ↓
Wallpaper Surface
   ↓
Android compositor / display
```

The renderer exists so KineWall can control scaling and crop positioning instead of relying only on `MediaPlayer` surface scaling.

## Requirements

- Android Studio compatible with the Android Gradle Plugin used by the project.
- JDK 17.
- Android SDK 37 installed for compilation.
- Android 11 or newer device (`API 30+`).

Current Android configuration:

```text
compileSdk = 37
minSdk     = 30
targetSdk  = 36
Java       = 17
```

## Build

Clone the repository:

```bash
git clone https://github.com/eaangrino/kinewall-video-wallpaper-android.git
cd kinewall-video-wallpaper-android
```

Build the debug APK:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
gradlew.bat assembleDebug
```

Build the release APK:

```bash
./gradlew assembleRelease
```

Generated APKs are available under:

```text
app/build/outputs/apk/
```

The project can also be opened directly in Android Studio and run on an Android 11+ device.

## Usage

1. Open KineWall.
2. Tap **Select video**.
3. Choose a local video using Android's document picker.
4. Choose a display mode:
   - **Fill & crop**
   - **Stretch**
5. Tap **Apply wallpaper**.
6. Android opens the native live wallpaper preview.
7. When using **Fill & crop**, drag the preview if you want to reposition the visible area of the video.
8. Confirm the wallpaper using Android's system UI.

The selected video, display mode, and crop position are persisted by the app.

The final wallpaper destination options are provided by Android. Depending on the device and Android build, the system may offer the home screen, lock screen, both, or a smaller set of choices.

## Display modes

### Stretch

The complete video frame is mapped to the wallpaper surface.

If the aspect ratio of the video differs from the display, the image may appear stretched or compressed.

### Fill & crop

The original aspect ratio is preserved while the entire wallpaper surface is filled. Any overflowing part of the video is cropped.

During the Android wallpaper preview, the video can be dragged along the axis where overflow exists. KineWall stores that crop position and applies it to the wallpaper renderer.

No letterboxing or black bars are intentionally added in this mode.

## Diagnostics

Diagnostic logging is optional and **disabled by default**.

It can be enabled from the Diagnostics screen in Settings. When enabled, KineWall records information useful for investigating playback, rendering, surface, orientation, and recovery problems.

Logs are stored internally as one file per day:

```text
kinewall-diagnostics-YYYY-MM-DD.log
```

The Diagnostics screen allows you to:

- View available logs.
- View today's log while it is being updated.
- Download a log.
- Share a log.
- Delete a log.

Logging can be disabled again at any time from the same screen.

## Updates

KineWall can check the repository's latest GitHub Release when the app starts.

When a newer production APK is available, the app can:

1. Notify the user that an update is available.
2. Download the release APK over HTTPS.
3. Open Android's package installer for the downloaded APK.

Android still controls installation confirmation. On devices that require it, the user must allow KineWall to install unknown apps before Android will accept an APK downloaded outside an app store.

KineWall selects the normal production APK from the GitHub Release and ignores APK assets marked as debug builds.

## Storage and permissions

KineWall uses `ActivityResultContracts.OpenDocument` to let the user explicitly select a video. The returned `content://` URI is stored, and the app requests persistable read access when the document provider supports it.

Because video selection is handled through Android's document picker, KineWall does not require broad media-library or external-storage access.

Permissions used by the app include:

- `android.permission.INTERNET` — checks GitHub Releases and downloads application updates.
- `android.permission.REQUEST_INSTALL_PACKAGES` — allows KineWall to hand a downloaded update APK to Android's package installer. Installation still requires user approval.
- `android.permission.SET_WALLPAPER` — used by the wallpaper assignment/reset flow.
- `android.permission.BIND_WALLPAPER` — declared on the wallpaper service so Android can bind it as a live wallpaper service.

Diagnostic log files shared with other apps are exposed through an Android `FileProvider` with temporary URI access instead of exposing internal application storage directly.

## Playback and recovery

KineWall monitors both `MediaPlayer` playback progress and the OpenGL renderer while the wallpaper is visible.

The service can detect situations such as:

- Playback time no longer advancing.
- Video frames no longer advancing.
- Frames reaching the renderer but no longer being presented.
- Invalid player or rendering states.

When a stall is detected, KineWall attempts to rebuild the player/rendering pipeline and resume playback close to the previous video position.

A recovery cooldown is used to avoid repeatedly recreating the pipeline in a tight loop.

## Performance

KineWall relies on Android's native media stack for video decoding and OpenGL ES for displaying decoded frames on the wallpaper surface.

Playback is paused when the wallpaper becomes invisible, and player/rendering resources are released when the wallpaper surface is destroyed.

Actual power usage depends heavily on the selected video and device, including:

- Codec and hardware decoder support.
- Video resolution.
- Frame rate.
- Bitrate.
- Device GPU and display resolution.
- Screen refresh rate.
- How long the launcher or wallpaper remains visible.

For better efficiency, use a hardware-decodable format such as H.264/AVC with a resolution and frame rate appropriate for the target device.

## Device notes

Live wallpaper behavior is partly controlled by Android and the device manufacturer.

Some vendor launchers, including some Xiaomi/HyperOS versions, may not expose third-party live wallpapers clearly in their wallpaper menus. KineWall avoids depending on those menus by opening Android's live wallpaper preview directly from the app.

Wallpaper destination choices also vary by Android version and manufacturer implementation.

## Known limitations

- Android 10 and older are not supported.
- KineWall currently uses local video files selected by the user.
- Video codec and container compatibility depends on Android and the device media stack.
- Wallpaper audio is intentionally disabled.
- Installing in-app APK updates requires Android to allow KineWall as an installation source.
- The application cannot control which wallpaper destination options a device manufacturer exposes in Android's live wallpaper UI.

### Known issue

In rare cases, the video wallpaper may still become frozen despite the automatic recovery system. Reapplying KineWall from **Apply wallpaper** normally restores playback.

If the problem can be reproduced, enabling diagnostic logging before reproducing it can provide useful information for an issue report.

## Project structure

```text
app/src/main/
├─ AndroidManifest.xml
├─ java/com/eaangrino/kinewall/
│  ├─ ComposeMainActivity.kt
│  ├─ ComposeDiagnosticsActivity.kt
│  ├─ VideoWallpaperService.kt
│  ├─ VideoFrameRenderer.kt
│  ├─ DiagnosticLogger.kt
│  ├─ DiagnosticSettings.kt
│  ├─ UpdateChecker.kt
│  ├─ UpdateInstaller.kt
│  └─ VersionComparator.kt
├─ java/com/eaangrino/kinewall/ui/
│  └─ Compose theme files
└─ res/
   ├─ drawable/
   ├─ mipmap-*/
   ├─ values/
   └─ xml/
```

## Reporting issues

Bug reports and device-specific problems can be submitted through GitHub Issues:

https://github.com/eaangrino/kinewall-video-wallpaper-android/issues

For playback or freezing problems, useful information includes the Android version, device model, reproduction steps, selected video characteristics, and diagnostic logs when available.
