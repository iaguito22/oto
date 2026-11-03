# Validación de Oto en Windows

Revisión del 02–03/11/2026. Rama `feat/prueba-acoplamiento`, base de la sesión Windows `0142c05fb09e1bd30e379897ec88839578c03a11`.

## Continuidad para Linux

Este conjunto contiene las correcciones de Windows y código compartido; revisar el diff completo antes de integrarlo en otros árboles. No sustituye el proyecto Android integrado `oto-movil-integrado`, que no estuvo accesible en Windows. El fallo al escanear QR en su APK 1.2.2 sigue sin resolver.

La última compilación Windows pasó `:desktopApp:test :desktopApp:packageMsi`. El usuario escuchó correctamente el audio y una transición con fundido; el fundido de dos motores se implementó solo para Windows y no se ha validado en Linux. El acceso a la cuenta se completó pegando la cookie localmente; no se incluyen credenciales ni datos del perfil en Git. Los «Me gusta de Oto» usan una lista propia al importar/exportar, separada de los «Me gusta de YouTube Music».

La última versión instalada incluye fondo compartido y esquinas redondeadas en grupos, animaciones, aleatorio al elegir una canción y desacoplamiento del reproductor al cambiar de vista. Estas últimas interacciones necesitan más prueba visual y de comportamiento; el resultado automático no sustituye una prueba en Linux/Hyprland. La barra de tareas y el atajo nativos de Windows tienen implementaciones específicas de ese sistema. No se han probado las operaciones reales de exportación tras cambiar la gestión de «Me gusta».

## Cambios posteriores: fundido y reproductor

En Windows se prepara la siguiente pista en un segundo motor y se solapan ambas con una curva de potencia constante. Si no llega a estar lista, se conserva la transición normal; pausar, saltar o cambiar la cola cancela el solapamiento. Las descargas para reproducción usan temporales independientes para evitar reutilizar un archivo incompleto. En Ajustes se puede elegir 0, 3, 6 o 10 segundos.

La prueba de integración con dos WAV silenciosos verificó que la segunda pista empieza antes de finalizar la primera y que pausar detiene la saliente. También pasaron las tres pruebas de audio y las dos de descarga por trozos con integración habilitada. El usuario confirmó que una transición con canciones reales se oye bien en Windows. Queda por comprobar con distintas velocidades de red y dispositivos de audio; no se ha validado el fundido en Linux.

## Entorno y aislamiento

- Windows 11 Pro 10.0.26200, sesión gráfica interactiva 1.
- Un monitor físico de 1920 × 1080 al 125 %. AWT: 1536 × 864 lógicos; área útil 1536 × 816.
- Git 2.56.0; Temurin JDK 21.0.13+9 portable; Gradle Wrapper 9.8.0 con SHA-256 verificado.
- SDK Android: plataforma 37.0, build-tools 36.0.0; herramientas locales, sin claves privadas.
- FFmpeg, ffplay y ffprobe 9.0.2 essentials, preparados en el espacio de trabajo sin cambiar el PATH global.
- Interacción nativa mediante la habilidad Computer Use (`@oai/sky`). No se ha utilizado WSL para acreditar Windows.
- Navegador predeterminado: Zen. Brave está instalado en Program Files.
- APPDATA y LOCALAPPDATA propios del proceso en perfiles de prueba, incluido uno con espacios y `ñ`. No se ha restaurado ni sustituido la biblioteca real.
- No se han publicado cookies, datos de sesión, QR, secretos de vinculación, DataStore ni claves.

## Defectos reproducidos y cambios

### W01 — Falta el Wrapper de Windows

El clon original no contiene `gradlew.bat`. Se generó con la tarea oficial `wrapper` de Gradle 9.8.0 en un proyecto temporal, manteniendo el JAR, versión y checksum originales. El script añadido ejecuta compilación, pruebas y empaquetado nativos.

### W02 — La réplica no sustituye su archivo existente

`LibraryReplicaTest.concurrentSongsMergeAndDeletionSurvivesReconnect` fallaba porque `renameTo` no reemplaza el destino en Windows. Se utiliza `Files.move` con `ATOMIC_MOVE` y `REPLACE_EXISTING`, con alternativa si no hay movimiento atómico. Pasan las dos pruebas existentes, con concurrencia, borrado, reconexión y reinicio. Compilación Android comprobada. El protocolo no se modificó.

### W03 — Atajo global ausente en Windows

