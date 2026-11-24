# Pendientes

Índice en `AGENTS.md`. Lo que queda por hacer, de más a menos urgente. Al arreglar algo, quitarlo de aquí en el mismo commit. Detalle histórico en `docs/historico/bitacora.md`.

## Próxima release

- Será una versión nueva (2.2.1 o superior), no sustituir los archivos de la 2.2.0: quien ya la instaló no actualizaría.
- Rehacer la captura del README (`docs/escritorio-una-ventana.png`) con un perfil de prueba: aún sale el «+» de «Crear lista» y se ven las listas reales de Iago.
- Rebasar la rama `android12` sobre `main` antes de compilar la variante.

## Bugs (noche de pruebas 23/11)

Evidencia en `scratchpad/noche-2026-11-23/resultados/` (fuera de Git). Arreglados en la rama `fix/noche-23-11` (sale de `integracion`, worktree `../oto-noche`), sin unir, sin oír y sin probar en la app:

- PC: al empezar una mezcla se apuntaba la canción entera (DJ normal: 25 min de más en 137). Ahora cuenta hasta la mitad del cruce.
- PC: una pista no disponible paraba la reproducción (ronda 4: 10 min). Ahora se salta; con más de 3 seguidas se para con aviso.
- DJ (selección): no superaba al aleatorio en tempo ni en tono porque solo conocía el análisis de lo ya sonado (378 de ~1.700). Ahora el PC analiza la cola del DJ por adelantado.
- DJ rápido: 16 de 48 mezclas eran fundido de respaldo porque el respaldo se adelantaba a planes que mezclaban hasta 6 s después del final del trozo.
- Logs: el `Timber` de `innertube` ignoraba `tag()` y no ponía hora.

No eran bugs: el DJ normal sale hasta 75 s antes del final porque así lo pidió Iago (`NORMAL_OUT_WINDOW_MS`); el ~90 % de `FAMILIAR` es el reparto objetivo (`SHARE_NORMAL`).

## Por comprobar

- Arreglos de la noche de pruebas del 23/11 (rama `fix/noche-23-11`, sin oír ni probar en la app): respaldo que se adelantaba al plan en DJ rápido, escucha real al mezclar, saltar canciones sin audio, análisis por adelantado de la cola del DJ y log con hora y etiqueta. Repetir una tanda (`scratchpad/noche-2026-11-23/ronda.ps1` y `analizar.py`) y comparar: salto de tempo del DJ (era 17–19 %), Camelot compatible (~32 %), `Legacy` en DJ rápido (16 de 48). Además (24/11, tras «pila de fundidos normales en DJ rápido»): el DJ rápido elige y cambia en la cola por tono y bombo a la entrada, no solo por tempo; contar cuántas mezclas del DJ rápido salen «a tempo» (simulación: 3 % al azar, 25 % solo por tempo, 47 % con las tres).

- «Diles» → «Ram Pam Pam» (Android): causa sin confirmar; mirar `oto.mix` si vuelve.
- CI `Tests` en Linux: aún no ha pasado entera (fallos de red de GitHub). La prueba de cancelación de descargas tiene márgenes de 30 s; ver si aguanta.
- Escucha de las mezclas del DJ en general (el agente no oye). Confirmado por Iago el 19/11: «Faint» va a tiempo y no hay distorsión tras un cuadre de beats.

## Mejoras

- CI más rápida: probar solo los módulos cambiados (como `tools/check`) y no instalar FFmpeg con apt.
- Vincular el móvil habitual con el PC (Dispositivos/QR) y comprobar la sincronización con los perfiles reales; probado solo con datos aislados.
- Medir el ahorro de RAM con tres ventanas y audio.
- Duplicados de YouTube: quedan grupos sin auditar (`../playlist-audit.json`). No borrar solo por nombre.
- MSI y lanzador sin firma Authenticode: no prometer que no salga SmartScreen.
- Idea (22/11, sin empezar): llevar a Oto la barra del laboratorio visual (`scratchpad/laboratorio-visual`): medidores por instrumento y mapa de la canción con secciones, subidas, drops y respiros. A Iago le sirve para ver «cómo piensa» el análisis; quizá útil para revisar las mezclas del DJ.
- Fondo de escritorio vivo (23/11): con los valores por defecto (barras en anillo, 120 fps, sin partículas) gasta ~0,5 núcleos y ~32 % de la gráfica integrada (a 30 fps, ~0,17 y ~14 %) y ~300 MB más de RAM (casi todo la propia ventana a pantalla completa); con el escritorio tapado, 0. Bajar la RAM (el fondo de pantalla ya se guarda al tamaño del monitor). Falta: «Calidad» (resolución), varios monitores probados de verdad y que Iago lo oiga. 24/11: cada 10-20 s hay un cuadro de ~0,2 s que no sale del fondo (su cálculo y dibujo < 15 ms; ni la letra ni la consulta del fondo de pantalla): mirar con JFR si es la ventana principal. Clics de la hora/controles probados con `WidgetInput` pero no con el ratón de verdad.
