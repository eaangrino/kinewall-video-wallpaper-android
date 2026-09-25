# KineWall — Video Wallpaper for Android

> [!WARNING]
> KineWall is still in an early stage of development and may contain bugs or device-specific behavior. It has been tested primarily on a Xiaomi Redmi Note 13.

KineWall is a native Android application for using local videos as live wallpapers. It is built with Kotlin, Jetpack Compose, `WallpaperService`, `MediaPlayer`, Media3 Transformer, Room, MediaStore, and an OpenGL ES rendering pipeline.

The project is focused on keeping video wallpaper playback native, lightweight, and integrated with Android instead of relying on a cross-platform runtime.

[Español](README.es.md)

## Features

- Native Android application written in Kotlin.
- Jetpack Compose and Material 3 interface.
- Responsive layouts for phones and tablets.
- Android 11+ support (`minSdk 30`).
- Managed **KineWall video gallery** with thumbnails and per-video status.
- Import local videos through Android's Storage Access Framework with `ActivityResultContracts.OpenDocument`.
- Imported originals are copied into KineWall-managed MediaStore storage for later reprocessing.
- Video metadata analysis for resolution, frame rate, bitrate, codec, duration, and audio presence.
- Device-aware optimization options for resolution, frame rate, and bitrate.
- Recommended optimization profile based on the source video, display size, and available H.264 encoder capabilities.
- Optimized wallpaper videos are published as MP4/H.264 when video encoding is required.
- Audio tracks are physically removed from optimized wallpaper videos.
- No upscaling during optimization; the source aspect ratio is preserved.
- Reprocessing always starts from the managed original instead of a previously optimized copy.
- Existing optimized output is preserved if a reprocessing attempt fails.
- Gallery actions for **Apply wallpaper**, **Edit / Reprocess**, **Details**, and **Delete**.
- Tapping an applicable gallery thumbnail opens Android's native live wallpaper preview directly.
- Active wallpapers are marked with an **Applied** indicator.
- Two runtime display modes:
  - **Stretch** — fills the wallpaper surface and may alter the video's aspect ratio.
  - **Fill & crop** — preserves aspect ratio and crops overflow to fill the surface.
- Drag-to-position support in Android's wallpaper preview when using **Fill & crop**.
- Infinite video loop with wallpaper audio disabled.
- Automatic playback and rendering recovery when a stalled wallpaper is detected.
- Wallpaper playback resources are temporarily released while KineWall is processing a video to reduce codec resource contention.
- Optional diagnostic logging, disabled by default.
- Daily diagnostic log files with view, download, share, and delete actions.
- In-app GitHub release checks and APK update installation flow.
- No broad media-library or external-storage permission is required.

## Architecture

KineWall separates video management and preprocessing from live wallpaper playback.

```text
ComposeMainActivity
 ├─ OpenDocument video import
 ├─ WallpaperGalleryScreen
 │   ├─ Gallery thumbnails and status
 │   ├─ Apply / Edit / Details / Delete actions
 │   └─ Optimization configuration
 ├─ WallpaperLibraryViewModel
 │   ├─ KineWallDatabase (Room)
 │   ├─ VideoMetadataAnalyzer
 │   ├─ VideoOptimizationPlanner
 │   ├─ VideoTranscoder (Media3 Transformer)
 │   └─ VideoStorage (MediaStore)
 ├─ WallpaperRuntimeStore
 └─ Settings / diagnostics / update flows

VideoWallpaperService
 └─ WallpaperService.Engine
     ├─ MediaPlayer
     │   └─ Decodes the optimized video into a SurfaceTexture input surface
     ├─ VideoFrameRenderer
     │   ├─ EGL / OpenGL ES 2.0
     │   ├─ Stretch rendering
     │   ├─ Fill & crop rendering
     │   └─ Crop positioning
     └─ Android wallpaper Surface
```

The managed video path is:

```text
Local video selected with OpenDocument
   ↓
MediaStore: Movies/KineWall/Originals/
   ↓
VideoMetadataAnalyzer + VideoOptimizationPlanner
   ↓
Media3 Transformer
   ↓
Temporary app cache: cache/kinewall/transcode/
   ↓
Validation
   ↓
MediaStore: Movies/KineWall/Optimized/
   ↓
Gallery / Android live wallpaper preview
```

Live wallpaper playback remains native and uses `MediaPlayer` plus the OpenGL renderer. Media3 is used for preprocessing only.

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
2. Tap the **+** button in the gallery.
3. Choose a local video using Android's document picker.
4. KineWall copies the selected file into its managed originals folder and analyzes the video.
5. Choose the optimization settings:
   - Resolution.
   - Frame rate.
   - Bitrate.
   - Runtime display mode: **Fill and crop** or **Stretch**.
6. Tap **Save & process**.
7. When processing finishes, tap **Apply now**, or return to the gallery.
8. From the gallery, tap an applicable thumbnail or use **Apply wallpaper** from the three-dot menu.
9. Android opens the native live wallpaper preview.
10. When using **Fill & crop**, drag the preview if you want to reposition the visible area.
11. Confirm the wallpaper using Android's system UI.

The three-dot menu also provides **Edit / Reprocess**, **Details**, and **Delete**.

### Gallery and optimization

Each imported video is tracked as a gallery item in the internal Room database. KineWall keeps metadata for the original source, the current optimized generation, optimization settings, display mode, crop position, and availability status.

