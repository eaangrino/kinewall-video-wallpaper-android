# KineWall — Video Wallpaper para Android

> [!WARNING]
> KineWall sigue en una fase temprana de desarrollo y puede presentar fallos o comportamientos específicos de algunos dispositivos. Las pruebas se han realizado principalmente en un Xiaomi Redmi Note 13.

KineWall es una aplicación Android nativa para usar videos locales como fondos de pantalla animados. Está desarrollada con Kotlin, Jetpack Compose, `WallpaperService`, `MediaPlayer`, Media3 Transformer, Room, MediaStore y un pipeline de renderizado con OpenGL ES.

El proyecto busca mantener la reproducción del video wallpaper de forma nativa, ligera e integrada con Android, sin depender de un runtime multiplataforma.

[English](README.md)

## Características

- Aplicación Android nativa escrita en Kotlin.
- Interfaz con Jetpack Compose y Material 3.
- Diseño responsive para teléfonos y tablets.
- Soporte para Android 11 o superior (`minSdk 30`).
- **Galería de videos KineWall** administrada por la aplicación, con miniaturas y estado por video.
- Importación de videos locales mediante Storage Access Framework y `ActivityResultContracts.OpenDocument`.
- Los originales importados se copian al almacenamiento MediaStore administrado por KineWall para poder reprocesarlos después.
- Análisis de metadata del video: resolución, frame rate, bitrate, codec, duración y presencia de audio.
- Opciones de optimización adaptadas al dispositivo para resolución, frame rate y bitrate.
- Perfil recomendado basado en el video original, el tamaño de pantalla y las capacidades disponibles del encoder H.264.
- Los videos optimizados se publican como MP4/H.264 cuando es necesario recodificar el video.
- La pista de audio se elimina físicamente de los videos optimizados.
- La optimización no hace upscale y conserva la relación de aspecto del video original.
- El reprocesamiento siempre parte del original administrado y no de una copia optimizada anterior.
- Si un reprocesamiento falla, se conserva la salida optimizada previa cuando existe.
- Acciones de galería para **Apply wallpaper**, **Edit / Reprocess**, **Details** y **Delete**.
- Al tocar una miniatura aplicable se abre directamente la vista previa nativa del live wallpaper de Android.
- El wallpaper activo se identifica con el indicador **Applied**.
- Dos modos de visualización en tiempo de ejecución:
  - **Stretch** — llena la superficie y puede modificar la relación de aspecto.
  - **Fill & crop** — conserva la relación de aspecto y recorta el excedente.
- Posicionamiento mediante arrastre en la vista previa de Android cuando se usa **Fill & crop**.
- Reproducción en loop infinito con el audio del wallpaper deshabilitado.
- Recuperación automática del pipeline de reproducción y renderizado cuando se detecta un bloqueo.
- Los recursos de reproducción del wallpaper se liberan temporalmente durante el procesamiento para reducir conflictos por codecs.
- Logging de diagnóstico opcional, desactivado por defecto.
- Archivos de diagnóstico diarios con opciones para ver, descargar, compartir y eliminar.
- Comprobación de nuevas versiones en GitHub Releases e instalación de actualizaciones APK desde la aplicación.
- No requiere permisos globales de acceso a medios ni al almacenamiento externo.

## Arquitectura

KineWall separa la administración y el preprocesamiento de videos de la reproducción del live wallpaper.

```text
ComposeMainActivity
 ├─ Importación de video con OpenDocument
 ├─ WallpaperGalleryScreen
 │   ├─ Miniaturas y estados de la galería
 │   ├─ Acciones Apply / Edit / Details / Delete
 │   └─ Configuración de optimización
 ├─ WallpaperLibraryViewModel
 │   ├─ KineWallDatabase (Room)
 │   ├─ VideoMetadataAnalyzer
 │   ├─ VideoOptimizationPlanner
 │   ├─ VideoTranscoder (Media3 Transformer)
 │   └─ VideoStorage (MediaStore)
 ├─ WallpaperRuntimeStore
 └─ Ajustes / diagnósticos / actualizaciones

VideoWallpaperService
 └─ WallpaperService.Engine
     ├─ MediaPlayer
     │   └─ Decodifica el video optimizado hacia un SurfaceTexture
     ├─ VideoFrameRenderer
     │   ├─ EGL / OpenGL ES 2.0
     │   ├─ Renderizado Stretch
     │   ├─ Renderizado Fill & crop
     │   └─ Posicionamiento del recorte
     └─ Surface del wallpaper de Android
```

