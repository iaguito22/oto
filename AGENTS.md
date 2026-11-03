# Oto — continuidad después de /clear

Actualizado: 03/11/2026, cierre de la release 1.2.3. Actualizar este archivo después de cambios importantes o antes de un /clear. Distinguir implementado, probado, instalado y publicado.

## Punto de partida

- Repo: https://github.com/iaguito22/oto; rama canónica `main` (unificación de la antigua rama `feat/prueba-acoplamiento`).
- Clon: `C:\Users\iago\Documents\Codex\2026-11-02\files-pasted-by-the-user-encargo\work\oto`.
- Los cambios acumulados ya están confirmados y subidos. Empezar con `git status --short --branch` y conservar cualquier cambio posterior del usuario.
- Estado vigente: bienvenida, valores por defecto, instalador y paquetes 1.2.3 terminados. Windows instalado y probado con el perfil habitual conservado. Las secciones antiguas «EN CURSO» son históricas; este punto de partida prevalece.
- Validación final: core 56 pruebas (5 omitidas), desktop 71 (5 omitidas), sin fallos; compilación y pruebas Android correctas. Ubuntu instalado y arrancado bajo Xvfb, incluidos tres ciclos de ocultar/mostrar: Actions 41724981355. Arch instalado y comprobado en contenedor: Actions 41724548965.
- Corregido el cierre involuntario de la composición al ocultar la última ventana: ventana invisible de sesión mantiene bandeja y servidor DesktopAccess activos. Probado también en Windows instalado. Regresión en `tools/check-desktop-session.py`.
- Defender terminó el análisis del MSI final sin amenazas. MSI y lanzador siguen sin firma Authenticode: no garantizar ausencia de avisos SmartScreen ni desactivar protecciones.
- Release: https://github.com/iaguito22/oto/releases/tag/v1.2.3; MSI Windows, DEB Ubuntu, paquete Arch, APK Android phone y SHA256SUMS. El APK phone conserva la firma personal y el identificador `.debug`; la clave de la antigua variante pública sin `.debug` no está disponible. La ruta de `prompt.md` correspondía a la clave personal, no a la pública.
- El AGENTS anterior completo está preservado en `docs/AGENTS_HISTORICO_20261102.md`. Consultarlo por tema; sus fechas y pendientes son históricos. Este estado y las peticiones nuevas prevalecen ante contradicciones.

## Cómo trabajar con Iago

- Español para respuestas, interfaz y commits; frases claras y breves.
- Ejecutar lo autorizado sin pedir permisos rutinarios repetidamente. El usuario pidió rapidez y autonomía.
- Respetar su feedback visual. Quiere animaciones presentes, variadas, con dirección y rebote, pero fluidas.
- Navegador predeterminado: **Zen**. Google bloqueó login en navegador temporal; cookie pegada por él dentro de Oto sí funcionó. Nunca pedir cookies en el chat.
- Capturas permitidas. Su borde amarillo NO debe quedar permanente: cerrar/resetear la sesión de captura al acabar. Con sky funcionó `mcp__node_repl__js_reset`.
- Leer la habilidad computer-use antes de automatizar UI. No automatizar autenticación ni avisos de seguridad.
- Cerrar Oto desde bandeja o «Salir de Oto» para actualizar. Las X mantienen el proceso en segundo plano. Evitar terminarlo a la fuerza y modificar DataStore abierto.
- No declarar fluidez por screenshots o compilación. Tras dos hipótesis fallidas, instrumentar antes de seguir cambiando código.

## Compilación en ESTE Windows

Desde la raíz del repositorio, PowerShell:

```powershell
. ..\env.ps1
.\gradlew.bat :core:test :desktopApp:test --offline --no-configuration-cache
.\gradlew.bat :desktopApp:packageMsi --offline --no-configuration-cache
```

- `../env.ps1` configura JDK 21, FFmpeg y **GRADLE_USER_HOME=../gradle**. Sin ello puede parecer que faltan Java/dependencias ya disponibles.
- Wrapper actual Gradle 9.8.0. WiX/empaquetado falla con configuration cache: usar el indicador anterior.
- Empaquetado Windows actual: `desktopApp/package-windows.ps1`; MSI: `desktopApp/build/dist/Oto-1.2.3.msi`. Ver `docs/PUBLICACION.md` para WiX y los paquetes Linux.
- Instalación: `%LOCALAPPDATA%\Oto Desktop\Oto.exe`.
- Actualizar misma versión ha requerido desinstalar paquete y después instalar MSI con msiexec `/qn /norestart`; comprobar códigos 0. Obtener UninstallString actual del registro; no asumir GUID antiguo. Datos conservados aparte.
- Usar `Start-Process -WindowStyle Hidden`. No matar todos los java.exe (hay daemons Gradle).
- Dos Oto.exe pueden ser lanzador y JVM; medir el de mayor memoria.

## Arquitectura y archivos útiles

