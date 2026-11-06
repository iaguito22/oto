package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileWeightTest {
    private fun songs(artist: String, n: Int) = (1..n).map { Heard("$artist-$it", "$artist $it", artist, null, artist) }

    /** Guardar en una lista pesa más que darle a Me gusta. */
    @Test fun listsWeighMoreThanLikes() {
        val listed = songs("EnListas", 6)
        val liked = songs("SoloMeGusta", 6)
        val p = Profile(listOf(LocalPlaylist("l", "Mía", listed)), liked, emptyList(), emptyList(), 0L)
        val a = p.find("EnListas", "EnListas")!!
        val b = p.find("SoloMeGusta", "SoloMeGusta")!!
        assertTrue("${a.affinity} > ${b.affinity}", a.affinity > b.affinity)
        assertTrue(p.weight.getValue(listed[0].toTrack().key) > p.weight.getValue(liked[0].toTrack().key))
        assertTrue(p.core.first() === a)
    }
}
