package io.github.iaguito22.oto.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

/**
 * El aviso de actualización contra GitHub de verdad (red): la última release publicada se ve como nueva desde una
 * versión vieja, la APK pública se descarga siguiendo la redirección y su SHA-256 coincide. Solo con
 * `OTO_UPDATE_LIVE=1`; sirve para comprobar una release recién publicada.
 */
class OtoUpdatesLiveTest {
    @Test
    fun laUltimaReleaseSeOfreceYSeDescargaVerificada() = runBlocking {
        assumeTrue(System.getenv("OTO_UPDATE_LIVE") == "1")
        val json = (URI("https://api.github.com/repos/iaguito22/oto/releases/latest").toURL().openConnection() as HttpURLConnection).run {
            setRequestProperty("User-Agent", "Oto"); setRequestProperty("Accept", "application/vnd.github+json")
            inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val release = checkNotNull(newerRelease(json, "0.0.1", UpdatePlatform.Android)) { "La última release no ofrece la APK pública" }
        assertEquals(UpdatePlatform.Android.assetName(release.version), release.asset.name)
        // Las de Windows y Linux también están, con su nombre exacto
        assertNotNull(newerRelease(json, "0.0.1", UpdatePlatform.Windows))
        assertNotNull(newerRelease(json, "0.0.1", UpdatePlatform.Debian))
        assertNotNull(newerRelease(json, "0.0.1", UpdatePlatform.Arch))
        // Desde la misma versión no se ofrece nada
        assertNull(newerRelease(json, release.version, UpdatePlatform.Android))
        val dir = Files.createTempDirectory("oto-update").toFile()
        try {
            val file = downloadUpdate(release.asset, dir)
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(release.asset.digest, "sha256:$hash")
            assertEquals(release.asset.size, file.length())
        } finally { dir.deleteRecursively() }
    }
}
