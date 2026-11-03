package io.github.iaguito22.oto.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.innertube.YouTube
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

internal fun sameKnownAccount(previous: Session?, incoming: Session): Boolean =
    previous != null && previous.dataSyncId.isNotBlank() && previous.dataSyncId == incoming.dataSyncId && previous.authUser == incoming.authUser

private val SESSION = stringPreferencesKey("session")

/**
 * Tu cuenta de YouTube Music, si has entrado. Con ella la portada y la radio salen de tus gustos (el «algoritmo»), lo
 * que oyes cuenta en tu historial de YouTube. Los Me gusta de Oto permanecen separados. Sin ella, Oto
 * funciona igual, pero como un visitante.
 */
class Account(
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val accountStore: DataStore<Preferences> = OtoDirs.dataStore("oto_account"),
    private val onClearWebCookies: (suspend () -> Unit)? = null,
) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Al arrancar, antes de la primera petición: si no, la portada saldría anónima. */
    fun restore(): Session? {
        val s = runCatching { runBlocking { accountStore.data.first()[SESSION]?.let(::decode) } }.getOrNull()
        apply(s)
        return s
    }

    suspend fun signIn(s: Session) {
        if (!sameKnownAccount(_session.value, s)) {
            prefs.setCloudSelection(null)
            prefs.setCloud(null)
        }
        apply(s)
        // Con la sesión puesta, YouTube dice quién eres.
        val info = withContext(Dispatchers.IO) { YouTube.accountInfo() }.getOrNull()
        val full = s.copy(name = info?.name.orEmpty(), email = info?.email, photo = info?.thumbnailUrl)
        accountStore.edit { it[SESSION] = encode(full) }
        _session.value = full
    }

    suspend fun signOut() {
        accountStore.edit { it.remove(SESSION) }
        prefs.clearLikesSent()
        onClearWebCookies?.invoke()
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

}
