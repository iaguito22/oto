package io.github.iaguito22.oto.account

import org.json.JSONArray
import org.json.JSONObject
import io.github.iaguito22.oto.LIKED_ID

const val YOUTUBE_LIKES_LIST_ID = "youtube:LM"

/** La ausencia de selección nunca autoriza a subir ni recuperar listas. */
data class CloudSelection(val imports: Set<String> = emptySet(), val exports: Set<String> = emptySet()) {
    fun separated(): CloudSelection = copy(exports=exports - YOUTUBE_LIKES_LIST_ID)
    fun encode(): String = JSONObject().put("version",2).put("imports", JSONArray(imports.sorted())).put("exports", JSONArray(separated().exports.sorted())).toString()
    companion object {
        fun decode(raw: String?): CloudSelection? = runCatching {
            val obj = JSONObject(raw ?: return null)
            fun ids(key: String): Set<String> {
                val a = obj.getJSONArray(key)
                return (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }.toSet()
            }
            val exports=ids("exports").let { if(obj.optInt("version")<2) it-LIKED_ID else it }
            CloudSelection(ids("imports"), exports).separated()
        }.getOrNull()
    }
}
