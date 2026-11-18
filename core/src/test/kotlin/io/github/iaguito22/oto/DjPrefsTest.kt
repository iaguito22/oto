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
            assertEquals(listOf(0, 5, 10, 15, 20), SMART_MIX_CHOICES)
            assertTrue(prefs.smartMix.first())
            assertEquals(DEFAULT_SMART_MIX_SECONDS, prefs.smartMixChoice.first())
            // Con la mezcla inteligente decide el DJ: la duración es solo una referencia.
            assertEquals(DEFAULT_SMART_MIX_SECONDS, prefs.mixSeconds.first())
            assertEquals(DjMood.Auto, prefs.djMood.first())
            assertEquals(DjMode.Normal, prefs.djMode.first())

            prefs.setCrossfade(6)
            prefs.setSmartMixChoice(15)
            assertTrue(prefs.smartMix.first())
            assertEquals(15, prefs.smartMixSeconds.first())
            assertEquals(15, prefs.smartMixChoice.first())
            assertEquals(DEFAULT_SMART_MIX_SECONDS, prefs.mixSeconds.first())
            // Un valor guardado con las opciones antiguas (6 s) se lleva a la opción más cercana.
            prefs.setSmartMixSeconds(6)
            assertEquals(5, prefs.smartMixChoice.first())
            assertEquals(DEFAULT_SMART_MIX_SECONDS, prefs.mixSeconds.first())

            // Apagar mezcla inteligente funciona de verdad y pasa a usar el fundido clásico configurado.
            prefs.setSmartMixChoice(0)
            assertFalse(prefs.smartMix.first())
            assertEquals(0, prefs.smartMixChoice.first())
            assertEquals(6, prefs.mixSeconds.first())

            prefs.setDjMood(DjMood.Chill)
            prefs.setDjMode(DjMode.Adhd)

            scope.coroutineContext[Job]?.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            prefs = createPrefs()

            assertEquals(6, prefs.crossfade.first())
            assertFalse(prefs.smartMix.first())
            assertEquals(DjMood.Chill, prefs.djMood.first())
            assertEquals(DjMode.Adhd, prefs.djMode.first())
            assertEquals(SmartMixMode.Off, prefs.smartMixMode.first())
            prefs.setSmartMixMode(SmartMixMode.FastDj)
            assertEquals(SmartMixMode.FastDj, prefs.smartMixMode.first())
            assertFalse(SmartMixMode.FastDj.enabled(false))
            assertTrue(SmartMixMode.FastDj.enabled(true))
            assertFalse(SmartMixMode.Off.enabled(true))
            assertTrue(SmartMixMode.Always.enabled(false))
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
