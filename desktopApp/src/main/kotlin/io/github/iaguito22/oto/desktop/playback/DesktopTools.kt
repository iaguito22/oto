package io.github.iaguito22.oto.desktop.playback

import java.io.File

/** El paquete instalado lleva sus herramientas; desarrollo puede usar OTO_FFMPEG_DIR o PATH. */
internal object DesktopTools {
    fun find(name: String, env: Map<String, String> = System.getenv(),
             resources: String? = System.getProperty("compose.application.resources.dir")): String? {
        require(name in setOf("ffmpeg", "ffplay", "ffprobe"))
        val windows = System.getProperty("os.name").startsWith("Windows", true)
        val environment = if (windows) env.entries.associate { it.key.uppercase() to it.value } else env
        val executable = name + if (windows) ".exe" else ""
        val dirs = listOfNotNull(resources?.let { File(it, "audio") }, environment["OTO_FFMPEG_DIR"]?.let(::File)) +
            environment["PATH"].orEmpty().split(File.pathSeparatorChar).filter(String::isNotBlank).map { File(it.trim('"')) }
        return dirs.map { File(it, executable) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }
    fun require(name: String): String = find(name)
        ?: error("Falta $name. Instala FFmpeg o configura OTO_FFMPEG_DIR con su carpeta bin.")
}