- `:core`: preferencias, cuenta, listas, letras, DJ y enlace compartido; los cambios pueden afectar Android. `:desktopApp`: Compose Desktop y audio. `:app`: Android.
- Entrada: `desktopApp/.../desktop/Main.kt`, `ModularDesktop.kt`.
- Ventanas: `DockLayout.kt` geometría pura; `DockWindows.kt` AWT/gestos; `DockPersistence.kt`; pruebas `DockLayoutTest.kt`.
- UI activa: `ui/LibrarySpace.kt`, `SettingsSpace.kt`, `DownloadsSpace.kt`, `PlayerSurface.kt`, `MobileLyrics.kt`. `DesktopStage.kt` y `DesktopMenuSheet.kt` contienen código antiguo y componentes reutilizados: seguir llamadas reales.
- Audio/cola/fundido: `playback/DesktopPlayerConnection.kt`, `DesktopAudioEngine.kt`, `DesktopBeat.kt`.
- Carátulas: `ui/DesktopTheme.kt` (ArtworkLoader), `PlayerBackdrop.kt`.
- Cuenta: `core/.../account/Account.kt`, `Cloud.kt`. Enlace móvil: `DeviceConnection.kt`, `remote/LibraryReplica.kt`, `LinkedPlayback.kt`.

## Datos y sincronización

- Perfil real: `%APPDATA%\oto`, DataStore en `datastore/`, distribución `modular_windows.json`. Caché Windows actual: `%LOCALAPPDATA%\oto\Cache`; descargas: `%APPDATA%\oto\downloads`.
- Hay perfiles de prueba en `../perfil visual`, `../perfil geometria`, etc. Confirmar perfil efectivo, no inferirlo de un log antiguo.
- Nunca subir cookies, secretos QR, DataStore, claves de firma, perfiles o logs privados a Git. Nunca restaurar respaldo antiguo sobre cambios nuevos del móvil.
- No ejecutar connectedAndroidTest contra móvil real. Ver reglas Android históricas si hace falta.
- Usuario quiere Me gusta de Oto separados de YouTube, sobre todo importación/exportación. Backup remoto «Me gusta de Oto»; likes YouTube como lista independiente.
- Se vaciaron SOLO likes del PC, con respaldo y retirando registros locales de réplica sin tombstones para móvil. UI verificada con 0 canciones. Último vaciado: 115 entradas; otro anterior: 440. No confundir fechas/perfiles.
- Decisión vigente: Me gusta de Oto SÍ compartidos entre PC y móvil; separados únicamente de los likes de YouTube. La primera vinculación reúne los de Oto de ambos, sin duplicados por id. No volver a vaciar ni separar likes entre dispositivos por la interpretación anterior. Los desmarcados explícitos se conservan. Queda verificar la vinculación de los perfiles habituales; las pruebas con teléfono real usaron datos aislados.

## Cambios recientes instalados y con tests pasados

- Ahorro RAM persistente `Prefs.ramSaver`, false por defecto: ahora limita la caché de imágenes por memoria estimada (48 a 8 MiB, máximo 96 entradas), además de cancelar precarga de siguientes portadas y precarga de audio/fundido. Al quitarlo recupera duración de fundido elegida. No fuerza GC ni promete liberar memoria inmediatamente.
- Ajustes indican cualitativamente CPU/GPU/RAM. Fundido señala RAM y CPU y aparece desactivado con ahorro RAM.
- `MemCache.resize` desaloja LRU excedentes inmediatamente; `MemCacheTest` lo cubre.
- Descargas internas: archivos .webm/.m4a con nombre por id; no cambiar nombres sin migrar índice. Se retiró el botón «Abrir carpeta de descargas»; ahora se exportan copias MP3 reproducibles desde «Exportar como MP3…».
- Latido: State leído dentro de graphicsLayer, evitando recomponer toda biblioteca/reproductor por frame; margen en portada para no cortar crecimiento.
- Letras: reloj detenido sin letra sincronizada; tiempo por palabra limitado al intervalo con cambios visuales para reducir invalidaciones estáticas. Mantiene sincronización; vibrato solo letra a letra.
- Apertura: sin Offscreen forzado; espera frames estables antes de transición (mínimo 150 ms, máximo 1200 ms, tres intervalos <35 ms). Cierre desmonta al terminar animación, no con temporizador fijo de 380 ms.
- Última instalación contiene ese arreglo de espera. Últimos tests core/desktop y MSI pasaron. Quedan mejoras de animaciones, no afirmar que todo esté resuelto.

## Evidencia de rendimiento: 03/11/2026

- Prueba real de **9 min 48 s**, tres ventanas, reproducción y cambios automáticos. CSV local `../performance-long-20261103.csv`.
- CPU principal media 49.13 % de UN núcleo, intervalos 20.96–78.71 %. No es porcentaje de todo el equipo, ni incluye CPU FFmpeg.
- Residente 759.29–821.20 MB, media 791.34 MB; privada 910.57–957.97 MB. Sin crecimiento sostenido en esa muestra; no descarta fugas a largo plazo.
- Muestras cortas: pausa ~1.4 % de núcleo; reproduciendo sin letra ~37 %. Canciones/cargas distintas: no afirmar A/B concluyente.
- Diagnóstico optativo `OTO_MOTION_TRACE=1`: escribe `%APPDATA%\oto\motion-frames.jsonl`. Mide intervalos de callbacks de animación, NO presentación GPU ni FPS reales. En la medición histórica se arrancó con diagnóstico activo; no asumir que la instancia actual lo tiene habilitado.
- Antes: aperturas iniciales solo 2–5 callbacks, pausas 200–750 ms. Después: reproductor/letra ~50 callbacks, medias ~9–10 ms; biblioteca inicial pico 116 ms.
- Última biblioteca: cierre 39 callbacks, media 8.8 ms, máximo 22.9 ms; reapertura 38 callbacks, media 13 ms, máximo 66 ms (excluyendo primer intervalo). Sigue pendiente ese pico.
- JFR no disponible en runtime empaquetado (falta jdk.jfr). jcmd Thread.print sí funcionó; no confundir muestreo de hilos con perfil completo.
- CSV usa decimales españoles: para estadística Parse con cultura es-ES; cast PowerShell/invariant puede interpretar comas como miles.
- Al terminar aquella medición se cerró la captura y se dejó el escritorio sin canción. Para el estado más reciente del móvil, ver la sección de instalación más abajo.

