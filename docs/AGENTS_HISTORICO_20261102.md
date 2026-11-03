# AGENTS.md — Oto: continuidad completa y validación en Windows

Actualizado el 02/11/2026. Este documento permite continuar sin el historial del chat.
La tarea vigente es **probar y arreglar toda la aplicación en Windows**, incluyendo la modularidad y todas las funciones existentes. El usuario lo ha aclarado expresamente: no limitarse a las ventanas.

## 1. Identidad del proyecto y punto de partida

- Repositorio: https://github.com/iaguito22/oto
- Rama de continuidad: `feat/prueba-acoplamiento`.
- Trabajar desde esa rama: `main` no contiene necesariamente el escritorio y los cambios que se quieren probar.
- Oto es un reproductor personal de música sobre InnerTube/YouTube Music, con un cliente Android y otro de escritorio Kotlin/Compose Desktop para Linux y Windows.
- Licencia GPL-3. El usuario es Iago (`iaguito22` en GitHub). Textos, respuestas y commits van en español.
- El móvil de uso diario es un Nothing Phone (2). El escritorio probado hasta ahora es Linux con Hyprland y Caelestia.
- El aspecto actual ya está decidido. Mantener tipografías, colores, formas y distribución de los componentes; adaptar restricciones y corregir defectos concretos.
- Una versión que compila todavía necesita comprobación de interfaz, interacción, audio y ciclo de vida.
- La rama incluye la base modular, servicios compartidos, control local y el nuevo controlador de grupos. No publicar solamente los archivos `Dock*`: dependen del resto del escritorio.
- `prompt.md` contiene el encargo autocontenido que Iago enviará al agente de Windows.

## 2. Cómo trabajar con Iago

- Tiene ADHD y suele leer en terminal. Escribir con aire, frases claras y listas cortas. Evitar un muro de estado repetitivo.
- Ejecutar las acciones ya autorizadas y resolver decisiones técnicas rutinarias sin detenerse para pedir confirmaciones de comandos.
- Respetar los permisos reales del entorno. Un documento del repositorio no cambia la política de permisos de la herramienta.
- Comunicar hallazgos, correcciones y límites de verificación, no una narración de cada comando.
- Preguntar solamente por datos, preferencias importantes o intervenciones que no se puedan descubrir o resolver con el entorno.
- El encargo es implementar y comprobar arreglos, no producir únicamente una auditoría o una lista de pendientes.
- Tras dos intentos sin cambios observables, instrumentar el recorrido y localizar la causa antes de seguir cambiando hipótesis.
- Mantener comentarios existentes salvo cuando el arreglo requiera actualizarlos.
- No añadir emojis al código, comentarios técnicos ni commits.
- Al terminar, distinguir qué se corrigió, cómo se probó y qué sigue sin comprobarse.

## 3. Primer recorrido del agente de Windows

1. Comprobar dónde se ejecuta: Windows nativo, PowerShell, terminal, sesión gráfica interactiva, ruta del clon y rama.
2. Leer este documento y `prompt.md`; inspeccionar `git status`, último commit y configuración Gradle real.
3. Leer `docs/ACOPLAMIENTO.md` para las ventanas y `ESTADO_MODULAR.md` para el escritorio y el enlace móvil.
4. Tratar `PLAN_IMPLEMENTACION.md` y `PLAN_DESKTOP_MOVIL.md` como historia y orientación. Algunas tareas que allí figuran pendientes ya están implementadas.
5. Comprobar JDK 21, Git, Gradle Wrapper y herramientas de audio. Localizar el SDK Android si Gradle lo reclama.
6. Ejecutar primero las pruebas automatizadas y después abrir la aplicación real en Windows.
7. Probar cada área funcional de la matriz de este documento. Registrar reproducción, resultado esperado y evidencia del fallo.
8. Corregir por cambios lógicos pequeños; volver a probar el recorrido que fallaba y las funciones vecinas que el cambio pueda afectar.
9. Actualizar `docs/VALIDACION_WINDOWS.md` con entorno, resultados, arreglos y límites. No dar por probadas las filas sin evidencia.
10. Comprobar instalación y arranque del paquete Windows después de validar la ejecución de desarrollo.

**Criterio de continuidad:** seguir trabajando mientras existan fallos reproducibles que se puedan resolver con el equipo y los accesos disponibles. Si falta una cuenta, teléfono o segundo monitor, completar las áreas independientes y documentar exactamente qué falta.

## 4. Arquitectura y fuentes de verdad

| Módulo | Responsabilidad | Restricción |
|---|---|---|
| `:app` | Cliente Android, UI móvil, Media3 y servicio multimedia | Preservar datos, firma y funcionamiento del móvil |
| `:core` | Preferencias, cuenta, catálogo, listas, DJ, letras y control local | JVM compartida; un cambio puede afectar a Android y escritorio |
| `:desktopApp` | Compose Desktop, ventanas, audio y adaptadores de escritorio | Probar con Windows nativo y mantener Linux |
| `:innertube` | Cliente de YouTube Music y dependencias de extracción | Respetar versiones y compatibilidad existentes |

Antes de modificar una API, leer su firma real y sus usos. No implementar contra una firma copiada de un documento antiguo.

### Entradas y servicios de escritorio

- `desktopApp/.../desktop/Main.kt`: inicialización, instancia única, servicios y entrada al escritorio modular.
- `ModularDesktop.kt`: composición de las ventanas reales, acciones, atajos y marcos compartidos.
- `DesktopInstance.kt`: bloqueo de instancia y recuperación de la instancia ya abierta.
- `DesktopAccess.kt`: endpoint local para mostrar/ocultar y registro de atajo en Hyprland.
- `BrowserLogin.kt`: login mediante navegador temporal y CDP, con ruta alternativa de extensión/consola.
- `DeviceConnection.kt`, `DevicesPane.kt`, `RemoteActions.kt`: integración del escritorio con el móvil.
- `playback/DesktopPlayerConnection.kt`: estado y comandos de reproducción, cola, biblioteca y servicios.
- `playback/DesktopAudioEngine.kt`: audio, descarga por trozos, procesos y volumen.
- `playback/DesktopBeat.kt`: análisis de graves a partir de audio decodificado con FFmpeg.

### Vistas de escritorio

