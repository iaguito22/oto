package io.github.iaguito22.oto.ui.sonda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
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
import kotlin.time.TimeSource

data class SondaState(
    val query: String = "",
    val loading: Boolean = false,
    val songs: List<SongItem> = emptyList(),
    val error: String? = null,
    val tookMs: Long? = null,
)

@OptIn(FlowPreview::class)
class SondaViewModel : ViewModel() {
    private val _state = MutableStateFlow(SondaState())
    val state: StateFlow<SondaState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.map { it.query.trim() }
                .distinctUntilChanged()
                .debounce(350)
                .filter { it.length >= 2 }
                .collect(::search)
        }
    }

    fun onQuery(q: String) = _state.update { it.copy(query = q, error = null) }

    private suspend fun search(q: String) {
        _state.update { it.copy(loading = true, error = null) }
        val start = TimeSource.Monotonic.markNow()
        val result = withContext(Dispatchers.IO) { YouTube.search(q, YouTube.SearchFilter.FILTER_SONG) }
        val took = start.elapsedNow().inWholeMilliseconds
        if (_state.value.query.trim() != q) return
        result
            .onSuccess { r -> _state.update { it.copy(loading = false, songs = r.items.filterIsInstance<SongItem>(), tookMs = took) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e::class.simpleName, tookMs = took) } }
    }
}
