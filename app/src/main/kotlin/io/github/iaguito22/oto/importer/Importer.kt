package io.github.iaguito22.oto.importer

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.toHeard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/** Una que no se encontró, y en qué lista estaba. */
data class Missing(val list: String, val track: SourceTrack)

sealed interface ImportState {
    data object Idle : ImportState

    /** [list]: la que se está buscando ahora, la [index]+1 de [lists]. [done] de [total] canciones. */
    data class Running(val list: String, val index: Int, val lists: Int, val done: Int, val total: Int, val missing: Int) : ImportState

    /** [stopped]: se paró a medias; lo guardado hasta entonces se queda. */
    data class Done(val lists: Int, val songs: Int, val missing: List<Missing>, val stopped: Boolean = false) : ImportState
}

/**
 * Trae listas de Spotify: busca cada canción en YouTube Music y guarda cada lista en cuanto acaba (si se corta a
 * medias, lo hecho no se pierde y volver a importarla la rehace en su sitio). Vive en la app, no en la pantalla:
 * se puede cerrar el Menú mientras tanto.
 */
class Importer(private val prefs: Prefs, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var job: Job? = null

    // La misma canción suele estar en varias listas: se busca una vez. null = se buscó y no está.
    private val found = ConcurrentHashMap<String, Result<Heard?>>()

    fun start(lists: List<SourceList>) {
        if (job?.isActive == true || lists.isEmpty()) return
        job = scope.launch(Dispatchers.IO) { run(lists) }
    }

    fun stop() {
        job?.cancel()
    }

    /** Olvidar el resumen de la última vez. */
    fun dismiss() {
        if (job?.isActive != true) _state.value = ImportState.Idle
    }

    private suspend fun run(lists: List<SourceList>) {
        val total = lists.sumOf { it.tracks.size }
        val missing = mutableListOf<Missing>()
        var done = 0
        var songs = 0
        var saved = 0
        val gate = Semaphore(PARALLEL)
        try {
            lists.forEachIndexed { i, l ->
                _state.value = ImportState.Running(l.name, i, lists.size, done, total, missing.size)
                val heard = coroutineScope {
                    l.tracks.map { t ->
                        async {
                            val h = gate.withPermit { find(t) }
                            _state.update { s -> if (s is ImportState.Running) s.copy(done = s.done + 1, missing = s.missing + if (h == null) 1 else 0) else s }
                            h
                        }
                    }.awaitAll()
                }
                done += l.tracks.size
                l.tracks.forEachIndexed { j, t -> if (heard[j] == null) missing += Missing(l.name, t) }
                val ok = heard.filterNotNull()
                when {
                    l.liked -> prefs.addLikedAll(ok)
                    ok.isNotEmpty() -> prefs.importPlaylist(l.id, l.name, ok, at = saved++)
                }
                songs += ok.size
            }
            _state.value = ImportState.Done(lists.size, songs, missing)
        } catch (e: CancellationException) {
            _state.value = ImportState.Done(saved, songs, missing, stopped = true)
            throw e
        }
    }

    /** Con un par de reintentos: con miles de búsquedas, alguna falla por la red sin que la canción falte. */
    private suspend fun find(t: SourceTrack): Heard? {
        val key = Matcher.norm(t.title) + "|" + Matcher.norm(t.artists.firstOrNull().orEmpty())
        found[key]?.let { if (it.isSuccess) return it.getOrNull() }
        var r = Matcher.find(t)
        for (wait in RETRY_MS) {
            if (r.isSuccess) break
            delay(wait)
            r = Matcher.find(t)
        }
        r.exceptionOrNull()?.let { Timber.w(it, "Importar: falló la búsqueda de %s", t.title) }
        val h = r.map { it?.song?.toHeard() }
        found[key] = h
        return h.getOrNull()
    }

    private companion object {
        const val PARALLEL = 6
        val RETRY_MS = listOf(1000L, 4000L)
    }
}
