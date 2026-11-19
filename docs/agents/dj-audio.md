# DJ, mezclas y audio

Índice en `AGENTS.md`. Reglas sacadas de la bitácora de 16–19/11/2026 (`docs/historico/bitacora.md` tiene el detalle y las mediciones). Contrastar con el código.

## Dónde está

- Lógica común en `core/.../dj/`: análisis (`AudioAnalysis`, `SongSections`, `PhraseMap`), planificación (`DjBridgePlanner`, `ChorusSafety`, `DjTempoMatch`), render de puentes (`DjBridgeRenderer`, `DjDsp`), elección de canciones (`Dj`, `Smart`, `Genres`, `Memory`).
- PC: `DesktopPlayerConnection` (fundido en vivo, `watchCrossfade`, ganancias) y `DesktopDjPlayback`. Android: `playback/Crossfade.kt` (puentes WAV pre-renderizados).
- Arnés de pruebas: `core/src/test/.../dj/DjHarness.kt`.

## Reglas

- **El agente no oye.** Medir por señal y logs, y dejar los cambios como «sin oír» hasta que Iago lo confirme con auriculares.
- **Ganancia nunca por encima de 1** al acabar una mezcla (`incomingMixGain`, `mixInGain`). Dejar margen antes de PCM16; sin recorte en niveles normales (`softClip` lineal hasta 0,89).
- **El tempo vuelve a 1×.** En el PC, `cancelCrossfade()` también se usa para preparar la siguiente mezcla: no debe cancelar `tempoReturnJob` (solo `startCurrentTrack`), y el `finally` de la vuelta deja siempre 1×. Sin test de integración todavía; `DjTailAfterTest` es de la cola del DJ, no de esto.
- **Proteger estribillos y letra:** no salir ni entrar en mitad de una frase cantada. Si no hay hueco, mejor sin mezcla que un corte malo.
- **Género con continuidad:** la elección mira el género del destino, y una racha larga lo relaja poco a poco.
- **No ajustar pesos de estilos** sin antes medir una tanda y comparar con la anterior.
- PC y Android planifican distinto: Android usa puentes WAV pre-renderizados (`DjBridgePlanner` + `DjBridgeRenderer`); el PC mezcla en vivo con `DjTransitionPlanner` y WSOLA. Comparten análisis, secciones y protección de estribillos. Un arreglo en `core` se comprueba en las dos.
- **La cola no se queda en «Preparando».** Si la siguiente está preparada y su plan no mezcla (fundido 0 o enlace al final), no se anuncia nada (`nextMixState` en el PC; `MixUiState.None` en Android).

## Letras

- Fuentes en `core/.../lyrics/LyricsSource.kt`; cada aparato tiene su caché (PC: `%LOCALAPPDATA%\oto\Cache\lyrics`). Subir `LyricsStore.MATCH_VERSION` invalida lo guardado.
- LRCLIB: las letras las sube cualquiera y la exacta puede ir desplazada. `pickLrclib` usa el consenso de las versiones (medido: «Faint», exacta 1,5 s tarde). Si Iago dice que una letra va atrasada o adelantada, mirar primero la caché y comparar versiones en `lrclib.net/api/search` antes de tocar el reloj.
- El reloj del PC (`MobileLyrics.kt`) sigue la posición del motor, que ya descuenta el búfer de Java Sound; no incluye la latencia del dispositivo de salida.

## Diagnóstico

- `oto.mix` (log de mezclas): «análisis:», «aviso: … none (motivo)», «fallo en», «cancelado en», «se conserva para reintentar».
- `plays.jsonl`: qué sonó y cuándo; sirve para localizar la pareja de la que se queja Iago.
- Tandas de validación en el móvil: evidencia en `scratchpad/<tema>/INFORME.md` y `transiciones.csv` (fuera de Git). Contar por modo (rápido, normal, lista) y tipo de transición.
- Mezclas de prueba con derechos en `app/src/debug/assets/`: nunca subirlas.

## Cada bug de mezcla

1. Localizar la pareja y el instante en `plays.jsonl` y `oto.mix`.
2. Reproducirla en un test de `core` (o del PC) que falle.
3. Arreglar, pasar `tools/check.sh` y, si afecta al sonido, pedir a Iago que lo escuche.
