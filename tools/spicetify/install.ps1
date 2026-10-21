# Oto · instala la extensión «Exportar a Oto» en el Spotify de Windows.
# Uso (PowerShell, sin abrir como administrador):
#   iwr -useb https://raw.githubusercontent.com/iaguito22/oto/main/tools/spicetify/install.ps1 | iex
$ErrorActionPreference = 'Stop'
$url = 'https://raw.githubusercontent.com/iaguito22/oto/main/tools/spicetify/oto-export.js'

function Say($text) { Write-Host "Oto: $text" -ForegroundColor Cyan }

# El instalador de Spicetify lo deja aquí, pero el PATH de esta ventana puede no saberlo todavía.
$env:PATH += ";$env:LOCALAPPDATA\spicetify"
if (-not (Get-Command spicetify -ErrorAction SilentlyContinue)) {
    Say 'no tienes Spicetify. Lo instalo (es lo que deja añadir extensiones a Spotify).'
    Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/spicetify/cli/main/install.ps1' | Invoke-Expression
    $env:PATH += ";$env:LOCALAPPDATA\spicetify"
    if (-not (Get-Command spicetify -ErrorAction SilentlyContinue)) {
        throw 'Spicetify no ha quedado instalado. Cierra esta ventana, abre otra y vuelve a pegar el comando.'
    }
}

$dir = Join-Path (spicetify path userdata | Select-Object -Last 1).Trim() 'Extensions'
New-Item -ItemType Directory -Force $dir | Out-Null
$local = if ($PSScriptRoot) { Join-Path $PSScriptRoot 'oto-export.js' }
if ($local -and (Test-Path $local)) { Copy-Item $local (Join-Path $dir 'oto-export.js') -Force }
else { Invoke-WebRequest -UseBasicParsing $url -OutFile (Join-Path $dir 'oto-export.js') }
Say "extensión copiada en $dir"

# Esta es la «variable» de Spicetify: la lista de extensiones activas. Se añade sin quitar las que ya tengas.
$current = (spicetify config extensions | Out-String)
if ($current -notmatch 'oto-export\.js') { spicetify config extensions oto-export.js | Out-Null }

# Aplica los cambios a Spotify; si es la primera vez que se usa Spicetify, hace falta la copia de seguridad antes.
spicetify apply
if ($LASTEXITCODE -ne 0) { spicetify backup apply }
if ($LASTEXITCODE -ne 0) { throw 'Spicetify no pudo aplicar los cambios (mira el mensaje de arriba).' }

Say 'listo. En Spotify: tu foto de perfil (arriba a la derecha) → «Exportar a Oto».'
