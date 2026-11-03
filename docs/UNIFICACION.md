# Integración de Android y escritorio

La rama de trabajo reúne el escritorio validado en Windows con los cambios posteriores de Android y la conexión local del móvil. El código común vive en `core`; las interfaces y servicios específicos permanecen en `app` y `desktopApp`.

La integración parte de `7710312` (escritorio), incorpora el historial de Android hasta `bdecfa8` y los cambios de conexión que estaban sin confirmar en el árbol Android. Las instalaciones y los árboles originales se conservan.

## Comprobaciones realizadas

- Pruebas de `core`, escritorio y Android; compilación Android `debug` y APK optimizado `bench`.
- Arranque e interacción de escritorio en Hyprland.
- Escaneo y vinculación desde un Nothing Phone real con la variante aislada `bench`. El escáner ya no se cierra: R8 debe conservar el constructor de los registros de componentes de Firebase que ML Kit descubre mediante reflexión.
- El cortafuegos del ordenador bloqueaba la conexión local. Se probó con excepciones temporales limitadas al teléfono y después se retiraron.

La vinculación se comprobó hasta el estado «Conectado · Red local». Quedan por comprobar con datos de prueba la sincronización de biblioteca, los controles y el traspaso de reproducción entre dispositivos, así como esta integración concreta en Windows. El informe de la validación anterior en Windows está en `VALIDACION_WINDOWS.md`.

Para pruebas en un móvil con datos reales, usa una variante con identificador independiente y evita reinstalar sobre la aplicación habitual. Nunca incluyas claves de firma, credenciales ni códigos de vinculación en Git.
