package io.github.iaguito22.oto.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopUpdatesTest {
    /** El script que instala la actualización se analiza sin errores en PowerShell 5.1 (no se ejecuta). */
    @Test fun elScriptDeInstalacionEsPowerShellValido() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val check = "\$e = \$null; [void][System.Management.Automation.Language.Parser]::ParseInput([Console]::In.ReadToEnd(), [ref]\$null, [ref]\$e); \$e.Count"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", check).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(installerScript) }
        val out = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        assertEquals("errores de PowerShell: $out", "0", out.lines().last().trim())
    }

    @Test fun elScriptVuelveAAbrirOtoAlAcabar() {
        val finally = installerScript.substringAfter("finally {")
        assertTrue("OTO_UPDATE_RELAUNCH" in finally && "Start-Process -FilePath \$env:OTO_UPDATE_RELAUNCH" in finally)
    }
}