La ruta del video administrado es:

```text
Video local seleccionado con OpenDocument
   ↓
MediaStore: Movies/KineWall/Originals/
   ↓
VideoMetadataAnalyzer + VideoOptimizationPlanner
   ↓
Media3 Transformer
   ↓
Cache temporal privada: cache/kinewall/transcode/
   ↓
Validación
   ↓
MediaStore: Movies/KineWall/Optimized/
   ↓
Galería / vista previa nativa de live wallpaper
```

La reproducción del live wallpaper sigue siendo nativa y utiliza `MediaPlayer` junto con el renderer OpenGL. Media3 se utiliza únicamente para el preprocesamiento.

## Requisitos

- Android Studio compatible con la versión del Android Gradle Plugin utilizada por el proyecto.
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

Genera el APK release:

```bash
./gradlew assembleRelease
```

Los APK generados se encuentran bajo:

```text
app/build/outputs/apk/
```

También puedes abrir el proyecto directamente en Android Studio y ejecutarlo en un dispositivo con Android 11 o superior.

## Uso

1. Abre KineWall.
2. Pulsa el botón **+** de la galería.
3. Selecciona un video local mediante el selector de documentos de Android.
4. KineWall copia el archivo seleccionado a su carpeta de originales administrados y analiza el video.
5. Elige los parámetros de optimización:
   - Resolución.
   - Frame rate.
   - Bitrate.
   - Modo de visualización en runtime: **Fill and crop** o **Stretch**.
6. Pulsa **Save & process**.
7. Cuando termine el procesamiento, pulsa **Apply now** o vuelve a la galería.
8. Desde la galería, toca una miniatura aplicable o usa **Apply wallpaper** desde el menú de tres puntos.
9. Android abrirá la vista previa nativa del live wallpaper.
10. Si usas **Fill & crop**, puedes arrastrar el video para elegir qué zona queda visible.
11. Confirma el wallpaper desde la interfaz del sistema Android.

El menú de tres puntos también ofrece **Edit / Reprocess**, **Details** y **Delete**.

### Galería y optimización

Cada video importado se registra como un elemento de la galería en la base de datos interna Room. KineWall conserva metadata del original, la generación optimizada actual, los parámetros de optimización, el modo de visualización, la posición de recorte y el estado de disponibilidad.

La optimización no recorta ni estira el video codificado. Conserva la relación de aspecto del original y evita hacer upscale. **Fill & crop** y **Stretch** se aplican posteriormente mediante el renderer OpenGL mientras el wallpaper está funcionando.

El perfil recomendado normalmente limita el frame rate a 30 FPS y la resolución al tamaño de la pantalla sin superar la resolución original. Los frame rates superiores solo se ofrecen cuando el dispositivo informa soporte H.264 para ese tamaño y frecuencia. Los presets de bitrate disponibles son 2, 3, 4, 6 y 8 Mbps, según las capacidades del encoder.

Si el origen ya es H.264 compatible y los parámetros elegidos no requieren recodificar el video, Media3 puede evitar una recodificación innecesaria mientras elimina la pista de audio. Cuando sí se necesita encoding, KineWall genera video H.264 dentro de un MP4.

El reprocesamiento siempre utiliza el original administrado. Una nueva generación se valida antes de reemplazar la salida anterior, y el archivo previo puede conservarse temporalmente cuando todavía corresponde al wallpaper activo.

## Modos de visualización

### Stretch

El frame completo del video se adapta a la superficie del wallpaper.

Si la relación de aspecto del video y la pantalla son diferentes, la imagen puede verse estirada o comprimida.

### Fill & crop

Se conserva la relación de aspecto original mientras se llena toda la superficie del wallpaper. La parte del video que excede el área visible se recorta.

Durante la vista previa de Android, el video puede arrastrarse en el eje donde exista contenido sobrante. KineWall guarda esa posición y la aplica posteriormente en el renderer del wallpaper.

