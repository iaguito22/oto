# Android y enlace con el móvil

Parte del contexto de Oto; índice en `AGENTS.md`. Fechas y estados son los de cada sección: contrastar con el código.

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

## Lo que suena en el otro dispositivo — 04/11/2026 (en la release 2.0.0)

- `RemoteNow` (core, LinkedPlayback.kt) lee el estado remoto y `follow()` decide si seguirlo: suena allí y aquí no, o aquí no hay nada; se mantiene tras pausar allí; lo local manda en cuanto suena o al desconectar. Pruebas en LinkedPlaybackTest.
- Móvil, modo tipo Connect: `PlayerConnection.Mirror`/`showRemote`/`hideRemote`. Siguiendo al PC, state/cola/aleatorio/bucle/origen/posición mostrados son los del PC y TODAS las órdenes de la interfaz se le envían; lo que se pone nuevo (play/listas) se monta aquí en pausa y se pasa entero con «transfer»; DJ con orden «dj». `localState`/`localSource`/… y `here {}` son siempre del teléfono: los usa MobileConnection.adapter (órdenes que llegan del PC) y Dispositivos. `MobileConnection.following` decide y alimenta el espejo. «Traer aquí» suelta el seguimiento aunque llegue en pausa.
- Píldora arriba (IslaScreen.RemoteBanner) SIN botones por petición de Iago: se controla con la gota; tocarla abre Dispositivos. Calidad de audio oculta en espejo. Crash corregido: el padding del hueco de la píldora rebotaba a negativo al desaparecer.
- PC (LibrarySpace): el mini reproductor muestra y controla lo del móvil. El PC no hace espejo completo (lo nuevo puesto en el PC suena en el PC).
- Probado con el Nothing real y el PC habitual: siguiente, pausa/reanudar, seek (1:52 vs 1:53), elegir canción en el móvil → suena en el PC (pantalla del PC verificada) y el móvil no suena, Traer aquí (también saliendo de Dispositivos), gota local tras traer. Cola habitual 58/índice17/177107 ms restaurada con `.linktest` (detenida). El PC quedó en pausa con canciones de prueba.

