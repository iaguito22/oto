#!/usr/bin/env python3
"""Prueba de sesión de bandeja en Linux/Xvfb, sin leer cuentas ni bibliotecas."""
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.request

data = Path(sys.argv[1])
show = json.loads((data / 'desktop_access.json').read_text())['url']
assert show.startswith('http://127.0.0.1:') and show.endswith('/show')
hide = show[:-len('/show')]

def request(url):
    with urllib.request.urlopen(url, timeout=5) as response:
        assert response.status == 204

def player_visible():
    return subprocess.run(['xdotool','search','--onlyvisible','--name','Oto · Reproductor'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode == 0

for _ in range(3):
    request(hide)
    time.sleep(1)
    assert not player_visible(), 'El reproductor debe ocultarse'
    request(show)
    for _ in range(30):
        if player_visible():
            break
        time.sleep(.1)
    assert player_visible(), 'La sesión debe poder restaurar el reproductor'
print('Sesión de bandeja: tres ciclos de ocultar/restaurar correctos')
