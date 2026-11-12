package io.github.iaguito22.oto.dj

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * El DJ, para cuando no sabes qué poner. Pincha por tandas, como uno de verdad: cada tanda gira alrededor de un
 * artista tuyo y de los que se le parecen (por tus listas y por YouTube), y mezcla tres cosas:
 *  - tuyas que llevabas tiempo sin oír (~70 % en modos normales),
 *  - canciones de tus artistas que no tienes y artistas nuevos relacionados (~30 % en modos normales).
 *
 * Adapta la selección al [DjMood] activo (incluyendo hora del día en [DjMood.Auto]), la continuidad entre la canción
 * N y N+1 (artistas, colaboradores, radio y ambiente/género), el enfriamiento obligatorio de canciones repetidas, la
 * reacción en vivo (saltos rápidos < 20 s vs escuchas completas, me gusta o guardados) y el modo [DjMode.Adhd] con
 * segmentos cortos.
 *
 * No es seguro entre hilos: se usa siempre desde el mismo (el principal en el servicio).
 */
class Dj(
    private val profile: Profile,
    private val catalog: Catalog,
    private val memory: Memory,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    mood: DjMood = DjMood.Auto,
    mode: DjMode = DjMode.Normal,
    /** Por dónde va (para el registro y las pruebas). */
    private val log: (String) -> Unit = {},
) {
    /** Lo que ha puesto y cuánto te quedaste (null: aún suena). [vias]: los artistas tuyos que llevaron a ella. */
    class Pick(
        val track: Track,
        val kind: Kind,
        val vias: Set<String>,
        var reward: Double? = null,
        var adhdSegment: AdhdSegment? = null,
    )

    private class Candidate(val track: Track, val kind: Kind, val vias: Set<String>, var weight: Double, val target: String? = null)

    /** Un artista que no es tuyo y los cercanos a ti que, según YouTube, se le parecen. */
    private class Cited(val id: String) {
        val by = HashSet<String>()
    }

    /** Refuerzo temporal en la sesión tras una escucha completa, me gusta o guardado (dura 4-5 canciones). */
    private class Boost(val track: Track, val vias: Set<String>, val vibe: VibeScore, var remaining: Int, val strength: Double)

    /** Enfriamiento temporal de una rama/dirección tras un salto rápido (< 20 s). */
    private class CooldownBranch(val who: String, val vias: Set<String>, val vibe: VibeScore, var remaining: Int)

    var mood: DjMood = mood
        private set

    var mode: DjMode = mode
        private set

    private val now = profile.now
    val picks = ArrayList<Pick>()
    private val keys = HashSet<String>()
    private val pool = LinkedHashMap<String, Candidate>()
    private val explored = HashSet<String>()
    private val radioSeeded = HashSet<String>()
    private val met = HashSet<String>()
    private val cited = HashMap<String, Cited>()
    /** Artistas tuyos que YouTube junta (por clave) aunque tus listas no. */
    private val links = HashMap<String, HashSet<String>>()
    /** Tuyas que salen en la radio de lo que suena: encajan con la tanda. */
    private val inRadio = HashSet<String>()
    private val banned = HashSet<String>()
    /** Artistas nuevos con los que te quedaste: se acercan a su tanda. */
    private val warm = HashSet<String>()
    private val boosts = ArrayList<Boost>()
    private val cooledBranches = ArrayList<CooldownBranch>()
    private val jobs = ArrayList<Job>()

    /** La tanda: su centro y los artistas que la forman, con lo cerca que están (0–1). */
    private lateinit var anchor: Profile.Artist
    private val region = HashMap<String, Double>()
    private var block = 0
    private var blockLen = 5
    private var skips = 0
    private var relaxLevel = 0

    /** Cambia el mood en vivo para que la siguiente canción ya se adapte sin reiniciar la sesión. */
    fun setMood(newMood: DjMood) {
        if (mood == newMood) return
        mood = newMood
        log("mood: ${newMood.label}")
        blockLen = minOf(blockLen, block + 1)
    }

    /** Cambia entre modo Normal y DJ rápido (ADHD) en vivo. */
    fun setMode(newMode: DjMode) {
        if (mode == newMode) return
        mode = newMode
        log("modo: ${newMode.label}")
    }

    /** Calcula el segmento ADHD para [track] (usando [durationMs] si la pista aún no traía duración). */
    fun adhdSegment(
        track: Track,
        durationMs: Long? = null,
        crossfadeMs: Long = AdhdPlanner.DEFAULT_MIX_MS,
    ): AdhdSegment? = track.adhdSegment(durationMs, crossfadeMs, mood)?.also { seg ->
        picks.lastOrNull { it.track.id == track.id }?.let { it.adhdSegment = seg }
    }

    /** La primera: una tuya, al momento, sin esperar a la red. null si no tienes nada guardado. */
    fun start(): Track? {
        memory.decay()
        repeat(10) { i ->
            relaxLevel = when {
                i < 5 -> 0
                i < 8 -> 1
                else -> 2
            }
            setAnchor(chooseAnchor(null) ?: return null)
            log("tanda: ${anchor.name}, para empezar (${mood.label})")
            familiar()?.let { return pick(it) }
        }
        return null
    }

    /**
     * La siguiente. Lo que llega de la red mientras tanto entra en las próximas; si la tanda se ha quedado sin nada y
     * aún se está pidiendo algo, espera un poco antes de cambiar de tanda.
     */
    suspend fun next(): Track? {
        relaxLevel = 0
        val moodMismatch = ::anchor.isInitialized && anchorMoodScore(anchor) < 0.72
        var c = if (block >= blockLen || skips >= 2 || moodMismatch) transition() else inBlock()
        if (c == null && jobs.any { it.isActive }) {
            withTimeoutOrNull(WAIT_MS) { settle() }
            c = inBlock()
        }
        if (c != null) return pick(c)
        // La tanda se quedó sin nada que poner: a otra.
        repeat(10) { i ->
            relaxLevel = when {
                i < 5 -> 0
                i < 8 -> 1
                else -> 2
            }
            setAnchor(chooseAnchor(anchor) ?: return null)
            log("tanda: ${anchor.name}, la otra se agotó")
            familiar()?.let { return pick(it) }
        }
        return null
    }

    /** Cuánto te quedaste con [id]: actualiza memoria y dispara enfriamiento o refuerzo en vivo. */
    fun heard(id: String, ms: Long, dur: Long) {
        val p = picks.lastOrNull { it.track.id == id && it.reward == null } ?: return
        val clipDur = if (mode == DjMode.Adhd) {
            (p.adhdSegment ?: if (dur > 0L) AdhdPlanner.segment(dur, id, mood = mood)?.also { p.adhdSegment = it } else null)?.clipDurationMs
        } else null
        val effectiveDur = if (mode == DjMode.Adhd && clipDur != null && clipDur > 0L) clipDur else dur
        val r = reward(ms, effectiveDur)
        p.reward = r
        memory.learn(p.kind, r)
        memory.save(now)

        val isFastSkip = ms < FAST_SKIP_MS && (effectiveDur <= 0L || ms < effectiveDur / 2L)
        val isFullListen = when {
            isFastSkip -> false
            mode == DjMode.Adhd && clipDur != null && clipDur > 0L -> ms >= (clipDur * 0.75).toLong()
            else -> r >= HOOKED
        }
        when {
            isFastSkip -> applyFastSkip(p)
            isFullListen -> applyBoost(p, DjReaction.FullListen)
            r < SKIPPED -> applyFastSkip(p)
            else -> skips = 0
        }
    }

    /** Reacción explícita en vivo (salto rápido, escucha completa, me gusta o guardado en lista). */
    fun react(id: String, reaction: DjReaction) {
        val p = picks.lastOrNull { it.track.id == id } ?: return
        when (reaction) {
            DjReaction.FastSkip -> {
                if (p.reward == null) {
                    p.reward = 0.0
                    memory.learn(p.kind, 0.0)
                    memory.save(now)
                }
                applyFastSkip(p)
            }
            DjReaction.FullListen, DjReaction.Like, DjReaction.Save -> {
                if (p.reward == null && reaction == DjReaction.FullListen) {
                    p.reward = 1.0
                    memory.learn(p.kind, 1.0)
                    memory.save(now)
                }
                applyBoost(p, reaction)
            }
        }
    }

    /** Espera a lo que se esté pidiendo (para las pruebas). */
    suspend fun settle() {
        while (jobs.any { it.isActive }) jobs.toList().joinAll()
    }

    // ---- Elegir ----

    private fun inBlock(): Candidate? {
        val maxDiscRun = if (mood == DjMood.Descubrir) 4 else 2
        val run = picks.takeLastWhile { it.kind != Kind.FAMILIAR }.size
        if (run >= maxDiscRun) return familiar()
        if (picks.size < WARM_UP) familiar()?.let { return it }

        val deep = eligible(Kind.DEEP)
        val fresh = eligible(Kind.NEW)
        val kinds = buildList {
            add(Kind.FAMILIAR)
            if (deep.isNotEmpty()) add(Kind.DEEP)
            if (fresh.isNotEmpty()) add(Kind.NEW)
        }
        val shares = ratioAdjustedShares()
        return when (weighted(kinds) { shares.getValue(it) * lift(it) }) {
            Kind.DEEP -> weighted(deep, ::score)
            Kind.NEW -> weighted(fresh, ::score)
            else -> familiar() ?: weighted(deep + fresh, ::score)
        }
    }

    /** Regula la proporción objetivo (~70 % conocida / ~30 % descubrimiento en modos normales; invertida en Descubrir). */
    private fun ratioAdjustedShares(): Map<Kind, Double> {
        val base = if (mood == DjMood.Descubrir) SHARE_DISCOVER else SHARE_NORMAL
        val targetDisc = if (mood == DjMood.Descubrir) 0.65 else 0.30
        if (picks.size < WARM_UP) return base
        val discCount = picks.count { it.kind != Kind.FAMILIAR }
        val actualDisc = discCount.toDouble() / picks.size
        val famFactor = when {
            actualDisc > targetDisc + 0.05 -> 2.8
            actualDisc < targetDisc - 0.05 -> 0.42
            else -> 1.0
        }
        val discFactor = when {
            actualDisc > targetDisc + 0.05 -> 0.32
            actualDisc < targetDisc - 0.05 -> 2.4
            else -> 1.0
        }
        return mapOf(
            Kind.FAMILIAR to base.getValue(Kind.FAMILIAR) * famFactor,
            Kind.DEEP to base.getValue(Kind.DEEP) * discFactor,
            Kind.NEW to base.getValue(Kind.NEW) * discFactor,
        )
    }

    /** Fin de tanda: un puente si toca descubrir y hay continuidad; si no, un artista afín al mood y al anterior. */
    private fun transition(): Candidate? {
        val maxDiscRun = if (mood == DjMood.Descubrir) 4 else 2
        val run = picks.takeLastWhile { it.kind != Kind.FAMILIAR }.size
        val targetDisc = if (mood == DjMood.Descubrir) 0.65 else 0.30
        val actualDisc = if (picks.isEmpty()) 0.0 else picks.count { it.kind != Kind.FAMILIAR }.toDouble() / picks.size
        val allowBridge = run < maxDiscRun && actualDisc <= targetDisc + 0.06
        val bridgeProb = (if (mood == DjMood.Descubrir) 0.8 else 0.55) * lift(Kind.BRIDGE)
        if (allowBridge && random.nextDouble() < bridgeProb.coerceIn(0.15, 0.9)) {
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
            .filter { it.key !in region && it.songs.isNotEmpty() && allowed(it.key) && profile.sim(anchor, it) < FAR }
        val linked = weighted(far) { it.affinity.pow(0.7) * artistMoodAndCooldownWeight(it) }
        val from = anchor
        val skipped = skips >= 2
        setAnchor(linked ?: chooseAnchor(anchor) ?: return null)
        log("tanda: ${anchor.name}, ${if (linked != null) "pariente en YouTube de" else "salto desde"} ${from.name}${if (skipped) " (pasaste dos)" else ""}")
        return familiar()
    }

    /** Una tuya de la tanda: combina afinidad de tanda, continuidad N→N+1, mood/hora, refuerzos y enfriamientos. */
    private fun familiar(): Candidate? {
        val artists = region.keys.mapNotNull(profile.artists::get)
            .filter { it.songs.isNotEmpty() && allowed(it.key) }
            .toMutableList()
        while (artists.isNotEmpty()) {
            val a = weighted(artists) { artist ->
                val base = (region[artist.key] ?: 0.0).coerceAtLeast(0.05) *
                    artist.affinity.pow(0.45) *
                    artistRepeatWeight(artist.key) *
                    branchMultiplier(artist.key, setOf(artist.key), artist.vibe)
                base
            } ?: return null
            val validTracks = a.songs.values.filter(::ok)
            val t = weighted(validTracks) { track ->
                val w = (profile.weight[track.key] ?: 1.0).pow(0.65) *
                    forgotten(track) *
                    moodFit(track, setOf(a.key)) *
                    smooth(track, Kind.FAMILIAR, setOf(a.key)) *
                    boostMultiplier(track, setOf(a.key)) *
                    branchMultiplier(track.who, setOf(a.key), profile.vibeOf(track, setOf(a.key))) *
                    (if (track.key in inRadio) 1.55 else 1.0)
                w
            }
            if (t != null) return Candidate(t, Kind.FAMILIAR, setOf(a.key), 1.0)
            artists -= a
        }
        return null
    }

    private fun eligible(kind: Kind) = pool.values.filter { it.kind == kind && ok(it.track) && near(it) > 0 }

    private fun score(c: Candidate): Double {
        var w = c.weight *
            near(c) *
            smooth(c.track, c.kind, c.vias) *
            moodFit(c.track, c.vias) *
            artistRepeatWeight(c.track.who) *
            boostMultiplier(c.track, c.vias) *
            branchMultiplier(c.track.who, c.vias, profile.vibeOf(c.track, c.vias))
        profile.artists[c.track.who]?.let { a ->
            if (c.kind == Kind.DEEP) w *= a.affinity.pow(0.3)
            w *= (profile.djRate(a) / 0.6)
        }
        return w
    }

    /**
     * Continuidad entre la canción anterior (N) y la candidata (N+1): combina similitud en tus listas,
     * enlaces de YouTube, colaboradores compartidos, presencia en radio y coherencia de ambiente/género.
     */
    private fun synergy(track: Track, vias: Set<String>): Double {
        val prev = picks.lastOrNull() ?: return 1.0
        if (prev.track.who == track.who) return 1.0

        // Colaboradores compartidos entre todos los artistas listados de la pista.
        val prevNames = prev.track.artists.mapTo(HashSet()) { artistKey(it) }
        val prevIds = prev.track.artistIds.filterNotNullTo(HashSet())
        val sharedCollab = track.artists.any { artistKey(it) in prevNames } ||
            track.artistIds.any { it != null && it in prevIds }
        if (sharedCollab) return 0.9

        var graphBest = 0.0
        for ((x, wx) in sides(prev.track.who, prev.vias)) for ((y, wy) in sides(track.who, vias)) {
            val s = if (x === y) 1.0 else maxOf(profile.sim(x, y), if (links[x.key]?.contains(y.key) == true) LINKED else 0.0)
            graphBest = maxOf(graphBest, s * wx * wy)
        }
        if (vias.isNotEmpty() && prev.vias.any { it in vias }) {
            graphBest = maxOf(graphBest, 0.45)
        }

        val prevVibe = profile.vibeOf(prev.track, prev.vias)
        val candVibe = profile.vibeOf(track, vias)
        val vibeSim = prevVibe.synergyWith(candVibe)
        val radioBonus = if (track.key in inRadio) 0.25 else 0.0

        return (0.6 * graphBest + 0.4 * vibeSim + radioBonus).coerceIn(0.0, 1.0)
    }

    /** Un artista tuyo es él mismo; uno nuevo, los tuyos que llevaron a él (un poco menos). */
    private fun sides(who: String, vias: Set<String>): List<Pair<Profile.Artist, Double>> =
        profile.artists[who]?.takeIf { it.lists.isNotEmpty() }?.let { listOf(it to 1.0) }
            ?: vias.mapNotNull(profile.artists::get).map { it to 0.8 }

    /** Cuánto pesa encadenar bien: evita saltos bruscos de género/energía, más estricto en Foco/Chill. */
    private fun smooth(track: Track, kind: Kind, vias: Set<String>): Double {
        val syn = synergy(track, vias)
        val base = FLOW_FLOOR + syn
        val strictness = when (mood) {
            DjMood.Foco -> 1.45
            DjMood.Chill -> 1.3
            DjMood.Auto -> if (isNightHour()) 1.25 else 1.05
            else -> 1.0
        }
        val kindFactor = if (kind == Kind.NEW) 0.95 else 1.0
        return base.pow(strictness) * kindFactor
    }

    /** Puntuación de [track] según el [mood] activo y la hora del día. */
    private fun moodFit(track: Track, vias: Set<String>): Double {
        val v = profile.vibeOf(track, vias)
        val artist = profile.find(track.artistId, track.artist)
        val nightBias = profile.nightBias(artist)
        return when (mood) {
            DjMood.Energia -> (0.15 + 2.2 * v.energy) * (1.15 - 0.65 * v.chill)
            DjMood.Chill -> (0.15 + 2.2 * v.chill) * (1.15 - 0.65 * v.energy)
            DjMood.Foco -> (0.15 + 2.1 * v.focus + 0.4 * v.chill) * (1.1 - 0.55 * maxOf(0.0, v.energy - 0.55))
            DjMood.Descubrir -> 0.75 + 0.5 * timeOfDayFit(v, nightBias)
            DjMood.Auto -> timeOfDayFit(v, nightBias)
        }.coerceIn(0.08, 3.2)
    }

    private fun timeOfDayFit(v: VibeScore, nightBias: Double): Double {
        val hour = profile.hourOfDay
        return when {
            // Noche y madrugada: más calmado y afín a escuchas nocturnas.
            hour in 22..23 || hour in 0..5 -> {
                val base = 0.30 + 1.55 * v.chill + 0.35 * (1.0 - v.energy)
                (base * (1.0 + 0.45 * nightBias)).coerceAtLeast(0.12)
            }
            // Día y tarde: más energía y ritmo activo.
            hour in 10..18 -> {
                val base = 0.35 + 1.50 * v.energy + 0.25 * (1.0 - v.chill)
                (base * (1.0 - 0.35 * nightBias)).coerceAtLeast(0.15)
            }
            // Mañana temprana: arranque equilibrado.
            hour in 6..9 -> 0.65 + 0.45 * v.energy + 0.25 * v.focus
            // Media tarde / noche temprana: transición suave.
            else -> 0.65 + 0.45 * v.chill + 0.25 * v.energy
        }
    }

    private fun isNightHour(): Boolean {
        val h = profile.hourOfDay
        return h in 22..23 || h in 0..5
    }

    private fun anchorMoodScore(a: Profile.Artist): Double {
        val songs = a.songs.values
        if (songs.isEmpty()) return 1.0
        val avg = songs.take(8).map { moodFit(it, setOf(a.key)) }.average()
        return avg.coerceIn(0.1, 3.0)
    }

    private fun artistMoodAndCooldownWeight(a: Profile.Artist): Double {
        val exp = if (mood == DjMood.Energia || mood == DjMood.Chill || mood == DjMood.Foco) 2.2 else 1.5
        return anchorMoodScore(a).pow(exp) * cooldown(a) * artistRepeatWeight(a.key)
    }

    /** Lo cerca que está de la tanda lo que llevó a ella; si la citan varios tuyos, más. */
    private fun near(c: Candidate): Double {
        if (c.kind == Kind.DEEP || c.kind == Kind.FAMILIAR) return (region[c.vias.first()] ?: 0.0)
        val sum = c.vias.sumOf { region[it] ?: 0.0 }
        return sum.coerceAtMost(1.5) * if (c.vias.size >= 2) 1.3 else 1.0
    }

    private fun pick(c: Candidate): Track {
        pool.remove(c.track.key)
        val first = block == 0
        val seg = if (mode == DjMode.Adhd) adhdSegment(c.track) else null
        picks += Pick(c.track, c.kind, c.vias, adhdSegment = seg)
        keys += c.track.key
        keys += c.track.id
        if (c.track.base.length >= 4) keys += "~" + c.track.base
        memory.rememberSong(c.track, now)
        advanceWindowCounters()
        block++
        if (first) radio(c.track)
        return c.track
    }

    private fun advanceWindowCounters() {
        val bIt = boosts.iterator()
        while (bIt.hasNext()) {
            val b = bIt.next()
            b.remaining--
            if (b.remaining <= 0) bIt.remove()
        }
        val cIt = cooledBranches.iterator()
        while (cIt.hasNext()) {
            val cb = cIt.next()
            cb.remaining--
            if (cb.remaining <= 0) cIt.remove()
        }
    }

    private fun ok(t: Track): Boolean {
        // Enfriamiento obligatorio de canción en la sesión actual: nunca se repite una misma canción.
        if (t.key in keys || t.id in keys || "~" + t.base in keys) return false
        when (relaxLevel) {
            0 -> {
                if (t.id in profile.djRecent || t.key in profile.djRecent) return false
                if (memory.isRecent(t, now)) return false
                profile.heardAt[t.key]?.let { if (now - it < RECENTLY) return false }
            }
            1 -> {
                // Con bibliotecas muy pequeñas, solo se relaja pasado > 1 día; nunca lo oído hoy.
                if (memory.isRecent(t, now, DAY)) return false
                profile.heardAt[t.key]?.let { if (now - it < DAY) return false }
            }
            else -> {
                // Último recurso si no queda ninguna otra canción fuera de la sesión actual.
            }
        }
        t.seconds?.let { if (it < 60 || it > 600) return false }
        if (t.upload) return false
        return allowed(t.who)
    }

    /**
     * Sin cooldown estricto de artista: solo evita repetir exactamente el mismo artista en la canción inmediatamente
     * anterior (cuando hay alternativas) y respeta los artistas/ramas saltados en esta sesión.
     */
    private fun allowed(who: String): Boolean {
        if (who in banned && relaxLevel < 2) return false
        if (relaxLevel == 0 && picks.lastOrNull()?.track?.who == who) return false
        return true
    }

    /** Penalización suave por repetición del mismo artista en la sesión, sin bloquearlo de forma estricta. */
    private fun artistRepeatWeight(who: String): Double {
        val count = picks.count { it.track.who == who }
        val recentCount = picks.takeLast(4).count { it.track.who == who }
        return (1.0 / (1.0 + 0.45 * count)) * (1.0 / (1.0 + 0.8 * recentCount))
    }

    // ---- Reacción en vivo ----

    private fun applyFastSkip(p: Pick) {
        skips++
        banned += p.track.who
        val vibe = profile.vibeOf(p.track, p.vias)
        cooledBranches += CooldownBranch(p.track.who, p.vias, vibe, remaining = 6)

        // Enfriar los boosts que apuntaban a esta misma rama.
        boosts.removeAll { b -> b.track.who == p.track.who || b.vias.any { it in p.vias } }

        // Reducir peso de candidatos de la misma rama/vía/estilo en el pool.
        for (c in pool.values) {
            val cv = profile.vibeOf(c.track, c.vias)
            when {
                c.track.who == p.track.who -> c.weight *= 0.1
                c.vias.any { it in p.vias } -> c.weight *= 0.3
                vibe.synergyWith(cv) >= 0.78 -> c.weight *= 0.55
            }
        }
        // Enfriar también las vías de la región para pivotar hacia otra dirección.
        for (v in p.vias + p.track.who) {
            region[v]?.let { region[v] = (it * 0.45).coerceAtLeast(0.05) }
        }
        log("skip rápido (<20s): enfriando rama de ${p.track.artist}")
    }

    private fun applyBoost(p: Pick, reaction: DjReaction) {
        skips = 0
        val strength = when (reaction) {
            DjReaction.Save -> 3.0
            DjReaction.Like -> 2.8
            DjReaction.FullListen -> 2.1
            DjReaction.FastSkip -> 0.0
        }
        val vibe = profile.vibeOf(p.track, p.vias)
        // Evitar duplicar exactamente el mismo boost si ya existe para la misma canción y reacción igual o mayor.
        val existing = boosts.firstOrNull { it.track.key == p.track.key }
        if (existing != null) {
            existing.remaining = 5
            if (strength > existing.strength) {
                boosts.remove(existing)
                boosts += Boost(p.track, p.vias, vibe, remaining = 5, strength = strength)
            }
        } else {
            boosts += Boost(p.track, p.vias, vibe, remaining = 5, strength = strength)
        }
        // Mantener viva la zona de la tanda y traer más temas afines.
        region.merge(p.track.who, 0.9, ::maxOf)
        p.vias.forEach { region.merge(it, 0.6, ::maxOf) }
        if (p.kind != Kind.FAMILIAR) follow(p.track)
        radio(p.track)
        log("refuerzo (${reaction.name}): ${p.track.artist} — ${p.track.title}")
    }

    private fun boostMultiplier(track: Track, vias: Set<String>): Double {
        if (boosts.isEmpty()) return 1.0
        val cv = profile.vibeOf(track, vias)
        var mult = 1.0
        for (b in boosts) {
            val artistSim = maxOf(
                if (track.who == b.track.who) 0.85 else 0.0,
                if (vias.any { it in b.vias || it == b.track.who }) 0.75 else 0.0,
                graphArtistSim(b.track.who, track.who),
                if (track.key in inRadio) 0.6 else 0.0,
            )
            val vibeSim = b.vibe.synergyWith(cv)
            val totalSim = maxOf(artistSim, vibeSim * 0.75)
            if (totalSim >= 0.45) {
                mult *= 1.0 + b.strength * totalSim * (b.remaining / 5.0)
            }
        }
        return mult.coerceAtMost(8.0)
    }

    private fun branchMultiplier(who: String, vias: Set<String>, vibe: VibeScore): Double {
        if (cooledBranches.isEmpty()) return 1.0
        var mult = 1.0
        for (cb in cooledBranches) {
            when {
                who == cb.who -> mult *= 0.15
                vias.any { it in cb.vias || it == cb.who } -> mult *= 0.35
                cb.vibe.synergyWith(vibe) >= 0.78 -> mult *= 0.55
            }
        }
        return mult.coerceAtLeast(0.05)
    }

    private fun graphArtistSim(aKey: String, bKey: String): Double {
        if (aKey == bKey) return 1.0
        val a = profile.artists[aKey] ?: return 0.0
        val b = profile.artists[bKey] ?: return 0.0
        return maxOf(profile.sim(a, b), if (links[aKey]?.contains(bKey) == true) LINKED else 0.0)
    }

    // ---- La tanda ----

    private fun setAnchor(a: Profile.Artist) {
        anchor = a
        memory.anchors[a.key] = now
        block = 0
        skips = 0
        blockLen = if (mode == DjMode.Adhd) random.nextInt(3, 6) else random.nextInt(4, 7)
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
     * El centro de una tanda: alguien muy tuyo acorde al [mood] activo y con algo de enfriamiento frente a sesiones
     * previas. Si viene de otra tanda, prefiere continuidad en vez de saltos bruscos.
     */
    private fun chooseAnchor(from: Profile.Artist?): Profile.Artist? {
        val options = profile.core.filter { it.songs.isNotEmpty() && it !== from && (from == null || allowed(it.key)) }
            .let { all -> all.filter { it.songs.size >= 2 }.ifEmpty { all } }
        val weight = { a: Profile.Artist ->
            a.affinity.pow(0.65) * artistMoodAndCooldownWeight(a) * branchMultiplier(a.key, setOf(a.key), a.vibe)
        }
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

    /** La radio de lo que abre cada tanda (o de un refuerzo): suma cohesión y aporta descubrimientos cercanos. */
    private fun radio(t: Track) {
        if (!radioSeeded.add(t.id)) return
        val center = if (::anchor.isInitialized) anchor.key else t.who
        jobs += scope.launch {
            for (r in catalog.radio(t.id).drop(1)) {
                pool[r.key]?.let { it.weight *= 1.6; continue }
                if (r.key in profile.weight) { inRadio += r.key; continue }
                val lib = profile.find(r.artistId, r.artist)?.takeIf { it.songs.isNotEmpty() }
                if (lib != null) add(Candidate(r, Kind.DEEP, setOf(lib.key), 0.5))
                else add(Candidate(r, Kind.NEW, setOf(center), 0.38))
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
        // Relleno que no es una canción: cortes de sesiones de DJ, intros, karaoke, versiones para niños…
        if (JUNK_TITLE.containsMatchIn(c.track.title) || JUNK_ARTIST.containsMatchIn(c.track.artist)) return
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
        return (theta / base).pow(1.2)
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

    companion object {
        const val FAST_SKIP_MS = 20_000L
        private val JUNK_TITLE = Regex("""\([^)]*\bmixed\b[^)]*\)|\bmix cut\b|\b(abgt|asot|gdjb)\s?\d+|\b(intro|interlude|outro|skit|karaoke)\b""", RegexOption.IGNORE_CASE)
        private val JUNK_ARTIST = Regex("""kidz bop|karaoke|tribute|rockabye baby|\b8[- ]bit\b""", RegexOption.IGNORE_CASE)
        /** La primera es siempre tuya (sin esperar a la red). */
        private const val WARM_UP = 1
        private const val WAIT_MS = 5_000L
        private const val REGION = 12
        private const val NEAR = 0.08
        private const val FAR = 0.06
        /** Cuántos cambios de tanda sin puente van a cualquier otra parte de lo tuyo. */
        private const val JUMP = 0.12
        private const val SKIPPED = 0.15
        /** Lo que no tiene nada que ver con la anterior aún puede salir, pero mucho menos. */
        private const val FLOW_FLOOR = 0.04
        /** Dos tuyos que YouTube junta aunque tus listas no: se parecen al menos esto. */
        private const val LINKED = 0.3
        private const val HOOKED = 0.75
        private const val RECENTLY = 21 * DAY
        private const val PRIOR = 3.0
        private val SHARE_NORMAL = mapOf(Kind.FAMILIAR to 0.70, Kind.DEEP to 0.14, Kind.NEW to 0.16)
        private val SHARE_DISCOVER = mapOf(Kind.FAMILIAR to 0.35, Kind.DEEP to 0.25, Kind.NEW to 0.40)
        /** Lo que se suele escuchar de cada tipo: con eso se compara lo tuyo. */
        private val BASE = mapOf(Kind.FAMILIAR to 0.8, Kind.DEEP to 0.6, Kind.NEW to 0.5, Kind.BRIDGE to 0.5)
    }
}
