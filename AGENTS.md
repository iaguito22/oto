# AGENTS.md — Oto

Reproductor de música Android (Kotlin + Compose + Media3) sobre InnerTube de YouTube Music.
Es personal: lo usa un solo usuario en su Nothing Phone (2). Se distribuye como APK en GitHub, nunca en Play Store.
Licencia GPL-3 (por usar InnerTubeX). Repo: https://github.com/iaguito22/oto

El usuario habla español: textos de la app, commits y respuestas en español.

## Reglas que no se discuten

- **Nunca** meter la clave `AIza...` (PoToken) en el código: va en `local.properties` -> `BuildConfig.GOOGLE_API_KEY`.
- **Nunca** `connectedAndroidTest` sin `ANDROID_SERIAL=emulator-5554`: sin eso corre en el móvil real y desinstala la app.
- **Nunca** restaurar una copia anterior de los datos del móvil (`oto.preferences_pb`, etc.): el usuario usa el móvil a la vez y se pierden sus cambios. Para limpiar pruebas, borrar solo las entradas que añadió la prueba.
- **Nunca** `Regex(...)` dentro de funciones calientes: causó un OOM nativo al pasar canciones rápido (233 MB -> 1,2 GB). Compilar fuera, una vez.
- **Nunca** reusar el token anónimo de `open.spotify.com/embed` para saltarse límites: se bloqueó. La API oficial de Spotify exige Premium del dueño.
- La clave de firma `~/.config/oto/oto-release.jks` (contraseña en `local.properties`, `OTO_KEYSTORE*`) no se pierde ni se sube: sin ella las actualizaciones no se instalan encima.
- Otra IA puede estar trabajando a la vez en el árbol. Mirar `git status` antes de tocar; si hay cambios sin commit ajenos, usar un worktree (`git worktree add ~/PROYECTOS/oto-<tema> -b <rama>`) y no mezclar.
- Fiarse de lo que el usuario ve en el móvil, no solo de las medidas propias (un recorte de letra que las medidas no cazaron).

## Compilar y probar

