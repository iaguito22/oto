# PLAN_IMPLEMENTACION — Oto Desktop (Linux y Windows)

Repo `oto-kmp`: copia de `oto` con la lógica compartida en `:core` y `:innertube` (JVM puro) y dos clientes:
`:app` (Android, Media3) y `:desktopApp` (Compose Desktop). Editar `:core` cambia ambos.

Regla de oro para cualquier agente: **leer el fichero real antes de llamar a una API**. Las firmas de `:core`
son las del código, no las de este documento. Compilar tras cada tarea y no pasar a la siguiente en rojo.

## Comandos de verificación

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew :core:test :app:compileDebugKotlin :desktopApp:compileKotlin
./gradlew :desktopApp:run            # ventana de escritorio
./gradlew :desktopApp:packageDeb     # Linux; en Windows: packageMsi / packageExe
```

## Mapa de módulos

| Módulo | Contenido | Depende de |
|---|---|---|
| `:innertube` | Cliente YouTube Music (JVM). `timber/log/Timber.kt` es un shim propio | — |
| `:core` | DJ, letras, importador, cuenta, `Prefs`, `OtoDirs`, `MenuController`, `Suggest`, `InnerTubeXPlayer`, contrato `OtoPlayer` | `:innertube` |
| `:app` | UI Android, `PlaybackService`, `PlayerConnection : OtoPlayer`, PoToken WebView | `:core` |
| `:desktopApp` | `Main.kt`, `DesktopPlayerConnection : OtoPlayer`, `DesktopAudioEngine`, UI Compose | `:core` |

## Reglas de la capa compartida

1. `:core` no importa `android.*`, `androidx.media3`, `coil` ni `androidx.compose`. Lo específico de plataforma entra por
   parámetro o hook (`OtoDirs.init(files, cache)`, `TraceHooks`, `InnerTubeXPlayer.PoTokenCallback`).
2. Rutas de datos: siempre `OtoDirs.filesDir` / `OtoDirs.cacheDir` / `OtoDirs.dataStore(name)`. En Android se inicializa en
   `OtoApp.onCreate` con `filesDir`/`cacheDir`, así los ficheros DataStore son los mismos de antes. En escritorio:
   `~/.local/share/oto` y `~/.cache/oto` (Linux), `%APPDATA%\oto` y `%LOCALAPPDATA%\oto` (Windows).
3. Logs: `timber.log.Timber` (shim en `:innertube`; en Android la dependencia real de Timber convive con él).
4. Todo cambio en `:core` se comprueba con `:core:test` y con las compilaciones de `:app` y `:desktopApp`.

## Contrato `OtoPlayer` (`core/.../playback/OtoPlayer.kt`)

`state`, `queue`, `previousEntry`, `source`, `shuffle`, `loop` (StateFlow) y `play`, `playAll`, `playLocal`, `playDj`, `playNext`,
`move`, `place`, `remove`, `setShuffle`, `setLoop`, `toggle`, `next`, `previous`, `restarts`, `seekTo`, `skipTo`, `position`.
`NowPlaying` no lleva posición: se lee con `position()` (en escritorio, además `DesktopPlayerConnection.positionMs`).

## Estado por fases

| Fase | Contenido | Estado |
|---|---|---|
| 1 | `:innertube` a JVM puro | hecha |
| 2 | `:core` extraído, `:app` cableado a él | hecha (compila y tests en verde) |
| 3 | `:desktopApp`: build, `Main`, tema y extracción de colores | hecha, sin probar a mano |
| 4 | Audio de escritorio (`DesktopAudioEngine`, `DesktopPlayerConnection`) | compila, sin probar sonando |
| 5 | UI: `DesktopStage` (copia de `fusion.html`) y `DesktopMenuSheet` | compila, sin revisar visualmente |
| 6 | Empaquetado `.deb`/AppImage/`.msi`/`.exe` y pruebas en Windows | pendiente |

## Tareas pendientes (una por vez, cada una con su criterio de fin)

### T1. Primera ejecución en Linux
- Hacer: `./gradlew :desktopApp:run`, poner una canción desde el buscador.
- Fin: suena audio, la barra de la corona avanza, la letra se sincroniza, 0 excepciones en consola.
- Trampa: sin `ffplay` en el `PATH` cae a `javax.sound.sampled`, que no decodifica Opus/WebM. Decidir y documentar si se
  exige `ffmpeg` como dependencia (`.deb`: `Depends: ffmpeg`) o se integra un decodificador JVM.

### T2. Paridad visual con `fusion.html`
- Referencia: `PROYECTOS/oto-web-prototipos/fusion.html`. Revisar con `agy-pantalla`.
- Fin: `DESDE TU LISTA` centrado arriba; título a la izquierda y artista a la derecha de borde a borde de carátula y letra,
  sin línea inferior ni sombra; tiempo arriba y calidad abajo de la carátula, sin borde ni fondo; controles solo al pasar el ratón.

### T3. Menú: abrir y cerrar con rueda
- `Main.kt` solo abre con rueda hacia abajo; falta cerrar con rueda hacia arriba cuando la lista del menú está arriba del todo.
- Fin: abrir/cerrar con rueda, `Ctrl+K` y `Escape`, sin parpadeos al encadenar eventos.

### T4. Sesión de YouTube Music en escritorio (implementada, sin probar)
- Código: `desktopApp/.../BrowserLogin.kt` (servidor en 127.0.0.1, token de un solo uso, 5 min) y la extensión
  `desktopApp/src/main/resources/oto-login-extension/` (MV3: Chrome/Chromium/Brave/Edge y Firefox).
- Flujo: "Iniciar sesión" abre `https://music.youtube.com/?oto_login=PUERTO.TOKEN`; la extensión espera a que haya sesión,
  lee las cookies (incluidas las HttpOnly, que una página no puede ver) y `yt.config_`, y hace POST a `/session`.
  `Account.signIn(Session)` hace el resto y `Cloud` sincroniza.
- Fin: instalar la extensión una vez ("Instalar extensión" extrae la carpeta; cargarla sin empaquetar en
  `chrome://extensions` o `about:debugging`), pulsar "Iniciar sesión", ver el nombre de la cuenta y que las listas
  del móvil aparecen.
- Trampa: la extensión sin firmar en Firefox solo dura hasta cerrarlo; para uso diario, firmarla o usar Chromium.

### T5. Cola, aleatorio y bucle en la UI de escritorio
- `QueuePane` solo salta a una canción: añadir mover/quitar con `move`/`remove` y botones de `setShuffle`/`setLoop`.

### T6. Empaquetado
- `packageDeb`, `packageAppImage` en Linux; `packageMsi` y `packageExe` solo se generan en Windows (jpackage no cruza SO).
- Fin: instalar y abrir cada paquete en su sistema.

## Prompt base por fase (para Gemini 3.8 Flash High)

```
Contexto: repo oto-kmp, leer AGENTS.md y PLAN_IMPLEMENTACION.md.
Tarea: <Tn>. Lee primero cada fichero de :core que vayas a llamar.
Criterio de fin: <el de la tarea>. Ejecuta los comandos de verificación y pega la salida.
Un commit `tipo: resumen` por cambio lógico. No toques /home/iago/PROYECTOS/oto.
```