- `ui/LibrarySpace.kt`: biblioteca actual, navegación lateral, búsqueda, colección y accesos.
- `ui/SearchSpace.kt`: búsqueda y sus resultados.
- `ui/AlbumSpace.kt`, `ArtistSpace.kt`, `LocalPlaylistView.kt`: navegación a álbum, artista y lista local.
- `ui/DownloadsSpace.kt`: descargas y operaciones relacionadas.
- `ui/SettingsSpace.kt`: ajustes del escritorio.
- `ui/CloudChoices.kt`: selecciones explícitas de importación/exportación de cuenta.
- `ui/PlayerSurface.kt`, `PlayerOptions.kt`: reproductor y menú de tres puntos compartido con biblioteca.
- `ui/MobileLyrics.kt`: renderizado de letras portado del móvil.
- `ui/PlayerBackdrop.kt`, `OtoSnackbar.kt`: fondo y avisos de la estética actual.
- `ui/DesktopMenuSheet.kt` y `DesktopStage.kt` conservan código anterior y componentes reutilizados. Seguir las llamadas reales desde `Main.kt` para distinguir UI activa de código heredado.

### Control local y cuenta

- `core/.../remote/LocalLink.kt`: transporte autenticado y comandos.
- `LinkedPlayback.kt`: control y transferencia de sesión.
- `LibraryReplica.kt`: réplica de biblioteca y resolución de cambios.
- `LocalDiscovery.kt`: descubrimiento local.
- `core/.../account/Account.kt`, `Cloud.kt`, `CloudSelection.kt`: cuenta y selección de listas.
- Las copias del transporte en el árbol móvil de Linux se mantuvieron temporalmente iguales. Ese otro árbol no se clona automáticamente con este repositorio.
- Un arreglo del protocolo compartido exige revisar compatibilidad con el móvil; no cambiar mensajes o cifrado por una adaptación de Windows.

## 5. Entorno de compilación y ejecución

### Windows: comprobaciones iniciales

```powershell
Get-Location
git status --short --branch
git log -1 --oneline
java -version
Get-Command java, git
.\gradlew.bat --version
Get-Command ffmpeg, ffplay -ErrorAction SilentlyContinue
```

- JDK 21 es la versión requerida por el proyecto. Resolver `JAVA_HOME` a una instalación real y verificar la versión; no copiar una ruta Linux.
- En PowerShell se usa `&` para ejecutar un programa cuya ruta está entre comillas o en una variable.
- Revisar también qué Java está usando Gradle, que puede diferir de `java` en el PATH.
- Utilizar el Wrapper del repositorio; las versiones declaradas en Gradle son la fuente de verdad.
- Una máquina nueva necesita descargar dependencias. Empezar sin `--offline`; usar ese indicador solo cuando la caché esté completa.
- Si Gradle falla por SDK Android, diagnosticar qué proyecto lo solicita. Configurar el SDK local o corregir de forma deliberada la configuración del escritorio; no eliminar módulos Android a ciegas.
- El `compileSdk` Android del código de esta entrega es 37. Confirmarlo en `app/build.gradle.kts` antes de instalar plataformas.
- La ausencia de claves privadas de firma o de PoToken no se resuelve añadiendo credenciales al repositorio.

### Pruebas y ejecución del escritorio en Windows

```powershell
.\gradlew.bat :core:test :desktopApp:test
.\gradlew.bat :desktopApp:run
```

- Los informes de pruebas quedan bajo `core/build/reports/tests/` y `desktopApp/build/reports/tests/`.
- Guardar logs de arranque con una ruta local apropiada; revisar excepciones completas con consultas acotadas.
- Para probar geometría sin servicios, cerrar la aplicación real o identificar con claridad qué ventanas pertenecen a cada proceso y lanzar el arnés:

```powershell
$env:OTO_DOCK_HARNESS = '1'
$env:OTO_DOCK_TRACE = '1'
.\gradlew.bat :desktopApp:run
```

Al terminar el arnés, limpiar esas variables antes de abrir Oto real:

```powershell
Remove-Item Env:OTO_DOCK_HARNESS -ErrorAction SilentlyContinue
Remove-Item Env:OTO_DOCK_TRACE -ErrorAction SilentlyContinue
.\gradlew.bat :desktopApp:run
```

El arnés usa el mismo marco y controlador con contenido de prueba. No inicia reproducción, cuenta, sincronización ni preferencias del usuario. Probarlo no sustituye a comprobar las vistas reales.

