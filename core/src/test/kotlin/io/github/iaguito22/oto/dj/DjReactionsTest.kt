package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** Cómo aprende el DJ de lo que haces en la sesión (saltos, oírla entera, Me gusta) sin que preparar la cola lo borre. */
class DjReactionsTest {
    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 6, 15, 15, 0, 0, 0, utc).toInstant().toEpochMilli()

    private val offline = object : Catalog {
        override suspend fun artist(id: String) = null
        override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
        override suspend fun radio(id: String) = emptyList<Track>()
        override suspend fun find(name: String) = null
    }

    /** Dos escenas de cuatro artistas, ocho canciones cada uno. */
    private fun lists() = listOf("rock", "jazz").map { scene ->
        LocalPlaylist(scene, scene.replaceFirstChar { it.uppercase() }, (0 until 32).map { i ->
            val a = "${scene}_${i % 4}"
            Heard("$scene-$i", "$a tema $i", a, null, a)
        })
    }

    private fun dj(log: MutableList<String>, genre: DjGenre? = null, genreOf: (Track) -> String? = { null }) =
        Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), offline, Memory(null),
            kotlinx.coroutines.GlobalScope, Random(7), log = { log += it }, genre = genre, genreOf = genreOf)

    /** Me gusta es pasajero: uno de hace cientos de Me gusta casi no cuenta; uno reciente, algo (menos que una lista). */
    @Test fun oldLikesBarelyCount() {
        val liked = (0 until 200).map { Heard("l$it", "Tema $it", "Art$it", null, "Art$it") }
        val listed = listOf(Heard("x", "Mía", "Lista", null, "Lista"))
        val p = Profile(listOf(LocalPlaylist("l", "Mía", listed)), liked, emptyList(), emptyList(), now, utc)
        val fresh = p.weight.getValue(liked[0].toTrack().key)
        val old = p.weight.getValue(liked[199].toTrack().key)
        assertTrue("reciente $fresh > viejo $old", fresh > 50 * old)
        assertTrue("una lista pesa más que el Me gusta más reciente", p.weight.getValue(listed[0].toTrack().key) > fresh * 3)
        assertTrue(p.find("Art199", "Art199")!!.affinity < p.find("Art0", "Art0")!!.affinity)
    }

    /** Lo que ya está en el diario de escuchas no suma otra vez desde el historial. */
    @Test fun historyIsNotCountedTwice() {
        val h = Heard("h1", "Tema", "Solo", null, "Solo")
        val play = Play("h1", "Tema", "Solo", "Solo", now - 1000, 200_000, 200_000)
        val both = Profile(emptyList(), emptyList(), listOf(h), listOf(play), now, utc)
        val onlyPlays = Profile(emptyList(), emptyList(), emptyList(), listOf(play), now, utc)
        assertEquals(onlyPlays.find("Solo", "Solo")!!.organic, both.find("Solo", "Solo")!!.organic, 1e-9)
    }

    /** El mismo salto avisado por tiempo ([Dj.heard]) y por gesto ([Dj.react]) cuenta una vez. */
    @Test fun aSkipIsCountedOnce() = runBlocking {
        val log = ArrayList<String>()
        val dj = dj(log)
        val first = dj.start()!!
        dj.heard(first.id, 5_000, 200_000)
        dj.react(first.id, DjReaction.FastSkip)
        assertEquals(1, log.count { it.startsWith("skip rápido") })
    }

    /** Dos saltos seguidos cambian de tanda aunque la cola se prepare entre medias (antes lo deshacía). */
    @Test fun twoSkipsSurviveTheQueue() = runBlocking {
        val log = ArrayList<String>()
        val dj = dj(log)
        // Cola de una: la siguiente ya preparada es de la misma tanda que las saltadas.
        val first = dj.start()!!
        dj.prepareNextUp(1)
        dj.heard(first.id, 5_000, 200_000)
        dj.prepareNextUp(1)
        val second = dj.next()!!
        dj.prepareNextUp(1)
        val tandas = log.count { it.startsWith("tanda:") }
        dj.heard(second.id, 5_000, 200_000)
        dj.next()
        dj.prepareNextUp(1)
        assertTrue("debería cambiar de tanda tras dos saltos: $log", log.count { it.startsWith("tanda:") } > tandas)
    }

    /** Un Me gusta suelto entre dos saltos no impide cambiar de tanda (antes ponía los saltos a cero). */
    @Test fun aLikeDoesNotResetTheSkips() = runBlocking {
        val log = ArrayList<String>()
        val dj = dj(log)
        val first = dj.start()!!
        dj.prepareNextUp(1)
        dj.heard(first.id, 5_000, 200_000)
        dj.react(first.id, DjReaction.Like)
        dj.prepareNextUp(1)
        val second = dj.next()!!
        dj.prepareNextUp(1)
        val tandas = log.count { it.startsWith("tanda:") }
        dj.heard(second.id, 5_000, 200_000)
        dj.next()
        dj.prepareNextUp(1)
        assertTrue("debería cambiar de tanda pese al Me gusta: $log", log.count { it.startsWith("tanda:") } > tandas)
    }

    /** Un salto aparta al artista un rato, no toda la sesión. */
    @Test fun aSkippedArtistComesBack() = runBlocking {
        val log = ArrayList<String>()
        val dj = dj(log)
        val first = dj.start()!!
        dj.heard(first.id, 5_000, 200_000)
        val after = (1..40).mapNotNull { dj.next().also { t -> t?.let { dj.heard(it.id, 190_000, 200_000) } } }
        val back = after.indexOfFirst { it.who == first.who }
        assertTrue("no vuelve durante el apartado: $back", back < 0 || back >= Dj.BAN_FIRST)
        assertTrue("vuelve en la sesión", back >= 0)
    }

    /** Me gusta es pasajero: no saca del apartado a un artista que acabas de saltar (guardarla sí lo haría). */
    @Test fun aLikeDoesNotUndoASkip() = runBlocking {
        val log = ArrayList<String>()
        val dj = dj(log)
        val first = dj.start()!!
        dj.heard(first.id, 5_000, 200_000)
        dj.react(first.id, DjReaction.Like)
        val after = (1 until Dj.BAN_FIRST).mapNotNull { dj.next()?.also { dj.heard(it.id, 190_000, 200_000) } }
        assertTrue("el artista saltado no vuelve por un Me gusta: ${after.map { it.who }}", after.none { it.who == first.who })
    }

    /**
     * Las peticiones de red corren en otros hilos mientras el DJ elige y reacciona (como en el PC):
     * no rompen su estado y lo que traen acaba sonando.
     */
    @Test fun networkResultsArriveWhileTheDjPicks() = runBlocking {
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.CoroutineExceptionHandler { _, e -> errors += e },
        )
        val calls = java.util.concurrent.atomic.AtomicInteger()
        fun net(tag: String, n: Int) = (0 until n).map { i ->
            val a = "net_${tag}_${i % 3}"
            Track("net-$tag-$i-${calls.incrementAndGet()}", "$a tema $i", listOf(a), listOf("id_$a"))
        }
        val catalog = object : Catalog {
            override suspend fun artist(id: String): ArtistInfo {
                kotlinx.coroutines.delay(1)
                return ArtistInfo(id, id.removePrefix("id_"), net(id, 5), listOf("id_c_$id" to "c_$id", "id_rock_1" to "rock_1"), null)
            }
            override suspend fun songs(info: ArtistInfo): List<Track> { kotlinx.coroutines.delay(1); return net(info.id, 8) }
            override suspend fun radio(id: String): List<Track> { kotlinx.coroutines.delay(1); return net("r$id", 12) }
            override suspend fun find(name: String): String { kotlinx.coroutines.delay(1); return "id_$name" }
        }
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), catalog, Memory(null),
            scope, Random(11), log = {})
        val picks = ArrayList<Track>()
        dj.start()?.let { picks += it }
        repeat(150) { i ->
            val t = dj.next() ?: return@repeat
            picks += t
            when (i % 5) {
                0 -> dj.heard(t.id, 5_000, 200_000)
                1 -> { dj.heard(t.id, 190_000, 200_000); dj.react(t.id, DjReaction.Save) }
                else -> dj.heard(t.id, 190_000, 200_000)
            }
            if (i % 7 == 0) dj.prepareNextUp(3)
        }
        dj.settle()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        assertTrue("errores en las peticiones: $errors", errors.isEmpty())
        assertTrue("lo traído de la red debería sonar alguna vez", picks.any { it.id.startsWith("net-") })
    }

    /** Me gusta en una canción nueva no arma Remate (ni por ratio de descubrimiento); Guardarla sí. */
    @Test fun onlySavingArmsTheRemate() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        fun net(tag: String, n: Int) = (0 until n).map { i ->
            val a = "net_${tag}_${i % 2}"
            Track("net-$tag-$i", "$a tema $i", listOf(a), listOf("id_$a"))
        }
        val catalog = object : Catalog {
            override suspend fun artist(id: String) = ArtistInfo(id, id.removePrefix("id_"), net(id, 4), emptyList(), null)
            override suspend fun songs(info: ArtistInfo) = net(info.id, 6)
            override suspend fun radio(id: String) = net("r$id", 12)
            override suspend fun find(name: String) = "id_$name"
        }
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), catalog, Memory(null),
            scope, Random(5), log = {})
        dj.start()
        // Sin reacciones hasta dar con una canción traída de la red (no familiar).
        val fresh = (1..60).firstNotNullOfOrNull { dj.next()?.takeIf { it.id.startsWith("net-") } }
        assertTrue("debería sonar algo nuevo", fresh != null)
        assertTrue("sin reacciones no hay Remate", !dj.remateArmed)
        dj.react(fresh!!.id, DjReaction.Like)
        assertTrue("un Me gusta no arma Remate", !dj.remateArmed)
        dj.react(fresh.id, DjReaction.Save)
        assertTrue("guardarla sí", dj.remateArmed)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    /** Un Me gusta tras Guardar no alarga ni debilita el refuerzo; volver a guardarla lo renueva. */
    @Test fun aLikeAfterSavingKeepsTheBoost() = runBlocking {
        val dj = dj(ArrayList())
        val first = dj.start()!!
        dj.react(first.id, DjReaction.Save)
        assertEquals(5 to 3.0, dj.boostOf(first.key))
        dj.next()
        val left = dj.boostOf(first.key)!!.first
        assertTrue("se gasta al elegir: $left", left < 5)
        dj.react(first.id, DjReaction.Like)
        assertEquals("Me gusta no lo toca", left to 3.0, dj.boostOf(first.key))
        dj.react(first.id, DjReaction.Save)
        assertEquals(5 to 3.0, dj.boostOf(first.key))
    }

    /** Me gusta no pide la radio de la canción, ni siquiera de una tuya; guardarla sí. */
    @Test fun aLikeDoesNotAskForRadio() = runBlocking {
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val catalog = object : Catalog {
            override suspend fun artist(id: String) = null
            override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
            override suspend fun radio(id: String) = emptyList<Track>().also { asked += id }
            override suspend fun find(name: String) = null
        }
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), catalog, Memory(null),
            this, Random(7), log = {})
        dj.start()!!
        val mine = dj.next()!!
        dj.settle()
        assertTrue("es una tuya y aún sin radio", mine.id !in asked)
        dj.react(mine.id, DjReaction.Like)
        dj.settle()
        assertTrue("Me gusta no pide radio", mine.id !in asked)
        dj.react(mine.id, DjReaction.Save)
        dj.settle()
        assertTrue("guardarla sí", mine.id in asked)
    }

    /** Con un género elegido, la tanda lleva ese nombre aunque el artista sea más de otro. */
    @Test fun theTandaIsNamedAfterTheChosenGenre() = runBlocking {
        val log = ArrayList<String>()
        val pop = DjGenre("Pop", (0 until 32).mapTo(HashSet()) { "x$it" }, setOf("rock_0", "rock_1"), 1.0)
        val dj = dj(log, genre = pop, genreOf = { if (it.who.startsWith("rock")) "Alternativa" else "Jazz" })
        val t = dj.start()!!
        assertEquals("Pop", dj.tandaOf(t.id))
        dj.setGenre(DjGenre("Jazz", emptySet(), setOf("jazz_0"), 1.0))
        assertEquals("al cambiar de género, la tanda cambia ya", "Jazz", dj.tanda)
    }
}
