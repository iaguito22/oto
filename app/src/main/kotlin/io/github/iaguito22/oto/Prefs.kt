package io.github.iaguito22.oto

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.store by preferencesDataStore("oto")
private val TUTORIAL_DONE = booleanPreferencesKey("tutorial_done")
private val HISTORY = stringPreferencesKey("history")

/** Una canción oída. Lo justo para enseñarla y volver a ponerla. */
data class Heard(val id: String, val title: String, val artist: String, val artwork: String?)

/** Lo poco que la app recuerda entre sesiones (la biblioteca de verdad, con Room, es la fase 6). */
class Prefs(private val context: Context) {
    val tutorialDone: Flow<Boolean> = context.store.data.map { it[TUTORIAL_DONE] ?: false }

    suspend fun setTutorialDone(done: Boolean) {
        context.store.edit { it[TUTORIAL_DONE] = done }
    }

    val history: Flow<List<Heard>> = context.store.data.map { decode(it[HISTORY]) }

    /** La más reciente primero, sin repetidas, hasta [HISTORY_SIZE]. */
    suspend fun addHeard(h: Heard) {
        context.store.edit { p ->
            val list = listOf(h) + decode(p[HISTORY]).filter { it.id != h.id }
            p[HISTORY] = encode(list.take(HISTORY_SIZE))
        }
    }

    private fun encode(list: List<Heard>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("t", it.title).put("a", it.artist).put("i", it.artwork)) }
    }.toString()

    private fun decode(s: String?): List<Heard> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Heard(o.getString("id"), o.optString("t"), o.optString("a"), o.optString("i").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val HISTORY_SIZE = 50
    }
}