### Linux: comandos ya utilizados

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:test :desktopApp:test --offline
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktopApp:run
./tools/run-dock-harness.sh --offline
```

- En esta máquina se usan Hyprland 0.56 y Caelestia. `agy-pantalla` está instalado y se utilizó para la revisión visual.
- Las herramientas locales de Iago no aparecen automáticamente en Windows. Comprobar herramientas disponibles antes de construir la prueba alrededor de ellas.
- Una prueba realizada en WSL o con una ventana Linux no acredita el comportamiento de la aplicación nativa Windows.
- Para configuración propia de Codex, consultar la documentación oficial vigente: https://developers.openai.com/codex/windows . No copiar permisos o rutas privadas de la máquina Linux.

### Verificación cuando cambie código compartido

```powershell
.\gradlew.bat :core:test :desktopApp:test :desktopApp:compileKotlin :app:compileDebugKotlin
```

Si no se dispone del SDK Android, completar las pruebas independientes y registrar la compilación Android como pendiente, con el error concreto. No presentarla como pasada.

### Empaquetado Windows

```powershell
.\gradlew.bat :desktopApp:tasks --all
.\gradlew.bat :desktopApp:packageMsi
.\gradlew.bat :desktopApp:packageExe
```

- Verificar las tareas disponibles y las dependencias de `jpackage` en esa máquina antes de diagnosticar un fallo de la aplicación.
- Los instaladores Windows se generan y comprueban en Windows. Un build Linux no valida un MSI.
- Probar también la aplicación empaquetada: un PATH o directorio de trabajo diferente puede romper audio, navegador, recursos o datos.
- La versión de escritorio declarada al preparar esta entrega es 1.2.2; confirmar en Gradle. La versión del módulo Android puede ser distinta.

## 6. Datos, credenciales y aislamiento de pruebas

### Rutas de datos

- `OtoDirs` determina las rutas efectivas; leer su implementación si algo no coincide.
- Linux: datos en `~/.local/share/oto`; caché en `~/.cache/oto`.
- Windows: datos normalmente en `%APPDATA%\oto`; caché normalmente en `%LOCALAPPDATA%\oto`.
- Preferencias de ventanas: `modular_windows.json`, con `docking_v1` para la distribución agrupada.
- DataStore guarda preferencias y listas; no es una base de datos Room.
- Las cachés de audio, descargas y DJ cumplen funciones diferentes. No borrar una para arreglar un problema de otra.

### Protección de los datos reales

- Elegir un perfil de prueba o un directorio aislado cuando se necesite un arranque limpio. El arnés ya proporciona un entorno sin datos reales para geometría.
- Hacer respaldo antes de una migración necesaria, pero no restaurar después una copia antigua sobre datos que el usuario o el móvil hayan modificado mientras tanto.
- Para limpiar una prueba, retirar exclusivamente las entradas de prueba identificadas.
- Registrar el estado de reproducción y los procesos propios antes de reiniciar una aplicación real durante una prueba.
- Iniciar sesión no autoriza importar o exportar toda la biblioteca. Las selecciones explícitas del usuario siguen siendo obligatorias.
- Probar primero las vistas de importación/exportación sin ejecutar operaciones remotas de escritura sobre listas reales.
- Nunca publicar cookies, tokens, secretos de vinculación, QR utilizables, archivos DataStore, claves de firma ni `local.properties`.
- En los logs y capturas destinados a Git, ocultar datos de sesión y direcciones o secretos que no sean necesarios para reproducir un fallo.

### Procesos y herramientas

- Identificar los PID de la aplicación y de sus procesos de audio antes de detenerlos.
- Cerrar primero desde la UI cuando funcione. Si se necesita terminar un proceso, hacerlo sobre el proceso propio concreto.
- No matar todos los procesos Java: también hay daemons de Gradle y otras aplicaciones.
- No recompilar o reemplazar un JAR que esté usando una aplicación abierta. Cerrar/reiniciar o ejecutar desde una copia inmutable de los artefactos.
- No confundir un fallo de dispositivo remoto desconectado con un fallo del controlador de ventanas.

## 7. Diseño de ventanas acordado con Iago

Estas son decisiones del usuario. Una corrección de Windows debe conservarlas salvo que Iago las cambie expresamente.

### Inicio, estética y módulos

- En una instalación sin distribución previa se abren biblioteca y reproductor, con reproductor a la derecha de biblioteca.
- Mantener la estética y los controles de los módulos actuales.
- Ventanas agrupables: biblioteca, reproductor, letra y cola.
- Dispositivos y Guardar siguen siendo ventanas auxiliares; no incorporarlas a la jerarquía sin una petición nueva.
- Las uniones son exclusivamente laterales. No añadir acoplamiento por arriba o abajo.
- Biblioteca y reproductor ofrecen accesos a módulos. Por petición de Iago del 02/11/2026 durante la revisión Windows, biblioteca no lleva menú de tres puntos; las opciones siguen en reproductor y en sus ubicaciones existentes.
- Las ventanas nuevas se reparten a ambos lados de la jefa, prefiriendo el lado con menos ancho ocupado. Si ambos están vacíos, se empieza por la derecha.
- La distribución guardada prevalece sobre ese reparto inicial.

### Jefa y dependencias

- Prioridad de mando: biblioteca > reproductor > letra/cola.
- «Secundaria» significa cualquier ventana que no sea la jefa de su grupo; incluye reproductor cuando hay biblioteca.
- Entre ventanas del mismo rango, manda la que recibió a la otra al unirse.
- Arrastrar la cabecera de la jefa mueve todo el grupo.
- Sacar una secundaria fuera del grupo la separa con los dependientes que le corresponden por la distribución actual.
- Ejemplo: `Biblioteca · Reproductor · Letra · Cola`; separar reproductor se lleva letra y cola.
- Ejemplo: `Biblioteca · Letra · Reproductor · Cola`; separar reproductor se lleva cola, mientras letra queda con biblioteca.
- Las relaciones se recalculan al reordenar: no conservar asociaciones invisibles antiguas.
- Quitar una secundaria de la izquierda debe mantener la jefa en su posición normal, salvo ajustes necesarios para mantener el conjunto visible.

### Reordenación

- Se usa la cabecera, con una franja de tolerancia para distinguir reorganizar de separar.
- Dentro del grupo se mueve solamente la ventana elegida, aunque tenga dependientes.
- Mismo lado de la jefa: inserción y desplazamiento de las demás.
- Lados opuestos: intercambio de origen y destino; la jefa no cambia de puesto.
- Ejemplo de inserción: `Biblioteca · Reproductor · Letra · Cola` pasa a `Biblioteca · Letra · Cola · Reproductor`.
- Ejemplo de intercambio: `Reproductor · Biblioteca · Letra · Cola` pasa a `Cola · Biblioteca · Letra · Reproductor`.
- Mostrar una previsualización antes de confirmar al soltar. Resaltar el borde de inserción; distinguir el caso de intercambio.
- Los valores iniciales del código son 24 dp para separar y 12 para proximidad del imán. Revisar la conversión real de unidades antes de retocar sensaciones en Windows.

### Geometría, altura y foco

- Todas las ventanas unidas comparten altura y se alinean arriba.
- Redimensionar la altura desde cualquier miembro afecta al grupo completo.
- Al unir grupos de alturas distintas manda la altura de la ventana de mayor prioridad. Entre iguales se mantiene la del grupo receptor.
- Conservar anchos individuales y respetar los mínimos del contenido.
- Mover y limitar a pantalla el conjunto como unidad; no ajustar cada ventana independientemente y romper las uniones.
- Hacer clic en una ventana eleva el grupo completo y conserva el foco de teclado en la pulsada.
- Cerrar una ventana afecta solo a ella. El resto sigue abierto y se compacta; cambia la jefa si hace falta.
- Recuperar el tamaño y la asociación guardados al reabrir una ventana. Si no cabe acoplada, abrirla suelta y avisar.

### Maximización: política por plataforma

- **Hyprland: Iago no quiere maximización propia.** No mostrar el control y no maximizar con doble clic.
- **Windows y otros escritorios Linux: sí hay maximización.** La acción afecta al conjunto.
- Usar el área útil del monitor, descontando barra de tareas o paneles.
- Maximizar y restaurar deben conservar la distribución normal anterior.
- Incorporar una ventana a un conjunto maximizado redistribuye sus anchos sin sacar al grupo de ese estado.
- Biblioteca absorbe el ancho flexible.
- Antes de estrechar biblioteca, reducir el exceso de ancho de las secundarias, solo lo necesario y hasta su ancho predeterminado.
- Después se puede reducir biblioteca hasta su mínimo.
- Si todavía falta espacio, la nueva ventana aparece suelta, con un aviso breve. No sustituir automáticamente otra ventana ni comprimir secundarias por debajo del límite acordado.
- Sin biblioteca, absorbe el ancho sobrante letra; después cola y, si está solo, reproductor.
- Separar reproductor de un conjunto maximizado recupera su tamaño normal bajo el cursor, junto con sus dependientes.
- El grupo restante sigue maximizado y aprovecha el espacio liberado.
- Verificar botón, doble clic y cambios nativos de maximización en Windows. Esta última ruta fue implementada pero no se ha comprobado allí.

### Chincheta

- Solo aparece cuando reproductor está completamente solo.
- Sirve para usarlo como reproductor multimedia flotante por encima de otras aplicaciones.
- Unirlo a cualquier otra ventana desactiva la chincheta y oculta su control.
- Separarlo otra vez deja la chincheta desactivada: Iago quiere fijarlo manualmente cada vez.
- No extender la chincheta al grupo entero.
- En vista Vertical, la chincheta va abajo, tras los accesos a módulos (petición de Iago durante la revisión Windows). La cabecera estrecha conserva menú y cierre.
- Letra y Cola admiten anchos menores que los predeterminados: mínimos actuales 220 y 240 dp, respectivamente; predeterminados 480 y 420 dp.

## 8. Implementación del acoplamiento: dónde intervenir

- `DockLayout.kt`: geometría pura, grupos, prioridades, apertura/cierre, dependencias, inserción/intercambio, maximización y recuperación de pantallas.
- `DockWindows.kt`: puente Compose/AWT, entrada de ratón, foco, cambios nativos, áreas útiles y política de plataforma.
- `DockPersistence.kt`: codificación/lectura y distribución inicial.
- `DockHarness.kt`: contenido de prueba que usa los mismos marcos de la aplicación.
- `ModularDesktop.kt`: conexión de ventanas reales, menús, chincheta y persistencia.
- `DockLayoutTest.kt`: pruebas del modelo, persistencia y cálculo de áreas.

### Trampas ya encontradas durante la implementación

- Aplicar `setBounds` demasiado pronto puede perder la geometría inicial al aparecer una ventana. Se crean estados a partir del modelo y se sincronizan con Compose.
- Un estado creado mientras una ventana estaba cerrada puede conservar el tamaño genérico de 800 × 600 al abrirla. `rememberDockState` renueva el estado con la visibilidad.
- Consultar solamente la posición global del ratón al recibir un evento tardío puede perder el inicio de un arrastre rápido. El código usa la posición local del primer contacto y la densidad.
- El escritorio puede capturar el redimensionado antes de Compose. El adaptador escucha cambios nativos y los reconcilia con el modelo.
- Distinguir esos cambios externos de los provocados por el controlador para evitar bucles de realimentación.
- Un `toFront()` por cada evento de foco puede producir ciclos y cerrar menús emergentes. El código eleva el grupo al pulsar y separa esa acción del registro de foco.
- Evitar que los botones de cabecera inicien a la vez un arrastre.
- Al acoplar un grupo que no cabe, restaurar completamente el estado anterior; no dejar anchos o asociaciones modificados a medias.
- Los cambios nativos pueden llegar después del evento de ratón. Esperar a que se estabilicen y medir, en vez de inferir el resultado de una captura demasiado temprana.
- El área útil de AWT bajo XWayland no reflejaba los paneles de Caelestia. En Hyprland se usa el área reservada que informa el compositor.
- AWT usa coordenadas lógicas de escritorio. Revisar con cuidado los píxeles físicos de capturas y el escalado de Compose/Windows.
- Hay tests de monitores con coordenadas negativas, escala y rotación, pero falta comprobación con monitores físicos de escalas distintas.

## 9. Matriz de pruebas de TODA la aplicación en Windows

Crear un registro con una fila por recorrido y estados `pendiente`, `pasa`, `falla` o `bloqueado`. En cada fallo incluir pasos, resultado esperado, resultado observado y evidencia. Un test del modelo no convierte automáticamente una prueba de interfaz en `pasa`.

### A. Preparación, inicio y ciclo de vida

- Clon limpio de la rama correcta con JDK 21 y dependencias descargadas.
- Arranque de desarrollo desde PowerShell y desde un directorio con espacios.
- Primera apertura sin distribución; apertura posterior con preferencias.
- Segunda ejecución: recuperar la instancia existente sin duplicar ventanas, servidores ni audio.
- Cierre de una ventana, cierre de todas, bandeja y salida completa.
- Reinicio después de una salida normal y después de un cierre inesperado del proceso propio.
- Suspensión/reanudación del equipo y cambio de dispositivo de salida, cuando estén disponibles.
- Persistencia de volumen, forma del reproductor, tamaño, posición y módulos abiertos.
- Ausencia de procesos huérfanos de audio o navegador al salir.

### B. Ventanas e interacción de escritorio

- Acoplar por izquierda y derecha, separar y volver a unir.
- Mover desde la jefa y desde una secundaria con dependientes.
- Reordenar en el mismo lado; intercambiar cruzando una jefa central.
- Recalcular dependientes después de reordenar.
- Cambiar anchos, altura compartida y límites mínimos desde los bordes disponibles.
- Botones de cabecera, menús, foco de teclado y atajos con un campo de texto enfocado.
- Abrir/cerrar desde biblioteca, reproductor, bandeja y atajos.
- Maximizar/restaurar por botón, doble clic y controles del sistema Windows.
- Añadir/quitar módulos maximizados y probar el caso de espacio insuficiente.
- Extraer un subgrupo maximizado bajo el cursor y restaurar sus tamaños.
- Chincheta en reproductor solo; desactivación al unir; nueva separación sin fijado automático.
- Barra de tareas, Alt+Tab y relación entre ventanas cuando otra aplicación las tapa.
- Escalas Windows de 100 %, 125 %, 150 % y, si cabe, 200 %.
- Cambio de escala en caliente, monitores secundarios, posiciones negativas, distinta escala por monitor y desconexión del monitor que contiene Oto.
- Datos de ventanas incompletos o inválidos y recuperación de ventanas fuera de pantalla en un perfil de prueba.

### C. Reproducción y audio

- Elegir una canción desde búsqueda, biblioteca, álbum, artista, lista y DJ.
- Inicio, pausa, reanudación, anterior, siguiente y final natural de pista.
- Buscar posición al principio, en mitad y cerca del final; no duplicar procesos de audio.
- Volumen, silencio y persistencia de volumen sin reinicios o saltos inesperados.
- Cambio rápido de canciones mientras carga audio.
- Audio largo, distintos formatos y ruta de reproducción alternativa cuando proceda.
- Medir inicio, carga y latido con audio real; distinguir silencio de fallo del reproductor.
- Pérdida de red durante preparación y reproducción, reconexión y errores de extracción.
- Salida de la aplicación mientras descarga o reproduce.
- Teclas multimedia y atajos documentados; detectar lo que no esté implementado realmente en Windows.

### D. Biblioteca, búsqueda y navegación

- Tu música, Explorar, recientes y navegación de vuelta.
- Búsqueda por canción, artista, álbum y lista, con y sin resultados.
- Escribir espacios en búsqueda sin pausar involuntariamente la música.
- Abrir artista/álbum/lista desde resultados y desde la pista actual.
- Listas largas: desplazamiento, paginación si existe, cambio de vista y selección.
- Crear, renombrar, borrar y recuperar mediante deshacer una lista de prueba.
- Crear carpetas vacías, mover listas, entrar/salir, renombrar y borrar la carpeta de prueba.
- Fijar/desfijar listas y carpetas, sin duplicarlas en las secciones.
- Arrastrar/reordenar listas, incluido desplazamiento automático y persistencia.
- Guardar una canción o álbum en una lista, retirar elementos y comprobar el deshacer.
- Me gusta y su sincronización visual entre vistas.

### E. Cola y modos de reproducción

- Iniciar una lista y comprobar orden de cola y canción actual.
- Reordenar, quitar, vaciar y saltar a una entrada.
- Quitar la canción actual y una anterior sin que reaparezcan al cambiar aleatorio.
- Aleatorio, bucle y transiciones entre modos.
- Cola vacía, una canción, muchas canciones y canciones repetidas.
- Radio/autoplay después de una canción elegida individualmente, respetando la lógica existente.
- Navegación al origen de la pista desde reproductor.

### F. Letras y apariencia

- Letra sincronizada, letra sin sincronizar y canción instrumental/sin letra.
- Romanización, cambio de tamaño y persistencia del ajuste.
- Desplazamiento manual, retorno al seguimiento y salto de reproducción al pulsar una línea.
- Palabras largas, líneas iniciales/finales, duetos, coros y pausas.
- Cambiar de canción con letra abierta sin enseñar la anterior como si perteneciera a la nueva.
- Carátulas que faltan, fallos de descarga de imagen y cambios de paleta.
- Formas Carátula, Horizontal y Vertical, unidas y separadas.
- Fondo, contraste, hover, tooltips, menús y avisos en cada escala.
- Revisar recortes de texto y botones inaccesibles en tamaños mínimos.

### G. Descargas y funcionamiento sin red

- Añadir una descarga de prueba, observar progreso y reproducirla al terminar.
- Cancelar, reintentar y retirar la descarga de prueba.
- Reabrir Oto y reconocer descargas existentes.
- Reproducir con red desactivada y volver a activarla.
- Rutas Windows con espacios y caracteres no ASCII.
- Caché parcial, archivo ausente, error de disco o permiso denegado en un perfil aislado.
- Ajustes de almacenamiento y limpieza sin borrar descargas o biblioteca equivocadas.

### H. Cuenta, importación y exportación

- Apertura del navegador de login, cancelación, timeout y cierre del navegador temporal.
- Descubrimiento de Chrome/Edge/Brave según las rutas reales de esa máquina.
- Si hay cuenta disponible, login y restauración después de reiniciar.
- Selecciones independientes de listas para importar y exportar; buscar y seleccionar múltiples.
- Confirmar que Me gusta requiere selección y que desmarcar exportación no borra la copia remota.
- Importación de archivo vacío, inválido y una muestra válida de prueba.
- Importación Spotify por archivo o enlace según las capacidades actuales; no declarar una importación real probada con un archivo vacío.
- Cerrar sesión y verificar que se limpia la sesión adecuada sin borrar biblioteca local.
- Marcar como bloqueadas las escrituras remotas que requieran una selección o cuenta que el usuario no haya facilitado.

### I. Dispositivos y conexión con el móvil

- Pantalla Dispositivos sin móvil, con móvil desconectado y durante reconexión.
- Generación de QR, revocación de secreto y desconexión.
- Descubrimiento y conexión directa cuando el descubrimiento falle.
- Si hay móvil disponible, vincular y probar controles en ambos sentidos.
- Cola completa: anteriores, actual y siguientes, orden, posición y estado de pausa.
- Continuar aquí/allí con éxito y con fallo; evitar que suenen los dos al mismo tiempo.
- Sincronización con bibliotecas grandes y cambios concurrentes usando datos de prueba identificados.
- Reconexión tras cerrar el escritorio, suspender o cambiar de red.
- Firewall Windows: diagnosticar aplicación y perfil de red; no desactivar globalmente la protección para ocultar un fallo.
- Probar móvil desconectado sin cascadas de excepciones ni bloqueo del hilo gráfico.

### J. Instalación y distribución

- Generar el instalador disponible y revisar la salida de `jpackage`.
- Instalar para el usuario actual y abrir desde menú Inicio/acceso directo.
- Comprobar iconos, fuentes, recursos y rutas de datos fuera del repositorio.
- Verificar disponibilidad de FFmpeg/ffplay desde la aplicación instalada, no solo desde la terminal de desarrollo.
- Repetir reproducción, búsqueda, persistencia, ventanas y login desde el paquete.
- Actualizar/reinstalar sin perder los datos del perfil de prueba.
- Desinstalar la aplicación de prueba y comprobar la política de conservación de datos.

## 10. Riesgos concretos que conviene investigar en Windows

Son puntos de inspección, no afirmaciones de que todos estén fallando.

1. **Atajo global:** `DesktopAccess` registra un atajo del compositor solo cuando detecta Hyprland. El endpoint local por sí solo no implementa un atajo global Windows. Comprobar y resolver el soporte que falte sin capturar el teclado de forma permanente.
2. **FFmpeg/ffplay:** `DesktopAudioEngine` detecta `ffplay` en PATH y `DesktopBeat` usa `ffmpeg`. Probar nombres `.exe`, instalación, empaquetado y rutas con espacios.
3. **Audio alternativo:** la ruta Java Sound no implica soporte real de todos los formatos de YouTube. Probar audio efectivo, no solo ausencia de excepción.
4. **Procesos de audio:** revisar pausa, seek, volumen y terminación en Windows; un proceso arrancado no acredita que reproduzca lo esperado.
5. **Navegador:** `BrowserLogin` contempla rutas de Windows y también tiene búsqueda con `which` en su lógica. Inspeccionar qué rama se ejecuta realmente y la limpieza del perfil temporal.
6. **Coordenadas y DPI:** Windows/AWT/Compose/capturas pueden usar escalas distintas. Medir geometría y observar el resultado con varias escalas.
7. **Ventanas transparentes:** probar recorte, sombra, borde de arrastre y cambios nativos de tamaño/foco con ventanas sin decoración.
8. **Maximización:** se implementó en el controlador y se conectó con `WindowPlacement`, pero falta validar la transición nativa Windows.
9. **Bandeja e instancia única:** comprobar soporte y comportamiento cuando no se ve ninguna ventana.
10. **Sincronización local:** durante la revisión Linux se observaron errores repetidos «El otro dispositivo no está conectado» desde `DeviceConnection`/`LocalLink`. Reproducir y resolver si aparece en Windows, sin cambiar el protocolo por intuición.
11. **Configuración Gradle:** una clonación nueva carece de `local.properties`, cachés y claves locales; diferenciar esos prerrequisitos de un defecto de portabilidad.
12. **Planes antiguos:** algunas rutas y pantallas se rediseñaron. No restaurar el escritorio anterior para que encaje con un plan histórico.

## 11. Evidencia disponible al entregar esta rama

### Comprobado en la sesión Linux de acoplamiento

- Compilación del escritorio con JDK 21.
- 25 pruebas de escritorio: 23 de acoplamiento y 2 del detector de audio, sin fallos en la última comprobación de esa implementación.
- Arrastre conjunto de biblioteca y reproductor.
- Separación de reproductor con cola asociada, manteniendo maximizado el resto durante la prueba inicial.
- Maximización de grupo y añadido de cola en la prueba inicial, antes de retirar esa acción de la interfaz Hyprland por petición del usuario.
- Inserción de secundarias dentro del mismo lado de la jefa.
- Intercambio entre lados con biblioteca en medio.
- Imán por el borde izquierdo y alineación de ventanas.
- Altura compartida desde el borde capturado por Hyprland.
- Cierre/reapertura y recuperación del orden tras reiniciar la aplicación.
- Menú de tres puntos compartido desde biblioteca.
- Aviso visible y grupo intacto cuando no cabe acoplar letra.
- Chincheta con reproductor solo; desactivación al unir y ausencia de recuperación automática al separar.
- Ajuste a áreas reservadas de Caelestia; casos de escala/rotación/coordenadas negativas cubiertos por pruebas de cálculo.

### Historial de funciones anteriores

`ESTADO_MODULAR.md` recoge pruebas Linux y móvil anteriores del transporte, bibliotecas grandes, transferencia y selectors de cuenta. Sus fechas y cifras son históricas: confirmar el resultado de la revisión Windows por separado.

### No comprobado todavía al escribir esta entrega

- Interfaz e interacción en Windows nativo.
- Instaladores Windows y comportamiento de la aplicación instalada.
- Atajo global Windows.
- Monitores físicos de distintas escalas y cambios de DPI Windows.
- Login completo Google en el escritorio con cuenta real.
- Importación Spotify real durante esta revisión.
- Escaneo físico del QR con cámara en la sesión anterior.
- Medición prolongada de batería del móvil.

No convertir estos pendientes en hechos verificados por haber leído el código o pasado una prueba JVM.

## 12. Cómo registrar y cerrar la revisión Windows

En `docs/VALIDACION_WINDOWS.md`, registrar:

- Fecha, rama y commit probado.
- Versión/edición de Windows, escala, resolución, monitores y JDK.
- Herramientas reales de captura/automatización usadas.
- Tareas Gradle ejecutadas y número real de pruebas.
- Resultado por área de la matriz, con evidencia de los fallos relevantes.
- Arreglos realizados y regresiones comprobadas.
- Resultado del paquete instalado.
- Pendientes con su requisito concreto: cuenta, móvil, segundo monitor o hardware.

Para cada arreglo, conservar pasos de reproducción suficientes para repetirlo después de un cambio. Si un gesto falla, registrar coordenadas, estado del grupo, escala, tamaños y eventos que recibió el adaptador. Si falla audio, registrar backend, formato, proceso y error sin URL firmada ni cookies.

El cierre debe expresar qué funciona de verdad y qué no se pudo comprobar. No sustituir ese balance por «todo arreglado» si quedan áreas bloqueadas.

## 13. Git y continuidad entre máquinas

- Esta entrega se publica en `feat/prueba-acoplamiento` para que Windows tenga el código completo y el contexto.
- `main` se conserva; no hacer force-push ni cambiar la rama por defecto para este traspaso.
- Antes de tocar, inspeccionar `git status`; conservar los cambios ajenos. Si hay trabajo concurrente, usar un worktree propio.
- Cuando se hayan pedido commits, uno por cambio lógico, con `tipo: resumen` en español.
- Email de commits del proyecto: `164151388+iaguito22@users.noreply.github.com`.
- Subir cambios solo cuando esté autorizado. No publicar paquetes/release ni enviar mensajes externos por asumir que una validación lo requiere.
- Nunca subir directorios de compilación, cachés de Gradle, datos de usuario ni secretos para facilitar un clon.
- Una corrección hecha en Windows debe mantener los límites de plataforma: código Hyprland condicionado y comportamiento Windows propio.
- Antes de transferir nuevas conclusiones, actualizar este documento si cambian reglas o trampas, y el informe de Windows con la evidencia real.

## 14. Referencia Android heredada

El contenido siguiente conserva las instrucciones originales del proyecto, especialmente las precauciones sobre el móvil real, audio, letras y datos. Sus versiones, rutas Linux y cifras de rendimiento son contexto histórico; contrastarlas con el código y el equipo actual antes de usarlas como estado presente.


# Referencia original — Oto

Reproductor de música Android (Kotlin + Compose + Media3) sobre InnerTube de YouTube Music.
Es personal: lo usa un solo usuario en su Nothing Phone (2). Se distribuye como APK en GitHub, nunca en Play Store.
Licencia GPL-3 (por usar InnerTubeX). Repo: https://github.com/iaguito22/oto

El usuario habla español: textos de la app, commits y respuestas en español.

## Reglas que no se discuten

- **Nunca** meter la clave `AIza...` (PoToken) en el código: va en `local.properties` -> `BuildConfig.GOOGLE_API_KEY`.
- **Nunca** `connectedAndroidTest` sin `ANDROID_SERIAL=emulator-5554`: sin eso corre en el móvil real y desinstala la app.
- **Nunca** restaurar una copia anterior de los datos del móvil (`oto.preferences_pb`, etc.): el usuario usa el móvil a la vez y se pierden sus cambios. Para limpiar pruebas, borrar solo las entradas que añadió la prueba.
- **Nunca** `Regex(...)` dentro de funciones calientes: causó un OOM nativo al pasar canciones rápido (233 MB -> 1,2 GB). Compilar fuera, una vez.
- **Nunca** reusar el token anónimo de `open.spotify.com/embed` para saltarse límites: se bloqueó. La API oficial de Spotify exige Premium del dueño.
- La clave de firma `~/.config/oto/oto-release.jks` (contraseña en `local.properties`, `OTO_KEYSTORE*`) no se pierde ni se sube: sin ella las actualizaciones no se instalan encima.
- Otra IA puede estar trabajando a la vez en el árbol. Mirar `git status` antes de tocar; si hay cambios sin commit ajenos, usar un worktree (`git worktree add ~/PROYECTOS/oto-<tema> -b <rama>`) y no mezclar.
- Fiarse de lo que el usuario ve en el móvil, no solo de las medidas propias (un recorte de letra que las medidas no cazaron).

## Compilar y probar

- JDK 21 obligatorio (el de por defecto es el 25):
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug` (~3,5 min la primera vez).
- Tests JVM: `./gradlew testDebugUnitTest` (la variante se llama `Debug`, no `Phone`).
- SDK en `~/Android/Sdk`. `adb` no está en el PATH: `~/Android/Sdk/platform-tools/adb`.
- Emulador `oto36` (Android 16, x86_64). **Se cuelga con la letra abierta** (sin GPU real): probar la letra en el móvil.
- Sin ImageMagick: para capturas, `ffmpeg`.
- Crashes: `adb logcat -b crash -d`; desofuscar con `app/build/outputs/mapping/phone/mapping.txt`.

