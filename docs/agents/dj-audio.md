# DJ, mezclas y audio

Índice en `AGENTS.md`. Reglas sacadas de la bitácora de 16–19/11/2026 (`docs/historico/bitacora.md` tiene el detalle y las mediciones). Contrastar con el código.

## Dónde está

- Lógica común en `core/.../dj/`: análisis (`AudioAnalysis`, `SongSections`, `PhraseMap`), planificación (`DjBridgePlanner`, `ChorusSafety`, `DjTempoMatch`), render de puentes (`DjBridgeRenderer`, `DjDsp`), elección de canciones (`Dj`, `Smart`, `Genres`, `Memory`).
- PC: `DesktopPlayerConnection` (fundido en vivo, `watchCrossfade`, ganancias) y `DesktopDjPlayback`. Android: `playback/Crossfade.kt` (puentes WAV pre-renderizados).
- Arnés de pruebas: `core/src/test/.../dj/DjHarness.kt`.

## Reglas

- **El agente no oye.** Medir por señal y logs, y dejar los cambios como «sin oír» hasta que Iago lo confirme con auriculares.
- **Ganancia nunca por encima de 1** al acabar una mezcla (`incomingMixGain`, `mixInGain`). Dejar margen antes de PCM16; sin recorte en niveles normales (`softClip` lineal hasta 0,89).
- **El tempo vuelve a 1×.** Cancelar un fundido no cancela la vuelta del tempo de la entrante (`DjTailAfterTest`).
- **Proteger estribillos y letra:** no salir ni entrar en mitad de una frase cantada. Si no hay hueco, mejor sin mezcla que un corte malo.
- **Género con continuidad:** la elección mira el género del destino, y una racha larga lo relaja poco a poco.
- **No ajustar pesos de estilos** sin antes medir una tanda y comparar con la anterior.
- PC y Android deben dar el mismo plan para la misma pareja. Un arreglo en `core` se comprueba en las dos plataformas.

## Diagnóstico

- `oto.mix` (log de mezclas): «análisis:», «aviso: … none (motivo)», «fallo en», «cancelado en», «se conserva para reintentar».
- `plays.jsonl`: qué sonó y cuándo; sirve para localizar la pareja de la que se queja Iago.
- Tandas de validación en el móvil: evidencia en `scratchpad/<tema>/INFORME.md` y `transiciones.csv` (fuera de Git). Contar por modo (rápido, normal, lista) y tipo de transición.
- Mezclas de prueba con derechos en `app/src/debug/assets/`: nunca subirlas.

## Cada bug de mezcla

1. Localizar la pareja y el instante en `plays.jsonl` y `oto.mix`.
2. Reproducirla en un test de `core` (o del PC) que falle.
3. Arreglar, pasar `tools/check.sh` y, si afecta al sonido, pedir a Iago que lo escuche.
