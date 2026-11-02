# Encargo para Codex en Windows: probar y arreglar Oto completo

Eres el agente de Codex que va a continuar este proyecto en mi ordenador Windows. No tienes contexto previo: este documento contiene el punto de entrada. Mi nombre es Iago y hablo español.

**Quiero que pruebes y arregles TODA la aplicación en Windows. No solo la modularidad ni solo los fallos que enumero.** Descubre los problemas reales, corrígelos, prueba de nuevo y continúa hasta completar todo lo que permita este equipo. No te quedes en un plan o una auditoría.

## 1. Consigue el proyecto correcto

El proyecto se llama **Oto**. Es un reproductor de música sobre YouTube Music/InnerTube, hecho en Kotlin, con cliente Android y aplicación de escritorio Compose Desktop para Linux y Windows.

- GitHub: https://github.com/iaguito22/oto
- **Rama que debes usar: `feat/prueba-acoplamiento`.**
- `main` puede no tener el escritorio y los últimos cambios. No empieces desde `main` ni sustituyas el trabajo por esa versión.
- El archivo principal de continuidad es `AGENTS.md` en la raíz. Es extenso y contiene arquitectura, decisiones de producto, riesgos, comandos y una matriz de pruebas de toda la aplicación.

Si no hay clon local, comprueba Git y elige una carpeta de trabajo disponible. Para un clon nuevo, desde PowerShell:

```powershell
git clone --single-branch --branch feat/prueba-acoplamiento https://github.com/iaguito22/oto.git
Set-Location oto
git status --short --branch
git log -1 --oneline
Get-Content AGENTS.md
```

Si ya existe un clon, comprueba su remoto, rama y cambios locales antes de actualizarlo. Conserva cualquier trabajo ajeno; usa un worktree si hace falta. No borres una carpeta existente para repetir el clon.

## 2. Lee el contexto y comprueba la máquina

Después del clon, lee:

1. `AGENTS.md`: instrucciones y contrato completo de lo que debe funcionar.
2. `docs/ACOPLAMIENTO.md`: cómo se comportan los grupos de ventanas y cómo usar el arnés.
3. `ESTADO_MODULAR.md`: historial de escritorio, conexión con el móvil y funciones anteriores.
4. El código real y la configuración Gradle de las áreas que vayas a tocar.

Los planes antiguos del repositorio no son una lista fiable de lo que falta: parte ya está hecho. La aplicación que debes seguir parte de `desktopApp/.../desktop/Main.kt` y `ModularDesktop.kt`.

Comprueba Windows nativo, sesión gráfica interactiva, JDK 21, Git, Gradle Wrapper, FFmpeg y ffplay. Usa las rutas reales de esta máquina; no copies las rutas Linux del documento. Si falta una herramienta necesaria, resuelve la preparación del entorno dentro de los permisos disponibles y continúa con lo independiente mientras tanto.

**Las pruebas en WSL o en una ventana Linux no acreditan que Oto funcione como aplicación Windows.** Necesito que abras y utilices su interfaz nativa.

## 3. Ejecuta, reproduce y arregla

En PowerShell, con JDK 21 correctamente configurado:

```powershell
.\gradlew.bat :core:test :desktopApp:test
.\gradlew.bat :desktopApp:run
```

En un equipo nuevo, deja descargar las dependencias: no uses `--offline` hasta tener la caché completa. Si aparece un error de SDK Android, claves o configuración, inspecciona el proyecto que lo produce y resuélvelo de forma adecuada. No inventes claves ni elimines Android a ciegas para que la tarea parezca pasar.

Haz una primera pasada por toda la interfaz. Registra fallos con pasos reproducibles, resultado esperado, resultado observado y evidencia. Corrige por cambios lógicos pequeños y comprueba el recorrido real que fallaba.

Para aislar geometría puedes usar el arnés, que no inicia música, cuenta ni sincronización:

