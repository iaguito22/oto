param([Parameter(Mandatory)][string]$App)
# Prepara el arranque rápido (CDS) en la imagen de Oto antes de crear el MSI.
#
# Medido el 06/11/2026 (perfil aislado, hasta ver la ventana del reproductor): sin CDS ~3,0 s; solo el archivo base
# del runtime ~2,9 s; con el archivo de clases de Oto ~1,7 s. Crearlo en el primer arranque del usuario
# (-XX:+AutoCreateSharedArchive) costaba ~15 s esa vez, por eso se entrena aquí y se instala ya hecho.
# Si el archivo no corresponde a la imagen (otro JAR u otra JVM), la JVM lo ignora y arranca como antes.
$ErrorActionPreference = 'Stop'
$cfg = Join-Path $App 'app\Oto.cfg'
$exe = Join-Path $App 'Oto.exe'
$archive = Join-Path $App 'app\oto.jsa'
$original = [IO.File]::ReadAllLines($cfg) | Where-Object { $_ -notmatch 'Xshare|SharedArchiveFile|ArchiveClassesAtExit' }
function Set-Options([string[]]$extra) { [IO.File]::WriteAllLines($cfg, [string[]]($original + $extra)) }

Add-Type @'
using System; using System.Text; using System.Runtime.InteropServices;
public class OtoCdsWindows {
  public delegate bool P(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumWindows(P p, IntPtr l);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageTimeout(IntPtr h, uint m, IntPtr w, IntPtr l, uint f, uint t, out IntPtr r);
  public static IntPtr Find(uint pid, string prefix, bool visible) { IntPtr found = IntPtr.Zero; EnumWindows((h,l)=>{ uint p; GetWindowThreadProcessId(h,out p); if(p==pid && (!visible || IsWindowVisible(h))){ var s=new StringBuilder(256); GetWindowText(h,s,256); if(s.ToString().StartsWith(prefix)){ found=h; return false; } } return true; }, IntPtr.Zero); return found; }
}
'@

try {
    # 1. Archivo base de las clases del runtime (jlink no lo genera).
    Set-Options @('java-options=-Xshare:dump')
    $dump = Start-Process $exe -PassThru -Wait -WindowStyle Hidden
    if ($dump.ExitCode -ne 0 -or !(Test-Path (Join-Path $App 'runtime\bin\server\classes.jsa'))) { throw 'No se pudo generar el archivo CDS base.' }

    # 2. Entrenamiento: arrancar Oto en un perfil temporal hasta ver el reproductor y salir como «Salir de Oto».
    if (Test-Path $archive) { (Get-Item $archive).IsReadOnly = $false; Remove-Item $archive }
    Set-Options @("java-options=-XX:ArchiveClassesAtExit=`$APPDIR\oto.jsa")
    $profile = Join-Path ([IO.Path]::GetTempPath()) ("oto-cds-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force "$profile\Roaming\oto", "$profile\Local" | Out-Null
    Set-Content -Encoding utf8 "$profile\Roaming\oto\desktop_welcome.json" '{"seen_version":"1"}'
    $saved = @{ APPDATA = $env:APPDATA; LOCALAPPDATA = $env:LOCALAPPDATA }
    $env:APPDATA = "$profile\Roaming"; $env:LOCALAPPDATA = "$profile\Local"
    try { $launcher = Start-Process $exe -PassThru } finally { $env:APPDATA = $saved.APPDATA; $env:LOCALAPPDATA = $saved.LOCALAPPDATA }
    $dot = [char]0xB7
    $jvm = $null; $clock = [Diagnostics.Stopwatch]::StartNew()
    while (!$jvm -and $clock.Elapsed.TotalSeconds -lt 90) {
        Start-Sleep -Milliseconds 200
        foreach ($p in @($launcher.Id) + @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($launcher.Id)" | ForEach-Object ProcessId)) {
            if ([OtoCdsWindows]::Find([uint32]$p, "Oto $dot Reproductor", $true) -ne [IntPtr]::Zero) { $jvm = $p; break }
        }
    }
    if (!$jvm) { Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue; throw 'El entrenamiento de CDS no llegó a abrir el reproductor.' }
    Start-Sleep -Seconds 4
    $session = [OtoCdsWindows]::Find([uint32]$jvm, "Oto $dot Sesi", $false); $r = [IntPtr]::Zero
    if ($session -ne [IntPtr]::Zero) { [OtoCdsWindows]::SendMessageTimeout($session, 0x10, [IntPtr]::Zero, [IntPtr]::Zero, 2, 3000, [ref]$r) | Out-Null }
    if (!$launcher.WaitForExit(60000)) { throw 'Oto no se cerró tras el entrenamiento de CDS.' }
    Remove-Item $profile -Recurse -Force -ErrorAction SilentlyContinue
    if (!(Test-Path $archive)) { throw 'No se generó oto.jsa.' }
    # La JVM lo crea de solo lectura; así el instalador lo puede sustituir al actualizar.
    (Get-Item $archive).IsReadOnly = $false

    # 3. Arranques normales: solo leerlo.
    Set-Options @("java-options=-XX:SharedArchiveFile=`$APPDIR\oto.jsa")
    Get-Item $archive, (Join-Path $App 'runtime\bin\server\classes.jsa') | Select-Object FullName, Length
} catch {
    # Mejor un MSI sin arranque rápido que uno con opciones a medias.
    Set-Options @()
    throw
}
