# Oto modular — 30/10/2026

Implementación aislada en `feat/escritorio-modular`. El árbol original `oto-kmp` conserva sus cambios de fuentes sin mezclar. El complemento móvil parte de Oto 1.2.2 y vive en `../oto-movil-integrado`, rama `feat/control-local`.

## Uso

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktopApp:run
```

- Ctrl+Alt+O muestra u oculta el reproductor en Hyprland 0.56. Puede configurarse en Opciones → Dispositivos y atajo.
- Iconos inferiores: biblioteca, letra, cola y guardar en listas. Cada ventana se mueve por su cabecera y se redimensiona por la esquina inferior derecha.
- La chincheta fija el reproductor. Opciones permite compactarlo, ajustar volumen, organizar las ventanas abiertas y salir.
- Cerrar una ventana no detiene la música. Volver a ejecutar Oto recupera la instancia existente. Los tamaños y posiciones se guardan en `modular_windows.json`.
- La biblioteca conserva búsqueda, Explorar, listas, carpetas, DJ, importación, descargas y ajustes. Se retiró la reproducción duplicada de su cabecera.
- La letra utiliza el renderizado del móvil: tiempos por palabra, relleno por letras, kerning, saltos, coros, duetos, pausas, fundidos y romanización al mantener pulsado. Reintenta fuentes temporales a los 20 y 90 segundos.

## Vincular el móvil

En escritorio: Opciones → Dispositivos y atajo. En el APK de la rama móvil: Ajustes → Dispositivos → Escanear QR. Ambos deben estar en la misma red. El móvil mantiene una notificación mientras está habilitada la conexión; «Desvincular» la detiene. El escritorio permite revocar el secreto y renovar el QR.

El canal es bidireccional y cifra los mensajes con AES-GCM, con claves de sesión derivadas del secreto del QR y de nonces de ambas partes. No se transmiten cookies ni claves de YouTube. La clave no se envía por la red. El descubrimiento usa UDP 48732; si no funciona, se utiliza la dirección del QR. El puerto TCP se guarda al iniciar.

La vista Dispositivos permite controlar reproducción, posición, volumen, Me gusta, aleatorio, bucle y cola del otro equipo. «Continuar aquí/allí» prepara la sesión, conserva la posición y la cola, y pausa el origen. Si falla, se muestra el error; no se reanuda automáticamente el origen para evitar que suenen ambos.

La biblioteca se sincroniza automáticamente cuando existe conexión local. Se conservan adiciones simultáneas mediante registros por campo y canción y se recuerdan los borrados. Los metadatos en conflicto se resuelven por revisión y se muestran en Dispositivos; ambas versiones quedan en `library-replica.json`. Las escrituras comprueban que no haya ocurrido una edición local durante la recepción. La sincronización de cuenta existente continúa disponible; no se ha convertido en un servicio remoto de control.

El transporte y la sincronización son idénticos en los dos árboles (`remote/LocalLink.kt`, `LinkedPlayback.kt`, `LibraryReplica.kt`, `LocalDiscovery.kt`). Están copiados temporalmente porque el móvil actual todavía no usa el módulo `core` de `oto-kmp`; hay que mantenerlos idénticos hasta unificar los repositorios.

## Verificación

- Núcleo: 30 pruebas sin fallos, incluidas pruebas de canal autenticado, comandos en ambos sentidos, clave incorrecta, transferencia pausada, posición, concurrencia, borrados, reinicio y protección frente a cambios locales durante una recepción.
- Compilación del escritorio y del Android de `oto-kmp`.
- APK debug del móvil 1.2.2 y 14 pruebas sin fallos de bucle, aleatorio y manifiesto.
- Comprobación real en Hyprland de ventanas flotantes, atajo, contenido de biblioteca y letra, más descubrimiento UDP en loopback.

La revisión posterior con el teléfono real figura abajo: conexión, sincronización y transferencia PC → móvil comprobadas. Quedan pendientes el escaneo físico de QR y la comprobación visual en Windows/Mint.

Documentación consultada: [atajos de Hyprland](https://wiki.hypr.land/0.56.0/Configuring/Basics/Binds/), [lector QR de Android](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner).


## Revisión visual y pruebas con el móvil — 30/10

- Cabecera y accesos secundarios aparecen solo al pasar el cursor, sin desplazar la distribución. Los accesos alternan la apertura de biblioteca, letra y cola.
- Vistas verificadas en Hyprland: carátula 320×530, horizontal 360×120 y vertical 136×320. Ctrl+Mayús+M alterna entre ellas. El marco, las esquinas y los controles son de la aplicación (`undecorated`); falta comprobar visualmente Windows y Mint reales.
- Menú de opciones propio con vistas previas, barras finas sin pomo, icono de biblioteca y reproducción dibujada con trazos. El corazón alterna Me gusta; mantenerlo o Ctrl+B abre Guardar.
- Biblioteca nueva en LibrarySpace: navegación lateral, búsqueda, colección, carpetas, detalles, Explorar, recientes y descargas. Se reutilizan los servicios y los formularios de cuenta/importación, no la distribución de la antigua biblioteca.
- Latido derivado del audio con el detector de graves del móvil y suavizado de ataque/caída. Silencio y golpes de bajo cubiertos por pruebas; no es una animación periódica.
- El canal local usa protocolo 2 con compresión de bibliotecas grandes y límites de descompresión. Se probó una carga superior a 4 MB y se comprobó la convergencia real de 13 444 registros entre PC y teléfono.
- Transferencia real PC → móvil verificada con 50 canciones en orden, pausa y posición 57 564 ms. La sesión antigua completa de 85 canciones no pudo recuperarse; sí se recuperaron la canción, su posición y la sesión posterior de 50 canciones. No se restauraron copias de preferencias ni de listas.
- El móvil tiene instalada la variante phone optimizada y firmada con la misma clave que la instalación previa. Conserva datos y vinculación; se reconecta al abrir la aplicación. La aplicación auxiliar de captura se desinstaló.
- Se añadieron dos reglas de nftables temporales, autorizadas mediante sudo/Zenity, solo para 192.168.1.128 y TCP 48731 / UDP 48732. No se modificó la configuración persistente del cortafuegos; desaparecerán al reiniciar o recargarlo.
- Verificación: 30 pruebas del núcleo, 2 del detector de audio, 14 pruebas móviles previas de bucle/aleatorio/manifiesto, compilaciones de escritorio y Android y R8 de phone. Vinculación probada con los datos del QR; falta escaneo físico con la cámara y comprobación en Windows/Mint.
- Para evitar errores de recursos al recompilar, la vista abierta usa copias inmutables de los JAR en /tmp/oto-preview-*. Una ejecución normal por Gradle no necesita este procedimiento si no se reconstruyen sus JAR mientras sigue abierta.

## Pulido e integración — 02/11

- Dispositivos rediseñado en escritorio y móvil: carátula, controles mínimos, transferencia en dos tarjetas y cola y conexión desplegables. Cola paginada y confirmación al desvincular.
- Cuenta Google: selección independiente de listas para importar y exportar, búsqueda y selección múltiple. Iniciar sesión no autoriza la sincronización de todas las listas; Me gusta también requiere selección. Desmarcar una exportación no borra la copia remota.
- Biblioteca: arrastre con reordenación durante el gesto, animación y desplazamiento automático. Diálogos de borrar y guardar álbum adaptados al estilo de las nuevas listas; deshacer conserva las propiedades de la lista.
- Corregido el bloqueo del transporte cuando ambos equipos responden con bibliotecas grandes. El lector permanece disponible mientras un trabajador procesa los comandos. Los comandos publican el estado actualizado antes de confirmar su ejecución.
- Corregida la cola remota móvil: ahora transmite la sesión completa, incluida la canción actual y las anteriores, en lugar de solo las siguientes.
- Verificado en Hyprland y teléfono real: controles remotos, cola, transferencia en ambos sentidos y transferencia final de 50 canciones al móvil, pausada y conservando la posición. La aplicación queda en el workspace 1 junto al chat.
- Selector Google comprobado visualmente en móvil con catálogo real y selecciones independientes; no se aplicaron importaciones ni exportaciones a la cuenta del usuario. En escritorio se compiló el selector, pero no se inició una sesión Google para comprobarlo visualmente.
- Pruebas: 36 del núcleo, 2 de escritorio y 28 móviles, sin fallos. Compilados escritorio, debug y phone; instalada la variante phone optimizada conservando datos. Ambos árboles pasan `git diff --check`.
- Importador de archivos revisado con un documento vacío, sin crear listas; no se verificó una importación Spotify real. Siguen pendientes Windows/Mint, el escaneo físico de QR y una medición prolongada de batería.
- Acceso de red de esta prueba: reglas temporales limitadas a 192.168.1.138, TCP 48731 y UDP 48732; no se cambió la configuración persistente del cortafuegos.