```powershell
$env:OTO_DOCK_HARNESS = '1'
$env:OTO_DOCK_TRACE = '1'
.\gradlew.bat :desktopApp:run
```

Cierra el arnés y limpia esas variables antes de volver a la aplicación real:

```powershell
Remove-Item Env:OTO_DOCK_HARNESS -ErrorAction SilentlyContinue
Remove-Item Env:OTO_DOCK_TRACE -ErrorAction SilentlyContinue
```

Comprueba qué herramientas de captura y automatización gráfica tienes disponibles. Usa capturas e interacción real; una prueba JVM o una captura de navegador no valida las ventanas Compose. No presupongas que existen las herramientas locales Linux `agy-pantalla` y `agy-ver` en Windows.

## 4. Alcance: toda la aplicación

Usa la matriz detallada de `AGENTS.md`. Esta lista resume el alcance, no lo restringe:

- **Arranque y ciclo de vida:** primera apertura, persistencia, segunda instancia, bandeja, ocultar/mostrar, cierre y salida completa, procesos huérfanos.
- **Audio:** reproducir música real, pausa/reanudación, anterior/siguiente, final natural, seek, volumen, cambio rápido, cortes de red y backend de reproducción.
- **Biblioteca:** Tu música, Explorar, recientes, búsquedas, navegación a listas/artistas/álbumes y vuelta, listas grandes y estados vacíos/error.
- **Edición:** listas y carpetas de prueba, mover, renombrar, fijar, reordenar, guardar, quitar y deshacer; Me gusta y coherencia entre vistas.
- **Cola:** orden, salto, reordenación, quitar/vaciar, actual/anteriores/siguientes, aleatorio, bucle, radio/autoplay.
- **Letras:** sincronización, seguimiento, desplazamiento, salto por línea, romanización, tamaño, ausencia de letra y cambio de canción.
- **Descargas:** progreso, cancelación, reintento, persistencia, reproducción sin red y limpieza correcta.
- **Cuenta:** navegador temporal, cancelación, errores, login y restauración si hay cuenta disponible; selecciones independientes de listas.
- **Importación/exportación:** archivos válidos e inválidos, Spotify según lo implementado y protección de la biblioteca real.
- **Dispositivos:** QR, desconectado/reconexión, descubrimiento, firewall, controles remotos, transferencia y sincronización si hay móvil disponible.
- **Escritorio:** menús, hover, tooltips, atajos internos y globales, teclado, foco, bandeja, varias escalas y monitores.
- **Ventanas:** acoplamiento, separación, reordenación, prioridades, altura común, maximización, chincheta, persistencia y falta de espacio.
- **Distribución:** generar e instalar el paquete Windows y probar la aplicación instalada, con sus recursos, rutas y audio.

No declares una función probada si faltan cuenta, móvil, monitor o pasos reales. Marca el bloqueo exacto, completa lo que sí puedas probar y continúa arreglando fallos independientes. Si necesitas una intervención mía para una prueba concreta, pídela sin detener el resto del trabajo.

## 5. Decisiones de producto que debes respetar

El diseño ya está decidido; corrige su funcionamiento en Windows y conserva su estética.

