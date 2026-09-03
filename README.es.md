# KineWall — Video Wallpaper para Android

> [!WARNING]
> Este proyecto está en una fase temprana de desarrollo, por lo que puede contener fallos o comportamientos inesperados. De momento ha sido probado en un Xiaomi Redmi Note 13.

Live wallpaper de video para Android, implementado de forma nativa con Kotlin, `WallpaperService` y `MediaPlayer`.

Kinewall está pensado para mantener una implementación pequeña y nativa, sin Flutter, React Native, Ionic, WebView ni frameworks de renderizado adicionales. El video se reproduce directamente sobre el `Surface` del wallpaper, manteniendo una ruta de reproducción simple y permitiendo que Android utilice su stack multimedia y decodificación por hardware cuando el dispositivo lo soporte.

[English](README.md)

## Estado

MVP funcional. La versión actual permite seleccionar un video local, recordarlo, elegir un modo de escalado y aplicarlo como live wallpaper de Android.

## Características

- Implementación Android nativa en Kotlin.
- Solo Android 11 o superior (`minSdk 30`).
- Selección de videos locales mediante Storage Access Framework de Android.
- Persistencia del acceso al URI del video seleccionado.
- Reproducción directa con `MediaPlayer` sobre el `Surface` del `WallpaperService`.
- Reproducción en loop infinito.
- Audio del wallpaper silenciado.
- La reproducción se pausa automáticamente cuando el wallpaper no está visible.
- Dos modos de escalado de video:
  - **Estirar** — ocupa toda la superficie y puede modificar la relación de aspecto del video.
  - **Rellenar y recortar** — conserva la relación de aspecto, llena toda la superficie y recorta los bordes sobrantes.
- Acceso directo desde la app a la vista previa/aplicación nativa de live wallpapers de Android.
- No requiere permisos globales de almacenamiento.

## Arquitectura

```text
MainActivity
 ├─ Selector de video OpenDocument
 ├─ Persistencia del URI content:// seleccionado
 ├─ Persistencia del modo de escalado
 └─ Apertura de la vista previa nativa del live wallpaper

VideoWallpaperService
 └─ WallpaperService.Engine
     ├─ SurfaceHolder
     ├─ MediaPlayer
     ├─ loop + mute
     ├─ modo de escalado
     └─ play/pause según visibilidad del wallpaper
```

La ruta de reproducción se mantiene deliberadamente corta:

```text
Archivo de video
   ↓
MediaPlayer / stack multimedia de Android
   ↓
Surface del wallpaper
   ↓
Compositor de Android / pantalla
```

La implementación actual no utiliza `Bitmap`, `Canvas`, WebView ni una capa gráfica adicional por frame.

## Requisitos

- Android Studio compatible con la versión actual del Android Gradle Plugin utilizada por el proyecto.
- JDK 17.
- Android SDK 37 instalado para compilar.
- Dispositivo con Android 11 o superior (`API 30+`).

Configuración Android actual:

```text
compileSdk = 37
minSdk     = 30
targetSdk  = 36
Java       = 17
```

## Compilación

Clona el repositorio:

```bash
git clone https://github.com/eaangrino/kinewall-video-wallpaper-android.git
cd kinewall-video-wallpaper-android
```

Genera el APK debug:

```bash
./gradlew assembleDebug
```

En Windows:

```powershell
gradlew.bat assembleDebug
```

El APK se generará en:

```text
app/build/outputs/apk/debug/
```

También puedes abrir directamente el repositorio en Android Studio y ejecutar la configuración `app` en un dispositivo Android 11+.

## Uso

1. Abre Kinewall.
2. Pulsa **Seleccionar video**.
3. Elige un video desde el selector de documentos de Android.
4. Elige un modo de escalado:
   - **Estirar**
   - **Rellenar y recortar**
5. Pulsa **Aplicar fondo de pantalla**.
6. Android abrirá la vista previa nativa del live wallpaper.
7. Confirma el fondo desde la interfaz del sistema.

El video seleccionado y el modo de escalado quedan persistidos, por lo que siguen disponibles después de cerrar y volver a abrir la app.

## Almacenamiento y permisos

Kinewall utiliza `ActivityResultContracts.OpenDocument` y guarda el URI `content://` devuelto por Android. Cuando el proveedor de documentos seleccionado lo permite, la app solicita acceso persistente de lectura mediante `takePersistableUriPermission()`.

Como el usuario selecciona explícitamente el video mediante el selector de documentos de Android, este flujo no necesita permisos globales de medios ni de almacenamiento externo.

El servicio de live wallpaper se registra con el permiso de servicio requerido por Android: `android.permission.BIND_WALLPAPER`.

## Enfoque de rendimiento

El proyecto está diseñado deliberadamente alrededor de las APIs nativas multimedia y de wallpaper de Android:

- `MediaPlayer` renderiza directamente sobre el `Surface` del wallpaper.
- No existe un runtime multiplataforma intermedio.
- El código de la aplicación no realiza copias de imágenes frame por frame.
- La reproducción se pausa mediante `onVisibilityChanged(false)` cuando otra app oculta el wallpaper.
- `MediaPlayer` se libera cuando se destruye el `Surface` del wallpaper.

El consumo real de batería sigue dependiendo del codec, resolución, frame rate y bitrate del video seleccionado, del soporte de decodificación del dispositivo, de la frecuencia de refresco de la pantalla y del tiempo durante el cual el launcher permanece visible.

Para obtener mejor eficiencia, conviene usar un formato decodificable por hardware en el dispositivo, como H.264/AVC, con una resolución y frame rate razonables para la pantalla objetivo.

## Comportamiento del escalado

### Estirar

Utiliza:

```kotlin
MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
```

Se ocupa toda la superficie disponible. Si la relación de aspecto del video y la pantalla son distintas, la imagen puede quedar estirada o comprimida.

### Rellenar y recortar

Utiliza:

```kotlin
MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
```

Se conserva la relación de aspecto original mientras se llena toda la superficie del wallpaper. Algunas partes del video pueden quedar recortadas.

El proyecto intencionalmente no añade letterboxing, bandas negras ni un pipeline gráfico adicional en tiempo real.

## Notas sobre dispositivos

Algunos launchers de fabricantes Android, incluyendo ciertas versiones de Xiaomi/HyperOS, pueden no mostrar claramente los live wallpapers de terceros dentro de sus menús de fondos de pantalla. Kinewall evita depender de ese menú abriendo directamente la vista previa nativa mediante `WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER`.

## Limitaciones conocidas

- Android 10 y anteriores no están soportados intencionalmente.
- La versión actual reproduce archivos de video locales.
- El audio del wallpaper está silenciado intencionalmente.
- La compatibilidad de codecs y contenedores depende finalmente de Android y del stack multimedia del dispositivo.
- Actualmente no existe renderizado personalizado por frame, procesamiento con shaders ni controles avanzados de posicionamiento.

## Estructura del proyecto

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

## Dirección de desarrollo

La prioridad actual es mantener la aplicación pequeña, nativa, predecible y eficiente, evitando capas de renderizado adicionales que incrementen el costo en tiempo de ejecución.
