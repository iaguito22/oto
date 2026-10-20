package io.github.iaguito22.oto.dj

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * El DJ, para cuando no sabes qué poner. Pincha por tandas, como uno de verdad: cada tanda gira alrededor de un
 * artista tuyo y de los que se le parecen (por tus listas y por YouTube), y mezcla tres cosas:
 *  - tuyas que llevabas tiempo sin oír,
 *  - canciones de tus artistas que no tienes,
 *  - artistas nuevos a los que apuntan varios de los tuyos a la vez.
 * Nunca pone tres desconocidas seguidas. Para cambiar de tanda busca un puente: un artista nuevo cercano a lo que
 * suena y, a la vez, a otra parte de lo tuyo que no tiene nada que ver; de ahí salta a esa otra parte.
 *
 * Mientras suena solo mira cuánto te quedas con cada una: si pasas algo nuevo deja ese camino, si te quedas tira del
 * hilo. De una sesión a otra no repite canciones en un mes ni arranca desde los mismos artistas, y lo que pone él
 * no cuenta como si lo hubieras elegido tú (ver [Profile]): así no se alimenta de sí mismo.
 *
 * No es seguro entre hilos: se usa siempre desde el mismo (el principal en el servicio).
 */
class Dj(
    private val profile: Profile,
    private val catalog: Catalog,
    private val memory: Memory,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    /** Por dónde va (para el registro y las pruebas). */
    private val log: (String) -> Unit = {},
) {
    /** Lo que ha puesto y cuánto te quedaste (null: aún suena). [vias]: los artistas tuyos que llevaron a ella. */
    class Pick(val track: Track, val kind: Kind, val vias: Set<String>, var reward: Double? = null)

    private class Candidate(val track: Track, val kind: Kind, val vias: Set<String>, var weight: Double, val target: String? = null)

    /** Un artista que no es tuyo y los cercanos a ti que, según YouTube, se le parecen. */
    private class Cited(val id: String) {
        val by = HashSet<String>()
    }

    private val now = profile.now
    val picks = ArrayList<Pick>()
    private val keys = HashSet<String>()
    private val pool = LinkedHashMap<String, Candidate>()
    private val explored = HashSet<String>()
    private val met = HashSet<String>()
    private val cited = HashMap<String, Cited>()
    /** Artistas tuyos que YouTube junta (por clave) aunque tus listas no. */
    private val links = HashMap<String, HashSet<String>>()
    /** Tuyas que salen en la radio de lo que suena: encajan con la tanda. */
    private val inRadio = HashSet<String>()
    private val banned = HashSet<String>()
    /** Artistas nuevos con los que te quedaste: pueden volver a sonar en la sesión y lo suyo se acerca a su tanda. */
    private val warm = HashSet<String>()
    private val jobs = ArrayList<Job>()

    /** La tanda: su centro y los artistas que la forman, con lo cerca que están (0–1). */
    private lateinit var anchor: Profile.Artist
    private val region = HashMap<String, Double>()
    private var block = 0
    private var blockLen = 5
    private var skips = 0
    private var relaxed = false

    /** La primera: una tuya, al momento, sin esperar a la red. null si no tienes nada guardado. */
    fun start(): Track? {
        memory.decay()
        repeat(10) { i ->
            // Con muy pocas tuyas, todas pueden haber sonado hace nada: mejor repetir que no poner nada.
            relaxed = i >= 5
            setAnchor(chooseAnchor(null) ?: return null)
            log("tanda: ${anchor.name}, para empezar")
            familiar()?.let { return pick(it) }
        }
        return null
    }

    /**
     * La siguiente. Lo que llega de la red mientras tanto entra en las próximas; si la tanda se ha quedado sin nada y
     * aún se está pidiendo algo, espera un poco antes de cambiar de tanda.
     */
    suspend fun next(): Track? {
        relaxed = false
        var c = if (block >= blockLen || skips >= 2) transition() else inBlock()
        if (c == null && jobs.any { it.isActive }) {
            withTimeoutOrNull(WAIT_MS) { settle() }
            c = inBlock()
        }
        if (c != null) return pick(c)
        // La tanda se quedó sin nada que poner: a otra.
        repeat(10) { i ->
            relaxed = i >= 5
            setAnchor(chooseAnchor(anchor) ?: return null)
            log("tanda: ${anchor.name}, la otra se agotó")
            familiar()?.let { return pick(it) }
        }
        return null
    }

    /** Cuánto te quedaste con [id]: lo único que mueve al DJ mientras suena. */
    fun heard(id: String, ms: Long, dur: Long) {
        val p = picks.lastOrNull { it.track.id == id && it.reward == null } ?: return
        val r = reward(ms, dur)
        p.reward = r
        memory.learn(p.kind, r)
        memory.save(now)
        if (r < SKIPPED) {
            skips++
            banned += p.track.who
            // El camino que llevó a ella pierde fuerza; si era alguien nuevo, lo que salió de él aún más.
            if (p.kind != Kind.FAMILIAR) for (c in pool.values) {
                if (p.track.who in c.vias) c.weight *= 0.3 else if (c.vias.any { it in p.vias }) c.weight *= 0.7
            }
        } else skips = 0
        if (r >= HOOKED && p.kind != Kind.FAMILIAR) follow(p.track)
    }

    /** Espera a lo que se esté pidiendo (para las pruebas). */
    suspend fun settle() {
        while (jobs.any { it.isActive }) jobs.toList().joinAll()
    }

    // ---- Elegir ----

    private fun inBlock(): Candidate? {
        val run = picks.takeLastWhile { it.kind != Kind.FAMILIAR }.size
        if (run >= 2) return familiar()
        // Al empezar, tuyas; si en la tanda no queda ninguna (con pocas listas pasa), ya lo que haya.
        if (picks.size < WARM_UP) familiar()?.let { return it }
        val deep = eligible(Kind.DEEP)
        val fresh = eligible(Kind.NEW)
        val kinds = buildList {
            add(Kind.FAMILIAR)
            if (deep.isNotEmpty()) add(Kind.DEEP)
            if (fresh.isNotEmpty()) add(Kind.NEW)
        }
        return when (weighted(kinds) { SHARE.getValue(it) * lift(it) }) {
            Kind.DEEP -> weighted(deep, ::score)
            Kind.NEW -> weighted(fresh, ::score)
            else -> familiar() ?: weighted(deep + fresh, ::score)
        }
    }

    /** Fin de tanda: un puente si lo hay; si no, un artista tuyo que YouTube junta con este; si no, otro cualquiera. */
    private fun transition(): Candidate? {
        val run = picks.takeLastWhile { it.kind != Kind.FAMILIAR }.size
        if (run < 2 && random.nextDouble() < (0.7 * lift(Kind.BRIDGE)).coerceIn(0.2, 0.95)) {
            val bridges = eligible(Kind.BRIDGE).filter { it.target != null && it.target !in region }
            weighted(bridges, ::score)?.let { b ->
                val from = anchor
                profile.artists[b.target]?.let(::setAnchor)
                log("tanda: ${anchor.name}, por el puente ${b.track.artist} desde ${from.name}")
                return b
            }
        }
        val far = region.keys.flatMap { links[it].orEmpty() }.distinct()
            .mapNotNull(profile.artists::get)
            .filter { it.key !in region && it.songs.size >= 2 && allowed(it.key) && profile.sim(anchor, it) < FAR }
        val linked = weighted(far) { it.affinity.pow(0.7) * cooldown(it) }
        val from = anchor
        val skipped = skips >= 2
        setAnchor(linked ?: chooseAnchor(anchor) ?: return null)
        log("tanda: ${anchor.name}, ${if (linked != null) "pariente en YouTube de" else "salto desde"} ${from.name}${if (skipped) " (pasaste dos)" else ""}")
        return familiar()
    }

    /** Una tuya de la tanda: primero el artista, luego cuál de las suyas (las de siempre y las olvidadas, antes). */
    private fun familiar(): Candidate? {
        val artists = region.keys.mapNotNull(profile.artists::get).filter { it.songs.isNotEmpty() && allowed(it.key) }.toMutableList()
        while (artists.isNotEmpty()) {
            val a = weighted(artists) { region.getValue(it.key) * it.affinity.pow(0.5) / (1 + 0.5 * it.djWeek) } ?: return null
            val t = weighted(a.songs.values.filter(::ok)) { t ->
                (profile.weight[t.key] ?: 1.0).pow(0.7) * forgotten(t) * if (t.key in inRadio) 1.5 else 1.0
            }
            if (t != null) return Candidate(t, Kind.FAMILIAR, setOf(a.key), 1.0)
            artists -= a
        }
        return null
    }

    private fun eligible(kind: Kind) = pool.values.filter { it.kind == kind && ok(it.track) && near(it) > 0 }

    private fun score(c: Candidate): Double {
        var w = c.weight * near(c)
        profile.artists[c.track.who]?.let { a ->
            if (c.kind == Kind.DEEP) w *= a.affinity.pow(0.3)
            // Lo que ya te puso el DJ: según cuánto te quedaste entonces, y menos si fue esta semana.
            w *= (profile.djRate(a) / 0.6) / (1 + 0.5 * a.djWeek)
        }
        return w
    }

    /** Lo cerca que está de la tanda lo que llevó a ella; si la citan varios tuyos, más. */
    private fun near(c: Candidate): Double {
        if (c.kind == Kind.DEEP || c.kind == Kind.FAMILIAR) return region[c.vias.first()] ?: 0.0
        val sum = c.vias.sumOf { region[it] ?: 0.0 }
        return sum.coerceAtMost(1.5) * if (c.vias.size >= 2) 1.3 else 1.0
    }

    private fun pick(c: Candidate): Track {
        pool.remove(c.track.key)
        val first = block == 0
        picks += Pick(c.track, c.kind, c.vias)
        keys += c.track.key
        keys += c.track.id
        if (c.track.base.length >= 4) keys += "~" + c.track.base
        block++
        if (first) radio(c.track)
        return c.track
    }

    private fun ok(t: Track): Boolean {
        if (t.key in keys || t.id in keys || "~" + t.base in keys) return false
        if (!relaxed) {
            if (t.id in profile.djRecent || t.key in profile.djRecent) return false
            profile.heardAt[t.key]?.let { if (now - it < RECENTLY) return false }
        }
        t.seconds?.let { if (it < 60 || it > 600) return false }
        if (t.upload) return false
        return allowed(t.who)
    }

    /** Ni saltado, ni sonando hace nada, ni más veces de las que le tocan en una sesión. */
    private fun allowed(who: String): Boolean {
        if (who in banned) return false
        // El centro de la tanda puede volver antes: es de quien va la tanda.
        val spacing = if (relaxed) 1 else if (who == anchor.key) 3 else SPACING
        if (picks.takeLast(spacing).any { it.track.who == who }) return false
        if (relaxed) return true
        val cap = when {
            who == anchor.key -> 3
            who in warm -> 2
            profile.artists[who]?.songs?.isNotEmpty() == true -> 2
            else -> 1
        }
        return picks.count { it.track.who == who } < cap
    }

    // ---- La tanda ----

    private fun setAnchor(a: Profile.Artist) {
        anchor = a
        memory.anchors[a.key] = now
        block = 0
        skips = 0
        blockLen = random.nextInt(4, 7)
        val before = region.keys.toSet()
        region.clear()
        region[a.key] = 1.0
        profile.core.asSequence()
            .filter { it !== a }
            .map { it to profile.sim(a, it) }
            .filter { it.second >= NEAR }
            .sortedByDescending { it.second }
            .take(REGION)
            .forEach { (b, s) -> region[b.key] = s }
        // Los parientes en YouTube, salvo los de la tanda anterior: si no, la nueva arrastraría lo de antes.
        links[a.key]?.forEach { if (it !in before || profile.artists[it]?.let { b -> profile.sim(a, b) >= NEAR } == true) region.merge(it, 0.5, ::maxOf) }
        // Se pide a YouTube el centro y unos pocos de alrededor, no siempre los mismos.
        explore(a)
        val around = region.keys.filter { it != a.key }.mapNotNull(profile.artists::get).toMutableList()
        repeat(3) { weighted(around) { region.getValue(it.key) * it.affinity.pow(0.5) }?.let { around -= it; explore(it) } }
    }

    /**
     * El centro de una tanda: alguien muy tuyo, no de los de las últimas sesiones. Si viene de otra, casi siempre
     * alguien con algo en común (ni pegado, que eso ya era la tanda, ni sin nada que ver); a veces, un cambio de aires.
     */
    private fun chooseAnchor(from: Profile.Artist?): Profile.Artist? {
        // Con pocas tuyas, también los de una sola canción.
        val options = profile.core.filter { it.songs.isNotEmpty() && it !== from && (from == null || allowed(it.key)) }
            .let { all -> all.filter { it.songs.size >= 2 }.ifEmpty { all } }
        val weight = { a: Profile.Artist -> a.affinity.pow(0.7) * cooldown(a) / (1 + 0.3 * a.djWeek) }
        if (from == null || random.nextDouble() < JUMP) return weighted(options, weight)
        val kin = options.map { it to profile.sim(from, it) }.filter { it.second >= 0.03 && it.first.key !in region }
        return weighted(kin) { (a, s) -> weight(a) * exp(-((s - 0.15) / 0.12).pow(2)) }?.first ?: weighted(options, weight)
    }

    /** Lo que fue centro hace poco tarda en volver a serlo (casi nada hoy, la mitad en tres días…). */
    private fun cooldown(a: Profile.Artist) = memory.anchors[a.key]?.let { 1 - 0.9 * exp(-(now - it).toDouble() / (5 * DAY)) } ?: 1.0

    /** Cuanto más tiempo sin oírla, mejor (hasta tres meses). Si no se sabe, como si hiciera bastante. */
    private fun forgotten(t: Track) = profile.heardAt[t.key]?.let { minOf(1.0, sqrt((now - it).toDouble() / (90 * DAY))) } ?: 0.8

    // ---- Lo que se pide fuera ----

    /** Lo de un artista tuyo: las suyas que no tienes, a quién se parece y, de ahí, artistas nuevos. */
    private fun explore(a: Profile.Artist) {
        if (!explored.add(a.key)) return
        jobs += scope.launch {
            val id = a.id ?: memory.ids[a.key] ?: catalog.find(a.name)?.also { memory.ids[a.key] = it } ?: return@launch
            val info = catalog.artist(id) ?: return@launch
            for ((rid, name) in info.related) {
                val b = profile.find(rid, name)
                if (b != null && b.songs.isNotEmpty()) link(a.key, b.key) else cite(rid, a.key)
            }
            catalog.songs(info).forEachIndexed { rank, t ->
                if (t.who == a.key || t.artistId == id) {
                    if (t.key !in profile.weight) add(Candidate(t, Kind.DEEP, setOf(a.key), 1.0 / (1 + rank / 8.0)))
                }
            }
            meet()
        }
    }

    /** Conoce a los dos artistas nuevos más citados desde la tanda: sus canciones más oídas y si hacen de puente. */
    private suspend fun meet() {
        val best = cited.values.filter { it.id !in met }
            .map { it to it.by.sumOf { k -> region[k] ?: 0.0 } * if (it.by.size >= 2) 1.5 else 1.0 }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(2)
        for ((c, _) in best) {
            if (!met.add(c.id)) continue
            val info = catalog.artist(c.id) ?: continue
            val who = artistKey(info.name)
            // Los tuyos a los que él se parece también lo citan, aunque no salga en sus páginas.
            val mine = info.related.mapNotNull { (id, n) -> profile.find(id, n)?.takeIf { it.songs.isNotEmpty() } }
            mine.forEach { c.by += it.key }
            val far = mine.filter { it.key !in region && it.songs.size >= 2 && profile.sim(anchor, it) < FAR }
            val target = weighted(far) { it.affinity.pow(0.7) * cooldown(it) }
            info.top.filter { (it.who == who || it.artistId == c.id) && it.key !in profile.weight }.take(3).forEachIndexed { rank, t ->
                val bridge = target != null && rank == 0
                add(Candidate(t, if (bridge) Kind.BRIDGE else Kind.NEW, c.by, 1.0 / (1 + rank), target?.key))
            }
        }
    }

    /** La radio de lo que abre cada tanda: lo que coincide con lo que ya había pensado gana, y aporta alguna nueva. */
    private fun radio(t: Track) {
        val center = anchor.key
        jobs += scope.launch {
            for (r in catalog.radio(t.id).drop(1)) {
                pool[r.key]?.let { it.weight *= 1.6; continue }
                if (r.key in profile.weight) { inRadio += r.key; continue }
                val lib = profile.find(r.artistId, r.artist)?.takeIf { it.songs.isNotEmpty() }
                if (lib != null) add(Candidate(r, Kind.DEEP, setOf(lib.key), 0.5))
                else add(Candidate(r, Kind.NEW, setOf(center), 0.35))
            }
        }
    }

    /** Te quedaste con algo nuevo: lo suyo y sus parecidos se acercan a la tanda. */
    private fun follow(t: Track) {
        warm += t.who
        region.merge(t.who, 0.9, ::maxOf)
        val id = t.artistId ?: return
        if (!explored.add(t.who)) return
        jobs += scope.launch {
            val info = catalog.artist(id) ?: return@launch
            for ((rid, name) in info.related) {
                val b = profile.find(rid, name)
                if (b != null && b.songs.isNotEmpty()) region.merge(b.key, 0.5, ::maxOf) else cite(rid, t.who)
            }
            info.top.filter { it.who == t.who && it.key !in profile.weight }.forEachIndexed { rank, s ->
                add(Candidate(s, Kind.NEW, setOf(t.who), 0.8 / (1 + rank)))
            }
            meet()
        }
    }

    private fun link(a: String, b: String) {
        links.getOrPut(a) { HashSet() } += b
        links.getOrPut(b) { HashSet() } += a
        if (a == anchor.key || b == anchor.key) region.merge(if (a == anchor.key) b else a, 0.5, ::maxOf)
    }

    private fun cite(id: String, by: String) {
        cited.getOrPut(id) { Cited(id) }.by += by
    }

    private fun add(c: Candidate) {
        if (c.track.key in keys || c.track.key in profile.weight || c.track.base + "|" + c.track.who in profile.bases) return
        val had = pool[c.track.key]
        if (had == null || had.weight < c.weight) pool[c.track.key] = c
    }

    // ---- Azar ----

    private inline fun <T> weighted(items: Collection<T>, w: (T) -> Double): T? {
        val ws = DoubleArray(items.size)
        var total = 0.0
        items.forEachIndexed { i, x -> ws[i] = w(x).coerceAtLeast(0.0); total += ws[i] }
        if (total <= 0) return null
        var r = random.nextDouble() * total
        items.forEachIndexed { i, x -> r -= ws[i]; if (r <= 0) return x }
        return items.last()
    }

    /**
     * Cuánto más (o menos) de [k] poner, por muestreo de Thompson: cuánto te quedas con lo de ese tipo frente a lo
     * normal para él (las tuyas siempre se oyen más que lo nuevo; no se trata de eso).
     */
    private fun lift(k: Kind): Double {
        val (s, n) = memory.arms.getValue(k).let { it[0] to it[1] }
        val base = BASE.getValue(k)
        val theta = beta(1 + PRIOR * base + s, 1 + PRIOR * (1 - base) + (n - s).coerceAtLeast(0.0))
        return (theta / base).pow(1.5)
    }

    private fun beta(a: Double, b: Double): Double {
        val x = gamma(a)
        return x / (x + gamma(b))
    }

    // Marsaglia y Tsang.
    private fun gamma(shape: Double): Double {
        if (shape < 1) return gamma(shape + 1) * random.nextDouble().pow(1 / shape)
        val d = shape - 1.0 / 3
        val c = 1 / sqrt(9 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = sqrt(-2 * ln(1 - random.nextDouble())) * cos(2 * PI * random.nextDouble())
                v = 1 + c * x
            } while (v <= 0)
            v = v * v * v
            if (ln(1 - random.nextDouble()) < 0.5 * x * x + d - d * v + d * ln(v)) return d * v
        }
    }

    private companion object {
        /** Las primeras, tuyas: se entra con algo que te gusta. */
        const val WARM_UP = 2
        const val WAIT_MS = 5_000L
        const val SPACING = 6
        const val REGION = 12
        const val NEAR = 0.08
        const val FAR = 0.06
        /** Cuántos cambios de tanda sin puente van a cualquier otra parte de lo tuyo. */
        const val JUMP = 0.15
        const val SKIPPED = 0.15
        const val HOOKED = 0.75
        const val RECENTLY = 21 * DAY
        const val PRIOR = 3.0
        val SHARE = mapOf(Kind.FAMILIAR to 0.34, Kind.DEEP to 0.28, Kind.NEW to 0.38)
        /** Lo que se suele escuchar de cada tipo: con eso se compara lo tuyo. */
        val BASE = mapOf(Kind.FAMILIAR to 0.8, Kind.DEEP to 0.6, Kind.NEW to 0.5, Kind.BRIDGE to 0.5)
    }
}