Este modo no añade bandas negras ni letterboxing de forma intencional.

## Diagnósticos

La recolección de logs de diagnóstico es opcional y está **desactivada por defecto**.

Puede activarse desde la pantalla de Diagnósticos dentro de Ajustes. Cuando está habilitada, KineWall registra información útil para investigar problemas de reproducción, renderizado, superficies, orientación y recuperación del wallpaper.

Los logs se almacenan internamente en un archivo por día:

```text
kinewall-diagnostics-YYYY-MM-DD.log
```

La pantalla de Diagnósticos permite:

- Ver los logs disponibles.
- Ver el log del día actual mientras continúa actualizándose.
- Descargar un log.
- Compartir un log.
- Eliminar un log.

El logging puede volver a desactivarse en cualquier momento desde la misma pantalla.

## Actualizaciones

KineWall puede consultar el último GitHub Release del repositorio cuando se inicia la aplicación.

Cuando existe un APK de producción más reciente, la aplicación puede:

1. Avisar que existe una nueva versión.
2. Descargar el APK del release mediante HTTPS.
3. Abrir el instalador de paquetes de Android para ese APK.

Android sigue controlando la confirmación de la instalación. En dispositivos que lo requieran, el usuario debe autorizar a KineWall como origen de instalación antes de que Android permita instalar un APK descargado fuera de una tienda de aplicaciones.

KineWall selecciona el APK normal de producción publicado en GitHub Releases e ignora los APK identificados como builds debug.

## Almacenamiento y permisos

KineWall utiliza `ActivityResultContracts.OpenDocument` únicamente para que el usuario seleccione el archivo de origen. Después de seleccionarlo, el video se copia al almacenamiento MediaStore administrado por KineWall, por lo que el funcionamiento normal de la galería no depende de mantener vivo el URI original del proveedor de documentos.

Almacenamiento compartido administrado:

```text
Movies/
└── KineWall/
    ├── Originals/
    └── Optimized/
```

- `Originals/` contiene las copias importadas utilizadas para reprocesar en el futuro.
- `Optimized/` contiene las generaciones procesadas utilizadas por el live wallpaper.
- Los archivos temporales de transcodificación se crean en el directorio privado `cache/kinewall/transcode/` y se limpian después del procesamiento.
- La metadata de la galería se guarda internamente en la base Room `kinewall.db`.

El flujo de eliminación permite quitar un elemento únicamente de KineWall o eliminar también del dispositivo los archivos administrados por KineWall. Los archivos compartidos de MediaStore pueden permanecer en el dispositivo después de desinstalar la aplicación si no se eliminan por separado.

Como la importación y el acceso a medios administrados utilizan el selector de documentos de Android y MediaStore, KineWall no necesita acceso global a la biblioteca multimedia ni al almacenamiento externo.

Los permisos utilizados por la aplicación incluyen:

- `android.permission.INTERNET` — permite consultar GitHub Releases y descargar actualizaciones de la aplicación.
- `android.permission.REQUEST_INSTALL_PACKAGES` — permite entregar a Android un APK de actualización descargado por KineWall. La instalación sigue requiriendo aprobación del usuario.
- `android.permission.SET_WALLPAPER` — utilizado por el flujo de asignación y restablecimiento del wallpaper.
- `android.permission.BIND_WALLPAPER` — declarado en el servicio para que Android pueda enlazarlo como un live wallpaper.

Los logs compartidos con otras aplicaciones se exponen mediante un `FileProvider` de Android con acceso temporal al URI, sin exponer directamente el almacenamiento interno de la aplicación.

## Reproducción y recuperación

Mientras el wallpaper está visible, KineWall supervisa tanto el progreso de `MediaPlayer` como el renderer OpenGL.

El servicio puede detectar situaciones como:

- El tiempo de reproducción deja de avanzar.
- Los frames de video dejan de avanzar.
- Los frames llegan al renderer pero dejan de presentarse en pantalla.
- El reproductor o el renderer entran en un estado inválido.

Cuando se detecta un bloqueo, KineWall intenta reconstruir el pipeline de reproducción y renderizado y continuar cerca de la posición anterior del video. Existe un periodo de espera entre recuperaciones para evitar reconstrucciones repetidas en un bucle cerrado.

