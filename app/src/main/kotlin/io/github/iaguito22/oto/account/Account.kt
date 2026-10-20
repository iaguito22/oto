package io.github.iaguito22.oto.account

import android.content.Context
import android.webkit.CookieManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metrolist.innertube.YouTube
import io.github.iaguito22.oto.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/** Lo que hace falta para hablar con YouTube Music como tú: las cookies de la sesión y quién eres. */
data class Session(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val authUser: String,
    val name: String = "",
    val email: String? = null,
    val photo: String? = null,
)

// Aparte de las preferencias: es una credencial y no tiene por qué viajar con nada más.
private val Context.accountStore by preferencesDataStore("oto_account")
private val SESSION = stringPreferencesKey("session")

/**
 * Tu cuenta de YouTube Music, si has entrado. Con ella la portada y la radio salen de tus gustos (el «algoritmo»), lo
 * que oyes cuenta en tu historial de YouTube y lo que te gusta en Oto también le gusta a YouTube Music. Sin ella, Oto
 * funciona igual, pero como un visitante.
 */
class Account(private val context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private var likes: Job? = null

    /** Al arrancar, antes de la primera petición: si no, la portada saldría anónima. */
    fun restore(): Session? {
        val s = runCatching { runBlocking { context.accountStore.data.first()[SESSION]?.let(::decode) } }.getOrNull()
        apply(s)
        return s
    }

    suspend fun signIn(s: Session) {
        apply(s)
        // Con la sesión puesta, YouTube dice quién eres.
        val info = withContext(Dispatchers.IO) { YouTube.accountInfo() }.getOrNull()
        val full = s.copy(name = info?.name.orEmpty(), email = info?.email, photo = info?.thumbnailUrl)
        context.accountStore.edit { it[SESSION] = encode(full) }
        _session.value = full
    }

    suspend fun signOut() {
        context.accountStore.edit { it.remove(SESSION) }
        prefs.clearLikesSent()
        withContext(Dispatchers.Main) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        apply(null)
        // Sin cuenta, otra vez un visitante con su visitorData.
        withContext(Dispatchers.IO) { YouTube.visitorData() }.onSuccess { YouTube.visitorData = it }
    }

    private fun apply(s: Session?) {
        YouTube.cookie = s?.cookie
        YouTube.dataSyncId = s?.dataSyncId
        YouTube.authUser = s?.authUser ?: "0"
        if (s != null) YouTube.visitorData = s.visitorData
        // La portada y la radio («next») van con la cuenta solo si esto está puesto.
        YouTube.useLoginForBrowse = s != null
        _session.value = s
        likes?.cancel()
        likes = if (s != null) scope.launch { syncLikes() } else null
    }

    /**
     * Lo que te gusta en Oto, también en YouTube Music: es la señal más clara para sus recomendaciones. Solo los
     * cambios desde que se entra (lo de antes, incluido lo importado de Spotify, se manda una vez, poco a poco).
     */
    private suspend fun syncLikes() {
        var before: Set<String>? = null
        prefs.likedIds.collect { now ->
            val sent = prefs.likesSent()
            val add = now - sent
            val remove = if (before == null) emptySet() else (before!! - now) intersect sent
            before = now
            for (id in add) {
                if (withContext(Dispatchers.IO) { YouTube.likeVideo(id, true) }.isFailure) { Timber.w("No se pudo dar me gusta en YouTube a %s", id); break }
                prefs.markLikeSent(id, true)
                delay(LIKE_PACE_MS)
            }
            for (id in remove) {
                if (withContext(Dispatchers.IO) { YouTube.likeVideo(id, false) }.isSuccess) prefs.markLikeSent(id, false)
            }
        }
    }

    private fun encode(s: Session) = JSONObject()
        .put("cookie", s.cookie).put("visitorData", s.visitorData).put("dataSyncId", s.dataSyncId).put("authUser", s.authUser)
        .put("name", s.name).put("email", s.email).put("photo", s.photo)
        .toString()

    private fun decode(t: String): Session? = runCatching {
        val o = JSONObject(t)
        Session(
            o.getString("cookie"), o.getString("visitorData"), o.getString("dataSyncId"), o.optString("authUser", "0"),
            o.optString("name"), o.optString("email").ifEmpty { null }, o.optString("photo").ifEmpty { null },
        )
    }.getOrNull()

    private companion object {
        const val LIKE_PACE_MS = 400L
    }
}
