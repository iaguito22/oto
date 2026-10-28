# PLAN — Oto Desktop alineado con Oto móvil

Decisiones tomadas con el usuario (28/10/2026):

- **Alcance:** todo lo que tenga sentido: visual, organización, opacidades, tipografía, ventanas, ajustes, lógica y funciones.
- **Referencia:** `PROYECTOS/oto` (v1.2.2). `oto-kmp/app` va unos commits por detrás y **no se toca** por ahora; los repos se juntarán más tarde.
- **Escenario:** se conserva la distribución de la app instalada (carátula cuadrada redondeada, título/artista debajo, letra a la derecha, barra superior de origen y tiempo/calidad). Nada de corona circular.
- **Colores:** paleta neutra del móvil (`#0B0B0B`, blancos con alfa `.62/.36`) y **un solo acento** extraído de la carátula. Fuera morado/melocotón/azul fijos.
- **Tipografía:** Rubik + escala `OtoType` exacta. Fuera Plus Jakarta y los tamaños M3 por defecto.
- **Menú:** hoja flotante grande con la estructura móvil exacta (pestañas arriba, descargas + ajustes, buscador arriba, en la cabecera, navegación dentro de la hoja, avisos con deshacer). Ancho adaptativo.
- **Cola:** panel inferior que se superpone al escenario y se cierra con Escape.
- **Letra:** panel fijo a la derecha (no hoja).
- **Interacción:** se mantienen los atajos y el hover actuales; no se copian los gestos móviles.
- **Ajustes:** lista única en el orden del móvil, adaptando «Espacio en el móvil» a disco y sin tutorial de gestos.
- **Iconos:** copiar los vectoriales del móvil.
- **Login:** navegador externo, pero arreglado: Chrome temporal + CDP (lee cookies HttpOnly y `yt.config_`, cierra Chrome solo). Fallback a extensión/consola.
- **Descargas:** reales, en carpeta aparte, con reproducción sin red.
- **Prioridad:** funciones primero (login, descargas, DJ, carpetas, importación), después el acabado visual.
- **Proceso:** fases con commits en `main`, compilando y probando en cada una. Verificación con `:desktopApp:run` + capturas (`grim`).

## Fases

1. **Login** — Chrome temporal + CDP; UI de cuenta estilo móvil; fallback.
2. **Descargas** — gestor JVM, estados, carpeta `downloads`, reproducción offline-first, UI y ajustes.
3. **Biblioteca** — carpetas, fijadas, orden (spots), historial, DJ/Smart/radio, importación y cuenta con paridad móvil.
4. **Tokens** — `DesktopTokens.kt` (Radius/Space/Motion/OtoType), colores neutros, acento de carátula, blur de fondo, iconos.
5. **Escenario** — carátula cuadrada redondeada, cabecera, tiempo/calidad, letra karaoke, controles hover, OSD.
6. **Menú** — hoja grande adaptativa con la estructura móvil y todas las vistas.
7. **Cola** — panel inferior superpuesto con mover/quitar/aleatorio/bucle.
8. **Ajustes** — lista móvil adaptada + espacio en disco + atajos.
9. **Empaquetado** — `.deb`/AppImage y prueba final.

## Reglas

- No tocar `/home/iago/PROYECTOS/oto` ni la UI de `oto-kmp/app`.
- `:core` puede cambiar si `:app` sigue compilando y los tests pasan.
- Cada fase: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:test :app:compileDebugKotlin :desktopApp:compileKotlin`.
- Commits `tipo: resumen` en español, email noreply.

## Estado (29/10/2026)

Hechas y probadas en ejecución, con un commit por fase:

1. Login con Chrome temporal + CDP.
2. Descargas reales (reanudables, `.part`, índice, reproducción sin red).
3. Carpetas, fijadas, «Mover a carpeta…» y renombrar con deshacer.
4. Tokens neutros y `OtoType`; acento único de la carátula.
6. Menú móvil con buscador arriba y avisos con deshacer.
7. Cola superpuesta que se cierra con Escape o clic fuera.
8. Ajustes en orden móvil y «Espacio en el disco».
9. Iconos vectoriales del móvil (`MobileIcons`, leídos de los XML copiados).
10. Empaquetado Linux con `desktopApp/package-linux.sh`.

Pendiente de acabado visual: extender `OtoType`/`Radius`/`Space`/`Motion` a descargas,
detalle de lista y ajustes.

## Empaquetar en Linux

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./desktopApp/package-linux.sh
```

Deja `desktopApp/build/dist/Oto-<versión>-x86_64.AppImage` y
`desktopApp/build/dist/oto_<versión>_amd64.deb`. En Arch no hay `dpkg-deb`, así que
jpackage no puede crear el `.deb`; el script lo arma con `ar` + `tar` (mismo formato).
El AppImage necesita `appimagetool`, que el script descarga si no está.
