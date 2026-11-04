# Rendimiento, consumo y animaciones

Índice en `AGENTS.md`. Mediciones del 03–04/11/2026; contrastar con el código. Registro detallado de la revisión de consumo: `docs/RENDIMIENTO_20261103.md`.

## Reglas

- No declarar fluidez por screenshots o compilación. Tras dos hipótesis fallidas, instrumentar antes de seguir.
- `OTO_MOTION_TRACE=1` escribe `%APPDATA%\oto\motion-frames.jsonl` (intervalos de callbacks de animación, `firstDrawDelayMs`, `animationStartedMs`) y `window-work.jsonl` (`WindowWorkTrace.kt`: dibujo, iconos, barra de tareas, foco). Son callbacks, NO FPS ni presentación GPU. No asumir que la instancia habitual lo tiene activo.
- CPU de proceso = % de UN núcleo; no incluye FFmpeg ni es CPU total.
- Los CSV usan decimales es-ES: parsear con esa cultura.
- JFR no está en el runtime empaquetado (falta `jdk.jfr`); `jcmd Thread.print` sí. Leer JFR con JDK completo: `. ..\env.ps1; & "$env:JAVA_HOME\bin\jfr.exe" print --json --stack-depth 40 --events jdk.ExecutionSample,jdk.NativeMethodSample,jdk.GCPhasePause <archivo.jfr>`. Sin `--stack-depth` recorta a cinco frames.
- Material privado (CSV, JFR, scripts, logs) fuera del repo: `C:\Users\iago\Documents\Codex\2026-11-03\q-x20\work`.

## Implementado

- **Progreso aislado** (`PlayerProgress`): la posición ya no recompone PlayerSurface/PlayerContents. Contadores por segundos, slider con precisión.
- **Carátulas:** miniaturas según píxeles físicos (64/128/256/544). `ArtworkLoader` con caché LRU por memoria estimada (48 MiB; 8 MiB con ahorro RAM; máx. 96 entradas), ocho cerrojos que comparten cargas iguales, timeouts y cancelación. `ArtworkLoader.cached(url,size)` consulta sin red. `LibraryArt` espera su tamaño físico, dibuja en un **Canvas permanente** (recorte centrado tipo Crop) y funde 220 ms los bitmaps nuevos. Tests: `ArtworkCacheTest`, `ArtworkLoaderTest`, `MemCacheTest` (`MemCache.resize` desaloja LRU de inmediato).
- **Latido y letra:** el State se lee dentro de `graphicsLayer`; 10 ms entre solicitudes de frame; reloj de letra detenido sin letra sincronizada; tiempo por palabra limitado al intervalo con cambios visuales. Vibrato solo letra a letra.
- **Apertura de ventanas:** sin Offscreen forzado; tras el primer dibujo de ESA ventana se espera a tres intervalos <20 ms (mín. 150 ms, máx. 1200 ms) mientras la preparación alterna alfa `.01f`/`.015f` y una traslación de .25 px con escala 1→.9999. El cierre desmonta al terminar la animación. Spring y dirección intactos.
- **Ahorro RAM** (`Prefs.ramSaver`, false por defecto): caché de carátulas 3 MiB/32 entradas, Skia 4 MiB de recursos y 0,5 MiB de fuentes, Min/MaxHeapFreeRatio 5/15, G1PeriodicGCInterval 20 s; cancela precarga de portadas y audio y desactiva el fundido. Con todo oculto limpia carátulas y Skia, `System.gc` y `EmptyWorkingSet` cada 2 min (`RamSaver.kt`; requiere `jdk.management` en el runtime). Ajustes muestra CPU/GPU/RAM de forma cualitativa.
- **Rueda suave** (`SmoothWheel.kt`): EventQueue propia que reparte las muescas en pasos fraccionarios por fotograma (τ 62 ms); touchpad, páginas y Ctrl/Alt pasan intactos; un Driver por ModuleWindow, la bandeja no se registra. Endurecido el 05/11: un fallo en un paso anula el movimiento restante en vez de matar la corrutina.

## Mediciones

