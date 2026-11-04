# Rendimiento, consumo y animaciones

Parte del contexto de Oto; índice en `AGENTS.md`. Fechas y estados son los de cada sección: contrastar con el código.

## Cambios recientes instalados y con tests pasados

- Ahorro RAM persistente `Prefs.ramSaver`, false por defecto: ahora limita la caché de imágenes por memoria estimada (48 a 8 MiB, máximo 96 entradas), además de cancelar precarga de siguientes portadas y precarga de audio/fundido. Al quitarlo recupera duración de fundido elegida. No fuerza GC ni promete liberar memoria inmediatamente.
- Ajustes indican cualitativamente CPU/GPU/RAM. Fundido señala RAM y CPU y aparece desactivado con ahorro RAM.
- `MemCache.resize` desaloja LRU excedentes inmediatamente; `MemCacheTest` lo cubre.
- Descargas internas: archivos .webm/.m4a con nombre por id; no cambiar nombres sin migrar índice. Se retiró el botón «Abrir carpeta de descargas»; ahora se exportan copias MP3 reproducibles desde «Exportar como MP3…».
- Latido: State leído dentro de graphicsLayer, evitando recomponer toda biblioteca/reproductor por frame; margen en portada para no cortar crecimiento.
- Letras: reloj detenido sin letra sincronizada; tiempo por palabra limitado al intervalo con cambios visuales para reducir invalidaciones estáticas. Mantiene sincronización; vibrato solo letra a letra.
- Apertura: sin Offscreen forzado; espera frames estables antes de transición (mínimo 150 ms, máximo 1200 ms, tres intervalos <35 ms). Cierre desmonta al terminar animación, no con temporizador fijo de 380 ms.
- Última instalación contiene ese arreglo de espera. Últimos tests core/desktop y MSI pasaron. Quedan mejoras de animaciones, no afirmar que todo esté resuelto.

## Evidencia de rendimiento: 03/11/2026

- Prueba real de **9 min 48 s**, tres ventanas, reproducción y cambios automáticos. CSV local `../performance-long-20261103.csv`.
- CPU principal media 49.13 % de UN núcleo, intervalos 20.96–78.71 %. No es porcentaje de todo el equipo, ni incluye CPU FFmpeg.
- Residente 759.29–821.20 MB, media 791.34 MB; privada 910.57–957.97 MB. Sin crecimiento sostenido en esa muestra; no descarta fugas a largo plazo.
- Muestras cortas: pausa ~1.4 % de núcleo; reproduciendo sin letra ~37 %. Canciones/cargas distintas: no afirmar A/B concluyente.
- Diagnóstico optativo `OTO_MOTION_TRACE=1`: escribe `%APPDATA%\oto\motion-frames.jsonl`. Mide intervalos de callbacks de animación, NO presentación GPU ni FPS reales. En la medición histórica se arrancó con diagnóstico activo; no asumir que la instancia actual lo tiene habilitado.
- Antes: aperturas iniciales solo 2–5 callbacks, pausas 200–750 ms. Después: reproductor/letra ~50 callbacks, medias ~9–10 ms; biblioteca inicial pico 116 ms.
- Última biblioteca: cierre 39 callbacks, media 8.8 ms, máximo 22.9 ms; reapertura 38 callbacks, media 13 ms, máximo 66 ms (excluyendo primer intervalo). Sigue pendiente ese pico.
- JFR no disponible en runtime empaquetado (falta jdk.jfr). jcmd Thread.print sí funcionó; no confundir muestreo de hilos con perfil completo.
- CSV usa decimales españoles: para estadística Parse con cultura es-ES; cast PowerShell/invariant puede interpretar comas como miles.
- Al terminar aquella medición se cerró la captura y se dejó el escritorio sin canción. Para el estado más reciente del móvil, ver la sección de instalación más abajo.

## Fluidez y consumo de escritorio: revisión del 03/11/2026

