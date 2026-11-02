package io.github.iaguito22.oto.account

import org.json.JSONArray
import org.json.JSONObject

/** La ausencia de selección nunca autoriza a subir ni recuperar listas. */
data class CloudSelection(val imports: Set<String> = emptySet(), val exports: Set<String> = emptySet()) {
    fun encode(): String = JSONObject().put("imports", JSONArray(imports.sorted())).put("exports", JSONArray(exports.sorted())).toString()
    companion object {
        fun decode(raw: String?): CloudSelection? = runCatching {
            val obj = JSONObject(raw ?: return null)
            fun ids(key: String): Set<String> {
                val a = obj.getJSONArray(key)
                return (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }.toSet()
            }
            CloudSelection(ids("imports"), ids("exports"))
        }.getOrNull()
    }
}