### Variantes

| Variante | Qué es |
|---|---|
| `debug` | sufijo `.debug` |
| `phone` | release con sufijo `.debug`: es donde están los datos del usuario; no depurable |
| `bench` | release R8 firmada con clave debug, sufijo `.bench`, para medir fotogramas |
| release | paquete `io.github.iaguito22.oto`, firmada con la clave propia |

- Tras instalar `phone` o `bench` se pierde la cola: devolverle lo que sonaba.
- Con la `bench` delante, «atrás» lleva a su Oto real y los toques caen ahí: no usar `keyevent 4`.
- Para limpiar datos de `phone` hay que poner `isDebuggable` temporal (`run-as` necesita rutas `/data/data/...`).

### Móvil real

- Nothing Phone (2), A065, Android 16 / API 36, 1080x2412, 420 dpi, 120 Hz.
- adb inalámbrico a `192.168.1.147`. **El mDNS de adb no funciona en su red**: escanear puertos 30000-50000 (`bash /dev/tcp` en paralelo) y `adb connect`. El puerto cambia al reactivar la depuración.
- Su volumen multimedia suele estar a 0 y a veces está en una llamada de Discord: probar en silencio. Con cascos se puede poner música.
- No se puede bajar el volumen de BT por adb.
- `pgrep/pkill -f` con el patrón en la línea de comandos mata la propia shell: buscar pids con `ps -eo pid,comm | awk`.

