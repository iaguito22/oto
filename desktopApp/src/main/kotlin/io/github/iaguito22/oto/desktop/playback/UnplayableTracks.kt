package io.github.iaguito22.oto.desktop.playback

/**
 * Canciones que YouTube no deja reproducir («This track is unavailable», sin clientes que sirvan). Antes una así dejaba
 * Oto parado (10 min en la noche del 23/11): ahora se salta. Si fallan varias seguidas, el problema es YouTube (un
 * bloqueo, sin red): se para con aviso en vez de recorrer la cola entera.
 */
internal object UnplayableTracks {
    const val MAX_SKIPS_IN_A_ROW = 3

    /** Falla la canción (no la red): la extracción respondió, pero sin audio que sirva. */
    fun isUnplayable(error: Throwable): Boolean {
        if (error is java.io.IOException) return false
        val name = error::class.simpleName.orEmpty()
        val message = error.message.orEmpty().lowercase()
        return name == "StreamResolveException" || "unavailable" in message || "no playable" in message
    }

    /** Saltar a la siguiente tras [failuresInARow] canciones seguidas sin audio (contando esta). */
    fun shouldSkip(failuresInARow: Int, hasNext: Boolean): Boolean = hasNext && failuresInARow <= MAX_SKIPS_IN_A_ROW

    /** La primera de [ids] después de [current] que no se sabe sin audio; -1 si no hay. */
    fun nextPlayable(ids: List<String>, current: Int, unplayable: Set<String>): Int {
        for (i in current + 1 until ids.size) if (ids[i] !in unplayable) return i
        return -1
    }
}
