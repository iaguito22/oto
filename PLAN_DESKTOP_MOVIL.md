# PLAN — Oto Desktop alineado con Oto móvil

Decisiones tomadas con el usuario (28/10/2026):

- **Alcance:** todo lo que tenga sentido: visual, organización, opacidades, tipografía, ventanas, ajustes, lógica y funciones.
- **Referencia:** `PROYECTOS/oto` (v1.2.2). `oto-kmp/app` va unos commits por detrás y **no se toca** por ahora; los repos se juntarán más tarde.
- **Escenario:** se conserva la distribución de la app instalada (carátula cuadrada redondeada, título/artista debajo, letra a la derecha, barra superior de origen y tiempo/calidad). Nada de corona circular.
- **Colores:** paleta neutra del móvil (`#0B0B0B`, blancos con alfa `.62/.36`) y **un solo acento** extraído de la carátula. Fuera morado/melocotón/azul fijos.
- **Tipografía:** Rubik + escala `OtoType` exacta. Fuera Plus Jakarta y los tamaños M3 por defecto.
- **Menú:** hoja flotante grande con la estructura móvil exacta (pestañas arriba, descargas + ajustes, buscador abajo, navegación dentro de la hoja, avisos con deshacer). Ancho adaptativo.
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
