# Exportar a Oto

Extensión de [Spicetify](https://spicetify.app) que guarda todas tus listas de Spotify (también las privadas, las que sigues y «Canciones que te gustan») en un archivo para importarlas en Oto.

## Instalar

Pega una línea en el PC donde tienes Spotify. Si no tienes Spicetify, lo instala también.

**Windows**: abre PowerShell (menú Inicio → «PowerShell», sin administrador) y pega:

```powershell
iwr -useb https://raw.githubusercontent.com/iaguito22/oto/main/tools/spicetify/install.ps1 | iex
```

**Linux o macOS**: en una terminal:

```sh
curl -fsSL https://raw.githubusercontent.com/iaguito22/oto/main/tools/spicetify/install.sh | sh
```

Spotify se reinicia solo al acabar.

## Usar

1. En Spotify, pulsa tu foto de perfil (arriba a la derecha) → **Exportar a Oto**. Se guarda `oto-spotify.json` en Descargas.
2. Pasa ese archivo al móvil (Quick Share, Telegram, un cable…).
3. En Oto: Ajustes → Importar de Spotify → **Abrir archivo**, y eliges qué listas traer.

## A mano

Lo que hacen los scripts, por si prefieres hacerlo tú:

1. Copia `oto-export.js` en la carpeta `Extensions` de Spicetify. Esa carpeta te la dice `spicetify path userdata` (en Windows suele ser `%APPDATA%\spicetify\Extensions`; en Linux, `~/.config/spicetify/Extensions`).
2. Actívala con `spicetify config extensions oto-export.js`. Así se añade a las extensiones que ya tengas, sin quitarlas.
3. Aplica con `spicetify apply`. Si es la primera vez que usas Spicetify, el comando es `spicetify backup apply`.

## Si no sale «Exportar a Oto»

- Spotify se actualizó y quitó Spicetify: vuelve a pegar la línea de arriba (o `spicetify backup apply`).
- En Linux, con Spotify instalado en `/opt/spotify` o en `/usr/share/spotify`, Spicetify necesita permiso para escribir ahí: `sudo chmod a+wr /opt/spotify /opt/spotify/Apps -R` (o la carpeta de tu Spotify).