## Pendientes reales para retomar

1. Vincular el móvil habitual con el Windows mediante Dispositivos/QR si aún no lo ha hecho Iago; comprobar sincronización con sus perfiles reales. Canal y reglas probados con teléfono físico y datos aislados. No afirmar que los perfiles habituales ya están sincronizados.
2. Para después, por petición explícita: pantalla de bienvenida del primer arranque, revisar valores por defecto y pulir fluidez/animaciones (incluido pico de biblioteca). No cambiarlo de oficio al retomar otro tema.
3. Medir ahorro RAM on/off con misma carga. Implementado/instalado, sin A/B prolongado todavía.
4. Duplicados YouTube: eliminadas 14 copias comparadas, conservando otra de cada lista. Quedan grupos distintos o sin auditar. Local `../playlist-audit.json`. No borrar por nombre solo.
5. Cloud anti-duplicados reutiliza ids, bloquea nombres y reintentos ambiguos, pero consulta Music; listas visibles solo en YouTube normal pueden escapar. NO garantizar resolución total.
6. Muchos cambios locales sin publicar. Revisar diff y datos privados antes de commit/push. No asumir que Codex Linux tiene este estado.

## Decisiones visuales recientes que conservar

- Fondo integrado entre ventanas, esquinas redondas incluso interiores, sin líneas rectas de unión. Carátula completa con laterales reflejados/extendidos evitando franjas planas grandes. Intensidad inicial 75 %.
- Conservar posición/tamaños al intercambiar, abrir/cerrar módulos, atajo y cambiar canción. Letra/cola no pueden quedar únicas; cerrar principales conserva conjunto para restaurarlo con atajo.
- Cambiar vista del reproductor acoplado DEBE desacoplarlo, petición explícita del usuario.
- Atajo global capturado por teclas en Ajustes; oculta/restaura todo el grupo. X de todas ventanas deja Oto en bandeja. Una entrada de tareas por grupo; minimizar/restaurar afecta grupo.
- Chincheta solo reproductor solo; vertical abajo izquierda, oculta hasta hover y estado reconocible.
- Biblioteca sin tres puntos; miniaturas listas con primeras cuatro canciones; pista inferior lleva a origen. Me gusta sin relleno si no está marcado.
- Letra con aspecto Android, subidas/bajadas/vibrato; no copiar sincronización a ciegas. Awaken francés fue corregido y usuario confirmó inglés.

## Referencias bajo demanda

`docs/AGENTS_HISTORICO_20261102.md`, `docs/ACOPLAMIENTO.md`, `ESTADO_MODULAR.md`, `docs/VALIDACION_WINDOWS.md`. Contrastar sus fechas con código. No cargar toda la historia tras cada /clear: empezar aquí, git status y archivos de la tarea concreta.

## Exportación MP3: implementada, probada e instalada

- Descargas → «Exportar como MP3…», con icono de descarga: todas o una canción, incluidas las de listas. Copias independientes `artista - título.mp3`, etiquetas y sufijos numéricos sin sobrescribir. Originales internos intactos.
- `playback/Mp3Export.kt`: FFmpeg incluido, libmp3lame VBR q2, temporales y limpieza al cancelar/fallar. `Mp3ExportTask.kt`: lote ligado al appScope de DesktopDownloads, independiente de la pantalla; progreso, Cancelar y errores detallados en `ui/Mp3ExportControls.kt`.
- Cambiar de pantalla o dejar Oto en bandeja mantiene el trabajo. Salir por completo termina la exportación; los MP3 ya guardados permanecen. No se reanudan lotes tras reiniciar la app.
- Preferencia final de Iago: selector habitual de Windows/Java moderno. `WindowsExportFolder.kt` usa IFileOpenDialog nativo mediante JNA ya incluido; respeta apariencia del sistema, probado oscuro. Fuera de Windows, JFileChooser. El navegador Compose propio fue descartado: NO recuperarlo.
- Eliminado «Abrir carpeta de descargas» de ambas vistas. Controles compartidos entre DownloadsSpace y DesktopDownloadsPane.
- Pruebas Mp3ExportTest: conversión real WebM→MP3, etiquetas, colisiones, originales y limpieza tras error. Mp3ExportTaskTest: independencia del scope de pantalla, rechazo de lotes simultáneos, cancelación y errores.
- :desktopApp:test y :desktopApp:packageMsi correctos; MSI actualizado con códigos 0/0 y app verificada. Cambios locales sin commit/push.

## Me gusta de Oto: implementados y probados en ambos dispositivos

