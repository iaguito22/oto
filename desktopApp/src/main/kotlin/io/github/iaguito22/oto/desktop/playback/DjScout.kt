package io.github.iaguito22.oto.desktop.playback

/**
 * Qué canción de la cola del DJ analizar por adelantado. El PC no precarga la cola (el móvil sí): sin esto el DJ solo
 * conocía el tempo, el tono y el volumen de lo ya sonado y elegía a ciegas (noche del 23/11). Con la cola analizada, la
 * revisión por tempo ([io.github.iaguito22.oto.dj.Dj.retuneUpcomingTempo]) puede cambiar las que no cuadran, y lo
 * analizado queda para siempre en la caché, así que cada sesión elige mejor.
 */
internal object DjScout {
    /** Canciones bajadas de YouTube para analizar por cada una que suena: cada una es una petición y unos MB. */
    const val SCOUTS_PER_TRACK = 3

    /**
     * La primera de [upcoming] (en orden: la más cercana antes) sin analizar ni en [skip]. Las descargadas no cuestan
     * red y van aunque no quede presupuesto. null: nada que hacer.
     */
    fun pick(
        upcoming: List<String>,
        skip: Set<String>,
        known: (String) -> Boolean,
        local: (String) -> Boolean,
        networkBudgetLeft: Int,
    ): String? = upcoming.firstOrNull { id ->
        id !in skip && !known(id) && (networkBudgetLeft > 0 || local(id))
    }
}
