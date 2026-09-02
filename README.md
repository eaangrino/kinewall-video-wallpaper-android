# Kinewall Video Wallpaper

Native Android video live wallpaper built with Kotlin, `WallpaperService`, and `MediaPlayer`.

Kinewall is intentionally focused on a small, native implementation with no Flutter, React Native, Ionic, WebView, or custom rendering framework. Video is rendered directly to the wallpaper `Surface`, keeping the playback path simple and allowing Android to use the device media stack and hardware decoding when available.

[Español](README.es.md)

## Status

Functional MVP. The current version can select a local video, remember it, choose a scaling mode, and apply it as an Android live wallpaper.

## Features

- Native Android implementation in Kotlin.
- Android 14+ only (`minSdk 34`).
- Local video selection through Android's Storage Access Framework.
- Persisted access to the selected video URI.
- Direct playback with `MediaPlayer` on the `WallpaperService` surface.
- Infinite video loop.
- Muted wallpaper playback.
- Playback pauses automatically while the wallpaper is not visible.
- Two video scaling modes:
  - **Stretch** — fills the complete surface and may change the video's aspect ratio.
  - **Fill & crop** — preserves aspect ratio, fills the surface, and crops overflowing edges.
- Direct access from the app to Android's live wallpaper preview/apply screen.
- No broad storage permission required.

## Architecture

```text
MainActivity
 ├─ OpenDocument video picker
 ├─ Persist selected content:// URI
 ├─ Persist scaling preference
 └─ Open Android live wallpaper preview

VideoWallpaperService
 └─ WallpaperService.Engine
     ├─ SurfaceHolder
     ├─ MediaPlayer
     ├─ loop + mute
     ├─ scaling mode
     └─ play/pause based on wallpaper visibility
```

The playback path is deliberately short:

```text
Video file
   ↓
MediaPlayer / Android media stack
   ↓
Wallpaper Surface
   ↓
Android compositor / display
```

There is no per-frame `Bitmap`, `Canvas`, WebView, or additional graphics layer in the current implementation.

## Requirements

- Android Studio with support for the current Android Gradle Plugin used by the project.
- JDK 17.
- Android SDK 37 installed for compilation.
- Android 14 or newer device (`API 34+`).

Current Android configuration:

```text
compileSdk = 37
minSdk     = 34
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

The APK will be generated under:

```text
app/build/outputs/apk/debug/
```

You can also open the repository directly in Android Studio and run the `app` configuration on an Android 14+ device.

## Usage

1. Open Kinewall.
2. Tap **Select video**.
3. Choose a video from the Android document picker.
4. Choose a scaling mode:
   - **Stretch**
   - **Fill & crop**
5. Tap **Apply wallpaper**.
6. Android opens the native live wallpaper preview.
7. Confirm the wallpaper from the system UI.

The selected video and scaling mode are persisted, so they remain available after reopening the app.

## Storage and permissions

Kinewall uses `ActivityResultContracts.OpenDocument` and stores the returned `content://` URI. When supported by the selected document provider, the app requests persistable read access with `takePersistableUriPermission()`.

Because the user explicitly selects the video through Android's document picker, the app does not require broad media or external-storage permissions for this flow.

The live wallpaper service itself is registered with Android's required `android.permission.BIND_WALLPAPER` service permission.

## Performance approach

The project is intentionally designed around Android's native media and wallpaper APIs:

- `MediaPlayer` renders directly to the wallpaper `Surface`.
- No cross-platform runtime is involved.
- No frame-by-frame image copies are performed by application code.
- Playback is paused in `onVisibilityChanged(false)` when the wallpaper is hidden by another app.
- `MediaPlayer` is released when the wallpaper surface is destroyed.

Actual battery usage still depends on the selected video's codec, resolution, frame rate, bitrate, device decoder support, screen refresh rate, and how long the launcher remains visible.

For best efficiency, use a device-supported hardware-decodable format such as H.264/AVC at a sensible resolution and frame rate for the target screen.

## Scaling behavior

### Stretch

Uses:

```kotlin
MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
```

The complete surface is filled. If the video and display aspect ratios differ, the image can be stretched or compressed.

### Fill & crop

Uses:

```kotlin
MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
```

The original aspect ratio is preserved while the complete wallpaper surface is filled. Parts of the video may be cropped.

The project intentionally does not add letterboxing, black bars, or an extra realtime graphics pipeline.

## Device notes

Some Android vendor launchers, including some Xiaomi/HyperOS versions, may not expose third-party live wallpapers clearly in their wallpaper menus. Kinewall avoids depending on that menu by opening Android's live wallpaper preview directly from the app through `WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER`.

## Known limitations

- Android 13 and older are intentionally unsupported.
- Playback is local-file based in the current version.
- Wallpaper audio is intentionally muted.
- Video codec/container compatibility ultimately depends on Android and the device media stack.
- There is currently no custom per-frame rendering, shader processing, or advanced positioning UI.

## Project structure

```text
app/src/main/
├─ AndroidManifest.xml
├─ java/com/eaangrino/kinewall/
│  ├─ MainActivity.kt
│  └─ VideoWallpaperService.kt
└─ res/
   ├─ layout/
   │  └─ activity_main.xml
   └─ xml/
      └─ video_wallpaper.xml
```

## Development direction

The current priority is to keep the application small, native, predictable, and efficient rather than adding rendering layers that increase runtime cost.
