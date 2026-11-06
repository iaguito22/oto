# Android y enlace con el móvil

Índice en `AGENTS.md`. Estados de 03–04/11/2026 (release 2.0.0); contrastar con el código.

## Teléfono y herramientas

- ADB local: `../tools/android-sdk/platform-tools/adb.exe`. Teléfono: Nothing Phone (2) A065; IP y puerto cambian. No escanear ni reinstalar otros dispositivos por suposición.
- App habitual: `io.github.iaguito22.oto.debug`, variante `phone` con R8, NO depurable, 2.0.0 (código 14) instalada con `adb install -r`. Nunca desinstalarla ni sustituir sus DataStore/cookies.
- El botón `ic_link_devices` está junto a Descargas en el menú DEL MÓVIL (no en la barra lateral de Windows). DevicesActivity y DevicesPane explican la sincronización de Oto y su separación de YouTube.
- El escáner/R8 se corrigió en Linux (`docs/UNIFICACION.md`). Aquí se verificó el enlace físico por ADB reverse; no afirmar un nuevo escaneo óptico desde Windows.
- Pruebas Android: `:app:assemblePhone`, `:app:testDebugUnitTest` (LibraryActions/Loop/Blur/SearchHarness).

## Firma y compilación de `phone`

- Keystore en `../tools/signing/oto-mobile-linux.keystore`, FUERA del repo, alias `androiddebugkey`. Huella SHA-256 del certificado: `717d8f898485e2643bc0ebce7b2ac878f0fecc928f217f21a3804c0faf5fcbbc`. No se cambió la clave debug de Windows (firma distinta).
- Nunca subir ni copiar el keystore, contraseñas, perfiles, QR ni datos de sesión. `OTO_PHONE_KEYSTORE` activa la firma; si no son las convenciones de debug, también `OTO_PHONE_STORE_PASSWORD`, `OTO_PHONE_KEY_ALIAS`, `OTO_PHONE_KEY_PASSWORD` (no ponerlas en comandos compartidos). `debug` y `bench` conservan su firma de Windows.
- `prompt.md` (transferencia de la clave) es un encargo CUMPLIDO: no volver a pedirla.

```powershell
. ..\env.ps1
$env:ANDROID_HOME = (Resolve-Path ..\tools\android-sdk).Path
$env:OTO_PHONE_KEYSTORE = (Resolve-Path ..\tools\signing\oto-mobile-linux.keystore).Path
.\gradlew.bat :app:assemblePhone :app:testDebugUnitTest --offline --no-configuration-cache
```

- APK: `app/build/outputs/apk/phone/app-phone.apk`. Antes de instalar, verificar con apksigner el certificado, el paquete `.debug` y que NO es debuggable. `adb -s <dispositivo> install -r`; nunca desinstalar la habitual para arreglar una firma.

## Pruebas aisladas y conservación de la cola

- `-PotoLinkTest` cambia SOLO el applicationId de debug a `.linktest`. No usar debug normal contra datos reales; no ejecutar `connectedAndroidTest` en el móvil real.
- `DebugLinkActivity` solo en `src/debug`, protegida por DUMP; `test-like` y `test-queue` solo admiten `.linktest` (ids `oto-link-test-…`). Nunca añadir estos accesos a phone/release.
- `.linktest` sigue instalada y detenida; un comando de limpieza que la desinstalaba fue rechazado y no se reintentó. No confundirla con `.debug`.
- Para conservar la cola al actualizar: instalar la APK aislada y ejecutar `capture-habitual` (`--activity-multiple-task`); comprobar `habitual-session-result.json`. La copia privada está en `files/habitual-session.bin` de `.linktest`: no publicarla ni limpiarla antes de restaurar (`UsualSession.kt` guarda solo la cola, no preferencias).
- Tras instalar phone, ABRIR PRIMERO MainActivity habitual (mantiene el controlador) y luego `restore-habitual`; sin UI habitual la sesión pausada puede destruirse. Verificar cola/posición en el servicio y en pantalla; detener `.linktest`.
- Informes y capturas privados en `../`; no incluirlos en commits.
- Integración con el móvil real (opcional, se omite sin variables): `OTO_LINK_TEST_ADB` y `OTO_LINK_TEST_SERIAL`; usa `adb reverse` temporal y APK `.linktest` instalada. Tests: `MobileLikeIntegrationTest` (core) y `MobileQueueIntegrationTest` (desktopApp). No prueban audio real ni la UI de pulsación larga.

## Cola del PC desde el móvil

- `MenuSheet` ofrece «Añadir a la cola del ordenador» en el menú de pulsación larga cuando `link.remote` está conectado (añade al final; «Reproducir a continuación» sigue siendo local). `MobileConnection.addToRemoteQueue` envía id/título/artista/portada y espera la respuesta; un error o desconexión se muestra en la hoja.
- Pendiente: comprobarlo con los perfiles habituales vinculados desde Dispositivos.

## Lo que suena en el otro dispositivo (modo tipo Connect)

- `RemoteNow` (core, `LinkedPlayback.kt`) lee el estado remoto y `follow()` decide si seguirlo: suena allí y aquí no, o aquí no hay nada; se mantiene tras pausar allí; lo local manda en cuanto suena o al desconectar. Tests: `LinkedPlaybackTest`.
- Móvil: `PlayerConnection.Mirror`/`showRemote`/`hideRemote`. Siguiendo al PC se muestran su estado/cola/aleatorio/bucle/origen/posición y TODAS las órdenes de la UI se le envían; lo nuevo (play/listas) se monta aquí en pausa y se pasa entero con «transfer»; DJ con orden «dj». `localState`/`localSource`/… y `here {}` son siempre del teléfono (los usan `MobileConnection.adapter` y Dispositivos). `MobileConnection.following` alimenta el espejo. «Traer aquí» suelta el seguimiento aunque llegue en pausa.
- Píldora superior (`IslaScreen.RemoteBanner`) SIN botones por petición de Iago: se controla con la gota; tocarla abre Dispositivos. Calidad de audio oculta en espejo.
- PC (06/11, sin probar con el móvil real): `RemoteMirror` (en `DeviceConnection.mirror`) aplica `RemoteNow.follow` y extrapola la posición; la ventana del reproductor, la bandeja, los atajos y el mini reproductor de `LibrarySpace` muestran lo del móvil y le envían play/pausa, saltos, seek, aleatorio y bucle (solo on/off). Letra y cola siguen siendo las del PC. Lo nuevo puesto en el PC suena en el PC. Test: `RemoteMirrorTest`.
- Pantalla de bloqueo (06/11, sin probar en el teléfono): mientras `MobileConnection.following` no es null, `ConnectionService` publica una MediaSession propia (`PcPlayer`, SimpleBasePlayer con la canción y sus vecinas) y una notificación MediaStyle; sus botones mandan toggle/next/previous/seek al PC. El artista lleva «· en el ordenador». Si el reproductor local tiene una canción en pausa, su notificación puede convivir con esta.
- Estado periódico (06/11): `StateOutbox` en `LocalLink` (ver su comentario). Los adaptadores reutilizan el JSONArray de la cola por identidad; `showRemote` reutiliza la lista parseada.
- Carátula del PC en el móvil: `showRemote` la pide a `ARTWORK_PX` con `resize` (antes se veía la miniatura pequeña de la lista).
- Probado con el Nothing real y el PC habitual: siguiente, pausa/reanudar, seek, elegir canción en el móvil (suena en el PC), «Traer aquí» y gota local. Cola habitual restaurada: 58 canciones, índice 17, 177107 ms, pausada.
