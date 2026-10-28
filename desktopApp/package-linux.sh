#!/usr/bin/env bash
# Empaqueta Oto para Linux a partir de la imagen de aplicación que genera jpackage.
#
#   ./desktopApp/package-linux.sh
#
# Deja en desktopApp/build/dist:
#   - Oto-<versión>-x86_64.AppImage
#   - oto_<versión>_amd64.deb
#
# En Arch (esta máquina) jpackage no puede crear el .deb por su cuenta porque no hay dpkg-deb,
# así que el .deb se arma a mano con ar + tar, que es exactamente lo que es un .deb.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
VERSION="$(sed -n 's/.*packageVersion = "\([^"]*\)".*/\1/p' desktopApp/build.gradle.kts | head -1)"
ARCH="x86_64"
APP_IMAGE="desktopApp/build/compose/binaries/main/app/Oto"
DIST="desktopApp/build/dist"
WORK="$DIST/work"
ICON="desktopApp/src/main/resources/oto-icon.png"

echo "==> Compilando la imagen de aplicación (Oto $VERSION)"
./gradlew :desktopApp:packageAppImage

rm -rf "$WORK"
mkdir -p "$DIST" "$WORK"

# ---------------------------------------------------------------- AppImage
echo "==> Armando el AppDir"
APPDIR="$WORK/Oto.AppDir"
mkdir -p "$APPDIR/usr/lib" "$APPDIR/usr/share/icons/hicolor/512x512/apps" "$APPDIR/usr/share/applications"
cp -a "$APP_IMAGE" "$APPDIR/usr/lib/oto"
cp "$ICON" "$APPDIR/oto.png"
cp "$ICON" "$APPDIR/usr/share/icons/hicolor/512x512/apps/oto.png"
ln -sf oto.png "$APPDIR/.DirIcon"
cat > "$APPDIR/AppRun" <<'SH'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/lib/oto/bin/Oto" "$@"
SH
chmod +x "$APPDIR/AppRun"
cat > "$APPDIR/oto.desktop" <<'DESK'
[Desktop Entry]
Type=Application
Name=Oto
Comment=Tu música de YouTube Music
Exec=oto %U
Icon=oto
Terminal=false
Categories=AudioVideo;Audio;Player;
StartupWMClass=Oto
DESK
cp "$APPDIR/oto.desktop" "$APPDIR/usr/share/applications/oto.desktop"

TOOL="$(command -v appimagetool || true)"
if [ -z "$TOOL" ]; then
    TOOL="$WORK/appimagetool"
    if [ ! -x "$TOOL" ]; then
        echo "==> Descargando appimagetool"
        curl -fsSL -o "$TOOL" \
            https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
        chmod +x "$TOOL"
    fi
fi

echo "==> Creando el AppImage"
ARCH="$ARCH" "$TOOL" --appimage-extract-and-run "$APPDIR" "$DIST/Oto-$VERSION-$ARCH.AppImage"

# --------------------------------------------------------------------- deb
echo "==> Armando el .deb"
DEB="$WORK/deb"
rm -rf "$DEB"
mkdir -p "$DEB/control" "$DEB/data/opt" "$DEB/data/usr/bin" \
    "$DEB/data/usr/share/applications" "$DEB/data/usr/share/icons/hicolor/512x512/apps"
cp -a "$APP_IMAGE" "$DEB/data/opt/oto"
ln -s /opt/oto/bin/Oto "$DEB/data/usr/bin/oto"
cp "$APPDIR/oto.desktop" "$DEB/data/usr/share/applications/oto.desktop"
cp "$ICON" "$DEB/data/usr/share/icons/hicolor/512x512/apps/oto.png"

SIZE_KB="$(du -sk "$DEB/data" | cut -f1)"
cat > "$DEB/control/control" <<CONTROL
Package: oto
Version: $VERSION
Section: sound
Priority: optional
Architecture: amd64
Installed-Size: $SIZE_KB
Maintainer: iaguito22 <hola@ejemplo.com>
Depends: libc6, libx11-6, libxext6, libxrender1, libxtst6, libxi6, libfreetype6, libfontconfig1, libasound2
Description: Tu música de YouTube Music
 Oto es un reproductor de YouTube Music con letras sincronizadas, descargas
 para escuchar sin conexión, cola, listas, carpetas y un DJ que mezcla lo que
 escuchas. La interfaz toma la paleta de la carátula en reproducción.
CONTROL

( cd "$DEB/data" && find . -type f -printf '%P\0' | sort -z | xargs -0 md5sum > "$ROOT/$DEB/control/md5sums" )
chmod 0644 "$DEB/control/md5sums" "$DEB/control/control"

printf '2.0\n' > "$WORK/debian-binary"
tar -C "$DEB/control" -czf "$WORK/control.tar.gz" .
tar -C "$DEB/data" -czf "$WORK/data.tar.gz" .
( cd "$WORK" && ar rcs "$ROOT/$DIST/oto_${VERSION}_amd64.deb" debian-binary control.tar.gz data.tar.gz )

echo
ls -lh "$DIST"/Oto-*.AppImage "$DIST"/oto_*.deb