- Petición de Iago: trabajar en fluidez y consumo, sin cambiar bienvenida ni valores por defecto. No retirar las animaciones existentes.
- Progreso aislado en `PlayerProgress`: la posición de audio ya no recompone toda PlayerSurface/PlayerContents. Contadores por segundos; slider conserva precisión. Probados los tres formatos y clic de seek horizontal (saltó a unos 00:53 manteniendo reproducción).
- Miniaturas de LibraryArt/mosaicos según píxeles físicos (64/128/256/544), portada principal 544 con paleta. ArtworkLoader usa caché LRU por memoria estimada 48 MiB / ahorro 8 MiB, máximo 96 entradas; ocho cerrojos comparten cargas iguales, con timeouts y cancelación correcta. `ArtworkCacheTest` y `ArtworkLoaderTest` pasan: expulsión por presupuesto, recencia, reducción, doce peticiones compartidas y reintento tras HTTP fallido.
- Latido y reloj de letra dejan 10 ms entre solicitudes de frame, reduciendo trabajo continuo en pantallas rápidas sin cambiar el cálculo temporal ni las transiciones de interacción. Políticas de igualdad explícitas en estados derivados de la letra.
- Apertura: preparación con dibujo a alfa .001 y espera del primer dibujo de ESA ventana antes de contar frames estables. OTO_MOTION_TRACE registra firstDrawDelayMs para aperturas. Mejora de apertura NO acreditada de forma concluyente: biblioteca sigue con picos, no prometer que esté fluida del todo.
- Comparación A/B corta con audio local, mismas tres ventanas, biblioteca en Tu música y mismo JDK 21.0.13: calentamiento ~50 s y 21 muestras cada 2 s sobre 00:30–01:10. CPU de proceso **67.95 → 55.18 % de un núcleo** (−18.8 %); residente medio **727.02 → 619.84 MiB** (−107.18 MiB, −14.7 %). No es CPU total del ordenador ni incluye FFmpeg. La comparación es anterior al último ajuste de espera del primer dibujo, que solo afecta a la entrada de las ventanas.
- No usar primeros ensayos con Downloads o JVM fría como A/B. CSV, JFR, scripts y logs privados: `C:\Users\iago\Documents\Codex\2026-11-03\q-x20\work`. Los CSV guardan decimales es-ES: parsearlos con esa cultura.
- Reapertura final instrumentada sin reproducción: media 14.2 ms, máximo 63.4 ms entre callbacks. Primer arranque: biblioteca pico ~134 ms; reproductor/letra ~58 ms en la última muestra con JDK completo. Son callbacks, NO FPS GPU; no comparar directamente con muestras históricas de otra carga.
- Registro detallado: `docs/RENDIMIENTO_20261103.md`. Tests escritorio y MSI pasaron. Instalación Windows actualizada, conserva cuenta/biblioteca. Se restauraron carátula y agrupación tras probar otras vistas. Cambios locales sin commit/push.
- Pendientes: pico de apertura en frío de biblioteca, sesión prolongada de consumo y A/B con ahorro RAM activado/desactivado. Bienvenida y valores por defecto siguen fuera de esta revisión.
- Cierre de la tarea: MSI final instalado con códigos 0/0; hash SHA-256 del JAR instalado coincide con el compilado. 59 pruebas escritorio, 5 omitidas por condiciones externas, 0 fallos. Tres ventanas iniciadas y canal local responde; escritorio sin canción, cola vacía y pausado. Diagnóstico de movimiento desactivado en el arranque final y captura Windows cerrada/resetada.

## Arreglo de apertura EN CURSO — estado para /clear, 03/11/2026

### Petición y estado instalado

- Tras informar del tirón pendiente, Iago dijo «si se puede arreglar hazlo». Después pidió actualizar este AGENTS.md.
- La app habitual sigue siendo la instalación de la sección anterior (mejora de consumo, JAR SHA-256 `4A5DB16F04373FC99908E7EC61E66CA7A1AB771FF1235F59DD703CA43C0579C2`). **Ningún cambio nuevo de esta sección está instalado.** Oto habitual sigue abierto, sin que estas pruebas cambien su perfil o distribución.
- Hay muchos cambios previos sin commit: conservarlos. No dar por terminado el tirón ni presentar los callbacks como FPS reales.

### Herramientas y reproducción nueva

