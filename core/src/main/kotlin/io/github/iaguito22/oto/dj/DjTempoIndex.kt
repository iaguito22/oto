package io.github.iaguito22.oto.dj

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Índice ligero id → tempo, fiabilidad, rejilla y bombo ([DjTempoMatch.TempoProfile]) para elegir canciones que se
 * puedan cuadrar. No depende de `AudioMetrics.VERSION`: cuando sube la versión del análisis completo, lo guardado se
 * vuelve a analizar, pero el DJ sigue sabiendo el tempo de lo que ya sonó (medido: con la v5 la caché del móvil se
 * quedó casi vacía y el DJ elegía a ciegas). Tiene su propio formato ([FORMAT]); solo sube si cambia lo que significa.
 */
class DjTempoIndex(private val file: File?) {
    private val map = ConcurrentHashMap<String, DjTempoMatch.TempoProfile>()
    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var dirty = false

    fun get(trackId: String): DjTempoMatch.TempoProfile? {
        loadIfNeeded()
        return map[trackId]
    }

    /**
     * Guarda lo que más sabe entre [p] y lo que había: tempo, rejilla y fiabilidad del mejor (entera antes que a
     * medias), y el bombo del que lo tenga medido (un análisis viejo entero no midió el bombo; uno nuevo a medias, sí).
     * Devuelve si cambió algo.
     */
    fun offer(p: DjTempoMatch.TempoProfile?): Boolean {
        if (p == null) return false
        loadIfNeeded()
        synchronized(lock) {
            val old = map[p.trackId]
            val merged = merge(old, p)
            if (merged == old) return false
            map[p.trackId] = merged
            dirty = true
            trim()
            return true
        }
    }

    val size: Int get() {
        loadIfNeeded()
        return map.size
    }

    fun save() {
        val f = file ?: return
        if (!dirty) return
        synchronized(lock) {
            runCatching {
                val root = JSONObject()
                root.put("format", FORMAT)
                val items = JSONObject()
                for ((id, p) in map) items.put(id, p.toJson())
                root.put("items", items)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(root.toString())
                // Reemplazo de una vez (en Windows, borrar y renombrar dejaba un instante sin archivo).
                val ok = runCatching {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        f.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                }.recoverCatching {
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }.isSuccess
                // Si no se pudo escribir, sigue pendiente: se reintenta en el próximo guardado.
                if (ok) dirty = false
            }
        }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            runCatching {
                val f = file?.takeIf { it.exists() } ?: return@runCatching
                val root = JSONObject(f.readText())
                if (root.optInt("format", 0) != FORMAT) return@runCatching
                val items = root.optJSONObject("items") ?: return@runCatching
                for (k in items.keys()) {
                    items.optJSONObject(k)?.let { DjTempoMatch.TempoProfile.fromJson(k, it) }?.let { map[k] = it }
                }
            }
            loaded = true
        }
    }

    /**
     * Tempo, rejilla y fiabilidad del perfil que más sabe ([DjTempoMatch.TempoProfile.betterThan]); el bombo, por su
     * lado, del que lo midió sobre más compases ([DjTempoMatch.TempoProfile.kickBetterThan]). Así un análisis entero
     * viejo sin bombo medido no tapa el bombo de uno nuevo, y el bombo de un principio no se queda congelado cuando
     * llega otro que oyó más canción.
     */
    private fun merge(old: DjTempoMatch.TempoProfile?, new: DjTempoMatch.TempoProfile): DjTempoMatch.TempoProfile {
        if (old == null) return new
        val tempo = if (new.betterThan(old)) new else old
        val kick = if (new.kickBetterThan(old)) new else old
        return tempo.copy(kickShare = kick.kickShare, kickBars = kick.kickBars, kickAt = kick.kickAt, kickWhole = kick.kickWhole)
    }

    private fun trim() {
        if (map.size <= MAX_ENTRIES) return
        map.values.sortedBy { it.analyzedAt }.take(map.size - MAX_ENTRIES).forEach { map.remove(it.trackId) }
    }

    companion object {
        const val FORMAT = 1
        private const val MAX_ENTRIES = 6_000
    }
}
