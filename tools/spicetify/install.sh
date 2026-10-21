#!/bin/sh
# Oto · instala la extensión «Exportar a Oto» en el Spotify de Linux o macOS.
# Uso:
#   curl -fsSL https://raw.githubusercontent.com/iaguito22/oto/main/tools/spicetify/install.sh | sh
set -e
url=https://raw.githubusercontent.com/iaguito22/oto/main/tools/spicetify/oto-export.js
say() { printf '\033[36mOto:\033[0m %s\n' "$*"; }

# El instalador de Spicetify lo deja aquí, pero el PATH de esta terminal puede no saberlo todavía.
PATH="$PATH:$HOME/.spicetify"
if ! command -v spicetify >/dev/null 2>&1; then
    say "no tienes Spicetify. Lo instalo (es lo que deja añadir extensiones a Spotify)."
    curl -fsSL https://raw.githubusercontent.com/spicetify/cli/main/install.sh | sh
    command -v spicetify >/dev/null 2>&1 || { say "Spicetify no ha quedado instalado: abre otra terminal y vuelve a pegar el comando."; exit 1; }
fi

dir="$(spicetify path userdata | tail -n 1)/Extensions"
mkdir -p "$dir"
here="$(dirname "$0" 2>/dev/null)"
if [ -f "$here/oto-export.js" ]; then cp "$here/oto-export.js" "$dir/"
else curl -fsSL "$url" -o "$dir/oto-export.js"; fi
say "extensión copiada en $dir"

# Esta es la «variable» de Spicetify: la lista de extensiones activas. Se añade sin quitar las que ya tengas.
spicetify config extensions | grep -q 'oto-export\.js' || spicetify config extensions oto-export.js >/dev/null

# Aplica los cambios a Spotify; si es la primera vez que se usa Spicetify, hace falta la copia de seguridad antes.
spicetify apply || spicetify backup apply || {
    say "Spicetify no pudo aplicar los cambios (mira el mensaje de arriba). Si Spotify está en /opt o /usr, dale permiso:"
    say "  sudo chmod a+wr /opt/spotify /opt/spotify/Apps -R   (o la carpeta donde esté) y vuelve a pegar el comando."
    exit 1
}

say "listo. En Spotify: tu foto de perfil (arriba a la derecha) → «Exportar a Oto»."
