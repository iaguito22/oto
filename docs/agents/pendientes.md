# Pendientes

Índice en `AGENTS.md`. Lo que queda por hacer, de más a menos urgente. Al arreglar algo, quitarlo de aquí en el mismo commit. Detalle histórico en `docs/historico/bitacora.md`.

## Tras la 2.3.0

- CI en Linux (26/11, run 45786446979): fallan 3 tests del escritorio que en Windows pasan. `ExtrasSpaceUiTest` («el clic enciende el fondo»: ¿la fila cae en otra posición con las fuentes de Linux?), `DesktopDownloadsTest` («la descarga no empezó» en 30 s) y `DesktopDjConnectionTest.vetoDjNextUp…` (sale `[chill-2, chill-6, rock-9]`, se esperaba `[chill-2, rock-7, rock-8]`). Informes en el artefacto `informes-tests` del run.
- Rehacer la captura del README (`docs/escritorio-una-ventana.png`) con un perfil de prueba: aún sale el «+» de «Crear lista» y se ven las listas reales de Iago.
- Actualizador: la 2.3.0 es la primera que lo lleva. En Windows no está probado de verdad lo que pasa tras descargar (cerrar Oto, abrir el MSI y volver a abrir Oto): probarlo con la 2.3.1. Sí está comprobado con red: consulta, elección del archivo, descarga por la redirección de GitHub y SHA-256 (`OTO_UPDATE_LIVE=1`, `OtoUpdatesLiveTest`).

## Bugs (noche de pruebas 23/11)

Evidencia en `scratchpad/noche-2026-11-23/resultados/` (fuera de Git). Arreglados y en `main` desde el 24/11 (`6caba97`), sin oír y sin probar en la app:

- PC: al empezar una mezcla se apuntaba la canción entera (DJ normal: 25 min de más en 137). Ahora cuenta hasta la mitad del cruce.
- PC: una pista no disponible paraba la reproducción (ronda 4: 10 min). Ahora se salta; con más de 3 seguidas se para con aviso.
- DJ (selección): no superaba al aleatorio en tempo ni en tono porque solo conocía el análisis de lo ya sonado (378 de ~1.700). Ahora el PC analiza la cola del DJ por adelantado.
- DJ rápido: 16 de 48 mezclas eran fundido de respaldo porque el respaldo se adelantaba a planes que mezclaban hasta 6 s después del final del trozo.
- Logs: el `Timber` de `innertube` ignoraba `tag()` y no ponía hora.

No eran bugs: el DJ normal sale hasta 75 s antes del final porque así lo pidió Iago (`NORMAL_OUT_WINDOW_MS`); el ~90 % de `FAMILIAR` es el reparto objetivo (`SHARE_NORMAL`).

## Por comprobar

- Arreglos de la noche de pruebas del 23/11 (en `main`, sin oír ni probar en la app): respaldo que se adelantaba al plan en DJ rápido, escucha real al mezclar, saltar canciones sin audio, análisis por adelantado de la cola del DJ y log con hora y etiqueta. Repetir una tanda (`scratchpad/noche-2026-11-23/ronda.ps1` y `analizar.py`) y comparar: salto de tempo del DJ (era 17–19 %), Camelot compatible (~32 %), `Legacy` en DJ rápido (16 de 48). Además (24/11, tras «pila de fundidos normales en DJ rápido»): el DJ rápido elige y cambia en la cola por tono y bombo a la entrada, no solo por tempo; contar cuántas mezclas del DJ rápido salen «a tempo» (simulación: 3 % al azar, 25 % solo por tempo, 47 % con las tres).

