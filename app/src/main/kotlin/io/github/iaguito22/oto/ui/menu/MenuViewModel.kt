package io.github.iaguito22.oto.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una fila de Explorar: un carrusel de la portada de YouTube Music. */
data class Shelf(val title: String, val items: List<YTItem>)

/** Un álbum o una playlist abiertos dentro del Menú. */
data class Detail(val item: YTItem, val songs: List<SongItem>? = null, val error: String? = null)

data class MenuState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<SongItem> = emptyList(),
    val searchError: String? = null,
    val shelves: List<Shelf>? = null,
    val homeError: String? = null,
    val detail: Detail? = null,
)

@OptIn(FlowPreview::class)
class MenuViewModel : ViewModel() {
    private val _state = MutableStateFlow(MenuState())
    val state: StateFlow<MenuState> = _state.asStateFlow()
    private var detailJob: Job? = null

    init {
        loadHome()
        viewModelScope.launch {
            _state.map { it.query.trim() }
                .distinctUntilChanged()
                .debounce(SEARCH_DEBOUNCE_MS)
                .filter { it.length >= 2 }
                .collect(::search)
        }
    }

    fun onQuery(q: String) = _state.update {
        if (q.isBlank()) it.copy(query = q, results = emptyList(), searchError = null, searching = false) else it.copy(query = q, searchError = null)
    }

    fun loadHome() {
        _state.update { it.copy(homeError = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { YouTube.home() }
                .onSuccess { page ->
                    val shelves = page.sections.mapNotNull { s ->
                        val items = s.items.filter { it is SongItem || it is AlbumItem || it is PlaylistItem || it is ArtistItem }
                        if (items.isEmpty()) null else Shelf(s.title, items)
                    }
                    _state.update { it.copy(shelves = shelves) }
                }
                .onFailure { e -> _state.update { it.copy(homeError = e.message ?: e::class.simpleName) } }
        }
    }

    fun open(item: YTItem) {
        detailJob?.cancel()
        _state.update { it.copy(detail = Detail(item)) }
        detailJob = viewModelScope.launch {
            val songs = withContext(Dispatchers.IO) {
                when (item) {
                    is AlbumItem -> YouTube.album(item.browseId).map { it.songs }
                    is PlaylistItem -> YouTube.playlist(item.id).map { it.songs }
                    else -> Result.success(emptyList())
                }
            }
            _state.update { s ->
                if (s.detail?.item != item) return@update s
                songs.fold({ s.copy(detail = Detail(item, it)) }, { e -> s.copy(detail = Detail(item, error = e.message ?: e::class.simpleName)) })
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        _state.update { it.copy(detail = null) }
    }

    private suspend fun search(q: String) {
        _state.update { it.copy(searching = true, searchError = null) }
        val result = withContext(Dispatchers.IO) { YouTube.search(q, YouTube.SearchFilter.FILTER_SONG) }
        if (_state.value.query.trim() != q) return
        result
            .onSuccess { r -> _state.update { it.copy(searching = false, results = r.items.filterIsInstance<SongItem>().distinctBy { it.id }) } }
            .onFailure { e -> _state.update { it.copy(searching = false, searchError = e.message ?: e::class.simpleName) } }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
