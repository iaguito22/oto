package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import java.time.Instant
import java.time.ZoneId
import kotlin.math.E
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tus gustos, tal y como los ve el DJ: de tus listas, Me gusta y lo que escuchas. Cada lista es una escena (anime,
 * reguetón, lo del gimnasio…): dos artistas se parecen si comparten listas, y más cuanto más pequeña y temática es
 * la lista; una lista de miles de canciones apenas dice nada.
 *
 * Lo que puso el DJ no suma como lo que eliges tú: si contara igual, lo que pone se reforzaría solo y acabaría
 * poniendo siempre lo mismo. Solo corrige, arriba o abajo, según cuánto te quedas cuando lo pone él.
 */
class Profile(
    lists: List<LocalPlaylist>,
    liked: List<Heard>,
    history: List<Heard>,
    plays: List<Play>,
    val now: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    class Artist(val key: String) {
        var name = ""
        var id: String? = null
        /** Las suyas que tienes, por canción ([Track.key]). */
        val songs = LinkedHashMap<String, Track>()
        var liked = 0
        /** Las suyas que están en alguna lista tuya (no solo en Me gusta). */
        val listed = HashSet<String>()
        /** Lista → cuánto está en ella. */
        val lists = HashMap<Int, Double>()
        var norm = 0.0
        /** Lo que lo escuchas porque quieres, con olvido de un mes. */
        var organic = 0.0
        /** Escucha ponderada en horario diurno (9–19) vs nocturno (22–5). */
        var dayOrganic = 0.0
        var nightOrganic = 0.0
        /** Lo que te lo puso el DJ y cuánto te quedaste (con el mismo olvido). */
        var djTimes = 0.0
        var djHeard = 0.0
        /** Veces que lo puso el DJ esta semana. */
        var djWeek = 0
        var affinity = 0.0
        var vibe = VibeScore()

        override fun toString() = name
    }

    val artists = HashMap<String, Artist>()
    private val byId = HashMap<String, Artist>()

    /** Canción → en cuántas de tus listas está (Me gusta cuenta doble): las de siempre pesan más. */
    val weight = HashMap<String, Double>()

    /** Tus canciones sin versión ([Track.base] y artista): un remix de una tuya no es nada nuevo. */
    val bases = HashSet<String>()

    /** Canción → la última vez que sonó de verdad (más de 30 s). */
    val heardAt = HashMap<String, Long>()

    /** Lo que puso el DJ el último mes, por id y por canción: no lo repite. */
    val djRecent = HashSet<String>()

    /** Los artistas de los que tienes algo, de más a menos tuyos. */
    val core: List<Artist>

    /** Hora local (0–23) de esta sesión, calculada a partir de [now] y [zone]. */
    val hourOfDay: Int = Instant.ofEpochMilli(now).atZone(zone).hour

    /** Vibe por canción conocido desde sus listas/título/horario de escucha. */
    private val songVibes = HashMap<String, VibeScore>()

    init {
        val all = lists.filter { it.songs.isNotEmpty() } + LocalPlaylist("", "", liked)
        val listVibes = all.map { VibeAnalyzer.fromText(it.name) }
        val artistAcc = HashMap<String, DoubleArray>() // [eSum, cSum, fSum, wSum]
        val artistTags = HashMap<String, HashSet<String>>()
        val songAcc = HashMap<String, DoubleArray>()
        val songTags = HashMap<String, HashSet<String>>()

        fun addAcc(map: MutableMap<String, DoubleArray>, tagMap: MutableMap<String, HashSet<String>>, key: String, v: VibeScore, w: Double) {
            val arr = map.getOrPut(key) { DoubleArray(4) }
            arr[0] += v.energy * w
            arr[1] += v.chill * w
            arr[2] += v.focus * w
            arr[3] += w
            if (v.tags.isNotEmpty()) tagMap.getOrPut(key) { HashSet() } += v.tags
        }

        // Guardar algo en una lista dice más de ti que darle a Me gusta (petición de Iago, 06/11): una canción pesa
        // 1,5 por cada lista y 0,5 por el Me gusta, y Me gusta, como escena, cuenta la mitad que una lista.
        all.forEachIndexed { l, list ->
            val isLiked = l == all.lastIndex
            val w = (if (isLiked) LIKED_SCENE else 1.0) / ln(E + list.songs.size / 25.0)
            val lv = listVibes[l]
            val hasNamedList = !isLiked && list.name.isNotBlank()
            for (h in list.songs) {
                val t = h.toTrack()
                val a = artist(t.who, t.artist, t.artistId)
                a.songs.getOrPut(t.key) { t }
                bases += t.base + "|" + t.who
                a.lists[l] = (a.lists[l] ?: 0.0) + w
                if (isLiked) a.liked++ else a.listed += t.key
                weight[t.key] = (weight[t.key] ?: 0.0) + if (isLiked) LIKED_WEIGHT else LIST_WEIGHT
                val tv = VibeAnalyzer.fromText("${t.title} ${t.artist}", t.seconds)
                val combined = if (hasNamedList) tv.blend(lv, 0.65) else tv
                addAcc(artistAcc, artistTags, a.key, combined, if (isLiked) 0.5 else 1.2)
                addAcc(songAcc, songTags, t.key, combined, if (isLiked) 0.5 else 1.4)
            }
        }
        // Lo último que sonó, por si el diario de escuchas aún está vacío (recién instalado).
        history.forEachIndexed { i, h ->
            val t = h.toTrack()
            heardAt.putIfAbsent(t.key, now - i * 30 * 60_000L)
            artist(t.who, t.artist, t.artistId).organic += 0.3
        }
        for (p in plays) {
            val age = (now - p.at).coerceAtLeast(0)
            val fade = exp(-age / (30.0 * DAY))
            val a = artist(p.who, p.artist, p.artistId)
            if (p.heard) {
                heardAt[p.key] = maxOf(heardAt[p.key] ?: 0, p.at)
                val h = Instant.ofEpochMilli(p.at).atZone(zone).hour
                val pw = p.reward * fade
                if (h in 22..23 || h in 0..5) {
                    a.nightOrganic += pw
                    addAcc(songAcc, songTags, p.key, VibeScore(energy = 0.25, chill = 0.85, focus = 0.65), pw * 0.45)
                } else if (h in 9..19) {
                    a.dayOrganic += pw
                    addAcc(songAcc, songTags, p.key, VibeScore(energy = 0.78, chill = 0.3, focus = 0.45), pw * 0.45)
                }
            }
            if (p.dj == null) a.organic += p.reward * fade
            else {
                a.djTimes += fade
                a.djHeard += p.reward * fade
                if (age < 30 * DAY) { djRecent += p.id; djRecent += p.key }
                if (age < 7 * DAY) a.djWeek++
            }
        }
        for ((k, arr) in songAcc) {
            if (arr[3] > 0.0) {
                songVibes[k] = VibeScore(
                    energy = (arr[0] / arr[3]).coerceIn(0.0, 1.0),
                    chill = (arr[1] / arr[3]).coerceIn(0.0, 1.0),
                    focus = (arr[2] / arr[3]).coerceIn(0.0, 1.0),
                    tags = songTags[k].orEmpty(),
                )
            }
        }
        for (a in artists.values) {
            a.norm = a.lists.values.sumOf { it * it }
            // Cuánto te quedas cuando lo pone el DJ, frente a lo normal (0,6): de ×0,5 a ×1,5, sin sumar volumen.
            val rate = djRate(a)
            // Lo tuyo: sus canciones en listas pesan más que las que solo tienen Me gusta.
            val owned = LIST_WEIGHT * a.listed.size + LIKED_WEIGHT * a.liked
            val base = sqrt(owned) + 1.5 * sqrt(a.organic)
            // Uno que descubrió el DJ y te gustó entra, poquito, en lo tuyo.
            a.affinity = if (base > 0) base * (0.5 + rate) else if (a.djTimes >= 1 && rate > 0.65) 0.6 else 0.0
            val acc = artistAcc[a.key]
            var v = if (acc != null && acc[3] > 0.0) {
                VibeScore(
                    energy = (acc[0] / acc[3]).coerceIn(0.0, 1.0),
                    chill = (acc[1] / acc[3]).coerceIn(0.0, 1.0),
                    focus = (acc[2] / acc[3]).coerceIn(0.0, 1.0),
                    tags = artistTags[a.key].orEmpty(),
                )
            } else VibeScore()
            val todTotal = a.dayOrganic + a.nightOrganic
            if (todTotal > 0.5) {
                val nightShare = a.nightOrganic / todTotal
                val dayShare = a.dayOrganic / todTotal
                v = v.blend(VibeScore(energy = 0.25 + 0.55 * dayShare, chill = 0.25 + 0.55 * nightShare, focus = 0.5), 0.25)
            }
            a.vibe = v
        }
        core = artists.values.filter { it.songs.isNotEmpty() || it.organic > 0.5 }.sortedByDescending { it.affinity }
    }

    /** Vibe estimado de una pista combinando su historial en listas, su título y los artistas/vías asociados. */
    fun vibeOf(track: Track, vias: Set<String> = emptySet()): VibeScore {
        songVibes[track.key]?.let { return it }
        var v = VibeAnalyzer.fromText("${track.title} ${track.artists.joinToString(" ")}", track.seconds)
        val own = find(track.artistId, track.artist)
        if (own != null) {
            v = v.blend(own.vibe, 0.55)
        } else if (vias.isNotEmpty()) {
            val viaVibes = vias.mapNotNull { artists[it]?.vibe }
            if (viaVibes.isNotEmpty()) {
                val avg = VibeScore(
                    energy = viaVibes.map { it.energy }.average(),
                    chill = viaVibes.map { it.chill }.average(),
                    focus = viaVibes.map { it.focus }.average(),
                    tags = viaVibes.flatMapTo(HashSet()) { it.tags },
                )
                v = v.blend(avg, 0.45)
            }
        }
        return v
    }

    /** Preferencia horaria de un artista según tus escuchas previas: > 0 favorece noche, < 0 favorece día. */
    fun nightBias(a: Artist?): Double {
        if (a == null) return 0.0
        val total = a.dayOrganic + a.nightOrganic
        if (total <= 0.3) return 0.0
        return ((a.nightOrganic - a.dayOrganic) / total).coerceIn(-1.0, 1.0)
    }

    private fun artist(key: String, name: String, id: String?): Artist {
        val a = id?.let(byId::get) ?: artists.getOrPut(key) { Artist(key) }
        if (a.name.isEmpty()) a.name = name
        if (a.id == null && id != null) { a.id = id; byId[id] = a }
        return a
    }

    /** De 0 a 1: cuánto te quedas cuando te lo pone el DJ; sin datos, 0,6. */
    fun djRate(a: Artist) = (a.djHeard + 1.2) / (a.djTimes + 2)

    fun find(id: String?, name: String): Artist? = id?.let(byId::get) ?: artists[artistKey(name)]

    /** De 0 a casi 1: cuánto comparten listas. Una sola lista enorme en común apenas suma. */
    fun sim(a: Artist, b: Artist): Double {
        if (a === b) return 1.0
        val (small, big) = if (a.lists.size <= b.lists.size) a to b else b to a
        var dot = 0.0
        for ((l, v) in small.lists) big.lists[l]?.let { dot += v * it }
        return if (dot == 0.0) 0.0 else dot / sqrt((a.norm + K) * (b.norm + K))
    }

    private companion object {
        // Sin esto, dos artistas con una canción cada uno en la misma lista gigante saldrían idénticos.
        const val K = 0.5
        const val LIST_WEIGHT = 1.5
        const val LIKED_WEIGHT = 0.5
        const val LIKED_SCENE = 0.5
    }
}