- Objetivo confirmado: una colección de Me gusta de Oto compartida entre PC/móvil. Los likes de YouTube Music van a una lista independiente `youtube:LM`. El corazón de Oto no modifica los likes de YouTube.
- Prefs guarda decisiones por canción (tiempo/token/estado) atómicamente con LIKED, incluso quitar una canción ya ausente. LibraryReplica intercambia registros compatibles con el formato previo, conserva eliminaciones, resuelve empates igual y ordena por fecha reciente. El cambio se fecha al tocar el corazón, no al reconectar.
- Recuperar una copia Oto respeta los desmarcados conocidos; canciones recuperadas sin decisión previa tienen antigüedad 0 para que una eliminación remota posterior recibida prevalezca. Un corazón explícito puede volver a añadirlas.
- Cloud protege `youtube:LM` incluso si los metadatos antiguos dicen `liked`. Account ya no envía corazones a YouTube. No se han limpiado ni reinterpretado likes antiguos del perfil real.
- :core:test y :desktopApp:test pasaron. Seis pruebas LikeSynchronizationTest cubren unión inicial, separación YouTube, reinicios, desmarcado de ausente, orden de reconexión, copias antiguas y edición durante recepción. CloudLikesTest amplía el caso de origen LM.
- `MobileLikeIntegrationTest` pasó en el Nothing real con APK `.linktest` y perfil Windows temporal: alta PC→móvil, alta/baja móvil→PC, cierre/reconexión con baja offline, likes YouTube aparte y nuevo marcado explícito.
- La prueba usa LocalLink real y `adb reverse` temporal, sin cambiar cortafuegos ni cuentas. Variables optativas `OTO_LINK_TEST_ADB` (ruta adb) y `OTO_LINK_TEST_SERIAL` (dispositivo explícito). Requiere APK aislada ya instalada; sin variables se omite.
- Informes locales: `../mobile-likes-real.log` y `core/build/test-results/test/TEST-io.github.iaguito22.oto.remote.MobileLikeIntegrationTest.xml`. Los primeros intentos fallaron por reutilización de la actividad diagnóstica, corregida con `--activity-multiple-task`.
- Escritorio con estas reglas instalado. Móvil habitual también actualizado (ver abajo). Queda la vinculación/validación de los perfiles habituales: no confundirla con la prueba aislada.

## Integración Android recibida y estado del teléfono

- Remoto integrado: `0313034` de `feat/prueba-acoplamiento`. Ya incluye Android/Dispositivos; la anterior falta de la rama móvil está RESUELTA.
- Para actualizar el árbol se guardó todo Windows con `git stash push -u`, fast-forward y `stash apply` sin conflictos. Stash «respaldo Windows antes de integrar movil 2026-11-03» conservado. No se han confirmado ni subido los cambios locales Windows.
- `MobileConnection.kt` adaptado a addToQueue de LinkPlayer; `PlayerConnection.enqueue` añade al final de la cola.
- Botón `ic_link_devices` añadido JUNTO A DESCARGAS en el menú DEL MÓVIL, con descripción accesible. No era una petición para la barra lateral Windows. Verificado visualmente en la app habitual optimizada y, en la aislada, que abre DevicesActivity.
- DevicesActivity y DevicesPane explican sincronización de Oto y separación de YouTube.
- ADB local: `../tools/android-sdk/platform-tools/adb.exe`; último teléfono `<dirección local variable>`, Nothing Phone (2) A065. Puerto/IP pueden cambiar. No escanear ni reinstalar otros dispositivos por suposición.
- App habitual: `io.github.iaguito22.oto.debug`, variante `phone` optimizada con R8, NO depurable, versión 1.2.2 código12. Actualizada mediante `adb install -r` con Success el 03/11/2026 a las 20:08. No se desinstaló ni se sustituyeron sus DataStore/cookies. Interfaz habitual con biblioteca conservada y botón nuevo comprobada.
- Cola conservada: 50 canciones, índice0, posición225957 ms, pausada. Verificada tras actualizar mediante dumpsys y pantalla real de la misma canción. Al cerrar esta tarea, móvil abierto en el menú de Tu música; no se inició reproducción.
- Pruebas Android LibraryActions/Loop/Blur/SearchHarness correctas; :app:assemblePhone y :app:testDebugUnitTest pasaron. La primera build offline necesitó descargar dependencias faltantes; ya están disponibles.
- La corrección previa de escáner/R8 vino de Linux (docs/UNIFICACION.md). Aquí se verificó el enlace físico por ADB reverse y la navegación a Dispositivos; no afirmar un nuevo escaneo óptico real desde Windows.

## Firma móvil en Windows: resuelta sin perder datos

- Clave Linux recibida en `../tools/signing/oto-mobile-linux.keystore`, FUERA del repo. Entrada privada verificada, alias `androiddebugkey`; certificado coincide con el APK habitual:
  `717d8f898485e2643bc0ebce7b2ac878f0fecc928f217f21a3804c0faf5fcbbc` (huella pública SHA-256).
- Nunca subir/copiar el keystore, contraseñas, perfiles, QR ni datos de sesión a Git o entregables. No se cambió la clave debug de Windows (`~/.android/debug.keystore`); tiene una firma distinta.
- `app/build.gradle.kts` admite `OTO_PHONE_KEYSTORE` para `phone`; opciones `OTO_PHONE_STORE_PASSWORD`, `OTO_PHONE_KEY_ALIAS`, `OTO_PHONE_KEY_PASSWORD` si no son las convenciones habituales de debug. No poner contraseñas en comandos compartidos ni en este archivo. `debug` y `bench` conservan su firma Windows.
- Compilación habitual, desde raíz y en PowerShell:

