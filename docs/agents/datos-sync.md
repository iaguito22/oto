# Datos y sincronización

Índice en `AGENTS.md`. Estados de 03–04/11/2026; contrastar con el código.

## Perfiles y datos privados

- Perfil real: `%APPDATA%\oto` (DataStore en `datastore/`, distribución `modular_windows.json`). Caché: `%LOCALAPPDATA%\oto\Cache`. Descargas: `%APPDATA%\oto\downloads`.
- Hay perfiles de prueba en `../perfil visual`, `../perfil geometria`, etc. Confirmar el perfil efectivo, no inferirlo de un log antiguo.
- Nunca subir cookies, secretos QR, DataStore, claves de firma, perfiles ni logs privados a Git. Nunca restaurar un respaldo antiguo sobre cambios nuevos del móvil.
- No ejecutar `connectedAndroidTest` contra el móvil real (ver `android-movil.md`).

## Me gusta de Oto

- **Decisión vigente:** los Me gusta de Oto están COMPARTIDOS entre PC y móvil y separados solo de los likes de YouTube Music, que van a la lista independiente `youtube:LM`. El corazón de Oto no modifica YouTube. La primera vinculación une los de ambos dispositivos sin duplicados por id; los desmarcados explícitos se conservan. No volver a vaciar ni separar likes entre dispositivos.
- Histórico: se vaciaron solo los likes del PC (115 entradas; antes 440) con respaldo, por una interpretación anterior ya descartada. No confundir fechas ni perfiles.
- `Prefs` guarda por canción la decisión (tiempo/token/estado) de forma atómica con LIKED, incluso al quitar una canción ya ausente. `LibraryReplica` intercambia registros compatibles con el formato previo, conserva eliminaciones, resuelve empates igual y ordena por fecha reciente; el cambio se fecha al tocar el corazón, no al reconectar.
- Al recuperar una copia de Oto se respetan los desmarcados conocidos; las canciones recuperadas sin decisión previa tienen antigüedad 0, así que una eliminación remota posterior prevalece. Un corazón explícito puede volver a añadirlas.
- `Cloud` protege `youtube:LM` aunque metadatos antiguos digan `liked`; `Account` ya no envía corazones a YouTube. No se han limpiado ni reinterpretado los likes antiguos del perfil real.
- Tests: `LikeSynchronizationTest` (6: unión inicial, separación YouTube, reinicios, desmarcado de ausente, orden de reconexión, copias antiguas, edición durante recepción), `CloudLikesTest`, y `MobileLikeIntegrationTest` (Nothing real con APK `.linktest` y perfil Windows temporal; ver `android-movil.md`). Informe local: `../mobile-likes-real.log`.
- Pendiente: vincular y validar los perfiles habituales; las pruebas con teléfono real usaron datos aislados.
