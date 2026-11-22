package io.github.iaguito22.oto.update

import io.github.iaguito22.oto.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

private const val REPOSITORY = "https://github.com/iaguito22/oto/releases"
private const val LATEST_RELEASE = "https://api.github.com/repos/iaguito22/oto/releases/latest"
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
private val semanticVersion = Regex(
    """v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"""
)
private val sha256Pattern = Regex("sha256:[a-fA-F0-9]{64}")

/** Las versiones de producción tienen tres componentes. Los metadatos de compilación no dan precedencia. */
internal fun isNewerStableVersion(candidate: String, installed: String): Boolean {
    val next = semanticVersion.matchEntire(candidate)?.groupValues ?: return false
    val current = semanticVersion.matchEntire(installed)?.groupValues ?: return false
    if (next[4].isNotEmpty()) return false
    for (i in 1..3) {
        val lengthOrder = next[i].length.compareTo(current[i].length)
        if (lengthOrder != 0) return lengthOrder > 0
        val digitsOrder = next[i].compareTo(current[i])
        if (digitsOrder != 0) return digitsOrder > 0
    }
    return current[4].isNotEmpty()
}

enum class UpdatePlatform {
    Windows, Debian, Arch, Android;

    fun assetName(version: String): String = when (this) {
        Windows -> "Oto-$version-windows-x64.msi"
        Debian -> "oto_${version}_amd64.deb"
        Arch -> "oto-$version-1-x86_64.pkg.tar.zst"
        Android -> "Oto-$version-android.apk"
    }
}

@ConsistentCopyVisibility
data class OtoRelease internal constructor(val version: String, val page: URI, val asset: ReleaseAsset)

@ConsistentCopyVisibility
data class ReleaseAsset internal constructor(val name: String, internal val url: URI, internal val size: Long, internal val digest: String)

internal fun newerRelease(json: String, installed: String, vararg platforms: UpdatePlatform): OtoRelease? {
    val release = JSONObject(json)
    if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) return null
    val tag = release.optString("tag_name")
    if (!tag.startsWith('v') || !isNewerStableVersion(tag, installed)) return null
    val version = tag.removePrefix("v").substringBefore('+')
    val assets = release.optJSONArray("assets") ?: return null
    for (platform in platforms) {
        val name = platform.assetName(version)
        val url = "$REPOSITORY/download/$tag/$name"
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != name || asset.optString("browser_download_url") != url) continue
            val digest = asset.optString("digest")
            val size = asset.optLong("size")
            if (platform == UpdatePlatform.Windows || platform == UpdatePlatform.Android) {
                if (!sha256Pattern.matches(digest) || size <= 0) continue
            }
            return OtoRelease(version, URI("$REPOSITORY/tag/$tag"), ReleaseAsset(name, URI(url), size, digest))
        }
    }
    return null
}

/** Una instancia por sesión y perfil. La última consulta se conserva también entre arranques. */
class OtoUpdates(
    private val prefs: Prefs,
    private val installed: String,
    private vararg val platforms: UpdatePlatform,
    private val fetch: suspend () -> String = ::fetchLatestRelease,
    private val now: () -> Long = System::currentTimeMillis,
    private val persistChecks: Boolean = true,
) {
    suspend fun observe(onRelease: (OtoRelease?) -> Unit) {
        prefs.checkUpdates.collectLatest { enabled ->
            if (!enabled) {
                onRelease(null)
                return@collectLatest
            }
            delay(5_000)
            fun parse(json: String?) = json?.let { runCatching { newerRelease(it, installed, *platforms) }.getOrNull() }
            val elapsed = if (persistChecks) prefs.updateCheckTime()?.let { now() - it } else null
            if (elapsed != null && elapsed in 0 until CHECK_INTERVAL_MS) {
                onRelease(parse(prefs.cachedUpdateRelease()))
                delay(CHECK_INTERVAL_MS - elapsed)
            }
            while (true) {
                if (persistChecks) prefs.beginUpdateCheck(now())
                val json = try {
                    fetch()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val release = parse(json)
                if (persistChecks) prefs.saveUpdateRelease(json.takeIf { release != null })
                onRelease(release)
                delay(CHECK_INTERVAL_MS)
            }
        }
    }
}

private suspend fun fetchLatestRelease(): String = withContext(Dispatchers.IO) {
    openGitHub(URI(LATEST_RELEASE), "application/vnd.github+json").useResponse { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= 512 * 1024)
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
}

/** Devuelve un archivo completo y verificado. Cada descarga tiene un nombre temporal propio. */
suspend fun downloadUpdate(asset: ReleaseAsset, folder: File, progress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
    check(asset.size > 0 && sha256Pattern.matches(asset.digest))
    check(folder.isDirectory || folder.mkdirs())
    val file = File.createTempFile("oto-update-", "-${asset.name}", folder)
    var verified = false
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        openGitHub(asset.url, "application/octet-stream").useResponse { input ->
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var lastPercent = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    check(received <= asset.size)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    val percent = (received.toDouble() / asset.size * 100).toInt()
                    if (percent != lastPercent) {
                        progress(percent / 100f)
                        lastPercent = percent
                    }
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        check(received == asset.size && asset.digest.equals("sha256:$hash", ignoreCase = true))
        verified = true
        file
    } finally {
        if (!verified) file.delete()
    }
}

private fun openGitHub(uri: URI, accept: String): HttpURLConnection {
    var target = uri
    repeat(5) {
        check(target.scheme == "https" && target.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com"))
        val connection = target.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "Oto")
        connection.setRequestProperty("Accept", accept)
        try {
            when (connection.responseCode) {
                200 -> return connection
                301, 302, 303, 307, 308 -> target = target.resolve(checkNotNull(connection.getHeaderField("Location")))
                else -> error("HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
        connection.disconnect()
    }
    error("Demasiadas redirecciones")
}

private inline fun <T> HttpURLConnection.useResponse(read: (InputStream) -> T): T =
    try { inputStream.buffered().use(read) } finally { disconnect() }