```powershell
. ..\env.ps1
$env:ANDROID_HOME = (Resolve-Path ..\tools\android-sdk).Path
$env:OTO_PHONE_KEYSTORE = (Resolve-Path ..\tools\signing\oto-mobile-linux.keystore).Path
.\gradlew.bat :app:assemblePhone :app:testDebugUnitTest --offline --no-configuration-cache
```

- APK: `app/build/outputs/apk/phone/app-phone.apk`. Antes de instalar, verificar con apksigner que el certificado coincide, paquete `.debug` y que NO es application-debuggable. Usar `adb -s <dispositivo> install -r`, nunca desinstalar la habitual para resolver una firma incorrecta.
- `prompt.md` en raíz fue el encargo para transferir/verificar la clave desde Linux: encargo YA CUMPLIDO. No volver a pedir la clave ni volver a transferirla.

## Herramientas de prueba y conservación de la cola

- `-PotoLinkTest` cambia SOLO el applicationId de debug a `.linktest`, para probar sin tocar la app habitual. No usar debug normal para pruebas contra datos reales.
- `DebugLinkActivity` solo en src/debug y protegida por DUMP; `test-like` solo admite `.linktest` e ids `oto-link-test-…`. `capture` de pruebas no contiene credenciales. Nunca añadir estos accesos a phone/release.
- `.linktest` sigue instalada y detenida. La revisión automática rechazó un comando de limpieza que incluía desinstalarla; no se ejecutó ni se reintentó. No confundirla con `.debug` ni declararla eliminada.
- `src/debug/.../UsualSession.kt` conserva/restaura únicamente la cola mediante MediaController y Parcel en `.linktest`, sin restaurar preferencias. Debug manifest permite consultar el paquete habitual; el servicio se resuelve con PackageManager.
- Si hay que conservar cola en otra actualización: instalar APK aislada, ejecutar DebugLinkActivity `capture-habitual` con `--activity-multiple-task`, comprobar `habitual-session-result.json` antes de actualizar. La copia privada está en files/habitual-session.bin de `.linktest`; NO publicarla ni limpiar la app aislada antes de recuperar.
- Tras instalar phone, ABRIR PRIMERO MainActivity habitual para mantener el controlador; ejecutar después `restore-habitual`. Sin UI habitual, soltar el controlador puede destruir la sesión pausada y perder la cola recién restaurada. Verificar cola/posición en el servicio y pantalla; luego detener `.linktest`.
- Capturas e informes privados se guardaron fuera del repo, en `../`. No incluirlos en un commit. Última captura Windows cerrada/resetada; no dejar bordes de captura activos.

## Cola del ordenador desde el móvil: 03/11/2026

- La recepción `addToQueue` ya existía en el protocolo y en ambos adaptadores, pero faltaba ofrecerla desde Android.
- `MenuSheet` ahora muestra «Añadir a la cola del ordenador» en el menú de pulsación larga de cada canción cuando `link.remote` está conectado. Añade al final; la opción local «Reproducir a continuación» conserva su función.
- `MobileConnection.addToRemoteQueue` envía id/título/artista/portada y espera la respuesta antes del aviso de éxito. Una desconexión o error se muestra en la misma hoja.
- `MobileQueueIntegrationTest` optativa en desktopApp: teléfono real `.linktest` → DeviceConnection y DesktopPlayerConnection reales con perfil Windows temporal. Dos envíos llegaron al final en orden; conservó el elemento actual de la cola, índice, posición observada y pausa. Sin audio: tareas del reproductor retenidas por StandardTestDispatcher. No prueba reproducción sonora ni UI de pulsación larga.
- Requiere `OTO_LINK_TEST_ADB` y `OTO_LINK_TEST_SERIAL`; usa adb reverse temporal, no vacía la app habitual ni la aislada. `DebugLinkActivity/test-queue` solo se permite en `.linktest` y llama al mismo método que la UI.
- :core:test, :app:testDebugUnitTest, :desktopApp:test (incluida la integración con `<dirección local variable>`), :app:assemblePhone y assembleDebug -PotoLinkTest pasaron.
- App habitual phone actualizada con `install -r`, firma habitual verificada y no depurable. Cola capturada/restaurada y comprobada: 50 canciones, índice 0, posición 225957 ms, pausada. `.linktest` detenida. No se ha comprobado este menú con los perfiles habituales vinculados; necesitan vincularse desde Dispositivos si aún no lo están.
- Cambios locales sin commit/push. Logs privados fuera del repo: `../remote-queue-build.log`, `../remote-queue-debug-build.log`, `../remote-queue-integration.log`.

## Fluidez y consumo de escritorio: revisión del 03/11/2026