| Prueba | Resultado |
|---|---|
| Larga, 9 min 48 s, 3 ventanas con reproducción | CPU media 49,13 % de un núcleo (20,96–78,71 %); residente 759–821 MB (media 791); privada 911–958 MB; sin crecimiento sostenido (no descarta fugas a largo plazo) |
| Pausa / reproduciendo sin letra | ~1,4 % / ~37 % de un núcleo (muestras cortas, cargas distintas: no es A/B) |
| A/B consumo (mismas 3 ventanas, 21 muestras) | CPU 67,95 → 55,18 % (−18,8 %); residente 727,02 → 619,84 MiB (−14,7 %) |
| A/B ahorro RAM (perfiles aislados, sin reproducción) | visible: residente 427 → 371 MB, privada 527 → 482; oculto: residente 384 → 62 MB, privada 421 → 360 (las páginas vuelven al usarse). Sin probar con 3 ventanas y audio |

No usar ensayos con Downloads o JVM fría como A/B. Script: `ram-ab.ps1`; lanzarlo en un `powershell` nuevo porque modifica APPDATA/LOCALAPPDATA.

## Tirón al abrir ventanas — SIN RESOLVER

Iago sigue notando a veces un corte antes de la animación (05/11). Picos entre callbacks al reabrir la biblioteca (cinco reaperturas por tanda, JFR activo, sin reproducción):

| Variante | Primer arranque | Cinco reaperturas (ms) |
|---|---:|---|
| `control1` (código anterior) | 114,3 | 57,8, 52,7, 41,7, 33,8, 44,5 |
| `cached1` (caché sin Crossfade inicial) | 109,0 | 43,7, 35,0, 42,8, 33,4, 35,2 |
| `focus1` (foco retrasado; REVERTIDO) | 127,6 | 39,0, 49,1, 42,7, 33,7, 34,9 |
| `drawing1` (Canvas en la raíz; REVERTIDO) | 121,6 | 47,4, 51,4, 44,4, 34,4, 46,1 |
| `warmed1` (alfa de preparación variable) | 58,5 | 28,6, 33,6, 24,2, 20,4, 20,2 |
| `fixed1` (+ Canvas permanente en miniaturas, umbral 20 ms) | 125,6 | **22,8, 21,0, 19,5, 18,3, 18,8** |
| `matrix1` (+ variaciones de matriz) | **51,4** | 29,4, 27,7, **52,3**, 19,7, 19,0 |

- `fixed1` no tuvo intervalos >33 ms en reaperturas; `matrix1` tiene una excepción de 52,3 ms. El arranque en frío es muy variable.
- Descartado: retrasar el foco del buscador (vuelve a 120 ms) y desplazar el dibujo con Canvas en la raíz de ModuleWindow (la raíz mantiene `graphicsLayer` y su manejo de entrada). El Canvas que queda es solo el de cada miniatura.
- JFR muestra medidas/layout de tarjetas y callbacks de posición/foco durante los picos. No se atribuye a una única llamada de Windows (iconos, foco y barra de tareas se midieron).
- Falta: repetir control contra `fixed1`/`matrix1`, probar con reproducción y letra, validar visualmente mosaicos, redimensionado y buscador, y repetir la prueba larga.
- Sospecha pendiente de medir: `useSharpTaskbarIcons`/`updateTaskbarEntry` o la composición de la ventana nueva en el hilo de UI compartido.

### Arnés de prueba

- `run-window-probe.ps1 <nombre-nuevo>` (en `q-x20/work`) copia el JAR de `desktopApp/build/libs/desktopApp.jar` a un perfil NUEVO `window-<nombre>` (rechaza uno existente), copia solo DataStore de preferencias/listas y los JSON de ventanas/biblioteca (sin cuenta ni secretos) y ejecuta con JDK 21 y JFR. `analyze-window-probe.mjs` correlaciona picos con JFR.
- El arnés usa `OTO_MOTION_TRACE=1`, `OTO_MOTION_PROBE=1` y el marcador `motion-probe.marker` en el perfil aislado: espera 4 s, cierra/abre la biblioteca cinco veces (1,2 s cerrada, 2,2 s abierta) y sale por `quit()`. No activar esas condiciones sobre el perfil habitual ni restaurar perfiles de prueba sobre él.
- Los perfiles de prueba no tienen descargas aunque las preferencias las pidan: hay trabajo inicial de red en el arranque en frío.
- El JAR control está en `window-control1/probe.jar`; para reproducirlo, parametrizar la ruta del JAR sin sobrescribir archivos en uso.
- Test frágil conocido: `DesktopDownloadsTest.removingActiveDownloadCancelsWorkBeforeDeletingWindowsFiles` falló una vez por timeout de 5 s; repetido solo, pasó.