- JDK 21 obligatorio (el de por defecto es el 25):
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug` (~3,5 min la primera vez).
- Tests JVM: `./gradlew testDebugUnitTest` (la variante se llama `Debug`, no `Phone`).
- SDK en `~/Android/Sdk`. `adb` no está en el PATH: `~/Android/Sdk/platform-tools/adb`.
- Emulador `oto36` (Android 16, x86_64). **Se cuelga con la letra abierta** (sin GPU real): probar la letra en el móvil.
- Sin ImageMagick: para capturas, `ffmpeg`.
- Crashes: `adb logcat -b crash -d`; desofuscar con `app/build/outputs/mapping/phone/mapping.txt`.

### Variantes

| Variante | Qué es |
|---|---|
| `debug` | sufijo `.debug` |
| `phone` | release con sufijo `.debug`: es donde están los datos del usuario; no depurable |
| `bench` | release R8 firmada con clave debug, sufijo `.bench`, para medir fotogramas |
| release | paquete `io.github.iaguito22.oto`, firmada con la clave propia |

- Tras instalar `phone` o `bench` se pierde la cola: devolverle lo que sonaba.
- Con la `bench` delante, «atrás» lleva a su Oto real y los toques caen ahí: no usar `keyevent 4`.
- Para limpiar datos de `phone` hay que poner `isDebuggable` temporal (`run-as` necesita rutas `/data/data/...`).

### Móvil real

- Nothing Phone (2), A065, Android 16 / API 36, 1080x2412, 420 dpi, 120 Hz.
- adb inalámbrico a `192.168.1.147`. **El mDNS de adb no funciona en su red**: escanear puertos 30000-50000 (`bash /dev/tcp` en paralelo) y `adb connect`. El puerto cambia al reactivar la depuración.
- Su volumen multimedia suele estar a 0 y a veces está en una llamada de Discord: probar en silencio. Con cascos se puede poner música.
- No se puede bajar el volumen de BT por adb.
- `pgrep/pkill -f` con el patrón en la línea de comandos mata la propia shell: buscar pids con `ps -eo pid,comm | awk`.

### Medir rendimiento

- Medir en el móvil, no dar animaciones por buenas. Objetivo: casi 0 % de fotogramas tarde (hoy 0,13-0,28 % con gestos y hojas).
- `framestats` engaña si hay fotogramas en reposo y guarda solo 120 (1 s a 120 Hz): usar `dumpsys gfxinfo` (histograma) o perfetto + `trace_processor`.
- `input swipe` desde `y < 200` baja la cortina de notificaciones.

## Estructura

Módulos Gradle: `:app` y `:innertube` (copiado de Metrolist b1dd1d0, con el paquete `com.metrolist.innertube` sin cambiar; InnerTubeX v0.10.0).
Paquete de la app: `io.github.iaguito22.oto`, en `app/src/main/kotlin/...`.

| Carpeta | Contenido |
|---|---|
| `playback/` | `PlaybackService` (MediaLibraryService + ExoPlayer), `Chunked`, `Fidelity`, `Downloads`, `Crossfade`, `Radio`, `Autoplay`, `Shuffle`, `Library` (Android Auto), `potoken/` |
| `ui/isla/` | La pantalla única: `Drop.kt` (AGSL + física de la gota), `Sheet`, `Lyrics`, `Tutorial`, `Panels`, `Output` |
| `ui/menu/` | La hoja Menú: `MenuSheet`, `MenuViewModel`, `Search`, `SaveTo`, `DownloadsView`, `Import`, `Suggest`, `MenuMemory` |
| `ui/theme/` | Tokens de Caelestia (`Tokens.kt`), tipografía Rubik variable empaquetada |
| `lyrics/` | `LyricsSource` (fuentes), `LyricsStore` (caché memoria + disco) |
| `dj/` | DJ, perfil de gusto, `Smart` (Explorar «Hecho con lo tuyo») |
| `importer/` | Importar de Spotify: enlace público o JSON de la extensión; `Matcher` |
| `account/` | Login de YouTube Music (WebView + cookies), copia de listas en la cuenta |
| `Prefs.kt`, `Storage.kt` | Preferencias (DataStore) y topes de caché |
| `tools/spicetify/` | `oto-export.js`, `install.sh`, `install.ps1` (extensión para exportar de Spotify) |
| `docs/` | Capturas y gifs del README (grabados con `screenrecord` en el móvil) |

Hay un `PatientExtractor.java` en `app/src/main/java/...` porque las clases de InnerTubeX son `internal` en Kotlin.

## Diseño del producto

- Concepto «La isla»: una pantalla con una **gota redonda** (forma B1, núcleo de luz, filtro goo; variante «Aro» por defecto, «Líquida» en Ajustes) que se transforma.
- La gota **no tiene controles ni texto «Buscar»**: minimalista, que dé ganas de tocarla.
- Gestos: tocar = pausa, a los lados = otra canción, subir = **Menú**, mantener = cola, bajar = letra. Se enseñan con un tutorial al abrir. Tocar solo cuenta en 52 dp del centro.
- Menú: pestañas **Explorar | Tu música**, buscador abajo, engranaje de Ajustes en la esquina. «Tu música» es la primera.
- Doble toque en la carátula = Me gusta (solo da, nunca quita). Mantener la carátula o tocar su marca = «Guardar en...» (`SaveTo.kt`).
- Mantener una lista/carpeta = opciones, incluido «Mover». No hay arrastre al mantener (no funcionaba).
- Ajustes: lista única en el orden de siempre. Probó apartados/páginas y lo descartó.
- Estética parecida a Caelestia / Spicetify, con interfaz distinta a la del resto de apps. Le gustan los bordes suaves y las filas de texto en columnas de 4 para canciones.
- Nombre: **Oto** (音). Descartados: Tsuyu, Gota, Marea, Rocío, Shizuku.
- Icono: anillo + núcleo blancos sobre `#252525`.
- Escala fija: `MainActivity.attachBaseContext` fuerza `densityDpi = ladoCortoPx * 160 / 411.43` y `fontScale = 1`, para que se vea igual que en su móvil con cualquier pantalla o tamaño de letra. En tabletas (lado corto >= 3,5") se respeta el sistema. Para probar: `wm density 540` + `settings put system font_scale 1.15`, y luego `wm density reset` + font_scale 1.0.

## Reproducción: lo aprendido

- **YouTube frena el audio pedido de una vez a ~33 KB/s**; a trozos de 1 MB va a ~5 MB/s: por eso existe `playback/Chunked.kt`. No pedir el audio entero.
- `Fidelity.kt`: `Tier` alta = clave `id`, baja = `id~l`; nunca se mezclan formatos. Auto/Máxima + ahorro de datos. Auto pasa a baja si no arranca en 6 s **y hay red validada**.
- `WholeSongLoadControl`: actual + siguiente enteras y nada más. No usar `PreloadConfiguration`.
- `PatientErrorPolicy`: sin red reintenta sin fin.
- `config` y `visitorData` se guardan (`oto_player_config`, `oto_visitor`). `/player` tiene 30 s (InnerTubeX daba 8 s).
- Caché de audio: SimpleCache 1 GB. Descargas: SimpleCache aparte en `filesDir/downloads` sin evictor; `OfflineFirstDataSource` va delante de todo.
- Topes en `Storage.kt`: audio 1 GB, carátulas 200 MB, HTTP 50 MB, DJ 20 MB.
- Poner UNA canción desde un controlador = radio detrás (`onSetMediaItems`). Radio = `RDAMVM` + id, se resiembra con la última de la cola; sin repetir (historial + `songKey` título+artista).
- El MediaController da un instante el id nuevo con `mediaMetadata` de la anterior: leer `currentMediaItem.mediaMetadata`.
- Fundido (`Crossfade`): cortar con `playWhenReady`, no `isPlaying`. `Crossfade.align` corrige el desfase (+-6 ms). El usuario lo tiene en «No».
- Android Auto: `PlaybackService` es `MediaLibraryService`; ids `in|lista|canción`. Hace falta `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` en `onConnect` (si no, «Oto no funciona»). Probar con el DHU (`~/Android/Sdk/extras/google/auto/desktop-head-unit`: necesita libc++/libc++abi, `SDL_VIDEODRIVER=x11 DISPLAY=:1`, sin sandbox, `adb forward tcp:5277 tcp:5277`, comandos por stdin: `tap x y`, `screenshot f`).
- «Anterior» de la gota = siempre la anterior (caché de disco, ~0,2 s). El botón del sistema reinicia pasados 3 s.
- InnerTube devuelve un 403 lento en ~1 de cada 11 lecturas: `InnerTube.hedged` (módulo innertube) lanza otra a 1,5 s o al fallar, hasta 3.
- Explorar sin ánimo pide la portada **sin sesión** (`YouTube.home(guest = true)`); con ánimo, con cuenta. Sin repetidos entre estanterías.

## Letra

- Fuentes en orden: lyricsplus (binimum; el workers.dev daba error 1042) -> NetEase -> LRCLIB (`estimate` portado del `theme.js` de Spicetify) -> YT plano. Romanización Google `dt=rm` (desde el móvil va, desde el PC no).
- lyricsplus limita a **2 peticiones / 10 s** (429: esperar y reintentar).
- Relleno y salto **letra a letra** con `drawText` sobre la palabra entera (kerning intacto). Pasar `alpha = 1f` a `drawText` con degradado o hereda la opacidad anterior.
- La letra sincronizada va en `LazyColumn`. La capa de la línea (`graphicsLayer`) va de borde a borde, con padding `EDGE` dentro: con alfa < 1 se pintaba recortada y comía la primera letra.
- `LEAD` 250 ms. Mantener la letra = romanización (`Prefs.romanize`).
- Arnés `LyricsHarness` (`OTO_LYRICS`, `_N`, `_PACE` >= 5500).

## Rendimiento de UI: trampas

- Si algo bajo una hoja cambia, se re-desenfoca el `Stage` entero cada fotograma: `LocalCovered` (barra, letra, hojas) para no dibujar lo tapado. Gota quieta en pausa a 30 fps.
- Fondo desenfocado en CPU (`blurredArtwork`, sigma ~9,8 px sobre la de 240).
- Descartado tras probarlo: fundido por franjas en vez de `Offscreen`; quitar `currentId` del menú.
- Pendiente conocido: el cambio de canción aún tiene un fotograma de ~40 ms.
- Arranque en frío 257 ms: `Prefs` decodifica listas/me gusta/historial una vez con `shareIn`. No volver a decodificar por canción en `MenuSheet` (rehacer `Taste` y `Smart.lists` en cada canción causó el OOM).
- Cola editable con `sh.calvin.reorderable` 3.1.0: no hacer el arrastre a mano. El gesto de reorderable se queda con el cierre del primer frame: estado único + `rememberUpdatedState`, no `remember(entries)`. En el deslizar para quitar, fondo por offset, no `progress` (vale 1 en reposo). Quitar de la cola también borra de `original` (si no vuelve con el aleatorio).
- Quitar/borrar en listas = `removeLater` (oculto en `gone`, se borra al irse el aviso).

## Datos

- DataStore (no Room): `oto` (prefs), `oto_lists` (listas locales), `oto_menu` (`MenuMemory`), `oto_account`.
- Me gusta = `LIKED_ID` tratado como `LocalPlaylist`. Carpetas = campo `folder` en `LocalPlaylist` + `folders` en prefs para las vacías.
- Copia en cuenta YT: manifiesto v2 (refs de 8 caracteres) + `ListMeta` en la descripción de cada lista.
- Importar de Spotify: enlace público (`__NEXT_DATA__`, **máx. 100 canciones**) o `oto-spotify.json` de la extensión. `Matcher` empareja por título/artista/duración (198/200 en su muestra). Arnés `MatchHarness` (`OTO_HARNESS=tsv`).
- Reinstalar la `phone` borra la cola.

## Flujo de trabajo

- Commits con el email noreply: `git -c user.email=164151388+iaguito22@users.noreply.github.com commit ...`. Mensaje `tipo: resumen` en español.
- Push con la cuenta `iaguito22` (la activa de `gh` puede ser otra): `gh auth switch -u iaguito22`, o `credential.helper` en línea con `gh auth token -u iaguito22`.
- Release: subir `versionCode`, APK firmado y `oto-export.js` a GitHub. Última conocida: **v1.2.0** (versionCode 10). Si se rehace una versión, borrar release y tag antiguos.
- Tests y arneses JVM viven en `app/src/test/`: `DjTest`, `SmartTest`, `AutoplayTest`, `ShuffleTest`, `BlurTest`, `ManifestTest` y los `*Harness` (leen variables de entorno `OTO_*`).