Se añade `RegisterHotKey` mediante JNA, `MOD_NOREPEAT`, hilo de mensajes y liberación al cerrar. Los atajos inválidos o en conflicto muestran un aviso. Se mantiene la rama Hyprland. Pruebas reales de registro, conflicto y liberación pasan. Desde Bloc de notas, Ctrl+Alt+O oculta y recupera el reproductor sin interrumpir el audio ni crear otra instancia.

### W04 — Descubrimiento de navegador y acceso rechazado por Google

El descubrimiento incluye Program Files, Program Files (x86), LocalAppData y PATH, sin `which` en Windows. Se corrigió también la lectura del mapa de entorno: Windows conserva claves como `ProgramFiles` y `Path`, mientras `System.getenv()` devuelve un mapa sensible a mayúsculas. Las pruebas cubren rutas con espacios y esas variantes de nombres.

La prueba nativa abre Brave con perfil temporal correctamente. El usuario intenta iniciar sesión y Google muestra «Es posible que el navegador o la aplicación no sean seguros». Esto bloquea el recorrido de autenticación; no se da por probado un login completo. Google documenta el posible rechazo de navegadores controlados por software: https://support.google.com/accounts/answer/7675428?co=GENIE.Platform%3DDesktop&hl=es . Es compatible con el flujo CDP usado, sin demostrar por sí solo la causa exacta.

La primera opción de la interfaz pasa a ser «Entrar con tu navegador», que abre el navegador predeterminado (Zen en este equipo). Se explica que abrir YouTube Music no conecta automáticamente la sesión: hace falta la extensión o el comando local del flujo ya existente. La opción temporal queda identificada y ofrece salida al navegador habitual si Google la rechaza. No se han alterado protecciones del navegador o de Google.

Se comprobó la apertura en Zen y la cancelación del flujo alternativo antes de este ajuste de etiquetas. El usuario confirmó que sí podía entrar en YouTube Music con Zen, pero Oto no reconocía la sesión: la apertura del navegador no realiza la transferencia. Completó «Pegar cookie» dentro de Oto, sin pegar credenciales en el chat. Oto mostró su cuenta y selecciones de importar/exportar a cero. Se cerró desde el menú y se arrancó de nuevo el paquete instalado con el mismo perfil: la cuenta seguía visible en Ajustes y la búsqueda/reproducción funcionaban. No se importaron ni exportaron listas de la cuenta. El cierre de Oto cancela el login; CDP termina las llamadas pendientes si se cierra la conexión.

### W05 — No se puede retirar la canción actual de la cola

En la interfaz original, pulsar su X no hacía nada. Ahora se selecciona la siguiente, o la anterior si no existe, conservando reproducción o pausa. Retirar la última detiene y limpia el estado. Se corrigen índices publicados al mover/quitar y la identificación de una aparición concreta cuando hay duplicados. Dos pruebas de cola pasan. Repetición nativa en el paquete instalado: retirar la actual de una cola de 50 deja 49, resalta la siguiente y conserva la pausa, sin procesos de audio. Activar aleatorio cambia las siguientes sin mover la actual; desactivarlo recupera el orden anterior y mantiene 49 entradas.

### W06 — Audio del instalador dependiente del entorno de desarrollo

El paquete original no incluía las herramientas. La tarea `prepareWindowsAudio` copia FFmpeg, ffplay y ffprobe, licencia y README al recurso nativo. Empaquetar falla claramente si falta alguna herramienta. La aplicación busca primero sus recursos instalados, después `OTO_FFMPEG_DIR` y PATH; reconoce `.exe` y nombres de variables Windows con distinta capitalización.

Una copia nativa se ejecutó con PATH limitado a Windows y sin JDK ni FFmpeg externos: utilizó el ffplay de sus recursos, y el usuario confirmó audio real. El último MSI se instaló y arrancó también con PATH limitado a Windows; abre biblioteca, letra, reproductor y cuenta. Reprodujo Get Lucky mediante el FFmpeg de `app/resources/audio`, y el usuario confirmó que se oye bien, sin cortes ni distorsión. Bajar el volumen al 59 % mantuvo el PID del decodificador y el avance de la canción.

### W07 — Colisión entre carpeta de instalación y caché

El instalador original utiliza LocalAppData/Oto, coincidiendo en Windows con LocalAppData/oto, la caché que puede limpiarse desde la aplicación. La instalación pasa a `Oto Desktop` y la caché Windows a `oto/Cache`. Linux y la inicialización Android conservan sus rutas. No se han movido ni eliminado los datos anteriores. Dos pruebas de rutas pasan.

Se añadió icono ICO basado en el PNG del proyecto. La instalación MSI para el usuario actual terminó con código 0 y creó el acceso de Inicio. Un intento de ruta anidada provocó ICE64 de WiX/jpackage; la ruta final evita ese fallo sin modificar el sistema de instalación.

