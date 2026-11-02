package io.github.iaguito22.oto.desktop

import io.github.iaguito22.oto.OtoDirs
import java.io.File
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Cerrar las ventanas deja la música activa; abrir Oto otra vez recupera esa misma instancia. */
internal object DesktopInstance {
    fun acquire(): AutoCloseable? {
        val channel = FileChannel.open(File(OtoDirs.filesDir,"desktop.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = channel.tryLock()
        if (lock == null) {
            channel.close()
            runCatching {
                OtoDirs.keyValue("desktop_access").getString("url")?.let { url ->
                    val connection=URI(url).toURL().openConnection().apply { connectTimeout=1500;readTimeout=1500 }
                    connection.getInputStream().close()
                }
            }
            return null
        }
        return AutoCloseable { lock.release();channel.close() }
    }
}
