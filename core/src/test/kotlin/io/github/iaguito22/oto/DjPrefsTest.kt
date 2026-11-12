package io.github.iaguito22.oto

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.dj.DjMode
import io.github.iaguito22.oto.dj.DjMood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DjPrefsTest {
    @Test
    fun smartMixMoodAndModePersistWithSensibleDefaults() = runBlocking {
        val dir = Files.createTempDirectory("oto-dj-prefs").toFile()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun createPrefs() = Prefs(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "main.preferences_pb") }),
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "lists.preferences_pb") }),
        )
        try {
            var prefs = createPrefs()
            // Defaults sensatos y compatibilidad con crossfade existente.
            assertEquals(DEFAULT_CROSSFADE, prefs.crossfade.first())
            assertTrue(prefs.smartMix.first())
            assertEquals(DjMood.Auto, prefs.djMood.first())
            assertEquals(DjMode.Normal, prefs.djMode.first())

            prefs.setCrossfade(6)
            prefs.setSmartMix(false)
            prefs.setDjMood(DjMood.Chill)
            prefs.setDjMode(DjMode.Adhd)

            scope.coroutineContext[Job]?.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            prefs = createPrefs()

            assertEquals(6, prefs.crossfade.first())
            assertFalse(prefs.smartMix.first())
            assertEquals(DjMood.Chill, prefs.djMood.first())
            assertEquals(DjMode.Adhd, prefs.djMode.first())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
