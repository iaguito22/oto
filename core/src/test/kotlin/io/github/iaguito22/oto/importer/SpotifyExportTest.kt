package io.github.iaguito22.oto.importer

import org.junit.Assert.*
import org.junit.Test

class SpotifyExportTest {
    @Test fun emptyExportDoesNotInventLists() {
        assertTrue(Spotify.parseExport("""{"oto":1,"lists":[]}""").isEmpty())
    }
    @Test fun invalidFilesAreRejected() {
        for (text in listOf("", "no es json", "{}", """{"oto":1}""")) {
            assertTrue(text, runCatching { Spotify.parseExport(text) }.isFailure)
        }
    }
    @Test fun validExportPreservesFolderAndTrackMetadata() {
        val source = Spotify.parseExport("""{"oto":1,"lists":[{"id":"spotify:playlist:codex","name":"Prueba ñ","folder":"Carpeta","tracks":[{"t":"Get Lucky","a":["Daft Punk"],"d":248000},{"t":"","a":[]}]}]}""").single()
        assertEquals("Prueba ñ", source.name)
        assertEquals("Carpeta", source.folder)
        assertEquals("Get Lucky", source.tracks.single().title)
        assertEquals(listOf("Daft Punk"), source.tracks.single().artists)
    }
}
