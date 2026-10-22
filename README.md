# Oto 音

Reproductor de música para Android sobre YouTube Music, sin pestañas ni botones: una gota que se maneja con gestos.

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

Descarga el APK de la [última versión](https://github.com/iaguito22/oto/releases/latest) y ábrelo en el móvil. Android 13 o más. No está en Play Store.

## Hecho con

- Kotlin · Jetpack Compose · Media3
- [InnerTubeX](https://github.com/MetrolistGroup/innertubex) para la extracción de streams
- El módulo `innertube/` viene de [Metrolist](https://github.com/MetrolistGroup/Metrolist) (GPL-3.0)
- Letras de lyricsplus, NetEase, [LRCLIB](https://lrclib.net) y YouTube Music

## Licencia

GPL-3.0
