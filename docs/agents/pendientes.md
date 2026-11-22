# Pendientes

Índice en `AGENTS.md`. Lo que queda por hacer, de más a menos urgente. Al arreglar algo, quitarlo de aquí en el mismo commit. Detalle histórico en `docs/historico/bitacora.md`.

## Próxima release

- Será una versión nueva (2.2.1 o superior), no sustituir los archivos de la 2.2.0: quien ya la instaló no actualizaría.
- Rehacer la captura del README (`docs/escritorio-una-ventana.png`) con un perfil de prueba: aún sale el «+» de «Crear lista» y se ven las listas reales de Iago.
- Rebasar la rama `android12` sobre `main` antes de compilar la variante.

## Por comprobar

- «Diles» → «Ram Pam Pam» (Android): causa sin confirmar; mirar `oto.mix` si vuelve.
- CI `Tests` en Linux: aún no ha pasado entera (fallos de red de GitHub). La prueba de cancelación de descargas tiene márgenes de 30 s; ver si aguanta.
- Escucha de las mezclas del DJ en general (el agente no oye). Confirmado por Iago el 19/11: «Faint» va a tiempo y no hay distorsión tras un cuadre de beats.

## Mejoras

- CI más rápida: probar solo los módulos cambiados (como `tools/check`) y no instalar FFmpeg con apt.
- Vincular el móvil habitual con el PC (Dispositivos/QR) y comprobar la sincronización con los perfiles reales; probado solo con datos aislados.
- Medir el ahorro de RAM con tres ventanas y audio.
- Duplicados de YouTube: quedan grupos sin auditar (`../playlist-audit.json`). No borrar solo por nombre.
- MSI y lanzador sin firma Authenticode: no prometer que no salga SmartScreen.
