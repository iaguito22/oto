package io.github.iaguito22.oto.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.iaguito22.oto.Prefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtoUpdatesTest {
    @Test
    fun comparesSemanticVersionsNumerically() {
        assertTrue(isNewerStableVersion("v2.10.0", "2.9.99"))
        assertTrue(isNewerStableVersion("v10.0.0", "9.99.99"))
        assertTrue(isNewerStableVersion("v2.2.1", "2.2.0"))
        assertTrue(isNewerStableVersion("v2.2.0", "2.2.0-rc.1+build"))
        assertFalse(isNewerStableVersion("v2.2.0+new-build", "2.2.0"))
        assertFalse(isNewerStableVersion("v2.2.0", "2.2.0+old-build"))
        assertFalse(isNewerStableVersion("v2.1.9", "2.2.0"))
        assertFalse(isNewerStableVersion("v3.0.0-rc.1", "2.2.0"))
        assertFalse(isNewerStableVersion("v3.0", "2.2.0"))
        assertFalse(isNewerStableVersion("v03.0.0", "2.2.0"))
        assertFalse(isNewerStableVersion("v3.0.0", "sin-version"))
    }

    @Test
    fun selectsThePublishedAssetForEachPlatform() {
        val msi = "Oto-3.0.0-windows-x64.msi"
        val deb = "oto_3.0.0_amd64.deb"
        val arch = "oto-3.0.0-1-x86_64.pkg.tar.zst"
        val apk = "Oto-3.0.0-android.apk"
        val json = fixture(
            "Oto-3.0.0-Setup.exe", "Oto-3.0.0-android-phone.apk",
            "Oto-3.0.0-android12.apk", msi, deb, arch, apk,
        )
        assertEquals(msi, newerRelease(json, "2.2.0", UpdatePlatform.Windows)?.asset?.name)
        assertEquals(deb, newerRelease(json, "2.2.0", UpdatePlatform.Debian)?.asset?.name)
        assertEquals(arch, newerRelease(json, "2.2.0", UpdatePlatform.Arch)?.asset?.name)
        assertEquals(apk, newerRelease(json, "2.2.0", UpdatePlatform.Android)?.asset?.name)
        assertEquals(arch, newerRelease(fixture(arch), "2.2.0", UpdatePlatform.Debian, UpdatePlatform.Arch)?.asset?.name)
        assertNull(newerRelease(fixture(msi), "2.2.0", UpdatePlatform.Android))
        assertNull(newerRelease(fixture(msi), "2.2.0", UpdatePlatform.Debian, UpdatePlatform.Arch))
    }

    @Test
    fun ignoresDraftsPrereleasesAndUnverifiableInstallers() {
        val msi = "Oto-3.0.0-windows-x64.msi"
        val json = fixture(msi)
        assertNull(newerRelease(JSONObject(json).put("prerelease", true).toString(), "2.2.0", UpdatePlatform.Windows))
        assertNull(newerRelease(JSONObject(json).put("draft", true).toString(), "2.2.0", UpdatePlatform.Windows))
        assertNull(newerRelease(json.replace("sha256:", ""), "2.2.0", UpdatePlatform.Windows))
        assertNull(newerRelease(json.replace("github.com", "example.org"), "2.2.0", UpdatePlatform.Windows))
        assertNull(newerRelease(json, "3.0.0", UpdatePlatform.Windows))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun delaysStartupAndRetainsTheCheckIntervalAcrossRestarts() = runTest {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val prefs = Prefs(store, store)
        var calls = 0
        var shown: OtoRelease? = null
        fun start() = backgroundScope.launch {
            OtoUpdates(prefs, "2.2.0", UpdatePlatform.Windows, now = { testScheduler.currentTime }, fetch = {
                calls++
                if (calls > 1) error("Sin conexión")
                fixture("Oto-3.0.0-windows-x64.msi")
            }).observe { shown = it }
        }
        val first = start()
        advanceTimeBy(4_999)
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("3.0.0", shown?.version)
        first.cancelAndJoin()
        shown = null
        start()
        advanceTimeBy(5_001)
        assertEquals("3.0.0", shown?.version)
        assertEquals(1, calls)
        prefs.setCheckUpdates(false)
        runCurrent()
        assertNull(shown)
        advanceTimeBy(6 * 60 * 60 * 1_000L)
        assertEquals(1, calls)
        prefs.setCheckUpdates(true)
        advanceTimeBy(5_001)
        assertEquals(2, calls)
        assertNull(shown)
    }

    private fun fixture(vararg names: String): String = JSONObject()
        .put("draft", false)
        .put("prerelease", false)
        .put("tag_name", "v3.0.0")
        .put("assets", JSONArray(names.map { name ->
            JSONObject()
                .put("name", name)
                .put("browser_download_url", "https://github.com/iaguito22/oto/releases/download/v3.0.0/$name")
                .put("size", 1024)
                .put("digest", "sha256:${"f".repeat(64)}")
        }))
        .toString()
}