Se generaron de nuevo MSI y EXE con el logo bajado y los cambios de acceso; ambas tareas terminaron correctamente. Reemplazar un MSI distinto de la misma versión mediante reparación silenciosa devuelve 1603 (`SecureRepair`, elevación deshabilitada en modo silencioso). Se registra como límite de ese recorrido, sin cambiar políticas. La desinstalación normal del producto de prueba y la instalación limpia del nuevo MSI terminaron con código 0. El perfil aislado se conservó y la aplicación instalada recuperó la disposición guardada. No se considera acreditada la actualización directa entre versiones de publicación.

### W08 — Cancelación, reintento y descarga truncada

El descargador detecta respuestas 200 que ignoran Range y cuerpos más cortos de lo esperado, sin marcar como completo un archivo truncado. Se comprueba cancelación antes de completar.

Retirar una descarga cancela y espera a su trabajo antes de borrar archivos, necesario para Windows. Detener/limpiar cancela también las resoluciones activas; una descarga fallida muestra error y reintento. Pasan dos pruebas HTTP locales y una de cancelación durante la resolución, sin archivos huérfanos. La descarga nativa de Get Lucky terminó con un WebM de 4 155 462 bytes y persistencia del índice.

### W09 — Errores de audio y volumen que reinicia la reproducción

Un ffplay puede terminar con código 0 incluso al recibir audio inválido. Se detecta ausencia de audio decodificado, se limpia reproducción/buffering y se comunica el error. Reintentar desde el reproductor vuelve a iniciar la pista.

Cambiar el volumen reiniciaba el proceso, provocando cortes y reposicionamiento. Una prueba de integración reprodujo el cambio de PID. En Windows se utiliza FFmpeg para decodificar y Java Sound para entregar PCM al dispositivo; el volumen se aplica a las muestras sin reiniciar el proceso. Linux conserva ffplay.

Tres pruebas de integración ejecutadas con `OTO_AUDIO_INTEGRATION=1` pasan en el dispositivo Windows: pausa/reanudación/seek/fin sin procesos huérfanos, entrada inválida con error y ajuste de volumen manteniendo el mismo decodificador y posición no decreciente. Utilizan WAV silencioso local. Además, el usuario confirmó música real con el nuevo backend del paquete instalado y se comprobó el volumen durante reproducción.

### W10 — Ventanas que heredan tamaño/posición de otra al cerrar

Cerrar reproductor hacía que biblioteca recibiese su posición/ancho y que la siguiente apertura heredase tamaños. Las trazas del frame AWT identificaron reutilización de identidad entre composiciones. Se añade identidad estable por módulo tanto al estado recordado como a `Window`.

Repetición nativa: cerrar/reabrir conserva biblioteca de 960 dp y reproductor de 320 dp. Abrir/cerrar letra deja tamaños coherentes. Las trazas comparan geometría esperada y real mediante `OTO_DOCK_TRACE`, sin activarlas en ejecución normal.

### W11 — Restaurar un grupo con un módulo añadido lo deja fuera de pantalla

Añadir letra al grupo maximizado y restaurar dejaba reproductor parcialmente fuera del monitor. Al restaurar se ajusta el ancho flexible cuando la suma no cabe y se limita el grupo al área útil. Hay prueba de regresión con área 1536 × 816 y mínimos reales. Pasan las 24 pruebas del modelo de acoplamiento.

### W12 — Biblioteca compacta y menú redundante

Por petición del usuario se quitan los tres puntos de biblioteca. Las opciones permanecen en reproductor y sus ubicaciones existentes.

Al compactar a 800 dp o menos, el logo pasa a 34 dp y se sitúa en la cabecera; los iconos laterales mantienen 18 dp, centrados en filas de 44 dp, sin comprimirse por el padding. Hay ayudas con el nombre al pasar el ratón. Se conserva la navegación con texto en la vista ancha. Tras revisión del usuario, el margen superior compacto pasa de 5 a 12 dp para bajar el logo 7 dp.

La ventana nativa se comprobó en ancho compacto de 736 dp y ampliado de 1056 dp. El último instalador se abrió y se restauró de ampliada a compacta; el logo mantiene el nuevo margen de 12 dp. Captura renovada: `evidencias/biblioteca-compacta.jpg` en los entregables.

### W13 — Cierre inaccesible en reproductor vertical

