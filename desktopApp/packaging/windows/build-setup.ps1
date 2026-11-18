param(
    [string]$Msi,
    [Parameter(Mandatory=$true)][string]$Version,
    [Parameter(Mandatory=$true)][string]$Destination,
    [switch]$Preview
)
$ErrorActionPreference='Stop'
if (!$Preview -and !$Msi) { throw 'Indica el MSI de Oto.' }
if ($Preview -and $Msi) { throw 'La vista previa no admite un MSI.' }

$framework=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
$compiler=Join-Path $framework 'csc.exe'
if (!(Test-Path -LiteralPath $compiler)) { throw 'Se necesita .NET Framework 4.8.' }
$source=Join-Path $PSScriptRoot 'setup.cs'
$layout=Join-Path $PSScriptRoot 'setup.xaml'
$manifest=Join-Path $PSScriptRoot 'setup.manifest'
$icon=(Resolve-Path -LiteralPath "$PSScriptRoot/../../src/main/resources/oto-icon.ico").Path
$showcase=(Resolve-Path -LiteralPath "$PSScriptRoot/../../../docs/escritorio-una-ventana.png").Path
$dir=[IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$versionFile=Join-Path $dir 'setup-version.txt'
$installer=Join-Path $dir $(if ($Preview) { 'Oto-Setup-VistaPrevia.exe' } else { "Oto-$Version-Setup.exe" })

try {
    [IO.File]::WriteAllText($versionFile, ([version]$Version).ToString())
    $arguments=@(
        '/nologo', '/target:winexe', '/platform:x64', '/optimize+', '/codepage:65001',
        "/out:$installer", "/win32manifest:$manifest", "/win32icon:$icon",
        "/reference:$framework\WPF\WindowsBase.dll", "/reference:$framework\WPF\PresentationCore.dll",
        "/reference:$framework\WPF\PresentationFramework.dll", "/reference:$framework\System.Xaml.dll",
        "/reference:$framework\System.Windows.Forms.dll",
        "/resource:$layout,OtoLayout", "/resource:$showcase,OtoShowcase",
        "/resource:$versionFile,OtoVersion"
    )
    if (!$Preview) {
        $payload=(Resolve-Path -LiteralPath $Msi).Path
        $arguments+="/resource:$payload,OtoPayload"
    }
    & $compiler @arguments $source
    if ($LASTEXITCODE) { throw 'No se pudo compilar el instalador de Oto.' }
    Get-Item -LiteralPath $installer
} finally {
    Remove-Item -LiteralPath $versionFile -ErrorAction SilentlyContinue
}
