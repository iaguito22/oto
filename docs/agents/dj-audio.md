# DJ, mezclas y audio

Índice en `AGENTS.md`. Reglas sacadas de la bitácora de 16–19/11/2026 (`docs/historico/bitacora.md` tiene el detalle y las mediciones). Contrastar con el código.

## Dónde está

- Lógica común en `core/.../dj/`: análisis (`AudioAnalysis`, `SongSections`, `PhraseMap`), planificación (`DjBridgePlanner`, `ChorusSafety`, `DjTempoMatch`), render de puentes (`DjBridgeRenderer`, `DjDsp`), elección de canciones (`Dj`, `Smart`, `Genres`, `Memory`).
- PC: `DesktopPlayerConnection` (fundido en vivo, `watchCrossfade`, ganancias) y `DesktopDjPlayback`. Android: `playback/Crossfade.kt` (puentes WAV pre-renderizados).
- Arnés de pruebas: `core/src/test/.../dj/DjHarness.kt`. Estilos del DJ rápido sin abrir Oto: `AdhdStyleProbeTest` con una copia de `%LOCALAPPDATA%\oto\Cache\dj` y `Cache\lyrics` en `scratchpad/` (`OTO_PROBE_CACHE`, `OTO_PROBE_LYRICS`). Calidad de las mezclas (salidas cantando, entradas a mitad de frase, estribillos, intensidad, respaldos) en DJ rápido y normal: `MixQualityProbeTest` con las mismas variables; `DjBridgePlanner.trace` dice por qué salió cada plan. Para comparar versiones, la misma sonda en un worktree de la versión anterior.

## Reglas