Al pasar con Ctrl+Mayús+M por Carátula, Horizontal y Vertical, el reproductor de 112 × 320 dp no tenía espacio para todos los controles de cabecera: la X quedaba fuera. Por debajo de 180 dp se utilizan áreas de acción de 30 dp y se omiten el indicador de jefa y la maximización de cabecera. Por petición posterior del usuario, en Vertical la chincheta se sitúa abajo, tras los accesos a módulos, y solo aparece cuando está solo. Cabecera con menú y cierre; carátula flexible para reservar sitio a los controles. Las cabeceras anchas conservan sus controles habituales. Repetición nativa con el último MSI: vista 112 × 320, menú y X visibles arriba, chincheta accesible abajo. Se deja abierta para revisión del usuario.

### W14 — Letra y Cola más estrechas

El usuario solicita poder compactarlas más. Mínimos de modelo y marco unificados: Letra 220 dp y Cola 240 dp, conservando los tamaños iniciales 480/420. La cabecera de Cola distribuye las acciones en otra fila cuando falta espacio y sus filas estrechas reservan el texto omitiendo el número de posición; siguen disponibles carátula, arrastre y quitar. Compilación y pruebas de escritorio pasan. Comprobación nativa final del MSI: ambas abren a esos anchos; Letra ajusta su estado vacío y Cola mantiene título, aleatorio/bucle y estado vacío. Se dejan abiertas para el feedback visual del usuario; la lectura de letras y filas pobladas en estos nuevos mínimos no se presenta como verificada.

## Recorridos nativos acreditados

- Inicio aislado con biblioteca y reproductor a su derecha, unidos; reapertura y persistencia.
- Búsqueda real de «Daft Punk Get Lucky», canciones y artista; reproducción y radio de 50 entradas.
- El usuario confirmó que Get Lucky se oía bien con el backend ffplay anterior al ajuste de volumen. Pausa, reanudación, seek en pausa a 2:04 y avance observado.
- Letra sincronizada visible, seguimiento y salto al pulsar una línea.
- Crear «Codex Windows prueba», fijarla, crear «Codex carpeta ñ», mover la lista, entrar en carpeta/lista y guardar con Ctrl+B. Me gusta y Guardar muestran selección y recuento coherentes.
- Descargar Get Lucky, archivo e índice persistidos y vista Descargas con 1 canción / 4,0 MB.
- Escape cierra biblioteca conservando reproductor/letra; Ctrl+F la recupera. Una segunda ejecución termina en unos 3 s y recupera el proceso existente. Salir no deja ffplay/ffmpeg.
- Grupo maximizado ocupa el área útil, sin tapar la barra. Añadir letra redistribuye anchos; una cola que no cabe se abre flotante y mantiene el grupo. El aviso concreto no quedó acreditado visualmente.
- Arrastrar jefa mueve el conjunto; separar/unir reproductor; chincheta disponible solo al quedar solo, se desactiva al unir y no se recupera al separar de nuevo.
- Redimensionado vertical de biblioteca propaga altura a letra (740 a 661 dp); reproductor separado conserva su altura.
- Persistencia de la disposición al reiniciar y transición compacta/ampliada de biblioteca.
- Cuenta transferida manualmente desde Zen por el usuario; nombre reconocido y persistencia después de salir/reabrir. Importar/exportar empiezan con cero selecciones. Sin operaciones de listas remotas.
- Navegación desde una pista importada a Daft Punk, carga de destacadas y álbumes, apertura de Random Access Memories y carga de sus pistas. Las tres formas del reproductor se abren y conservan estado; se encontró y corrigió W13 durante la revisión estrecha.
- Importación nativa desde FileDialog: JSON inválido muestra error; archivo válido sin listas muestra cero y deshabilita importar; muestra de una lista/una canción termina con «No faltó ninguna canción». La carpeta y lista de prueba aparecen en biblioteca, con la pista Get Lucky emparejada. Captura: `evidencias/importacion-correcta.jpg`. Es una muestra preparada, no una exportación de la cuenta Spotify real del usuario.

## Pruebas automatizadas

Última ejecución completa: `:core:test :desktopApp:test :app:compileDebugKotlin :app:testDebugUnitTest :innertube:test`, con JDK 21 y `OTO_AUDIO_INTEGRATION=1`. Resultado: **BUILD SUCCESSFUL**.

| Módulo | Ejecutadas | Omitidas | Fallos |
|---|---:|---:|---:|
| core | 37 | 4 | 0 |
| desktopApp | 39 | 0 | 0 |
| app (unidad debug) | 1 | 1 | 0 |
| innertube | 32 | 0 | 0 |
| Total | 109 | 5 | 0 |