- Petición de Iago: trabajar en fluidez y consumo, sin cambiar bienvenida ni valores por defecto. No retirar las animaciones existentes.
- Progreso aislado en `PlayerProgress`: la posición de audio ya no recompone toda PlayerSurface/PlayerContents. Contadores por segundos; slider conserva precisión. Probados los tres formatos y clic de seek horizontal (saltó a unos 00:53 manteniendo reproducción).
- Miniaturas de LibraryArt/mosaicos según píxeles físicos (64/128/256/544), portada principal 544 con paleta. ArtworkLoader usa caché LRU por memoria estimada 48 MiB / ahorro 8 MiB, máximo 96 entradas; ocho cerrojos comparten cargas iguales, con timeouts y cancelación correcta. `ArtworkCacheTest` y `ArtworkLoaderTest` pasan: expulsión por presupuesto, recencia, reducción, doce peticiones compartidas y reintento tras HTTP fallido.
- Latido y reloj de letra dejan 10 ms entre solicitudes de frame, reduciendo trabajo continuo en pantallas rápidas sin cambiar el cálculo temporal ni las transiciones de interacción. Políticas de igualdad explícitas en estados derivados de la letra.
- Apertura: preparación con dibujo a alfa .001 y espera del primer dibujo de ESA ventana antes de contar frames estables. OTO_MOTION_TRACE registra firstDrawDelayMs para aperturas. Mejora de apertura NO acreditada de forma concluyente: biblioteca sigue con picos, no prometer que esté fluida del todo.
- Comparación A/B corta con audio local, mismas tres ventanas, biblioteca en Tu música y mismo JDK 21.0.13: calentamiento ~50 s y 21 muestras cada 2 s sobre 00:30–01:10. CPU de proceso **67.95 → 55.18 % de un núcleo** (−18.8 %); residente medio **727.02 → 619.84 MiB** (−107.18 MiB, −14.7 %). No es CPU total del ordenador ni incluye FFmpeg. La comparación es anterior al último ajuste de espera del primer dibujo, que solo afecta a la entrada de las ventanas.
- No usar primeros ensayos con Downloads o JVM fría como A/B. CSV, JFR, scripts y logs privados: `C:\Users\iago\Documents\Codex\2026-11-03\q-x20\work`. Los CSV guardan decimales es-ES: parsearlos con esa cultura.
- Reapertura final instrumentada sin reproducción: media 14.2 ms, máximo 63.4 ms entre callbacks. Primer arranque: biblioteca pico ~134 ms; reproductor/letra ~58 ms en la última muestra con JDK completo. Son callbacks, NO FPS GPU; no comparar directamente con muestras históricas de otra carga.
- Registro detallado: `docs/RENDIMIENTO_20261103.md`. Tests escritorio y MSI pasaron. Instalación Windows actualizada, conserva cuenta/biblioteca. Se restauraron carátula y agrupación tras probar otras vistas. Cambios locales sin commit/push.
- Pendientes: pico de apertura en frío de biblioteca, sesión prolongada de consumo y A/B con ahorro RAM activado/desactivado. Bienvenida y valores por defecto siguen fuera de esta revisión.
- Cierre de la tarea: MSI final instalado con códigos 0/0; hash SHA-256 del JAR instalado coincide con el compilado. 59 pruebas escritorio, 5 omitidas por condiciones externas, 0 fallos. Tres ventanas iniciadas y canal local responde; escritorio sin canción, cola vacía y pausado. Diagnóstico de movimiento desactivado en el arranque final y captura Windows cerrada/resetada.

## Arreglo de apertura EN CURSO — estado para /clear, 03/11/2026

### Petición y estado instalado

- Tras informar del tirón pendiente, Iago dijo «si se puede arreglar hazlo». Después pidió actualizar este AGENTS.md.
- La app habitual sigue siendo la instalación de la sección anterior (mejora de consumo, JAR SHA-256 `4A5DB16F04373FC99908E7EC61E66CA7A1AB771FF1235F59DD703CA43C0579C2`). **Ningún cambio nuevo de esta sección está instalado.** Oto habitual sigue abierto, sin que estas pruebas cambien su perfil o distribución.
- Hay muchos cambios previos sin commit: conservarlos. No dar por terminado el tirón ni presentar los callbacks como FPS reales.

### Herramientas y reproducción nueva

- Todo el material privado está en `C:\Users\iago\Documents\Codex\2026-11-03\q-x20\work`.
- `run-window-probe.ps1 <nombre-nuevo>` copia el JAR compilado a un perfil NUEVO `window-<nombre>`, copia SOLO los DataStore de preferencias/listas y los JSON de ventanas/biblioteca desde el perfil habitual, sin cuenta ni secretos, y ejecuta con JDK 21 y JFR. Cada nombre debe ser nuevo: el script rechaza una carpeta ya existente.
- El arnés usa `OTO_MOTION_TRACE=1`, `OTO_MOTION_PROBE=1` y el marcador `motion-probe.marker` dentro del perfil aislado. `ModularDesktop` espera 4 s, cierra/abre biblioteca cinco veces (1.2 s cerrada, 2.2 s abierta) y sale por `quit()`. Solo funciona con variable Y marcador. No activar esas dos condiciones sobre el perfil habitual.
- Las pruebas aisladas han terminado solas; no quedan procesos de arnés esperando. La app habitual tiene dos procesos Oto (lanzador/JVM), no confundirlos con los Java de Gradle.
- `WindowWorkTrace.kt` es NUEVO, sin seguimiento Git: diagnóstico optativo que mide CPU/tiempo de dibujo, iconos, llamada de barra de tareas y foco. Registra solo etiquetas/tiempos en `window-work.jsonl`, con escritor IO. Desactivado sin OTO_MOTION_TRACE.
- `motion-frames.jsonl` incorpora `animationStartedMs` además de intervalos y primer dibujo. `analyze-window-probe.mjs` correlaciona picos con JFR. Para leer JFR: `. ..\env.ps1; & "$env:JAVA_HOME\bin\jfr.exe" print --json --stack-depth 40 --events jdk.ExecutionSample,jdk.NativeMethodSample,jdk.GCPhasePause <archivo.jfr> > <events.json>`; **sin --stack-depth, jfr print recorta a cinco frames**.
- Los perfiles de prueba no tienen los archivos descargados aunque las preferencias pueden pedirlos: hay trabajo inicial anónimo de red/descargas. Tenerlo en cuenta al comparar arranques en frío; las cinco reaperturas son más consistentes. No restaurar esos perfiles sobre el habitual.