- Arreglos tras la escucha del 24/11 (Iago valoró ~45 transiciones de DJ rápido, normal, radio y lista; notas y log en `scratchpad/escucha-2026-11-24/`). En `main`, con tests, sin oír: corte en drop que no empezaba (Battlecry → Energy) y pantalla que se quedaba en la anterior; tempo casi obligatorio al elegir en el DJ; en DJ rápido, salida antes del estribillo si llena la ventana; salidas sin cortar la letra y entradas donde B no canta; radio del PC reordenada por tempo; menos canciones de otro estilo dentro de una tanda. Repetir la escucha y contar: mezclas «a tempo»/«cuadre» (eran 1 de 18 en DJ normal), «FUERA de frase» en DJ rápido, respaldos (4 de 12 en rápido, 3 de 6 en radio) y salidas/entradas en letra. El log nuevo dice por qué salió cada canción («[dj] elegida»).
- Arreglos tras la escucha del 25/11 (log en `scratchpad/escucha-2026-11-25/`; en `main`, con tests y sonda, sin oír). Iago: «el DJ cambia mucho de género», «entradas o salidas en letra, creo que está peor», «en DJ rápido no arreglamos nada», «el corte en drop se ejecuta mal», «siguen los cambios en estribillos o en mitad de letra», «la radio del móvil igual que el PC». Hecho: voz obligatoria al planificar (sonda: A cantando al irse 61 → 9 % rápido, 47 → 6 % normal; B a mitad de frase 31 → 10 % y 23 → 4 %), corte con eco al acabar un bloque cuando no hay hueco, fin real de la última línea de LRCLIB, estilo antes que tempo al elegir, corte en drop de verdad en el PC (corte en el pico, eco y reverb, B tras el silencio), radio del móvil reordenada como el PC, intensidad de la entrada medida donde B ya manda. Repetir la escucha y contar: «corta la voz (no había otra)», «Corte en drop … B sonó a los … ms», cuántos «Enlace al final» y cuántos cortes con eco seguidos (variedad). La intensidad de la sonda no mejoró: mirarla con lo que diga Iago.
- «Diles» → «Ram Pam Pam» (Android): causa sin confirmar; mirar `oto.mix` si vuelve.
- PC: «el bucle no funciona» (24/11, desde una lista). Con audio real (`DesktopLoopAudioTest`, `OTO_AUDIO_INTEGRATION=1`; silencio, y música con el volumen de Oto a 0) funcionan repetir la lista y la canción, con fundido y sin él, en `main` y en la 2.2.0. Lo único que falla en la 2.2.0: «Siguiente» en la última con «Repetir la lista» pone una radio en vez de volver a la primera. En `main` vuelve (ahora explícito en `next()`). Si pasa con la 2.3.0, pedir cómo.
- Enlace: al abrir el PC salía siempre «sonando en el móvil» con la última canción del móvil aunque no sonara (25/11). Arreglado en `RemoteNow.follow` (`RemoteMirrorTest`, `LinkedPlaybackTest`); sin probar con el móvil real.
- Enlace: «si cambias, la cola vuelve a empezar» (24/11). Causa probable, arreglada: con el PC mostrando el móvil, el panel «Cola» listaba la cola del móvil desde la primera canción (ya sonadas incluidas) y no desde la que suena (`remoteUpcoming`, `RemoteQueueTest`). En el código, el traspaso (índice, posición, cola manual) es correcto; sin probar con el móvil real.
- Luces: en el PC de Iago, `%APPDATA%\oto\laboratorio-visual` es un enlace (junction) a `scratchpad/laboratorio-visual` para que la fila salga en Extras. Si se mueve el laboratorio, rehacer el enlace.
- Fondo vivo: espectro nuevo (graves con FFT de 8192 y menos barras por nota; agudos compensados 6 dB por octava solo por encima de 1 kHz; punto muerto del 20 %, 24/11 tras «el bajo sube poco y los agudos casi siempre activos»). Probado con señales; falta que Iago lo vea con música.
- Escucha de las mezclas del DJ en general (el agente no oye). Confirmado por Iago el 19/11: «Faint» va a tiempo y no hay distorsión tras un cuadre de beats.

## Mejoras

- CI más rápida: probar solo los módulos cambiados (como `tools/check`) y no instalar FFmpeg con apt.
- Vincular el móvil habitual con el PC (Dispositivos/QR) y comprobar la sincronización con los perfiles reales; probado solo con datos aislados.
- Medir el ahorro de RAM con tres ventanas y audio.
- Duplicados de YouTube: quedan grupos sin auditar (`../playlist-audit.json`). No borrar solo por nombre.
- MSI y lanzador sin firma Authenticode: no prometer que no salga SmartScreen.
- Idea (22/11, sin empezar): llevar a Oto la barra del laboratorio visual (`scratchpad/laboratorio-visual`): medidores por instrumento y mapa de la canción con secciones, subidas, drops y respiros. A Iago le sirve para ver «cómo piensa» el análisis; quizá útil para revisar las mezclas del DJ.
- Fondo de escritorio vivo (23/11): con los valores por defecto (barras en anillo, 120 fps, sin partículas) gasta ~0,5 núcleos y ~32 % de la gráfica integrada (a 30 fps, ~0,17 y ~14 %) y ~300 MB más de RAM (casi todo la propia ventana a pantalla completa); con el escritorio tapado, 0. Bajar la RAM (el fondo de pantalla ya se guarda al tamaño del monitor). Falta: «Calidad» (resolución), varios monitores probados de verdad y que Iago lo oiga. 24/11: cada 10-20 s hay un cuadro de ~0,2 s que no sale del fondo (su cálculo y dibujo < 15 ms; ni la letra ni la consulta del fondo de pantalla): mirar con JFR si es la ventana principal. Clics de la hora/controles probados con `WidgetInput` pero no con el ratón de verdad.