- **El agente no oye.** Medir por señal y logs, y dejar los cambios como «sin oír» hasta que Iago lo confirme con auriculares. Confirmado por Iago (19/11): tras un cuadre de beats la entrante ya no pierde calidad en el PC.
- **Ganancia nunca por encima de 1** al acabar una mezcla (`incomingMixGain`, `mixInGain`). Dejar margen antes de PCM16; sin recorte en niveles normales (`softClip` lineal hasta 0,89).
- **El tempo vuelve a 1×.** En el PC, `cancelCrossfade()` también se usa para preparar la siguiente mezcla: no debe cancelar `tempoReturnJob` (solo `startCurrentTrack`), y el `finally` de la vuelta deja siempre 1×. Sin test de integración todavía; `DjTailAfterTest` es de la cola del DJ, no de esto.
- **Proteger estribillos y letra:** el estribillo y la voz son obligatorios en todos los modos del DJ (25/11). Una mezcla en la que A canta mientras aún se oye (`audibleShareA`: 45 % de la mezcla con filtro, 60 % fundido, 75 % cuadre) o B canta antes de mandar (`B_QUIET_SHARE`, salvo que entre justo al empezar una frase) va al cubo relajado y solo gana si no hay otra cosa. La voz se mira por las palabras (`DjBridgePlanner.Voice`): en LRCLIB una línea dura hasta la siguiente. La mezcla se acorta para caber en el hueco sin voz (`voiceFit`). Orden: limpia > relajada sin cortar voz > respaldo sin voz > corte con eco al acabar un bloque > B entra cantando > enlace al final > A canta al irse > respaldo de siempre. Sonda con las canciones de Iago (400 mezclas por modo, antes → después): A cantando al irse 61 → 9 % (rápido) y 47 → 6 % (normal); B a mitad de frase 31 → 10 % y 23 → 4 %. Sin oír.
- **Corte con eco al acabar un bloque:** sin ningún hueco de voz en la ventana (estribillos repetidos hasta el final), `EnergyDropCut` justo tras la última palabra de un bloque: A se calla, queda su eco y reverb y entra B (por un sitio sin voz y con intensidad parecida). Sin esto ~1/3 del DJ normal era «Enlace al final» y ~1/6 del rápido «sin mezcla».
- **Fin de la voz:** con tiempos estimados, una línea acaba como mucho 1 s tras su última palabra (`SongSections.sungEnd`); la última de LRCLIB duraba hasta el final de la canción y su estribillo tapaba el outro.
- El respaldo nunca se adelanta a un plan que mezcla y aún no ha llegado a su momento (en DJ rápido el plan puede pasarse 6 s del final del trozo): solo entra si el momento pasa sin poder empezar (`DesktopTransitionPlanner.withFallback`).
- **DJ rápido: elegir lo que se puede cuadrar.** Mezcla cada minuto y pico, así que la elección (`Dj.tempoFit`) y la revisión de la cola (`retuneUpcomingTempo`) miran, además del tempo, el tono (armonía < `DjTempoMatch.MIN_BEAT_MATCH_HARMONY`, la misma puerta del puente) y el bombo los 8 s desde donde entra la siguiente (`fastMixBlock`). Con las canciones de Iago (24/11, `AdhdStyleProbeTest`): al azar cuadraba el 3 %, solo por tempo el 25 %, con las tres el 47 %. El tempo es casi obligatorio en los dos modos (`Dj.tempoGate`), pero **después del estilo** (25/11, `styleThenTempo`): solo entre las candidatas que pesan al menos un 35 % de la mejor (género, ánimo, energía, continuidad). Antes filtraba primero y, si solo encajaba en tempo una de otro estilo, salía esa («el DJ cambia mucho de género»). Dentro de la tanda, un artista de otro estilo que su centro pesa ×0,15 (`looseArtistGenreFit`). Si el estribillo de A cubre toda la ventana de salida del trozo, se sale antes (`ADHD_EARLY_EXIT_MIN_LISTEN_MS`), solo donde A no canta.
- **Corte en drop en el PC (25/11):** ya no es un fundido. `DropCutScript` programa cambios en ms exactos de cada canción (`BandsCue`, aplicados por el motor en el bloque de audio que los contiene): A suena hasta el pico, se corta y queda su eco a pulso y una reverb (`SmallRoom`) que se apagan antes de B; B arranca a tiempo (mide cuánto tarda en sonar) y entra entera tras un riser corto. Sin oír.
- **El DJ del PC analiza la cola por adelantado** (`DjScout`, como el móvil al precargar): sin tempo, tono ni volumen de las candidatas elegía a ciegas. Una a la vez, como mucho 3 descargas por canción que suena, nunca la siguiente y nada mientras YouTube falle.
- **La radio** se reordena por tempo y tono (`core/.../dj/RadioTempoOrder`, la misma en los dos): de las 4 siguientes (nunca la siguiente ni lo que pusiste tú) se adelanta la que encaja con la anterior; sin tempos medidos no cambia nada. El PC analiza la radio por adelantado; el móvil reordena cuando su precarga termina de analizar (y al rellenar la radio), y no con el aleatorio puesto.
- **Estribillo y mezcla en vivo**: solo cuenta lo que aún suena de A (`ChorusSafety.outgoingSpanMs`: en un puente, hasta su corte). Con el cruce entero, un corte en drop justo antes del estribillo no empezaba nunca en el PC (Battlecry → Energy, 24/11).
- **Lo escuchado es lo que sonó.** Al empezar una mezcla, la saliente cuenta hasta la mitad del cruce (`mixOutListenedMs`), no entera; el móvil apunta la posición al cambiar.
- **Canción sin audio** («This track is unavailable»): se salta sin contarla como salto; la mezcla va a la siguiente que suena. Con más de 3 seguidas se para con aviso: es YouTube, no la canción (`UnplayableTracks`).
- **Género con continuidad:** la elección mira el género del destino, y una racha larga lo relaja poco a poco.
- **No ajustar pesos de estilos** sin antes medir una tanda y comparar con la anterior.
- PC y Android planifican distinto: Android usa puentes WAV pre-renderizados (`DjBridgePlanner` + `DjBridgeRenderer`); el PC mezcla en vivo con `DjTransitionPlanner` y WSOLA. Comparten análisis, secciones y protección de estribillos. Un arreglo en `core` se comprueba en las dos.
- **La cola no se queda en «Preparando».** Si la siguiente está preparada y su plan no mezcla (fundido 0 o enlace al final), no se anuncia nada (`nextMixState` en el PC; `MixUiState.None` en Android).