### Hallazgos y cambios actuales

- Las imágenes en caché volvían a entrar desde un bitmap null y lanzaban transiciones Crossfade al reabrir. `ArtworkLoader.cached(url,size)` permite consultar la caché sin red.
- `LibraryArt` ahora espera su tamaño físico antes de montar la imagen; si está en caché la primera composición ya la muestra. Los nuevos bitmaps mantienen fundido de 220 ms mediante Animatable + alpha en graphicsLayer. Si cambia el tamaño y falla la carga, conserva la imagen anterior.
- Además, su nodo de imagen es ahora un **Canvas permanente**: dibuja con recorte centrado equivalente a ContentScale.Crop. La llegada del bitmap invalida el dibujo, sin insertar un Image nuevo ni volver a medir toda la cuadrícula. Hay que verificar visualmente miniaturas/mosaicos y redimensionado.
- La preparación anterior esperaba ticks del reloj con alfa fijo .001; no garantizaba ejercitar el renderizado real. Ahora alterna `.01f` / `.015f` durante la preparación y pide tres intervalos <20 ms (antes 35 ms), mínimo 150 ms, máximo 1200 ms, después del primer dibujo. Animación visible mantiene spring y dirección.
- **Última variante en fuentes/JAR:** también alterna en esa fase una traslación de .25 px y escala de 1 a .9999 para ejercitar matrices/posiciones antes de mostrar el movimiento. Su mejora adicional NO es concluyente: comparar con la variante previa `fixed1` antes de conservarla por defecto.
- Probado y REVERTIDO: retrasar el foco del buscador hasta acabar la entrada. No eliminó los picos; foco vuelve a su demora original de 120 ms.
- Probado y REVERTIDO: desplazar el dibujo con Canvas en la raíz de ModuleWindow en vez de graphicsLayer, bloqueando clicks durante la transición. No mejoró; **la raíz mantiene graphicsLayer y el manejo de entrada original**. El Canvas que sí queda es SOLO el de cada miniatura.
- JFR muestra medidas/layout de tarjetas y callbacks de posiciones/foco durante algunos picos. Iconos, foco inicial y barra de tareas se midieron; no se atribuye todo el tirón a una única llamada de Windows. No seguir inventando hipótesis sin comparar las tandas.

### Resultados exactos de las tandas (picos entre callbacks, ms)

Cada tanda incluye un arranque en frío y cinco reaperturas. JFR activo en todas; misma biblioteca copiada, tres ventanas, sin reproducción.

| Perfil | Primer arranque biblioteca | Cinco reaperturas biblioteca |
|---|---:|---|
| `window-control1` (código antes de esta sección, con instrumentación) | 114.3 | 57.8, 52.7, 41.7, 33.8, 44.5 |
| `window-cached1` (caché sin Crossfade inicial) | 109.0 | 43.7, 35.0, 42.8, 33.4, 35.2 |
| `window-focus1` (foco retrasado, descartado) | 127.6 | 39.0, 49.1, 42.7, 33.7, 34.9 |
| `window-drawing1` (transformación raíz Canvas, descartada) | 121.6 | 47.4, 51.4, 44.4, 34.4, 46.1 |
| `window-warmed1` (alfa de preparación variable) | 58.5 | 28.6, 33.6, 24.2, 20.4, 20.2 |
| `window-fixed1` (lo anterior + Canvas permanente en miniaturas y umbral20ms) | 125.6 | **22.8, 21.0, 19.5, 18.3, 18.8** |
| `window-matrix1` (última variante, minúsculas variaciones de matriz preparatoria) | **51.4** | 29.4, 27.7, **52.3**, 19.7, 19.0 |

- La variante `fixed1` dejó las cinco reaperturas sin intervalos >33.4 ms; medias 8.6–8.9 ms. La última `matrix1` deja una excepción de 52.3 ms, por lo que no afirmar que todo esté arreglado ni que supere siempre a fixed1.
- Los resultados iniciales en frío siguen siendo variables. Falta repetir control/candidata, comprobar reproducción activa y validar UI. No se ha repetido aún la prueba larga de CPU/RAM.
- El JAR control está preservado en `window-control1/probe.jar`; el de cada variante en su carpeta. El script actual toma `desktopApp/build/libs/desktopApp.jar`; para reproducir control, adaptar un parámetro de ruta al JAR sin sobrescribir fuentes ni archivos usados por un proceso.

### Compilación y próximos pasos concretos

