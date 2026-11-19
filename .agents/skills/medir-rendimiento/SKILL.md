---
name: medir-rendimiento
description: Medir fluidez, CPU y RAM de Oto en el escritorio con trazas, JFR y CSV. Usar con «va a tirones», «consume mucho», «no es fluido», o tras dos hipótesis fallidas sobre rendimiento.
---

# Medir rendimiento de Oto

Reglas y resultados anteriores en `docs/agents/rendimiento.md`.

1. **Línea base antes de cambiar nada**, con el mismo perfil aislado, ventanas y canción.
2. Trazas: arrancar con `OTO_MOTION_TRACE=1` → `motion-frames.jsonl` y `window-work.jsonl` en la carpeta del perfil. Son callbacks de Compose, **no** FPS ni presentación de la GPU.
3. CPU: el % es de **un** núcleo y no incluye FFmpeg. Los CSV usan decimales es-ES.
4. Pilas: `jcmd <pid> Thread.print` funciona con el runtime empaquetado. Para JFR hace falta el JDK completo:
   `jfr print --json --stack-depth 40 --events jdk.ExecutionSample,jdk.NativeMethodSample,jdk.GCPhasePause <archivo.jfr>`.
5. Cambiar una cosa, repetir la misma medición y comparar (p95, parones, RAM).
6. Material (CSV, JFR, logs) en `scratchpad/`, nunca en Git.

Informar con números de antes y después, y qué no se midió (GPU integrada, 120 Hz, ratón físico…).