Los cinco casos omitidos son arneses que requieren condiciones externas; no se cuentan como éxitos. Compilación Android comprobada sin instalar un APK diferente sobre el móvil del usuario. Hay avisos de Gradle sobre futuras incompatibilidades de APIs obsoletas; no falló la compilación.

Después de los últimos ajustes visuales, `:desktopApp:compileKotlin :desktopApp:test :desktopApp:packageMsi :desktopApp:packageExe` vuelve a pasar. Esta ejecución omite los tres casos de integración de audio porque no activa `OTO_AUDIO_INTEGRATION`; el audio ya se había comprobado en la ejecución completa anterior y con música instalada. A petición del usuario se entrega ahora el control de la aplicación para acelerar el feedback visual, manteniendo los límites de la matriz.

## Estado por área y límites concretos

| Área | Estado y alcance |
|---|---|
| Arranque, instancia única y persistencia | Recorridos nativos acreditados en perfiles aislados. |
| Bandeja y salida | Salida completa comprobada. No se acredita todo el menú de bandeja. |
| Ventanas | Modelo y varios gestos nativos comprobados; intercambio cruzando jefa no repetido físicamente en Windows por límites del arrastre de la herramienta. |
| DPI y monitores | Un monitor al 125 %. Faltan otras escalas y varios monitores físicos; los cálculos tienen pruebas de áreas/coordenadas. |
| Audio | Música real confirmada con ffplay y PCM instalado; volumen sin reinicio y pausa acreditados. Faltan teclas multimedia y cambio de dispositivo. |
| Biblioteca | Búsqueda, crear/fijar/mover/guardar, persistencia y recorrido canción → artista → álbum comprobados. Faltan varias ediciones y borrado/deshacer. |
| Cola y radio | Radio, retirada de la actual y aleatorio activado/desactivado probados en UI; duplicados/índices cubiertos por pruebas. Falta recorrido completo del bucle y reordenación gráfica. |
| Letras | Sincronizada y salto comprobados. Faltan sin letra, romanización y casos largos/duetos. |
| Descargas | Descarga real y persistencia; HTTP/cancelación probados. Falta reproducción nativa sin red y limpieza completa de almacenamiento. |
| Cuenta | Transferencia manual desde Zen completada por el usuario, cuenta y persistencia tras reiniciar acreditadas. Selección inicial a cero; faltan recorridos completos de selectores sin ejecutar escrituras remotas. |
| Importación | Parser y recorrido nativo con archivos inválido, sin listas y válido con una pista: completado. Carpeta/lista/pista comprobadas en biblioteca. Falta una exportación Spotify real y un enlace público real. |
| Dispositivos | Pantalla y QR de escritorio comprobados; permiso privado de firewall concedido por el usuario. Vinculación bloqueada por crash del APK móvil. |
| Instalador | Último MSI instalado con código 0 y arranque sin herramientas externas. Desinstalación/reinstalación conserva el perfil. Reparación silenciosa con otro MSI de igual versión falla por SecureRepair; falta probar una actualización entre versiones. |

## Bloqueo del móvil y partición Linux

El usuario intentó escanear el QR y la aplicación Android se cerró al pulsar escanear. Se recogió el crash mediante ADB, sin reinstalar ni borrar datos. El APK instalado es 1.2.2 (versionCode 12); este clon contiene Android 1.2.0 (versionCode 10) y carece de la pantalla Dispositivos. El código integrado pertenece al otro árbol `oto-movil-integrado`, rama `feat/control-local`.

El fallo es una NPE en código ofuscado durante la inicialización de ML Kit: la dependencia SharedPrefManager recibida del contenedor es nula. El registrador y el proveedor figuran en el APK. Sin fuentes y mapping de ese build, no se atribuye definitivamente a R8 ni se presenta un arreglo especulativo como probado.

El usuario sitúa ese árbol en `/PROYECTOS` de otra partición Linux de este mismo equipo. Se identificaron particiones Linux, pero Windows no las monta; no hay WSL ni carpeta compartida. La sesión no es administradora y abrir el disco físico en solo lectura devuelve acceso denegado. No se han instalado controladores ni escrito en esas particiones. Para continuar hace falta acceso de lectura a los fuentes/mapping o copiar el proyecto desde Linux, conservando también la firma original al preparar una actualización.

Los logs privados del móvil y del navegador permanecen fuera del repositorio y de los entregables. La red Windows sigue figurando como pública; el usuario permitió Oto solo en perfil privado. No se han cambiado perfiles ni desactivado el firewall. El crash ocurre antes de escanear, por lo que una conexión física completa todavía no puede validarse.
