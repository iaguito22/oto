# Escritorio: interfaz, bienvenida y valores por defecto

Parte del contexto de Oto; índice en `AGENTS.md`. Fechas y estados son los de cada sección: contrastar con el código.

## Decisiones visuales recientes que conservar

- Fondo integrado entre ventanas, esquinas redondas incluso interiores, sin líneas rectas de unión. Carátula completa con laterales reflejados/extendidos evitando franjas planas grandes. Intensidad inicial 75 %.
- Conservar posición/tamaños al intercambiar, abrir/cerrar módulos, atajo y cambiar canción. Letra/cola no pueden quedar únicas; cerrar principales conserva conjunto para restaurarlo con atajo.
- Cambiar vista del reproductor acoplado DEBE desacoplarlo, petición explícita del usuario.
- Atajo global capturado por teclas en Ajustes; oculta/restaura todo el grupo. X de todas ventanas deja Oto en bandeja. Una entrada de tareas por grupo; minimizar/restaurar afecta grupo.
- Chincheta solo reproductor solo; vertical abajo izquierda, oculta hasta hover y estado reconocible.
- Biblioteca sin tres puntos; miniaturas listas con primeras cuatro canciones; pista inferior lleva a origen. Me gusta sin relleno si no está marcado.
- Letra con aspecto Android, subidas/bajadas/vibrato; no copiar sincronización a ciegas. Awaken francés fue corregido y usuario confirmó inglés.

## Exportación MP3: implementada, probada e instalada

- Descargas → «Exportar como MP3…», con icono de descarga: todas o una canción, incluidas las de listas. Copias independientes `artista - título.mp3`, etiquetas y sufijos numéricos sin sobrescribir. Originales internos intactos.
- `playback/Mp3Export.kt`: FFmpeg incluido, libmp3lame VBR q2, temporales y limpieza al cancelar/fallar. `Mp3ExportTask.kt`: lote ligado al appScope de DesktopDownloads, independiente de la pantalla; progreso, Cancelar y errores detallados en `ui/Mp3ExportControls.kt`.
- Cambiar de pantalla o dejar Oto en bandeja mantiene el trabajo. Salir por completo termina la exportación; los MP3 ya guardados permanecen. No se reanudan lotes tras reiniciar la app.
- Preferencia final de Iago: selector habitual de Windows/Java moderno. `WindowsExportFolder.kt` usa IFileOpenDialog nativo mediante JNA ya incluido; respeta apariencia del sistema, probado oscuro. Fuera de Windows, JFileChooser. El navegador Compose propio fue descartado: NO recuperarlo.
- Eliminado «Abrir carpeta de descargas» de ambas vistas. Controles compartidos entre DownloadsSpace y DesktopDownloadsPane.
- Pruebas Mp3ExportTest: conversión real WebM→MP3, etiquetas, colisiones, originales y limpieza tras error. Mp3ExportTaskTest: independencia del scope de pantalla, rechazo de lotes simultáneos, cancelación y errores.
- :desktopApp:test y :desktopApp:packageMsi correctos; MSI actualizado con códigos 0/0 y app verificada. Cambios locales sin commit/push.

## Bienvenida Windows — 03/11/2026, implementada y en vista previa

