package io.github.iaguito22.oto.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

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

    /** Ejecuta el script real y la espera al proceso; sustituye únicamente el instalador y el lanzador. */
    @Test fun esperaAlCierreYLimpiaYReabreAunqueNoArranqueElInstalador() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("oto actualización ' & ").toFile()
        try {
            for (installerFails in listOf(false, true)) {
                val msi = File(root, "Oto prueba.msi").apply { writeText("instalador simulado") }
                val launcher = File(root, "Oto.exe").apply { writeText("lanzador simulado") }
                val parent = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Milliseconds 800").start()
                val script = """
                    ${'$'}ErrorActionPreference = 'Stop'
                    function Start-Process {
                        param([string]${'$'}FilePath, [string]${'$'}ArgumentList, [switch]${'$'}Wait)
                        if (Get-Process -Id ${'$'}env:OTO_UPDATE_PARENT_PID -ErrorAction SilentlyContinue) { throw 'Oto sigue abierto' }
                        if (${'$'}FilePath -like '*msiexec.exe') {
                            if (-not ${'$'}Wait) { throw 'No espera al instalador' }
                            if (${'$'}ArgumentList -cne ('/i "' + ${'$'}env:OTO_UPDATE_MSI + '"')) { throw 'Ruta MSI alterada' }
                            Write-Output 'instalador invocado'
                            ${if (installerFails) "throw 'No se pudo abrir el instalador'" else ""}
                        } elseif (${'$'}FilePath -ceq ${'$'}env:OTO_UPDATE_RELAUNCH) {
                            if (Test-Path -LiteralPath ${'$'}env:OTO_UPDATE_MSI) { throw 'MSI sin limpiar' }
                            Write-Output 'Oto reabierto'
                        } else { throw 'Lanzamiento inesperado' }
                    }
                    $installerScript
                """.trimIndent()
                val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
                val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectErrorStream(true).apply {
                        environment()["OTO_UPDATE_PARENT_PID"] = parent.pid().toString()
                        environment()["OTO_UPDATE_MSI"] = msi.absolutePath
                        environment()["OTO_UPDATE_RELAUNCH"] = launcher.absolutePath
                    }.start()
                try {
                    assertTrue("el script no terminó", process.waitFor(20, TimeUnit.SECONDS))
                    val output = process.inputStream.bufferedReader().readText()
                    assertEquals(output, if (installerFails) 1 else 0, process.exitValue())
                    assertTrue(output, output.indexOf("instalador invocado") >= 0)
                    assertTrue(output, output.indexOf("Oto reabierto") > output.indexOf("instalador invocado"))
                    assertTrue("el MSI se elimina tras el instalador", !msi.exists())
                } finally {
                    process.destroy()
                    parent.destroy()
                }
            }
        } finally { root.deleteRecursively() }
    }
}
