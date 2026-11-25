package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.DjTempoMatch

/**
 * Orden de lo que viene en la radio para que las mezclas puedan ir a tempo (Iago, 24/11: en la radio casi todo eran
 * fundidos; «se podría manipular como en DJ normal»). Son las mismas canciones y casi en el mismo orden: la radio va
 * ordenada por parecido a la canción de partida y ese estilo se conserva.
 */
internal object RadioTempoOrder {
    /** Cuántas de las siguientes se miran para adelantar una: más lejos ya se pierde el orden de la radio. */
    const val LOOKAHEAD = 4

    const val FITS = 0
    const val FITS_KEY_CLASH = 1
    const val UNKNOWN = 2
    const val FAR = 3

    /** Rango de una candidata tras otra: encaja en tempo y tono, solo en tempo, sin saber o lejos. */
    fun rankOf(verdict: DjTempoMatch.TempoVerdict, harmony: Double?): Int = when {
        verdict.fit == DjTempoMatch.TempoFit.Fits && (harmony ?: 1.0) >= DjTempoMatch.MIN_BEAT_MATCH_HARMONY -> FITS
        verdict.fit == DjTempoMatch.TempoFit.Fits -> FITS_KEY_CLASH
        verdict.fit == DjTempoMatch.TempoFit.Unknown || verdict.fit == DjTempoMatch.TempoFit.NotApplicable -> UNKNOWN
        else -> FAR
    }

    /**
     * Nuevo orden de [ids], que van detrás de [prevId]: en cada hueco, de las [LOOKAHEAD] siguientes la de mejor [rank]
     * respecto a la anterior; a igualdad, la que ya iba antes. Sin tempos medidos no cambia nada.
     */
    fun reorder(prevId: String, ids: List<String>, rank: (prev: String, id: String) -> Int): List<String> {
        val left = ids.toMutableList()
        val out = ArrayList<String>(ids.size)
        var prev = prevId
        while (left.isNotEmpty()) {
            val pick = left.take(LOOKAHEAD).minBy { rank(prev, it) }
            out += pick
            left.remove(pick)
            prev = pick
        }
        return out
    }
}