- Petición nueva de Iago en otro chat: bienvenida breve centrada en ventanas; sin muchos «Siguiente», con carrusel de clips relacionados, sin audio y recortando la barra de tareas. Incluye biblioteca/reproductor, reproductor/letra, las tres con letra o cola, intercambio, y reproductor horizontal a una esquina y fijado. **El horizontal debe mostrarse con altura mínima (108 px lógicos).**
- `WelcomeWindow.kt`: una pantalla, pestañas «Combina ventanas», «Cambia el orden» y «Reproductor», selección de las cuatro combinaciones, avance automático, pausa, Omitir y Empezar. Marco habitual de Oto. Decodifica WebP con Skia, un bitmap mutable y un decoder; libera recursos al salir/cambiar. Sin red, audio ni procesos FFmpeg en ejecución de bienvenida.
- `ModularDesktop.kt`: preferencia independiente `desktop_welcome.json`, seen_version=1 al cerrar/omitir; reabrir desde «Ver bienvenida» en PlayerOptions. Se amplió el diálogo de opciones horizontal a 430 px para no recortar la nueva fila.
- Corrección adicional de `changeForm`: actualizar minimumSize de AWT ANTES de reducir los bounds, evitando que el formato horizontal herede los 370 px mínimos del reproductor con carátula. La demostración utiliza 108 px de alto.
- Medios en `desktopApp/src/main/resources/welcome/`: 6 WebP locales (~1.5 MiB en conjunto), procedentes de capturas de la app habitual. El intercambio es animación explicativa a partir de sus paneles; el horizontal es un montaje de grabaciones con movimiento sobre fondo neutro. No presentar ambos como una grabación continua sin editar.
- MP4 finales sin pista de audio, 1280x680 y sin barra de tareas. Entregables y ZIP en `C:\Users\iago\Documents\Codex\2026-11-03\quier\outputs`. Grabaciones originales y herramientas SOLO en `...\quier\work`, fuera del repo. No se copiaron cookies, cuenta, perfiles ni QR a recursos/entregables.
- `WelcomeMediaTest` decodifica todos los fotogramas de los seis assets durante dos vueltas y comprueba reinicio idéntico, duración, tamaño y presupuesto. `:desktopApp:test :desktopApp:jar --offline --no-configuration-cache`: 60 tests, 5 omitidos por condiciones externas, 0 fallos. Último cambio posterior: activar Timber en modo preview, JAR recompilado con éxito.
- `OTO_WELCOME_PREVIEW=1` abre SOLO la bienvenida, antes de inicializar preferencias/servicios/single-instance. Se deja una vista previa independiente abierta con COPIA del JAR: `...\quier\work\welcome-preview-final.jar`. PID inicial 18348, log `welcome-final-err.log`. **No sobrescribir el JAR que esté usando ese proceso.** Un primer preview apuntó directamente a build/libs y perdió lectura de recursos al recompilar; se cerró y corrigió usando copia estable. No era fallo de los assets, que pasan la prueba de decodificación completa.
- UI comprobada: carrusel y paso entre clips, pestaña reproductor y chincheta activada en el montaje. No se ha creado MSI, instalado, hecho commit ni push de esta bienvenida. No mezclar su estado con el arreglo de rendimiento EN CURSO descrito arriba.
- Durante la grabación se cambiaron las ventanas reales, y también hubo interacción manual del usuario. No restaurar a ciegas el respaldo inicial: su último estado fue reproductor horizontal, 351x108, esquina inferior derecha y fijado. Se ocultó mediante Ctrl+Alt+O para que no tapara la vista previa; reproducción continúa. Respaldo inicial privado en `...\quier\work\modular_windows-before-welcome.json`. La vista previa no modifica el perfil real.

## Valores por defecto y tamaños de reproductor — 03/11/2026

- Iago pidió inventariar TODOS los valores iniciales para decidir cuáles cambiar, y autorizó estas reglas: horizontal SIEMPRE aparece con altura mínima (108); vertical con ancho mínimo (96); carátula con proporción de móvil, elegida 9:16 y tamaño inicial 360x640.
- `PlayerForm.Full` pasa de 390x656 a 360x640. `PlayerWindowSizing.kt` centraliza mínimos y tamaños de apertura: horizontal conserva ancho y fuerza 108 de alto; vertical conserva alto y fuerza 96 de ancho; carátula conserva ancho válido y calcula alto 16/9. En conjunto acoplado, carátula adapta ancho a la altura compartida. Los tamaños se pueden cambiar manualmente después; se normalizan al seleccionar/abrir/restaurar la vista.
- `initialDockLayout()` inicia biblioteca y reproductor con altura 640 si cabe y reproductor 9:16. Biblioteca mantiene ancho adaptable 640–960. Se aplican reglas también al restaurar la distribución y al mostrar/abrir el reproductor. Horizontal se separa si estaba acoplado, porque una altura de 108 no es compatible con biblioteca; una maximización guardada del reproductor se deshace al normalizar su aparición.
- Continúa la corrección de minimumSize AWT antes de reducir geometría. `PlayerWindowSizingTest` cubre preferencias antiguas expandidas, proporción, normalización de layout serializado, compatibilidad del grupo, reapertura y posibilidad de redimensionar manualmente.
- Compilación y suite de escritorio correctas: 66 tests, 5 omitidos, 0 fallos. Comando `:desktopApp:test :desktopApp:jar --offline --no-configuration-cache`. Log en `C:\Users\iago\Documents\Codex\2026-11-03\quier\work\defaults-tests.log`. Sin instalación, MSI, commit ni push de estos cambios. La vista previa anterior usa copia estable y no se ha sobrescrito.
- Inventario completo para Iago en `C:\Users\iago\Documents\Codex\2026-11-03\quier\outputs\valores-por-defecto-oto.md`. Se basa en defaults de código, no en su perfil personal. No se han cambiado las demás preferencias: calidad Auto, volumen100%, fundido3s, ahorro datos/RAM off, aleatorio/repetición off, fondo on75%, latido on, romanización on, letra120%, tarjetas medianas, inicio Tu música. La afirmación antigua de Ajustes sobre caché RAM «12 carátulas» está desactualizada respecto al presupuesto 8MiB; no usarla como default real.

