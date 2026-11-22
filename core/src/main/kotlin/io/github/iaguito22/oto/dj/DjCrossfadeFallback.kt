package io.github.iaguito22.oto.dj

/** Ventana del fundido simple cuando el plan o puente del DJ no se puede ejecutar. */
object DjCrossfadeFallback {
    data class Window(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    fun window(
        durationMs: Long,
        exitMs: Long,
        configuredMs: Long,
        sections: List<SongSection>,
    ): Window? {
        val end = exitMs.coerceAtMost(durationMs)
        if (end <= 0L) return null
        val fade = minOf(configuredMs.takeIf { it > 0L } ?: 3_000L, 3_000L, end / 3)
        if (fade < 250L) return null
        val start = end - fade
        val safe = SongSections.safeExitAt(start, sections, durationMs, fade)
        val at = if (safe + fade <= durationMs) safe else durationMs - minOf(fade, 750L)
        return Window(at, minOf(at + fade, durationMs))
    }
}
