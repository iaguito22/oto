package io.github.iaguito22.oto.playback

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionPlaybackTest {
    @Test fun laListaActivaPausaYReanudaSinReiniciarLaCola() {
        val source = Source("DESDE TU LISTA", "Nombre anterior", local = "lista")
        assertEquals(CollectionPlayback.Pause, collectionPlayback(source, "pista", true, localId = "lista"))
        assertEquals(CollectionPlayback.Resume, collectionPlayback(source, "pista", false, localId = "lista"))
        assertEquals(CollectionPlayback.Start, collectionPlayback(source, "pista", true, localId = "otra"))
        assertEquals(CollectionPlayback.Start, collectionPlayback(source, null, false, localId = "lista"))
    }

    @Test fun distingueColeccionesPorTipoEIdAunqueCambienSusMetadatos() {
        val album = AlbumItem("coleccion", "PL1", title = "Álbum", artists = null, thumbnail = "")
        val playlist = PlaylistItem("coleccion", "Lista", null, null, null, null, null, null)
        val source = Source("DEL ÁLBUM", album.title, item = album)
        assertEquals(CollectionPlayback.Pause, collectionPlayback(source, "pista", true, item = album.copy(title = "Otro título")))
        assertEquals(CollectionPlayback.Start, collectionPlayback(source, "pista", true, item = playlist))
        assertEquals(CollectionPlayback.Start, collectionPlayback(source, "pista", true, localId = album.id))
        assertEquals(CollectionPlayback.Resume, collectionPlayback(Source("DE LA PLAYLIST", playlist.title, item = playlist), "pista", false, item = playlist))
    }
}
