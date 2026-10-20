package io.github.iaguito22.oto

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import io.github.iaguito22.oto.account.Account
import io.github.iaguito22.oto.importer.Importer
import io.github.iaguito22.oto.playback.InnerTubeXPlayer
import io.github.iaguito22.oto.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Locale

class OtoApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val player by lazy { PlayerConnection(this) }
    val prefs by lazy { Prefs(this) }
    val importer by lazy { Importer(prefs, scope) }
    val account by lazy { Account(this, prefs, scope) }

    // Una SimpleCache por carpeta y proceso: vive aquí y no en el servicio, que puede recrearse.
    @get:OptIn(UnstableApi::class)
    val audioCache by lazy {
        SimpleCache(File(cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(AUDIO_CACHE_BYTES), StandaloneDatabaseProvider(this))
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        InnerTubeXPlayer.initialize(this)

        val system = Locale.getDefault()
        YouTube.locale = YouTubeLocale(gl = system.country.ifBlank { "ES" }, hl = system.language.ifBlank { "es" })

        // Con cuenta, su sesión. Sin visitorData, YouTube responde como a un visitante nuevo en cada petición y las
        // recomendaciones salen peores.
        if (account.restore() == null) scope.launch(Dispatchers.IO) {
            YouTube.visitorData()
                .onSuccess { YouTube.visitorData = it }
                .onFailure { Timber.w(it, "No se pudo pedir visitorData") }
        }
    }

    private companion object {
        const val AUDIO_CACHE_BYTES = 1L shl 30 // 1 GB: unas 200 canciones
    }
}