Optimization does not crop or stretch the encoded video. It preserves the source aspect ratio and avoids upscaling. **Fill & crop** and **Stretch** are applied later by the OpenGL renderer while the wallpaper is running.

The recommended profile normally caps the frame rate at 30 FPS and limits resolution to the device display size without exceeding the source resolution. Higher source frame rates are offered only when the device reports support for the requested H.264 size and rate. Available bitrate presets are 2, 3, 4, 6, and 8 Mbps, depending on encoder capabilities.

If the source is already compatible H.264 and the selected settings do not require video re-encoding, Media3 can avoid unnecessary video encoding while still removing the audio track. When encoding is required, KineWall outputs H.264 video in MP4.

Reprocessing always uses the managed original. A newly processed generation is validated before it replaces the previous optimized output, and the previous wallpaper file can be retained temporarily when it is still active.

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

KineWall uses `ActivityResultContracts.OpenDocument` only to let the user select a source file. After selection, the video is copied into KineWall-managed MediaStore storage, so normal gallery operation does not depend on keeping the original document-provider URI alive.

Managed shared storage:

```text
Movies/
└── KineWall/
    ├── Originals/
    └── Optimized/
```

- `Originals/` contains the imported source copies used for future reprocessing.
- `Optimized/` contains the processed generations used by the live wallpaper.
- Temporary transcode files are created in the app-private `cache/kinewall/transcode/` directory and cleaned up after processing.
- Gallery metadata is stored internally in the Room database `kinewall.db`.

The gallery delete flow can either remove an item from KineWall only or delete the KineWall-managed media files from the device. Shared MediaStore files can outlive an app uninstall unless they are deleted separately.

Because import and managed media access use Android's document picker and MediaStore, KineWall does not require broad media-library or external-storage access.

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

When a stall is detected, KineWall attempts to rebuild the player/rendering pipeline and resume playback close to the previous video position. A recovery cooldown prevents repeated rebuilds in a tight loop.

While a video is being optimized or reprocessed, KineWall coordinates with the wallpaper service and temporarily releases the wallpaper `MediaPlayer` codec resources. When processing ends and the wallpaper is visible again, playback is recreated on the existing renderer surface when possible.

## Performance

KineWall uses Android's native media stack for live wallpaper decoding and OpenGL ES for displaying decoded frames. Media3 Transformer is used only when importing/reprocessing requires preprocessing.

The optimizer is designed to avoid unnecessary work:

- It never intentionally upscales above the source video.
- The recommended resolution does not exceed the device display long side.
- The recommended frame rate is normally at most 30 FPS.
- H.264 encoder capabilities are checked before offering size/frame-rate/bitrate combinations.
- Compatible H.264 sources can avoid unnecessary video re-encoding when the selected settings allow it.
- Audio is removed from the processed wallpaper file instead of merely muting it at playback time.

Actual power usage and processing support still depend heavily on the selected video and device, including codec support, resolution, frame rate, bitrate, GPU, display resolution, and available hardware codec resources.

Playback is paused when the wallpaper is not visible, and player/rendering resources are released when the wallpaper surface is destroyed.

## Device notes

Live wallpaper behavior is partly controlled by Android and the device manufacturer.

Some vendor launchers, including some Xiaomi/HyperOS versions, may not expose third-party live wallpapers clearly in their wallpaper menus. KineWall avoids depending on those menus by opening Android's live wallpaper preview directly from the app.

Wallpaper destination choices also vary by Android version and manufacturer implementation.

## Known limitations

- Android 10 and older are not supported.
- KineWall currently imports local video files selected by the user.
- Video decoding and H.264 encoding capabilities depend on Android and the device media stack.
- Some high-resolution or high-frame-rate combinations may not be available if the device encoder does not report support.
- A device may still reject a conversion because of temporary codec or memory pressure; lowering resolution or frame rate can help.
- Wallpaper audio is intentionally removed from optimized videos and also muted at runtime.
- KineWall-managed originals and optimized files are stored in shared MediaStore locations and may remain on the device after uninstall unless deleted.
- Installing in-app APK updates requires Android to allow KineWall as an installation source.
- The application cannot control which wallpaper destination options a device manufacturer exposes in Android's live wallpaper UI.

### Known issue

In rare cases, the video wallpaper may still become frozen despite the automatic recovery system. Reapplying KineWall from the gallery normally restores playback.

If the problem can be reproduced, enabling diagnostic logging before reproducing it can provide useful information for an issue report.

## Project structure

```text
app/src/main/
├─ AndroidManifest.xml
├─ java/com/eaangrino/kinewall/
│  ├─ ComposeMainActivity.kt
│  ├─ WallpaperGalleryScreen.kt
│  ├─ WallpaperLibraryViewModel.kt
│  ├─ WallpaperLibraryModels.kt
│  ├─ KineWallDatabase.kt
│  ├─ VideoMetadataAnalyzer.kt
│  ├─ VideoOptimizationPlanner.kt
│  ├─ VideoTranscoder.kt
│  ├─ VideoStorage.kt
│  ├─ WallpaperRuntimeStore.kt
│  ├─ WallpaperMediaResourceCoordinator.kt
│  ├─ VideoWallpaperService.kt
│  ├─ VideoFrameRenderer.kt
│  ├─ ComposeDiagnosticsActivity.kt
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
