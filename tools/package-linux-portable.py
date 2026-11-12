#!/usr/bin/env python3
"""Empaqueta JAR ya compilados con JRE/Skiko Linux, incluso desde Windows.

No reutiliza ejecutables ni DLL de Windows. Requiere Python 3 y zstandard.
Las entradas deben proceder de distribuciones oficiales verificadas.
"""
import argparse
import copy
import gzip
import hashlib
import io
import os
from pathlib import Path
import tarfile
import time
import zipfile
import zstandard

p = argparse.ArgumentParser()
p.add_argument('--app-dir', type=Path, required=True)
p.add_argument('--linux-jre', type=Path, required=True)
p.add_argument('--linux-skiko', type=Path, required=True)
p.add_argument('--version', required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
stamp = int(os.environ.get('SOURCE_DATE_EPOCH', time.time()))
repo = Path(__file__).resolve().parent.parent
payload = a.output / 'oto-linux-payload.tar'

def ensure_parents(tar, name):
    directories = getattr(tar, '_oto_directories', set())
    tar._oto_directories = directories
    parts = name.split('/')
    for count in range(1, len(parts)):
        parent = '/'.join(parts[:count])
        if parent not in directories:
            info = tarfile.TarInfo(parent)
            info.type, info.mode, info.mtime = tarfile.DIRTYPE, 0o755, stamp
            info.uname = info.gname = 'root'
            tar.addfile(info)
            directories.add(parent)

def add_bytes(tar, name, data, mode=0o644):
    ensure_parents(tar, name)
    info = tarfile.TarInfo(name)
    info.size, info.mode, info.mtime = len(data), mode, stamp
    info.uid = info.gid = 0
    info.uname = info.gname = 'root'
    tar.addfile(info, io.BytesIO(data))

def add_link(tar, name, target):
    ensure_parents(tar, name)
    info = tarfile.TarInfo(name)
    info.type, info.linkname, info.mode, info.mtime = tarfile.SYMTYPE, target, 0o777, stamp
    tar.addfile(info)

launcher = b'''#!/bin/sh
SELF="$(readlink -f -- "$0" 2>/dev/null || printf '%s' "$0")"
ROOT="$(CDPATH= cd -- "$(dirname -- "$SELF")/.." && pwd)"
exec "$ROOT/runtime/bin/java" -Dcompose.application.configure.swing.globals=true -Dcompose.application.resources.dir="$ROOT/app/resources" -cp "$ROOT/app/*" io.github.iaguito22.oto.desktop.MainKt "$@"
'''
desktop = b'''[Desktop Entry]
Type=Application
Name=Oto
Comment=Tu musica, a tu manera
Exec=oto %U
Icon=oto
Terminal=false
Categories=AudioVideo;Audio;Player;
StartupWMClass=io-github-iaguito22-oto-desktop-MainKt
'''

with zipfile.ZipFile(a.linux_skiko) as skiko:
    native = next(n for n in skiko.namelist() if n.endswith('libskiko-linux-x64.so'))
    assert skiko.read(native).startswith(b'\x7fELF'), 'Skiko no es un binario ELF Linux'

with tarfile.open(payload, 'w', format=tarfile.PAX_FORMAT) as out:
    for jar in sorted((a.app_dir / 'app').glob('*.jar')):
        if 'runtime-windows' not in jar.name and 'runtime-linux' not in jar.name:
            add_bytes(out, 'opt/oto/app/' + jar.name, jar.read_bytes())
    add_bytes(out, 'opt/oto/app/skiko-awt-runtime-linux-x64.jar', a.linux_skiko.read_bytes())
    with tarfile.open(a.linux_jre, 'r:gz') as jre:
        prefix = jre.getmembers()[0].name.split('/')[0] + '/'
        java = jre.extractfile(prefix + 'bin/java').read(4)
        assert java == b'\x7fELF', 'El JRE no es Linux'
        for item in jre:
            if not item.name.startswith(prefix):
                continue
            relative = item.name[len(prefix):]
            if not relative or '..' in Path(relative).parts:
                continue
            target = copy.copy(item)
            target.name = 'opt/oto/runtime/' + relative
            ensure_parents(out, target.name)
            if target.isdir():
                out._oto_directories.add(target.name.rstrip('/'))
            if target.islnk() and target.linkname.startswith(prefix):
                target.linkname = 'opt/oto/runtime/' + target.linkname[len(prefix):]
            target.uid = target.gid = 0
            target.uname = target.gname = 'root'
            target.mtime = stamp
            out.addfile(target, jre.extractfile(item) if item.isfile() else None)
    add_bytes(out, 'opt/oto/bin/Oto', launcher, 0o755)
    add_link(out, 'usr/bin/oto', '/opt/oto/bin/Oto')
    add_bytes(out, 'usr/share/applications/oto.desktop', desktop)
    add_bytes(out, 'usr/share/icons/hicolor/512x512/apps/oto.png', (repo / 'desktopApp/src/main/resources/oto-icon.png').read_bytes())
    license_file = next((f for f in (repo/'LICENSE',repo/'LICENSE.md',repo/'COPYING') if f.exists()), None)
    if license_file:
        add_bytes(out, 'usr/share/licenses/oto/LICENSE', license_file.read_bytes())

with tarfile.open(payload) as content:
    size = sum(item.size for item in content)
    assert not any(item.name.lower().endswith(('.exe','.dll','.keystore','.jks')) for item in content)
    directories = set()
    for item in content:
        parts = item.name.rstrip('/').split('/')
        assert all('/'.join(parts[:count]) in directories for count in range(1,len(parts))), item.name
        if item.isdir():
            directories.add(item.name.rstrip('/'))

control = f'''Package: oto
Version: {a.version}
Section: sound
Priority: optional
Architecture: amd64
Installed-Size: {(size+1023)//1024}
Maintainer: iaguito22
Depends: libc6 (>= 2.17), libx11-6, libxext6, libxrender1, libxtst6, libxi6, libfreetype6, libfontconfig1, libasound2 | libasound2t64, libgl1, ffmpeg
Description: Tu musica, a tu manera
 Reproductor con biblioteca, letras sincronizadas y ventanas combinables.
'''.encode()
control_stream = io.BytesIO()
with tarfile.open(fileobj=control_stream, mode='w:gz') as tar:
    add_bytes(tar, 'control', control)
data_gz = a.output / 'data.tar.gz'
with payload.open('rb') as src, gzip.open(data_gz,'wb',compresslevel=6) as dst:
    import shutil
    shutil.copyfileobj(src,dst)

def ar_member(out, name, stream, length):
    header = f'{name+"/":<16}{stamp:<12}{0:<6}{0:<6}{100644:<8}{length:<10}`\n'.encode('ascii')
    assert len(header) == 60
    out.write(header)
    shutil.copyfileobj(stream,out)
    if length % 2:
        out.write(b'\n')

deb = a.output / f'oto_{a.version}_amd64.deb'
with deb.open('wb') as out:
    out.write(b'!<arch>\n')
    ar_member(out,'debian-binary',io.BytesIO(b'2.0\n'),4)
    ar_member(out,'control.tar.gz',io.BytesIO(control_stream.getvalue()),len(control_stream.getvalue()))
    with data_gz.open('rb') as src:
        ar_member(out,'data.tar.gz',src,data_gz.stat().st_size)

pkginfo = f'''pkgname = oto
pkgbase = oto
pkgver = {a.version}-1
pkgdesc = Tu musica, a tu manera
url = https://github.com/iaguito22/oto
builddate = {stamp}
packager = iaguito22
size = {size}
arch = x86_64
license = GPL-3.0-only
depend = glibc
depend = libx11
depend = libxext
depend = libxrender
depend = libxtst
depend = libxi
depend = freetype2
depend = fontconfig
depend = alsa-lib
depend = libglvnd
depend = ffmpeg
'''.encode()
arch = a.output / f'oto-{a.version}-1-x86_64.pkg.tar.zst'
with arch.open('wb') as file, zstandard.ZstdCompressor(level=12).stream_writer(file) as compressed:
    with tarfile.open(fileobj=compressed,mode='w|') as out:
        add_bytes(out,'.PKGINFO',pkginfo)
        with tarfile.open(payload) as source:
            for item in source:
                out.addfile(item,source.extractfile(item) if item.isfile() else None)

for artifact in (deb,arch):
    print(f'{artifact.name}: {artifact.stat().st_size} bytes; sha256={hashlib.file_digest(artifact.open("rb"),"sha256").hexdigest()}')
