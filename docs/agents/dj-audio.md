# DJ, mezclas y audio

Índice en `AGENTS.md`. Reglas sacadas de la bitácora de 16–19/11/2026 (`docs/historico/bitacora.md` tiene el detalle y las mediciones). Contrastar con el código.

## Dónde está

- Lógica común en `core/.../dj/`: análisis (`AudioAnalysis`, `SongSections`, `PhraseMap`), planificación (`DjBridgePlanner`, `ChorusSafety`, `DjTempoMatch`), render de puentes (`DjBridgeRenderer`, `DjDsp`), elección de canciones (`Dj`, `Smart`, `Genres`, `Memory`).
- PC: `DesktopPlayerConnection` (fundido en vivo, `watchCrossfade`, ganancias) y `DesktopDjPlayback`. Android: `playback/Crossfade.kt` (puentes WAV pre-renderizados).
- Arnés de pruebas: `core/src/test/.../dj/DjHarness.kt`. Estilos del DJ rápido sin abrir Oto: `AdhdStyleProbeTest` con una copia de `%LOCALAPPDATA%\oto\Cache\dj` y `Cache\lyrics` en `scratchpad/` (`OTO_PROBE_CACHE`, `OTO_PROBE_LYRICS`).

## Reglas

- **El agente no oye.** Medir por señal y logs, y dejar los cambios como «sin oír» hasta que Iago lo confirme con auriculares. Confirmado por Iago (19/11): tras un cuadre de beats la entrante ya no pierde calidad en el PC.
- **Ganancia nunca por encima de 1** al acabar una mezcla (`incomingMixGain`, `mixInGain`). Dejar margen antes de PCM16; sin recorte en niveles normales (`softClip` lineal hasta 0,89).
- **El tempo vuelve a 1×.** En el PC, `cancelCrossfade()` también se usa para preparar la siguiente mezcla: no debe cancelar `tempoReturnJob` (solo `startCurrentTrack`), y el `finally` de la vuelta deja siempre 1×. Sin test de integración todavía; `DjTailAfterTest` es de la cola del DJ, no de esto.
- **Proteger estribillos y letra:** los planes del DJ evitan salir o entrar en mitad de una frase cantada. Si el plan no se puede ejecutar, se intenta un fundido sencillo cerca de la salida, con prioridad para un hueco fuera del estribillo. Ese respaldo nunca se adelanta a un plan que mezcla y aún no ha llegado a su momento (en DJ rápido el plan puede pasarse 6 s del final del trozo): solo entra si el momento pasa sin poder empezar (`DesktopTransitionPlanner.withFallback`).
- **DJ rápido: elegir lo que se puede cuadrar.** Mezcla cada minuto y pico, así que la elección (`Dj.tempoFit`) y la revisión de la cola (`retuneUpcomingTempo`) miran, además del tempo, el tono (armonía < `DjTempoMatch.MIN_BEAT_MATCH_HARMONY`, la misma puerta del puente) y el bombo los 8 s desde donde entra la siguiente (`fastMixBlock`). Con las canciones de Iago (24/11, `AdhdStyleProbeTest`): al azar cuadraba el 3 %, solo por tempo el 25 %, con las tres el 47 %. El DJ normal no lo mira. Lo que queda: ~9 % sin mezcla porque el estribillo de A cubre toda la ventana de salida (respaldo de 3 s).
- **El DJ del PC analiza la cola por adelantado** (`DjScout`, como el móvil al precargar): sin tempo, tono ni volumen de las candidatas elegía a ciegas. Una a la vez, como mucho 3 descargas por canción que suena, nunca la siguiente y nada mientras YouTube falle.
- **Lo escuchado es lo que sonó.** Al empezar una mezcla, la saliente cuenta hasta la mitad del cruce (`mixOutListenedMs`), no entera; el móvil apunta la posición al cambiar.
- **Canción sin audio** («This track is unavailable»): se salta sin contarla como salto; la mezcla va a la siguiente que suena. Con más de 3 seguidas se para con aviso: es YouTube, no la canción (`UnplayableTracks`).
- **Género con continuidad:** la elección mira el género del destino, y una racha larga lo relaja poco a poco.
- **No ajustar pesos de estilos** sin antes medir una tanda y comparar con la anterior.
- PC y Android planifican distinto: Android usa puentes WAV pre-renderizados (`DjBridgePlanner` + `DjBridgeRenderer`); el PC mezcla en vivo con `DjTransitionPlanner` y WSOLA. Comparten análisis, secciones y protección de estribillos. Un arreglo en `core` se comprueba en las dos.
- **La cola no se queda en «Preparando».** Si la siguiente está preparada y su plan no mezcla (fundido 0 o enlace al final), no se anuncia nada (`nextMixState` en el PC; `MixUiState.None` en Android).

## Letras

- Fuentes en `core/.../lyrics/LyricsSource.kt`; cada aparato tiene su caché (PC: `%LOCALAPPDATA%\oto\Cache\lyrics`). Subir `LyricsStore.MATCH_VERSION` invalida lo guardado.
- LRCLIB: las letras las sube cualquiera y la exacta puede ir desplazada. `pickLrclib` usa el consenso de las versiones (medido: «Faint», exacta 1,5 s tarde). Si Iago dice que una letra va atrasada o adelantada, mirar primero la caché y comparar versiones en `lrclib.net/api/search` antes de tocar el reloj.
- El reloj del PC (`MobileLyrics.kt`) sigue la posición del motor, que ya descuenta el búfer de Java Sound; no incluye la latencia del dispositivo de salida.

## Diagnóstico

- `oto.mix` (log de mezclas): «análisis:», «aviso: … none (motivo)», «fallo en», «cancelado en», «se conserva para reintentar». En el PC: «Fundido de respaldo: A → B (… ms) a … ms: motivo», «DJ: analizada por adelantado», «… no está disponible: se salta».
- Consola del PC: solo existe si se lanza con la salida redirigida; cada línea lleva hora (`HH:mm:ss.SSS`) y `[etiqueta]` (el `Timber` propio de `innertube`, que antes ignoraba `tag()`).
- `plays.jsonl`: qué sonó y cuándo; sirve para localizar la pareja de la que se queja Iago.
- Tandas de validación en el móvil: evidencia en `scratchpad/<tema>/INFORME.md` y `transiciones.csv` (fuera de Git). Contar por modo (rápido, normal, lista) y tipo de transición.
- Mezclas de prueba con derechos en `app/src/debug/assets/`: nunca subirlas.

## Cada bug de mezcla

1. Localizar la pareja y el instante en `plays.jsonl` y `oto.mix`.
2. Reproducirla en un test de `core` (o del PC) que falle.
3. Arreglar, pasar `tools/check.sh` y, si afecta al sonido, pedir a Iago que lo escuche.