Mientras se optimiza o reprocesa un video, KineWall se coordina con el servicio de wallpaper y libera temporalmente los recursos del codec de `MediaPlayer`. Cuando termina el procesamiento y el wallpaper vuelve a estar visible, la reproducción se recrea sobre la superficie del renderer existente cuando es posible.

## Rendimiento

KineWall utiliza el stack multimedia nativo de Android para decodificar el live wallpaper y OpenGL ES para mostrar los frames. Media3 Transformer se utiliza únicamente cuando la importación o el reprocesamiento necesitan preprocesamiento.

El optimizador intenta evitar trabajo innecesario:

- Nunca hace upscale intencional por encima de la resolución del origen.
- La resolución recomendada no supera el lado largo de la pantalla del dispositivo.
- El frame rate recomendado normalmente no supera 30 FPS.
- Se consultan las capacidades del encoder H.264 antes de ofrecer combinaciones de tamaño, frame rate y bitrate.
- Los orígenes H.264 compatibles pueden evitar una recodificación innecesaria cuando los parámetros seleccionados lo permiten.
- El audio se elimina del archivo procesado en vez de limitarse a silenciarlo durante la reproducción.

El consumo real y la compatibilidad del procesamiento siguen dependiendo del video y del dispositivo, incluyendo soporte de codecs, resolución, frame rate, bitrate, GPU, resolución de pantalla y disponibilidad de recursos de codec por hardware.

La reproducción se pausa cuando el wallpaper deja de estar visible, y los recursos del reproductor y renderer se liberan cuando se destruye la superficie del wallpaper.

## Notas sobre dispositivos

El comportamiento de los live wallpapers depende parcialmente de Android y del fabricante del dispositivo.

Algunos launchers de fabricantes, incluyendo ciertas versiones de Xiaomi/HyperOS, pueden no mostrar claramente los live wallpapers de terceros dentro de sus menús de fondos de pantalla. KineWall evita depender de estos menús abriendo directamente la vista previa nativa de live wallpapers desde la aplicación.

Las opciones de destino del wallpaper también pueden variar según la versión de Android y la implementación del fabricante.

## Limitaciones conocidas

- Android 10 y versiones anteriores no están soportados.
- KineWall actualmente importa archivos de video locales seleccionados por el usuario.
- La capacidad de decodificación de video y encoding H.264 depende de Android y del stack multimedia del dispositivo.
- Algunas combinaciones de alta resolución o alto frame rate pueden no estar disponibles si el encoder del dispositivo no informa soporte.
- El dispositivo todavía puede rechazar una conversión por presión temporal de memoria o recursos de codec; bajar resolución o frame rate puede ayudar.
- El audio se elimina intencionalmente de los videos optimizados y también se mantiene silenciado durante la reproducción.
- Los originales y optimizados administrados por KineWall se guardan en ubicaciones compartidas de MediaStore y pueden permanecer en el dispositivo después de desinstalar la aplicación si no se eliminan.
- La instalación de actualizaciones APK desde la aplicación requiere que Android permita a KineWall actuar como origen de instalación.
- La aplicación no puede controlar qué destinos de wallpaper decide exponer el fabricante dentro de la interfaz de live wallpapers de Android.

### Problema conocido

En casos poco frecuentes, el video wallpaper todavía puede quedar congelado a pesar del sistema de recuperación automática. Volver a aplicar KineWall desde la galería normalmente restaura la reproducción.

Si el problema puede reproducirse, activar los logs de diagnóstico antes de provocarlo puede aportar información útil para reportarlo.

## Estructura del proyecto

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
│  └─ Archivos del tema Compose
└─ res/
   ├─ drawable/
   ├─ mipmap-*/
   ├─ values/
   └─ xml/
```

## Reportar problemas

Los errores y problemas específicos de dispositivos pueden reportarse mediante GitHub Issues:

https://github.com/eaangrino/kinewall-video-wallpaper-android/issues

Para problemas de reproducción o congelamiento resulta útil incluir la versión de Android, modelo del dispositivo, pasos para reproducir el problema, características del video utilizado y logs de diagnóstico cuando estén disponibles.