## Letras

- Fuentes en `core/.../lyrics/LyricsSource.kt`; cada aparato tiene su caché (PC: `%LOCALAPPDATA%\oto\Cache\lyrics`). Subir `LyricsStore.MATCH_VERSION` invalida lo guardado.
- LRCLIB: las letras las sube cualquiera y la exacta puede ir desplazada. `pickLrclib` usa el consenso de las versiones (medido: «Faint», exacta 1,5 s tarde). Si Iago dice que una letra va atrasada o adelantada, mirar primero la caché y comparar versiones en `lrclib.net/api/search` antes de tocar el reloj.
- Letras palabra a palabra desplazadas (Iago, 25/11): hay letras hechas para otra versión del audio (en el audio de YouTube, «In the End» va 1,1 s tarde y «Can You Feel My Heart» 0,9 s antes, con NetEase y LRCLIB de acuerdo entre sí). Ninguna fuente lo sabe. Medirlo contra el audio (correlación de la voz centrada con los inicios de palabra) acertó esas dos pero se equivocó en 2 de 4 comprobadas («bloodline», «Aurora»): no usarlo sin algo mejor. Por eso hay ajuste a mano por canción («Antes» / «Después» al final de la letra, `LyricsStore.offsets`, en `desfases.json` de la caché de letras), que usan la letra, el fondo vivo y el DJ (`peekAligned`). NetEase a veces parte la línea en trozos iguales (3 % de sus líneas): `LyricsSource.fixEvenLines` las reparte a ritmo de canto.
- El reloj del PC (`MobileLyrics.kt`) sigue la posición del motor, que ya descuenta el búfer de Java Sound; no incluye la latencia del dispositivo de salida.

## Diagnóstico

- `oto.mix` (log de mezclas): «análisis:», «aviso: … none (motivo)», «fallo en», «cancelado en», «se conserva para reintentar». En el PC: «Fundido de respaldo: A → B (… ms) a … ms: motivo», «DJ: analizada por adelantado» (o «Radio: …»), «Radio: reordenada por tempo», «Mezcla A -> B cancelada al empezar» / «no arrancó», «… no está disponible: se salta», «Corte en drop A -> B: A corta en … ms» / «B sonó a los … ms» / «finalizado». El DJ apunta por qué salió cada canción: «[dj] elegida: …» (la primera) y «[dj] preparada: artista — título (tipo, motivo, tanda, género, tempo)» (las de la cola). En el plan, «corta la voz (no había otra)» marca una mezcla relajada que corta la letra.
- Consola del PC: solo existe si se lanza con la salida redirigida; cada línea lleva hora (`HH:mm:ss.SSS`) y `[etiqueta]` (el `Timber` propio de `innertube`, que antes ignoraba `tag()`).
- `plays.jsonl`: qué sonó y cuándo; sirve para localizar la pareja de la que se queja Iago.
- Tandas de validación en el móvil: evidencia en `scratchpad/<tema>/INFORME.md` y `transiciones.csv` (fuera de Git). Contar por modo (rápido, normal, lista) y tipo de transición.
- Mezclas de prueba con derechos en `app/src/debug/assets/`: nunca subirlas.

## Cada bug de mezcla

1. Localizar la pareja y el instante en `plays.jsonl` y `oto.mix`.
2. Reproducirla en un test de `core` (o del PC) que falle.
3. Arreglar, pasar `tools/check.sh` y, si afecta al sonido, pedir a Iago que lo escuche.