### Medir rendimiento

- Medir en el móvil, no dar animaciones por buenas. Objetivo: casi 0 % de fotogramas tarde (hoy 0,13-0,28 % con gestos y hojas).
- `framestats` engaña si hay fotogramas en reposo y guarda solo 120 (1 s a 120 Hz): usar `dumpsys gfxinfo` (histograma) o perfetto + `trace_processor`.
- `input swipe` desde `y < 200` baja la cortina de notificaciones.

## Estructura

Módulos Gradle: `:app`, `:core` (lógica compartida), `:desktopApp` (Linux/Windows) y `:innertube`. Ver `PLAN_IMPLEMENTACION.md` para el escritorio. Antes: `:app` y `:innertube` (copiado de Metrolist b1dd1d0, con el paquete `com.metrolist.innertube` sin cambiar; InnerTubeX v0.10.0).
Paquete de la app: `io.github.iaguito22.oto`, en `app/src/main/kotlin/...`.

| Carpeta | Contenido |
|---|---|
| `playback/` | `PlaybackService` (MediaLibraryService + ExoPlayer), `Chunked`, `Fidelity`, `Downloads`, `Crossfade`, `Radio`, `Autoplay`, `Shuffle`, `Library` (Android Auto), `potoken/` |
| `ui/isla/` | La pantalla única: `Drop.kt` (AGSL + física de la gota), `Sheet`, `Lyrics`, `Tutorial`, `Panels`, `Output` |
| `ui/menu/` | La hoja Menú: `MenuSheet`, `MenuViewModel`, `Search`, `SaveTo`, `DownloadsView`, `Import`, `Suggest`, `MenuMemory` |
| `ui/theme/` | Tokens de Caelestia (`Tokens.kt`), tipografía Rubik variable empaquetada |
| `lyrics/` | `LyricsSource` (fuentes), `LyricsStore` (caché memoria + disco) |
| `dj/` | DJ, perfil de gusto, `Smart` (Explorar «Hecho con lo tuyo») |
| `importer/` | Importar de Spotify: enlace público o JSON de la extensión; `Matcher` |
| `account/` | Login de YouTube Music (WebView + cookies), copia de listas en la cuenta |
| `Prefs.kt`, `Storage.kt` | Preferencias (DataStore) y topes de caché |
| `tools/spicetify/` | `oto-export.js`, `install.sh`, `install.ps1` (extensión para exportar de Spotify) |
| `docs/` | Capturas y gifs del README (grabados con `screenrecord` en el móvil) |

