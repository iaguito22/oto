package io.github.iaguito22.oto

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toOkioPath
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Rutas de datos y cachés multiplataforma para `:core`.
 * - En Android (`OtoApp.onCreate`): se inicializa con `filesDir` y `cacheDir`, manteniendo la ruta exacta
 *   `filesDir/datastore/<name>.preferences_pb` que usa `preferencesDataStore`.
 * - En Desktop (Linux / Windows): usa por defecto `~/.local/share/oto` (`XDG_DATA_HOME`) o `%APPDATA%\Oto`,
 *   y `~/.cache/oto` (`XDG_CACHE_HOME`) o `%LOCALAPPDATA%\Oto\Cache`.
 */
object OtoDirs {
    @Volatile
    var filesDir: File = defaultFilesDir()
        private set

    @Volatile
    var cacheDir: File = defaultCacheDir()
        private set

    fun init(files: File, cache: File) {
        filesDir = files.apply { mkdirs() }
        cacheDir = cache.apply { mkdirs() }
    }

    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

    fun dataStore(
        name: String,
        migrations: (DataStore<Preferences>) -> List<DataMigration<Preferences>> = { emptyList() },
    ): DataStore<Preferences> = stores.getOrPut(name) {
        val dir = File(filesDir, "datastore").apply { mkdirs() }
        val file = File(dir, "$name.preferences_pb")
        PreferenceDataStoreFactory.createWithPath(
            migrations = if (name == "oto_lists") migrations(dataStore("oto")) else emptyList(),
            produceFile = { file.toOkioPath() },
        )
    }

    private val kvStores = ConcurrentHashMap<String, KeyValueFile>()

    fun keyValue(name: String): KeyValueFile = kvStores.getOrPut(name) {
        KeyValueFile(File(filesDir, "$name.json"))
    }

    private fun defaultFilesDir(): File {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val base = when {
            "win" in os -> System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Roaming")
            else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
        }
        return File(base, "oto").apply { mkdirs() }
    }

    private fun defaultCacheDir(): File {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val base = when {
            "win" in os -> System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Local")
            else -> System.getenv("XDG_CACHE_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cache")
        }
        return desktopCacheDirectory(os, base).apply { mkdirs() }
    }

    private const val DJ_BYTES = 20L shl 20
    private const val DJ_MAX_AGE_MS = 8L * 7 * 24 * 3600 * 1000

    /** Lo del DJ: fuera lo caducado y, si aun así pasa del tope, lo más viejo. */
    fun trimDj(dir: File = File(cacheDir, "dj")) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (now - f.lastModified() < DJ_MAX_AGE_MS && total <= DJ_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}

/** En Windows la raíz antigua también podía contener la instalación por usuario. */
internal fun desktopCacheDirectory(os: String, base: File): File =
    File(base, if (os.startsWith("Windows", true)) "oto/Cache" else "oto")

/** Almacén ligero clave-valor en JSON para configuraciones como `oto_player_config`, `innertubex_player_config` y `oto_visitor`. */
class KeyValueFile(private val file: File) {
    private val lock = Any()

    private fun readObj(): JSONObject = synchronized(lock) {
        if (!file.exists()) return JSONObject()
        runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeObj(o: JSONObject): Unit = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(o.toString())
        }
    }

    fun getString(key: String, def: String? = null): String? = synchronized(lock) {
        val o = readObj()
        if (o.has(key) && !o.isNull(key)) o.optString(key, def) else def
    }

    fun getLong(key: String, def: Long = 0L): Long = synchronized(lock) {
        val o = readObj()
        if (o.has(key)) o.optLong(key, def) else def
    }

    fun putString(key: String, value: String?): Unit = synchronized(lock) {
        val o = readObj()
        if (value == null) o.remove(key) else o.put(key, value)
        writeObj(o)
    }

    fun putLong(key: String, value: Long): Unit = synchronized(lock) {
        val o = readObj()
        o.put(key, value)
        writeObj(o)
    }

    fun clear(): Unit = synchronized(lock) {
        runCatching { if (file.exists()) file.delete() }
    }
}

/** Caché LRU en memoria seguro entre hilos (reemplaza `android.util.LruCache` en `:core`). */
class MemCache<K : Any, V : Any>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V): V? = map.put(key, value)

    @Synchronized
    fun clear() = map.clear()
}