- Biblioteca es la jefa sobre reproductor; reproductor manda sobre letra y cola. Entre iguales dirige la que recibió a la otra.
- Secundaria significa cualquier ventana que no sea la jefa, incluido reproductor cuando hay biblioteca.
- Arrastrar la jefa mueve el grupo. Sacar una secundaria se lleva sus dependientes según la distribución actual.
- Reordenar dentro del grupo mueve solo esa ventana: inserción en el mismo lado de la jefa; intercambio si cruza al otro lado.
- Las uniones son laterales, con altura compartida y ajuste desde cualquier miembro.
- En una instalación limpia se abre biblioteca con reproductor a la derecha. Los módulos nuevos se reparten después a ambos lados de la jefa; los conocidos recuperan lo guardado.
- Biblioteca tiene los accesos a módulos y los mismos tres puntos de opciones que reproductor.
- En **Windows y otros escritorios Linux sí quiero maximizar el conjunto**. En **Hyprland no quiero controles ni doble clic de maximización propios**.
- Al añadir ventanas maximizadas se reduce primero el ancho sobrante de secundarias hasta su predeterminado, después biblioteca hasta su mínimo. Si no cabe, la nueva abre suelta y se avisa.
- Sin biblioteca, el ancho flexible lo absorbe letra, después cola y después reproductor.
- La chincheta solo existe con reproductor completamente solo. Acoplarlo la desactiva. Volver a separarlo exige fijarlo manualmente otra vez.
- Cerrar solo cierra esa ventana. Pulsar una trae su grupo al frente y conserva el foco de teclado en la pulsada.

Los ejemplos y casos límite están detallados en `AGENTS.md`.

## 6. Puntos que merecen especial atención

- El atajo global actual se registra mediante Hyprland; comprobar y completar Windows, no darlo por soportado por existir un servidor local.
- FFmpeg/ffplay se buscan como procesos externos. Verificar PATH, ejecutables Windows, procesos al pausar/salir y la instalación empaquetada.
- Comprobar el login de navegador con las rutas de Chrome/Edge/Brave reales y la limpieza del perfil temporal.
- El adaptador de ventanas escucha eventos nativos: vigilar bucles entre AWT, Compose y el modelo, así como escala/DPI y maximización del sistema.
- Los menús emergentes no deben perder el foco por elevar las ventanas repetidamente.
- En Linux apareció un error repetido de sincronización local con el móvil desconectado: «El otro dispositivo no está conectado». Reproducir, diagnosticar y arreglar si corresponde; no limitarte a ocultar el log.
- El código compartido también sirve al móvil. Si lo cambias, ejecutar sus pruebas y comprobar compilación Android cuando esté disponible el SDK.

## 7. Datos y forma de trabajar

- Tengo datos y listas reales. Usa datos de prueba identificados o perfiles aislados para operaciones destructivas.
- No restaures una copia antigua sobre una biblioteca que yo pueda estar modificando desde el móvil.
- Iniciar sesión no autoriza importar/exportar todas las listas. Respeta las selecciones explícitas antes de escribir en mi cuenta.
- No publiques claves, cookies, secretos de QR, preferencias reales ni `local.properties`.
- No mates todos los procesos Java ni cambies globalmente el firewall para ocultar un problema.
- Después de dos arreglos sin efecto observable, instrumenta antes de seguir probando hipótesis.
- Resuelve las tareas autorizadas sin pedirme confirmación de cada comando; respeta la política efectiva de permisos de Codex.
- Conserva los cambios ajenos y organiza los tuyos por arreglos lógicos.
- Mantén los comentarios existentes y evita añadir dependencias o reescribir arquitectura sin una causa concreta.

## 8. Entrega y criterio de fin

Crea y mantén `docs/VALIDACION_WINDOWS.md` con:

- Windows, resolución, escalas, monitores, JDK y commit probado.
- Pruebas automatizadas ejecutadas y resultado real.
- Recorridos de cada área, fallos reproducidos, arreglos y comprobación posterior.
- Capturas o evidencia útil sin secretos.
- Resultado del instalador y de la aplicación instalada.
- Pendientes exactos que necesiten mi cuenta, teléfono, otro monitor u otro acceso.

**No termines al conseguir que compile ni al arreglar las ventanas.** Completa el recorrido por toda la aplicación, corrige los defectos que puedas resolver y comprueba regresiones relacionadas. Al terminar, dame un resumen breve en español de qué arreglaste, qué verificaste y qué no pudiste comprobar. Si queda un bloqueo externo, explica cuál y qué parte del resto ya completaste.

Empieza por localizar/clonar la rama indicada, leer `AGENTS.md` y comprobar el entorno Windows real.
