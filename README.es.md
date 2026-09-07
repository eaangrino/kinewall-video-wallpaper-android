# KineWall — Video Wallpaper para Android

> [!WARNING]
> KineWall sigue en una fase temprana de desarrollo y puede presentar fallos o comportamientos específicos de algunos dispositivos. Las pruebas se han realizado principalmente en un Xiaomi Redmi Note 13.

KineWall es una aplicación Android nativa para usar videos locales como fondos de pantalla animados. Está desarrollada con Kotlin, Jetpack Compose, `WallpaperService`, `MediaPlayer` y un pipeline de renderizado con OpenGL ES.

El proyecto busca mantener la reproducción del video wallpaper de forma nativa, ligera e integrada con Android, sin depender de un runtime multiplataforma.

[English](README.md)

## Características

- Aplicación Android nativa escrita en Kotlin.
- Interfaz con Jetpack Compose y Material 3.
- Diseño responsive para teléfonos y tablets.
- Soporte para Android 11 o superior (`minSdk 30`).
- Selección de videos locales mediante Storage Access Framework de Android.
- Persistencia del acceso al URI del video seleccionado.
- Reproducción del video en loop infinito.
- Audio del wallpaper silenciado y configurado para evitar su captura cuando Android lo soporte.
- La reproducción se pausa cuando el wallpaper no está visible.
- Dos modos de visualización:
  - **Estirar** — llena toda la superficie del wallpaper y puede modificar la relación de aspecto del video.
  - **Rellenar y recortar** — conserva la relación de aspecto y recorta el excedente para llenar toda la superficie.
- Posicionamiento mediante arrastre en la vista previa de Android cuando se usa **Rellenar y recortar**.
- Persistencia de la posición de recorte del video.
- Acceso directo a la vista previa/aplicación nativa de live wallpapers de Android.
- Recuperación automática del pipeline de reproducción y renderizado cuando se detecta un bloqueo.
- Logging de diagnóstico opcional, desactivado por defecto.
- Archivos de diagnóstico diarios con opciones para ver, descargar, compartir y eliminar.
- Comprobación de nuevas versiones publicadas en GitHub Releases.
- Descarga e instalación de nuevas versiones APK desde la propia aplicación.
- No requiere permisos globales de acceso a medios ni al almacenamiento externo para seleccionar videos.

## Arquitectura

KineWall utiliza el sistema de live wallpapers de Android junto con un pipeline multimedia y gráfico nativo.

```text
ComposeMainActivity
 ├─ Selector de video (OpenDocument)
 ├─ Preferencias de video y visualización
 ├─ Configuración del wallpaper
 ├─ Navegación hacia Ajustes y Diagnósticos
 └─ Comprobación de actualizaciones en GitHub Releases

VideoWallpaperService
 └─ WallpaperService.Engine
     ├─ MediaPlayer
     │   └─ Decodifica el video seleccionado hacia un SurfaceTexture
     ├─ VideoFrameRenderer
     │   ├─ EGL / OpenGL ES 2.0
     │   ├─ Renderizado Stretch
     │   ├─ Renderizado Fill & Crop
     │   └─ Posicionamiento del recorte
     └─ Surface del wallpaper de Android
```

La ruta del video es:

```text
Video local
   ↓
MediaPlayer / stack multimedia de Android
   ↓
SurfaceTexture
   ↓
VideoFrameRenderer (OpenGL ES)
   ↓
Surface del wallpaper
   ↓
Compositor de Android / pantalla
```

El renderer permite que KineWall controle el escalado y la posición del recorte en lugar de depender únicamente del escalado de superficies de `MediaPlayer`.

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
2. Pulsa **Select video**.
3. Selecciona un video local mediante el selector de documentos de Android.
4. Elige un modo de visualización:
   - **Fill & crop**
   - **Stretch**
5. Pulsa **Apply wallpaper**.
6. Android abrirá la vista previa nativa del live wallpaper.
7. Si usas **Fill & crop**, puedes arrastrar el video en la vista previa para elegir qué zona queda visible.
8. Confirma el wallpaper desde la interfaz del sistema Android.

KineWall conserva el video seleccionado, el modo de visualización y la posición del recorte.

Las opciones finales de destino del wallpaper son proporcionadas por Android. Dependiendo del dispositivo y de la versión de Android, el sistema puede ofrecer pantalla de inicio, pantalla de bloqueo, ambas o un conjunto más reducido de opciones.

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

KineWall utiliza `ActivityResultContracts.OpenDocument` para que el usuario seleccione explícitamente un video. La aplicación guarda el URI `content://` devuelto por Android y solicita acceso persistente de lectura cuando el proveedor de documentos lo soporta.

Como la selección del video se realiza mediante el selector de documentos de Android, KineWall no necesita acceso global a la biblioteca multimedia ni al almacenamiento externo.

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

Cuando se detecta un bloqueo, KineWall intenta reconstruir el pipeline de reproducción y renderizado y continuar cerca de la posición anterior del video.

Existe un periodo de espera entre recuperaciones para evitar recrear el pipeline repetidamente en un bucle cerrado.

## Rendimiento

KineWall utiliza el stack multimedia nativo de Android para decodificar el video y OpenGL ES para presentar los frames decodificados sobre la superficie del wallpaper.

La reproducción se pausa cuando el wallpaper deja de estar visible, y los recursos del reproductor y renderer se liberan cuando se destruye la superficie del wallpaper.

El consumo real depende en gran medida del video seleccionado y del dispositivo, incluyendo:

- Codec y soporte de decodificación por hardware.
- Resolución del video.
- Frame rate.
- Bitrate.
- GPU y resolución de pantalla del dispositivo.
- Frecuencia de refresco de la pantalla.
- Tiempo durante el cual el launcher o wallpaper permanece visible.

Para una mejor eficiencia, conviene utilizar un formato decodificable por hardware, como H.264/AVC, con una resolución y un frame rate razonables para el dispositivo objetivo.

## Notas sobre dispositivos

El comportamiento de los live wallpapers depende parcialmente de Android y del fabricante del dispositivo.

Algunos launchers de fabricantes, incluyendo ciertas versiones de Xiaomi/HyperOS, pueden no mostrar claramente los live wallpapers de terceros dentro de sus menús de fondos de pantalla. KineWall evita depender de estos menús abriendo directamente la vista previa nativa de live wallpapers desde la aplicación.

Las opciones de destino del wallpaper también pueden variar según la versión de Android y la implementación del fabricante.

## Limitaciones conocidas

- Android 10 y versiones anteriores no están soportados.
- KineWall utiliza actualmente archivos de video locales seleccionados por el usuario.
- La compatibilidad de codecs y contenedores depende de Android y del stack multimedia del dispositivo.
- El audio del wallpaper está deshabilitado intencionalmente.
- La instalación de actualizaciones APK desde la aplicación requiere que Android permita a KineWall actuar como origen de instalación.
- La aplicación no puede controlar qué destinos de wallpaper decide exponer el fabricante dentro de la interfaz de live wallpapers de Android.

### Problema conocido

En casos poco frecuentes, el video wallpaper todavía puede quedar congelado a pesar del sistema de recuperación automática. Volver a aplicar KineWall desde **Apply wallpaper** normalmente restaura la reproducción.

Si el problema puede reproducirse, activar los logs de diagnóstico antes de provocarlo puede aportar información útil para reportarlo.

## Estructura del proyecto

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