Hay un `PatientExtractor.java` en `app/src/main/java/...` porque las clases de InnerTubeX son `internal` en Kotlin.

## Diseño del producto

- Concepto «La isla»: una pantalla con una **gota redonda** (forma B1, núcleo de luz, filtro goo; variante «Aro» por defecto, «Líquida» en Ajustes) que se transforma.
- La gota **no tiene controles ni texto «Buscar»**: minimalista, que dé ganas de tocarla.
- Gestos: tocar = pausa, a los lados = otra canción, subir = **Menú**, mantener = cola, bajar = letra. Se enseñan con un tutorial al abrir. Tocar solo cuenta en 52 dp del centro.
- Menú: pestañas **Explorar | Tu música**, buscador abajo, engranaje de Ajustes en la esquina. «Tu música» es la primera.
- Doble toque en la carátula = Me gusta (solo da, nunca quita). Mantener la carátula o tocar su marca = «Guardar en...» (`SaveTo.kt`).
- Mantener una lista/carpeta = opciones, incluido «Mover». No hay arrastre al mantener (no funcionaba).
- Ajustes: lista única en el orden de siempre. Probó apartados/páginas y lo descartó.
- Estética parecida a Caelestia / Spicetify, con interfaz distinta a la del resto de apps. Le gustan los bordes suaves y las filas de texto en columnas de 4 para canciones.
- Nombre: **Oto** (音). Descartados: Tsuyu, Gota, Marea, Rocío, Shizuku.
- Icono: anillo + núcleo blancos sobre `#252525`.
- Escala fija: `MainActivity.attachBaseContext` fuerza `densityDpi = ladoCortoPx * 160 / 411.43` y `fontScale = 1`, para que se vea igual que en su móvil con cualquier pantalla o tamaño de letra. En tabletas (lado corto >= 3,5") se respeta el sistema. Para probar: `wm density 540` + `settings put system font_scale 1.15`, y luego `wm density reset` + font_scale 1.0.

## Reproducción: lo aprendido

- **YouTube frena el audio pedido de una vez a ~33 KB/s**; a trozos de 1 MB va a ~5 MB/s: por eso existe `playback/Chunked.kt`. No pedir el audio entero.
- `Fidelity.kt`: `Tier` alta = clave `id`, baja = `id~l`; nunca se mezclan formatos. Auto/Máxima + ahorro de datos. Auto pasa a baja si no arranca en 6 s **y hay red validada**.
- `WholeSongLoadControl`: actual + siguiente enteras y nada más. No usar `PreloadConfiguration`.
- `PatientErrorPolicy`: sin red reintenta sin fin.
- `config` y `visitorData` se guardan (`oto_player_config`, `oto_visitor`). `/player` tiene 30 s (InnerTubeX daba 8 s).
- Caché de audio: SimpleCache 1 GB. Descargas: SimpleCache aparte en `filesDir/downloads` sin evictor; `OfflineFirstDataSource` va delante de todo.
- Topes en `Storage.kt`: audio 1 GB, carátulas 200 MB, HTTP 50 MB, DJ 20 MB.
- Poner UNA canción desde un controlador = radio detrás (`onSetMediaItems`). Radio = `RDAMVM` + id, se resiembra con la última de la cola; sin repetir (historial + `songKey` título+artista).
- El MediaController da un instante el id nuevo con `mediaMetadata` de la anterior: leer `currentMediaItem.mediaMetadata`.
- Fundido (`Crossfade`): cortar con `playWhenReady`, no `isPlaying`. `Crossfade.align` corrige el desfase (+-6 ms). El usuario lo tiene en «No».
- Android Auto: `PlaybackService` es `MediaLibraryService`; ids `in|lista|canción`. Hace falta `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` en `onConnect` (si no, «Oto no funciona»). Probar con el DHU (`~/Android/Sdk/extras/google/auto/desktop-head-unit`: necesita libc++/libc++abi, `SDL_VIDEODRIVER=x11 DISPLAY=:1`, sin sandbox, `adb forward tcp:5277 tcp:5277`, comandos por stdin: `tap x y`, `screenshot f`).
- «Anterior» de la gota = siempre la anterior (caché de disco, ~0,2 s). El botón del sistema reinicia pasados 3 s.
- InnerTube devuelve un 403 lento en ~1 de cada 11 lecturas: `InnerTube.hedged` (módulo innertube) lanza otra a 1,5 s o al fallar, hasta 3.
- Explorar sin ánimo pide la portada **sin sesión** (`YouTube.home(guest = true)`); con ánimo, con cuenta. Sin repetidos entre estanterías.

## Letra

- Fuentes en orden: lyricsplus (binimum; el workers.dev daba error 1042) -> NetEase -> LRCLIB (`estimate` portado del `theme.js` de Spicetify) -> YT plano. Romanización Google `dt=rm` (desde el móvil va, desde el PC no).
- lyricsplus limita a **2 peticiones / 10 s** (429: esperar y reintentar).
- Relleno y salto **letra a letra** con `drawText` sobre la palabra entera (kerning intacto). Pasar `alpha = 1f` a `drawText` con degradado o hereda la opacidad anterior.
- La letra sincronizada va en `LazyColumn`. La capa de la línea (`graphicsLayer`) va de borde a borde, con padding `EDGE` dentro: con alfa < 1 se pintaba recortada y comía la primera letra.
- `LEAD` 250 ms. Mantener la letra = romanización (`Prefs.romanize`).
- Arnés `LyricsHarness` (`OTO_LYRICS`, `_N`, `_PACE` >= 5500).

## Rendimiento de UI: trampas

- Si algo bajo una hoja cambia, se re-desenfoca el `Stage` entero cada fotograma: `LocalCovered` (barra, letra, hojas) para no dibujar lo tapado. Gota quieta en pausa a 30 fps.
- Fondo desenfocado en CPU (`blurredArtwork`, sigma ~9,8 px sobre la de 240).
- Descartado tras probarlo: fundido por franjas en vez de `Offscreen`; quitar `currentId` del menú.
- Pendiente conocido: el cambio de canción aún tiene un fotograma de ~40 ms.
- Arranque en frío 257 ms: `Prefs` decodifica listas/me gusta/historial una vez con `shareIn`. No volver a decodificar por canción en `MenuSheet` (rehacer `Taste` y `Smart.lists` en cada canción causó el OOM).
- Cola editable con `sh.calvin.reorderable` 3.1.0: no hacer el arrastre a mano. El gesto de reorderable se queda con el cierre del primer frame: estado único + `rememberUpdatedState`, no `remember(entries)`. En el deslizar para quitar, fondo por offset, no `progress` (vale 1 en reposo). Quitar de la cola también borra de `original` (si no vuelve con el aleatorio).
- Quitar/borrar en listas = `removeLater` (oculto en `gone`, se borra al irse el aviso).

## Datos

- DataStore (no Room): `oto` (prefs), `oto_lists` (listas locales), `oto_menu` (`MenuMemory`), `oto_account`.
- Me gusta = `LIKED_ID` tratado como `LocalPlaylist`. Carpetas = campo `folder` en `LocalPlaylist` + `folders` en prefs para las vacías.
- Copia en cuenta YT: manifiesto v2 (refs de 8 caracteres) + `ListMeta` en la descripción de cada lista.
- Importar de Spotify: enlace público (`__NEXT_DATA__`, **máx. 100 canciones**) o `oto-spotify.json` de la extensión. `Matcher` empareja por título/artista/duración (198/200 en su muestra). Arnés `MatchHarness` (`OTO_HARNESS=tsv`).
- Reinstalar la `phone` borra la cola.

## Flujo de trabajo

- Commits con el email noreply: `git -c user.email=164151388+iaguito22@users.noreply.github.com commit ...`. Mensaje `tipo: resumen` en español.
- Push con la cuenta `iaguito22` (la activa de `gh` puede ser otra): `gh auth switch -u iaguito22`, o `credential.helper` en línea con `gh auth token -u iaguito22`.
- Release: subir `versionCode`, APK firmado y `oto-export.js` a GitHub. Última conocida: **v1.2.0** (versionCode 10). Si se rehace una versión, borrar release y tag antiguos.
- Tests y arneses JVM viven en `app/src/test/`: `DjTest`, `SmartTest`, `AutoplayTest`, `ShuffleTest`, `BlurTest`, `ManifestTest` y los `*Harness` (leen variables de entorno `OTO_*`).
