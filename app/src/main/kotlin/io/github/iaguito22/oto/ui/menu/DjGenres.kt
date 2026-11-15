package io.github.iaguito22.oto.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.dj.DjGenre
import io.github.iaguito22.oto.dj.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Tus géneros para el DJ, de más a menos escuchado (ver [io.github.iaguito22.oto.dj.GenreStore.top]). Salen al momento
 * de lo ya guardado y, mientras se ve, se miran los que falten (poco a poco: iTunes limita las consultas).
 */
@Composable
fun rememberDjGenres(): List<DjGenre> {
    val app = LocalContext.current.applicationContext as OtoApp
    return produceState(emptyList<DjGenre>()) {
        val profile = withContext(Dispatchers.Default) {
            Profile(app.prefs.playlists.first(), app.prefs.liked.first(), app.prefs.history.first(), app.plays.all(), System.currentTimeMillis())
        }
        val genres = app.genres.ready()
        value = withContext(Dispatchers.Default) { genres.top(profile) }
        runCatching {
            genres.fill(profile, onFound = {
                // Recalcular es barato; se hace en este mismo hilo de la corrutina.
                value = genres.top(profile)
            })
        }
        value = withContext(Dispatchers.Default) { genres.top(profile) }
    }.value
}
