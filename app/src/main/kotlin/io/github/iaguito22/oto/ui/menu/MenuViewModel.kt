package io.github.iaguito22.oto.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import io.github.iaguito22.oto.LocalPlaylist
import kotlinx.coroutines.flow.StateFlow

class MenuViewModel : ViewModel() {
    private val controller = MenuController(viewModelScope)
    val state: StateFlow<MenuState> get() = controller.state
    var taste: Taste
        get() = controller.taste
        set(value) { controller.taste = value }

    fun onQuery(q: String) = controller.onQuery(q)
    fun onFilter(f: Filter) = controller.onFilter(f)
    fun loadHome() = controller.loadHome()
    fun onChip(title: String) = controller.onChip(title)
    suspend fun songsOf(item: YTItem): List<SongItem>? = controller.songsOf(item)
    fun open(item: YTItem) = controller.open(item)
    fun openArtist(id: String, name: String) = controller.openArtist(id, name)
    fun openLocal(id: String) = controller.openLocal(id)
    fun openDownloads() = controller.openDownloads()
    fun openLists(arranging: Boolean = false) = controller.openLists(arranging)
    fun openSmart(list: LocalPlaylist) = controller.openSmart(list)
    fun openFolder(name: String) = controller.openFolder(name)
    fun renameFolder(old: String, name: String) = controller.renameFolder(old, name)
    fun closeDetail() = controller.closeDetail()
    fun closeAll() = controller.closeAll()
}
