param([string]$WixBin=$env:WIX_TOOLSET_DIR,[switch]$SkipBuild)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    if (!$env:JAVA_HOME) { throw 'Configura JAVA_HOME con un JDK 21.' }
    if ($WixBin) { $env:PATH="$WixBin;$env:PATH" }
    if (!(Get-Command candle.exe -ErrorAction SilentlyContinue)) { throw 'Configura WIX_TOOLSET_DIR con WiX 3.11 o 3.14.' }
    if (!$SkipBuild) {
        & ./gradlew.bat :desktopApp:createDistributable --no-configuration-cache
        if ($LASTEXITCODE) { throw 'No se pudo compilar la imagen de Oto.' }
    }
    $version=[regex]::Match([IO.File]::ReadAllText("$PSScriptRoot/build.gradle.kts"),'packageVersion = "([^"]+)"').Groups[1].Value
    $resources="$PSScriptRoot/build/installer-resources"
    $dist="$PSScriptRoot/build/dist"
    New-Item -ItemType Directory -Force -Path $resources,$dist | Out-Null
    & "$PSScriptRoot/packaging/windows/generate-assets.ps1" -Destination $resources
    Copy-Item "$PSScriptRoot/packaging/windows/main.wxs" "$resources/main.wxs"
    $resourceXml=[System.Security.SecurityElement]::Escape($resources.Replace('\','/'))
    [IO.File]::WriteAllText("$resources/overrides.wxi",'<?xml version="1.0" encoding="utf-8"?><Include><?define OtoResourceDir="'+$resourceXml+'" ?></Include>')
    & "$env:JAVA_HOME/bin/jpackage.exe" --type msi --app-image "$PSScriptRoot/build/compose/binaries/main/app/Oto" --dest $dist --name Oto --app-version $version --vendor iaguito22 --description 'Oto - Tu música, a tu manera' --resource-dir $resources --install-dir 'Oto Desktop' --win-per-user-install --win-dir-chooser --win-shortcut --win-menu --win-menu-group Oto --win-upgrade-uuid '8f45e92a-3c74-4b2e-9a81-56e8049a1201' --win-help-url 'https://github.com/iaguito22/oto/issues' --win-update-url 'https://github.com/iaguito22/oto/releases' --verbose
    if ($LASTEXITCODE) { throw 'No se pudo crear el instalador MSI.' }
    Get-Item "$dist/Oto-$version.msi"
} finally { Pop-Location }
