# Pendientes

Índice en `AGENTS.md`. Lo que queda por hacer, de más a menos urgente. Al arreglar algo, quitarlo de aquí en el mismo commit. Detalle histórico en `docs/historico/bitacora.md`.

## Próxima release

- Será una versión nueva (2.2.1 o superior), no sustituir los archivos de la 2.2.0: quien ya la instaló no actualizaría.
- Rehacer la captura del README (`docs/escritorio-una-ventana.png`) con un perfil de prueba: aún sale el «+» de «Crear lista» y se ven las listas reales de Iago.
- Rebasar la rama `android12` sobre `main` antes de compilar la variante.

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