- Todo el material privado está en `C:\Users\iago\Documents\Codex\2026-11-03\q-x20\work`.
- `run-window-probe.ps1 <nombre-nuevo>` copia el JAR compilado a un perfil NUEVO `window-<nombre>`, copia SOLO los DataStore de preferencias/listas y los JSON de ventanas/biblioteca desde el perfil habitual, sin cuenta ni secretos, y ejecuta con JDK 21 y JFR. Cada nombre debe ser nuevo: el script rechaza una carpeta ya existente.
- El arnés usa `OTO_MOTION_TRACE=1`, `OTO_MOTION_PROBE=1` y el marcador `motion-probe.marker` dentro del perfil aislado. `ModularDesktop` espera 4 s, cierra/abre biblioteca cinco veces (1.2 s cerrada, 2.2 s abierta) y sale por `quit()`. Solo funciona con variable Y marcador. No activar esas dos condiciones sobre el perfil habitual.
- Las pruebas aisladas han terminado solas; no quedan procesos de arnés esperando. La app habitual tiene dos procesos Oto (lanzador/JVM), no confundirlos con los Java de Gradle.
- `WindowWorkTrace.kt` es NUEVO, sin seguimiento Git: diagnóstico optativo que mide CPU/tiempo de dibujo, iconos, llamada de barra de tareas y foco. Registra solo etiquetas/tiempos en `window-work.jsonl`, con escritor IO. Desactivado sin OTO_MOTION_TRACE.
- `motion-frames.jsonl` incorpora `animationStartedMs` además de intervalos y primer dibujo. `analyze-window-probe.mjs` correlaciona picos con JFR. Para leer JFR: `. ..\env.ps1; & "$env:JAVA_HOME\bin\jfr.exe" print --json --stack-depth 40 --events jdk.ExecutionSample,jdk.NativeMethodSample,jdk.GCPhasePause <archivo.jfr> > <events.json>`; **sin --stack-depth, jfr print recorta a cinco frames**.
- Los perfiles de prueba no tienen los archivos descargados aunque las preferencias pueden pedirlos: hay trabajo inicial anónimo de red/descargas. Tenerlo en cuenta al comparar arranques en frío; las cinco reaperturas son más consistentes. No restaurar esos perfiles sobre el habitual.

### Hallazgos y cambios actuales

- Las imágenes en caché volvían a entrar desde un bitmap null y lanzaban transiciones Crossfade al reabrir. `ArtworkLoader.cached(url,size)` permite consultar la caché sin red.
- `LibraryArt` ahora espera su tamaño físico antes de montar la imagen; si está en caché la primera composición ya la muestra. Los nuevos bitmaps mantienen fundido de 220 ms mediante Animatable + alpha en graphicsLayer. Si cambia el tamaño y falla la carga, conserva la imagen anterior.
- Además, su nodo de imagen es ahora un **Canvas permanente**: dibuja con recorte centrado equivalente a ContentScale.Crop. La llegada del bitmap invalida el dibujo, sin insertar un Image nuevo ni volver a medir toda la cuadrícula. Hay que verificar visualmente miniaturas/mosaicos y redimensionado.
- La preparación anterior esperaba ticks del reloj con alfa fijo .001; no garantizaba ejercitar el renderizado real. Ahora alterna `.01f` / `.015f` durante la preparación y pide tres intervalos <20 ms (antes 35 ms), mínimo 150 ms, máximo 1200 ms, después del primer dibujo. Animación visible mantiene spring y dirección.
- **Última variante en fuentes/JAR:** también alterna en esa fase una traslación de .25 px y escala de 1 a .9999 para ejercitar matrices/posiciones antes de mostrar el movimiento. Su mejora adicional NO es concluyente: comparar con la variante previa `fixed1` antes de conservarla por defecto.
- Probado y REVERTIDO: retrasar el foco del buscador hasta acabar la entrada. No eliminó los picos; foco vuelve a su demora original de 120 ms.
- Probado y REVERTIDO: desplazar el dibujo con Canvas en la raíz de ModuleWindow en vez de graphicsLayer, bloqueando clicks durante la transición. No mejoró; **la raíz mantiene graphicsLayer y el manejo de entrada original**. El Canvas que sí queda es SOLO el de cada miniatura.
- JFR muestra medidas/layout de tarjetas y callbacks de posiciones/foco durante algunos picos. Iconos, foco inicial y barra de tareas se midieron; no se atribuye todo el tirón a una única llamada de Windows. No seguir inventando hipótesis sin comparar las tandas.

### Resultados exactos de las tandas (picos entre callbacks, ms)

Cada tanda incluye un arranque en frío y cinco reaperturas. JFR activo en todas; misma biblioteca copiada, tres ventanas, sin reproducción.

| Perfil | Primer arranque biblioteca | Cinco reaperturas biblioteca |
|---|---:|---|
| `window-control1` (código antes de esta sección, con instrumentación) | 114.3 | 57.8, 52.7, 41.7, 33.8, 44.5 |
| `window-cached1` (caché sin Crossfade inicial) | 109.0 | 43.7, 35.0, 42.8, 33.4, 35.2 |
| `window-focus1` (foco retrasado, descartado) | 127.6 | 39.0, 49.1, 42.7, 33.7, 34.9 |
| `window-drawing1` (transformación raíz Canvas, descartada) | 121.6 | 47.4, 51.4, 44.4, 34.4, 46.1 |
| `window-warmed1` (alfa de preparación variable) | 58.5 | 28.6, 33.6, 24.2, 20.4, 20.2 |
| `window-fixed1` (lo anterior + Canvas permanente en miniaturas y umbral20ms) | 125.6 | **22.8, 21.0, 19.5, 18.3, 18.8** |
| `window-matrix1` (última variante, minúsculas variaciones de matriz preparatoria) | **51.4** | 29.4, 27.7, **52.3**, 19.7, 19.0 |

