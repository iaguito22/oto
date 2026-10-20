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

/** Lo que se abre dentro del Menú, apilado: atrás vuelve al anterior (un álbum abierto desde un artista, por ejemplo). */
sealed interface Page {
    /** Un álbum, una playlist o un artista de YouTube Music. [shelves]: los carruseles del artista (álbumes, sencillos…). */
    data class Remote(
        val item: YTItem,
        val songs: List<SongItem>? = null,
        val shelves: List<Shelf> = emptyList(),
        val sub: String? = null,
        val error: String? = null,
    ) : Page

    /** Una lista tuya, por su id: el contenido se lee de Prefs, que es quien la cambia. */
    data class Local(val id: String) : Page
}

data class MenuState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<YTItem> = emptyList(),
    val searchError: String? = null,
    val shelves: List<Shelf>? = null,
    val homeError: String? = null,
    val pages: List<Page> = emptyList(),
)

@OptIn(FlowPreview::class)
class MenuViewModel : ViewModel() {
    private val _state = MutableStateFlow(MenuState())
    val state: StateFlow<MenuState> = _state.asStateFlow()

    /** Lo tuyo, para ordenar lo que sale al buscar. Lo pone el Menú desde Prefs. */
    @Volatile var taste = Taste()

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
        _state.update { it.copy(pages = it.pages + Page.Remote(item)) }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { load(item) }
            _state.update { s ->
                s.copy(pages = s.pages.map { p -> if (p is Page.Remote && p.item == item && p.songs == null && p.error == null) loaded else p })
            }
        }
    }

    fun openArtist(id: String, name: String) =
        open(ArtistItem(id = id, title = name, thumbnail = null, shuffleEndpoint = null, radioEndpoint = null))

    fun openLocal(id: String) = _state.update { it.copy(pages = it.pages + Page.Local(id)) }

    /** Atrás: la página anterior. */
    fun closeDetail() = _state.update { it.copy(pages = it.pages.dropLast(1)) }

    fun closeAll() = _state.update { it.copy(pages = emptyList()) }

    private suspend fun load(item: YTItem): Page.Remote {
        val r: Result<Page.Remote> = when (item) {
            is AlbumItem -> YouTube.album(item.browseId).map { Page.Remote(item, it.songs) }
            is PlaylistItem -> YouTube.playlist(item.id).map { Page.Remote(item, it.songs) }
            is ArtistItem -> YouTube.artist(item.id).map { page ->
                // Las canciones más oídas son la lista que se reproduce; el resto, carruseles como en Explorar.
                val top = page.sections.firstOrNull { s -> s.items.isNotEmpty() && s.items.all { it is SongItem } }
                val shelves = page.sections.filter { it !== top }.mapNotNull { s ->
                    val items = s.items.filter { it is SongItem || it is AlbumItem || it is PlaylistItem || it is ArtistItem }
                    if (items.isEmpty()) null else Shelf(s.title, items)
                }
                Page.Remote(
                    page.artist.copy(title = page.artist.title.ifEmpty { item.title }),
                    top?.items?.filterIsInstance<SongItem>().orEmpty(),
                    shelves,
                    sub = page.monthlyListenerCount ?: page.subscriberCountText,
                )
            }
            else -> Result.success(Page.Remote(item, emptyList()))
        }
        return r.getOrElse { e -> Page.Remote(item, error = e.message ?: e::class.simpleName) }
    }

    private suspend fun search(q: String) {
        _state.update { it.copy(searching = true, searchError = null) }
        val result = withContext(Dispatchers.IO) { Search.run(q, taste) }
        if (_state.value.query.trim() != q) return
        result
            .onSuccess { r -> _state.update { it.copy(searching = false, results = r) } }
            .onFailure { e -> _state.update { it.copy(searching = false, searchError = e.message ?: e::class.simpleName) } }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
