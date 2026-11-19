# Tests de los módulos que cambian respecto a main (o todos con -All).
# Requiere `. ..\env.ps1` antes (JDK 21, FFmpeg, GRADLE_USER_HOME). Plantilla: tools/env.example.ps1
# -BuildDir <carpeta>: compila fuera de */build. Útil si otro agente compila a la vez en el mismo checkout
# (se pisan core/build y fallan pruebas al azar).
param([switch]$All, [string]$Base = "origin/main", [string]$BuildDir)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ($All) {
    $changed = @("core/", "desktopApp/", "app/")
} else {
    $changed = @(git diff --name-only "$Base...HEAD") + @(git diff --name-only HEAD) + @(git ls-files --others --exclude-standard) | Sort-Object -Unique
}
function Want($pattern) { [bool]($changed | Where-Object { $_ -match "^($pattern)" }) }

# El SDK de este PC vive junto al repo; env.ps1 no lo pone.
if (-not $env:ANDROID_HOME) {
    $sdk = Join-Path $PSScriptRoot "..\..\tools\android-sdk"
    if (Test-Path $sdk) { $env:ANDROID_HOME = (Resolve-Path $sdk).Path }
}

$shared = Want 'core/|innertube/|gradle/|build.gradle.kts|settings.gradle.kts'
$tasks = @()
if ($shared) { $tasks += ":core:test" }
if ($shared -or (Want 'desktopApp/')) { $tasks += ":desktopApp:test" }
if ($shared -or (Want 'app/')) {
    if ($env:ANDROID_HOME) { $tasks += ":app:testDebugUnitTest" }
    else { Write-Warning "Sin ANDROID_HOME: se omiten los tests de :app (los pasa la CI)." }
}

if ($tasks.Count -eq 0) { Write-Host "Sin cambios en código Gradle: nada que probar."; exit 0 }
$extra = @()
if ($BuildDir) {
    $init = Join-Path ([IO.Path]::GetTempPath()) "oto-builddir.gradle"
    $dir = [IO.Path]::GetFullPath($BuildDir).Replace('\', '/')
    Set-Content -Encoding ascii $init "allprojects { layout.buildDirectory.set(new File('$dir/' + name)) }"
    $extra = @("-I", $init)
}
Write-Host "Ejecutando: $($tasks -join ' ')"
& .\gradlew.bat @extra @tasks --continue --offline --no-configuration-cache
exit $LASTEXITCODE