- La variante `fixed1` dejó las cinco reaperturas sin intervalos >33.4 ms; medias 8.6–8.9 ms. La última `matrix1` deja una excepción de 52.3 ms, por lo que no afirmar que todo esté arreglado ni que supere siempre a fixed1.
- Los resultados iniciales en frío siguen siendo variables. Falta repetir control/candidata, comprobar reproducción activa y validar UI. No se ha repetido aún la prueba larga de CPU/RAM.
- El JAR control está preservado en `window-control1/probe.jar`; el de cada variante en su carpeta. El script actual toma `desktopApp/build/libs/desktopApp.jar`; para reproducir control, adaptar un parámetro de ruta al JAR sin sobrescribir fuentes ni archivos usados por un proceso.

### Compilación y próximos pasos concretos

- Las tandas `cached1`, `focus1`, `drawing1`, `fixed1` se compilaron con `:desktopApp:test :desktopApp:jar`; pasaron 59 tests (5 omitidos). En una ejecución intermedia falló por timeout de 5 s el test existente `DesktopDownloadsTest.removingActiveDownloadCancelsWorkBeforeDeletingWindowsFiles`; se repitió SOLO ese test sin cambiar código y pasó. La ejecución completa posterior de fixed1 pasó.
- La **última** variación matrix1 se compiló solo con `:desktopApp:jar` (BUILD SUCCESSFUL), después de la suite completa previa. Repetir la suite al decidir la variante final.
- No se ha creado un MSI de estos cambios. No reutilizar como si fuese nuevo el MSI de la tarea anterior.
- Siguiente: comparar/repetir `fixed1` y `matrix1` contra control; elegir lo que realmente mejora y descartar ajustes sin evidencia. Revisar visualmente (habilidad computer-use) mosaicos, apertura, foco/búsqueda, redimensionado y botones; probar también con audio local y letra activos.
- Después: `:desktopApp:test :desktopApp:packageMsi --offline --no-configuration-cache`; cerrar Oto desde «Salir de Oto», instalar respetando datos/firma de escritorio (reglas anteriores), verificar hash del JAR instalado y arranque. Guardar la geometría ANTES de tocar formatos; las pruebas de esta sección no han cambiado la habitual.
- Actualizar `docs/RENDIMIENTO_20261103.md` con resultados nuevos cuando se elija/instale el arreglo. Su contenido actual corresponde a la revisión anterior de consumo; no darlo por actualizado con esta sección.
- No se ha iniciado una nueva captura Windows en esta sección; no hay borde de captura pendiente de cerrar. No se han hecho commits ni push.

## Rueda suave y ahorro RAM agresivo — 04/11/2026 (instalado, sin commit)

- `SmoothWheel.kt`: EventQueue propia retiene las muescas enteras de la rueda y las reparte en pasos fraccionarios por fotograma (τ 62 ms). Compose los trata como panel táctil. Touchpad, páginas y Ctrl/Alt pasan intactos. Driver en cada ModuleWindow; la bandeja no se registra.
- `RamSaver.kt`: con ahorro, caché carátulas 3 MiB/32 entradas, Skia 4 MiB recursos y 0,5 MiB fuentes, Min/MaxHeapFreeRatio 5/15 y G1PeriodicGCInterval 20 s. Con todo oculto: limpia carátulas y Skia, System.gc y EmptyWorkingSet cada 2 min. Requiere el módulo `jdk.management` en el runtime (añadido).
- A/B con perfiles aislados `q-x20/work/ram-{off1,off3,on1,on2}` (sin cuenta ni reproducción, biblioteca+bienvenida): visible 427→371 MB residente (−13 %), privada 527→482; oculto residente 384→62 MB, privada 421→360. El residente oculto baja por el recorte; esas páginas vuelven al usarse. Sin probar con tres ventanas y audio.
- Script: `ram-ab.ps1` en el scratchpad de la sesión; lanzarlo en un `powershell` nuevo cada vez porque modifica APPDATA/LOCALAPPDATA.

