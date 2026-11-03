# Fluidez y consumo del escritorio — 03/11/2026

## Cambios

- El reloj del audio se observa únicamente dentro del progreso del reproductor. La portada, los controles y el resto de la superficie ya no se recomponen por cada actualización de posición. Los textos de tiempo reciben segundos completos.
- Las miniaturas de biblioteca y los mosaicos solicitan 64, 128, 256 o 544 píxeles según su tamaño físico. La portada principal conserva 544 píxeles y su extracción de color.
- Caché LRU de imágenes con presupuesto estimado de 48 MiB, u 8 MiB con ahorro de RAM, además del máximo de 96 entradas. Es el presupuesto de esa caché, no el de toda la aplicación, sus texturas ni las imágenes que siguen visibles.
- Ocho cerrojos compartidos evitan descargar/decodificar simultáneamente la misma imagen. Los fallos no se guardan como aciertos; hay tiempos máximos de conexión y lectura. La cancelación se propaga.
- Los efectos continuos de latido y reloj de letras descansan 10 ms entre solicitudes de fotograma, alineadas con el reloj de Compose. En la pantalla de alta frecuencia de esta prueba disminuye su frecuencia de trabajo. Aperturas, desplazamientos, hover y demás transiciones conservan sus especificaciones. La posición y las curvas se calculan con tiempo real.
- La letra usa políticas explícitas de igualdad para no propagar cambios de un índice activo o de un tiempo acotado que permanecen iguales.
- La preparación de apertura dibuja con alfa imperceptible antes del movimiento y espera al primer dibujo registrado de esa ventana. `OTO_MOTION_TRACE=1` incorpora `firstDrawDelayMs` para las aperturas. No se considera resuelto el tirón del primer arranque.

## Comparación corta reproducida

Windows, JDK 21.0.13, mismos JAR de dependencias, perfil habitual y tres ventanas con la misma geometría: biblioteca en Tu música, reproductor de carátula y letra. Audio de la misma descarga local, `pa ti toa <3`, sin depender de streaming para el audio.

Control: escritorio instalado al comenzar la tarea. Candidata: cambios de esta revisión, antes del último ajuste de preparación del primer dibujo. Ese último ajuste afecta al inicio de las ventanas; no se ha repetido la comparación completa con él.

En ambas ejecuciones se dejó reproducir unos 50 segundos para calentar la JVM y cargar la interfaz; después se volvió a 00:30 y se tomaron 21 muestras cada dos segundos. No se grabó JFR ni se manipuló la interfaz durante los intervalos medidos. Cada intervalo dura aproximadamente 40 segundos.

| Medida del proceso Java | Control | Candidata |
|---|---:|---:|
| CPU, porcentaje de un núcleo | 67,95 % | 55,18 % |
| Memoria residente media | 727,02 MiB | 619,84 MiB |
| Memoria residente mínima–máxima | 715,62–739,09 MiB | 611,78–628,17 MiB |
| Memoria privada al final | 761,57 MiB | 657,16 MiB |

En esta muestra: **18,8 % menos CPU del proceso** y **107,2 MiB menos de memoria residente media (14,7 %)**. No es el porcentaje total de CPU del ordenador; no incluye FFmpeg ni otros procesos. No garantiza el mismo ahorro con otras bibliotecas, equipos o sesiones largas.

Los primeros ensayos con distintas pantallas o durante el calentamiento dieron valores diferentes y no se usaron para esta comparación. Los CSV, JFR y ayudantes privados permanecen fuera del repositorio, en el `work/` del chat `q-x20`; no deben publicarse con datos de sesión.

## Verificación y límites

- `:desktopApp:test` pasó, incluidas pruebas nuevas de expulsión LRU por memoria, reducción inmediata del presupuesto, tamaño/URL de miniaturas, una única descarga para doce peticiones simultáneas y reintento tras error HTTP.
- `:desktopApp:packageMsi` generó el paquete Windows. Se verificó instalación y arranque, y se mantuvieron la biblioteca y la cuenta.
- Comprobadas las tres vistas del reproductor; el clic en la barra horizontal saltó a unos 00:53 y mantuvo reproducción. Se restauró la vista de carátula y el grupo de ventanas después de la prueba.
- El perfil JFR confirmó trabajo de recomposición en el reproductor y pausas de GC de aproximadamente 43–48 ms en una muestra. No se atribuye todo el consumo nativo a un solo método a partir del muestreo.
- La reapertura instrumentada final de biblioteca, sin reproducción, registró media de 14,2 ms y máximo de 63,4 ms entre callbacks. El primer arranque aún tuvo un pico de 134 ms en biblioteca. **Son intervalos de callbacks, no FPS presentados por la GPU**, ni una comparación equivalente a las aperturas históricas con cargas distintas.
- Pendientes: reducir el pico inicial de biblioteca, comparar ahorro RAM activado/desactivado con la misma carga y repetir una sesión larga para comprobar consumo sostenido y estabilidad. No se han retirado los efectos visuales elegidos por el usuario.