- Las tandas `cached1`, `focus1`, `drawing1`, `fixed1` se compilaron con `:desktopApp:test :desktopApp:jar`; pasaron 59 tests (5 omitidos). En una ejecución intermedia falló por timeout de 5 s el test existente `DesktopDownloadsTest.removingActiveDownloadCancelsWorkBeforeDeletingWindowsFiles`; se repitió SOLO ese test sin cambiar código y pasó. La ejecución completa posterior de fixed1 pasó.
- La **última** variación matrix1 se compiló solo con `:desktopApp:jar` (BUILD SUCCESSFUL), después de la suite completa previa. Repetir la suite al decidir la variante final.
- No se ha creado un MSI de estos cambios. No reutilizar como si fuese nuevo el MSI de la tarea anterior.
- Siguiente: comparar/repetir `fixed1` y `matrix1` contra control; elegir lo que realmente mejora y descartar ajustes sin evidencia. Revisar visualmente (habilidad computer-use) mosaicos, apertura, foco/búsqueda, redimensionado y botones; probar también con audio local y letra activos.
- Después: `:desktopApp:test :desktopApp:packageMsi --offline --no-configuration-cache`; cerrar Oto desde «Salir de Oto», instalar respetando datos/firma de escritorio (reglas anteriores), verificar hash del JAR instalado y arranque. Guardar la geometría ANTES de tocar formatos; las pruebas de esta sección no han cambiado la habitual.
- Actualizar `docs/RENDIMIENTO_20261103.md` con resultados nuevos cuando se elija/instale el arreglo. Su contenido actual corresponde a la revisión anterior de consumo; no darlo por actualizado con esta sección.
- No se ha iniciado una nueva captura Windows en esta sección; no hay borde de captura pendiente de cerrar. No se han hecho commits ni push.

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

## Release 1.2.3 — instalación y publicación, 03/11/2026

- Peticiones nuevas de Iago: primer arranque con SOLO reproductor centrado; al abrir módulos, Letra / Biblioteca / Reproductor, Cola independiente; aleatorio activado. Mantener carátula9:16, horizontalalto108 y verticalancho96. Implementado en initialDockLayout (con homes) y Prefs; probado en perfil vacío y UI Windows.
- Instalador MSI nuevo con panel y cabecera de Oto, por usuario y carpeta `Oto Desktop`; generador `desktopApp/package-windows.ps1`. Versión Windows/Android1.2.3, Android código13. JDK21/WiX3.14 para este empaquetado. El diseño NO aporta reputación SmartScreen y no hay certificado Authenticode disponible.
- Instalación Windows final: tras una reparación MSI de la misma versión rechazada por SecureRepair, se retiró el paquete anterior y se instaló el MSI definitivo; códigos0/0, JAR instalado igual al de la imagen compilada. No se cambió ninguna política de Windows. Se respaldaron preferencias en `.../quier/work/backup-before-1.2.3`. Biblioteca habitual conservada (incluida lista Todo con1675 canciones y cuenta restaurada).
- La primera validación visual detectó desacoplamiento del reproductor al añadir Letra: trazas OTO_DOCK_TRACE mostraron diferencias DPI de1px interpretadas como resize/move. `nativeDockChange` ignora ese redondeo; conserva gestos reales. Repetición con trazas: tres miembros unidos y Cola en grupo independiente. UI de formatos: Horizontal351x108, Vertical96x240. Suite escritorio final71tests,5omitidos,0fallos; core56tests,5omitidos,0fallos. Android :app:assemblePhone y :app:testDebugUnitTest pasaron.
- Paquetes Linux desde JAR, JRE Temurin Linux21.0.13+9 y Skiko Linux0.9.4 oficiales. Hash del JRE verificado. Empaquetador `tools/package-linux-portable.py`; declara directorios explícitos, permisos y symlinks, excluye EXE/DLL/keystores. Ubuntu DEB y Arch pkg.tar.zst. CI Ubuntu22.04 instala DEB y abre app con Xvfb; comprobar estado final del workflow antes de marcar publicado. El paquete Arch comparte payload, su estructura se valida; no afirmar prueba en máquina Arch física.
- APK adjunto es PHONE optimizado, paquete io.github.iaguito22.oto.debug, firma717d8f… igual al móvil habitual. La clave del prompt.md solo era esa; APK público1.2.2 usa paquete sin.debug y firma4043c8… distinta, ausente en este Windows. No sustituir la firma del paquete público ni afirmar que phone actualiza el APK público. Notas de release lo explican.
- Iago autorizó explícitamente subir TODO al repo, arreglar ramas y crear releases Windows/Ubuntu/Arch/Android. Todo el código acumulado quedó en a63c409; `main` se adelantó conservando historial, con correcciones posteriores. Se amplió remote.origin.fetch a todas las ramas; el clon solo seguía feat/prueba-acoplamiento. Un intento inicial de crear tracking para origin/main falló y dejó su árbol antiguo en índice; se comprobó que coincidía EXACTAMENTE con el tree de origin/main y se recuperó HEAD confirmado sin pérdida de cambios. Rama canónica main. No forzar ramas; sincronizar rama antigua por fast-forward al terminar.
- Release v1.2.3 preparada como BORRADOR con MSI, DEB, Arch, APKphone y SHA256SUMS. Actualizar etiqueta del borrador al commit probado antes de publicarla: no dejarla apuntando al primer empaquetado anterior a la corrección DPI. No reescribir etiquetas ya publicadas.
- Capturas originales, perfiles, herramientas descargadas, keystores y logs permanecen FUERA del repo. prompt.md ahora es guía pública de continuidad en main; la petición privada antigua está preservada en `.../quier/work/prompt-key-transfer-private.md`. Direcciones IP locales retiradas de las notas públicas.
