# Datos y sincronización

Parte del contexto de Oto; índice en `AGENTS.md`. Fechas y estados son los de cada sección: contrastar con el código.

## Datos y sincronización

- Perfil real: `%APPDATA%\oto`, DataStore en `datastore/`, distribución `modular_windows.json`. Caché Windows actual: `%LOCALAPPDATA%\oto\Cache`; descargas: `%APPDATA%\oto\downloads`.
- Hay perfiles de prueba en `../perfil visual`, `../perfil geometria`, etc. Confirmar perfil efectivo, no inferirlo de un log antiguo.
- Nunca subir cookies, secretos QR, DataStore, claves de firma, perfiles o logs privados a Git. Nunca restaurar respaldo antiguo sobre cambios nuevos del móvil.
- No ejecutar connectedAndroidTest contra móvil real. Ver reglas Android históricas si hace falta.
- Usuario quiere Me gusta de Oto separados de YouTube, sobre todo importación/exportación. Backup remoto «Me gusta de Oto»; likes YouTube como lista independiente.
- Se vaciaron SOLO likes del PC, con respaldo y retirando registros locales de réplica sin tombstones para móvil. UI verificada con 0 canciones. Último vaciado: 115 entradas; otro anterior: 440. No confundir fechas/perfiles.
- Decisión vigente: Me gusta de Oto SÍ compartidos entre PC y móvil; separados únicamente de los likes de YouTube. La primera vinculación reúne los de Oto de ambos, sin duplicados por id. No volver a vaciar ni separar likes entre dispositivos por la interpretación anterior. Los desmarcados explícitos se conservan. Queda verificar la vinculación de los perfiles habituales; las pruebas con teléfono real usaron datos aislados.

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

