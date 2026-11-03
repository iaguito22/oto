# Oto 音

Reproductor de música para Android y escritorio sobre YouTube Music. En Android se maneja con gestos; en escritorio, con ventanas que se pueden acoplar.

## Organización del proyecto

- `app/`: aplicación Android y conexión con el escritorio.
- `desktopApp/`: aplicación de escritorio y sus ventanas modulares.
- `core/`: preferencias, biblioteca, reproducción y protocolo compartidos.
- `innertube/`: integración con YouTube Music utilizada por ambas aplicaciones.

Las dos aplicaciones pueden vincularse en la red local desde «Dispositivos». La disponibilidad de la conexión depende también de la configuración del cortafuegos.

<p align="center">
  <img src="docs/letra.gif" width="260" alt="La letra, palabra a palabra, con la gota debajo">
  &nbsp;&nbsp;
  <img src="docs/latido.gif" width="260" alt="La carátula y el aro laten con los graves">
</p>

<p align="center">
  <img src="docs/sonando.jpg" width="200" alt="Lo que suena">
  <img src="docs/colores.jpg" width="200" alt="El color de la carátula tiñe toda la pantalla">
  <img src="docs/superpower.jpg" width="200" alt="Una canción desde la radio">
</p>
<p align="center">
  <img src="docs/letra.jpg" width="200" alt="La letra">
  <img src="docs/library.jpg" width="200" alt="Tu música">
  <img src="docs/settings.jpg" width="200" alt="Ajustes">
</p>
<p align="center">
  <img src="docs/listas.jpg" width="200" alt="Todas tus listas">
  <img src="docs/explorar.jpg" width="200" alt="Explorar: éxitos, tendencias y lo nuevo, con los ánimos arriba">
  <img src="docs/buscar.jpg" width="200" alt="Buscar, con el resultado principal en grande">
</p>

## Qué tiene

- **Letra sincronizada palabra a palabra** cuando la fuente lo trae, con los coros aparte y la pronunciación debajo del japonés, chino, coreano o cirílico. Se busca en cuanto empieza la canción y se guarda en el móvil: abrirla es al momento.
- **Tus listas y carpetas**, con Me gusta y el DJ. Se fijan arriba y se recolocan arrastrando («Mover», manteniendo cualquiera); las que has oído hace poco salen debajo, y todas en «Todas tus listas», con orden y filtros.
- **Importar de Spotify** tus listas y tus me gusta, con la extensión de Spicetify de [`tools/spicetify`](tools/spicetify) (se instala pegando una línea).
- **Cuenta de YouTube Music** (opcional): recomendaciones tuyas y una copia de tus listas en tu cuenta, que vuelven solas al reinstalar.
- **DJ**: una mezcla continua de lo tuyo, lo que te falta de tus artistas y artistas nuevos, que aprende de cuánto te quedas con cada canción.
- **Hecho con lo tuyo** en Explorar: En bucle, mezclas de tus artistas, lo que hace tiempo que no oyes y lo mejor del DJ.
- **Android Auto**: tu música, lo oído hace poco, el DJ y la búsqueda (también por voz) en el coche.
- **Descargas** de listas o canciones sueltas, para oír sin conexión.
- **Calidad Auto o Máxima**, ahorro de datos y precarga de las siguientes canciones.
- Aleatorio que separa artistas, bucle, canciones parecidas al acabar una lista, fundido entre canciones, cola editable y salida de audio.

## Instalar

Descargas de [Oto 1.2.3](https://github.com/iaguito22/oto/releases/tag/v1.2.3):

| Sistema | Paquete |
|---|---|
| Windows x64 | MSI con instalador de Oto |
| Ubuntu/Debian amd64 | DEB; instalar con `sudo apt install ./oto_1.2.3_amd64.deb` |
| Arch Linux x86_64 | Paquete nativo; instalar con `sudo pacman -U ./oto-1.2.3-1-x86_64.pkg.tar.zst` |
| Android 13 o superior | APK optimizado `phone`, para el paquete `io.github.iaguito22.oto.debug` |

El APK `phone` conserva la firma de las instalaciones personales de esa variante. El APK público anterior, sin sufijo `.debug`, tiene otra firma: consultar las notas de la release antes de actualizar y no desinstalarlo para sortear diferencias de firma. No está en Play Store.

Los paquetes Linux incluyen Java y dependen de FFmpeg. El MSI de Windows todavía no está firmado con Authenticode, por lo que SmartScreen puede mostrar un aviso de reputación. Los hashes se adjuntan a la release. Véase [Publicación](docs/PUBLICACION.md) para compilar paquetes y mantener las identidades de firma.

La rama común de desarrollo y publicación para todos los sistemas es `main`.

## Hecho con

- Kotlin · Jetpack Compose · Media3
- [InnerTubeX](https://github.com/MetrolistGroup/innertubex) para la extracción de streams
- El módulo `innertube/` viene de [Metrolist](https://github.com/MetrolistGroup/Metrolist) (GPL-3.0)
- Letras de lyricsplus, NetEase, [LRCLIB](https://lrclib.net) y YouTube Music

## Licencia

GPL-3.0
