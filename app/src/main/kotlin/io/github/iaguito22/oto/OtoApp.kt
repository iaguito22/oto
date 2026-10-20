package io.github.iaguito22.oto

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import io.github.iaguito22.oto.account.Account
import io.github.iaguito22.oto.account.Cloud
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.YouTubeCatalog
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

class OtoApp : Application(), SingletonImageLoader.Factory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val player by lazy { PlayerConnection(this, prefs, scope) }
    val prefs by lazy { Prefs(this) }
    val importer by lazy { Importer(prefs, scope) }
    val account by lazy { Account(this, prefs, scope) }
    val cloud by lazy { Cloud(prefs, account, scope) }
    /** Lo que vas escuchando, para el DJ (lo escribe el servicio). */
    val plays by lazy { Plays(File(filesDir, "plays.jsonl")) }
    val djCatalog by lazy { YouTubeCatalog(File(cacheDir, "dj")) }

    // Una SimpleCache por carpeta y proceso: vive aquí y no en el servicio, que puede recrearse.
    @get:OptIn(UnstableApi::class)
    val audioCache by lazy {
        SimpleCache(File(cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(Storage.AUDIO_BYTES), StandaloneDatabaseProvider(this))
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        InnerTubeXPlayer.initialize(this)

        val system = Locale.getDefault()
        YouTube.locale = YouTubeLocale(gl = system.country.ifBlank { "ES" }, hl = system.language.ifBlank { "es" })

        // Con cuenta, su sesión. Sin visitorData, YouTube responde como a un visitante nuevo en cada petición y las
        // recomendaciones salen peores; y sin él no hay cliente que dé el audio. Se guarda: pedirlo cada vez al abrir
        // fallaba con poca cobertura (InnerTubeX le da 15 s) y entonces no sonaba nada.
        if (account.restore() == null) {
            val visitor = getSharedPreferences("oto_visitor", MODE_PRIVATE)
            val saved = visitor.getString("visitorData", null)
            if (saved != null) YouTube.visitorData = saved
            else scope.launch(Dispatchers.IO) {
                YouTube.visitorData()
                    .onSuccess { YouTube.visitorData = it; visitor.edit().putString("visitorData", it).apply() }
                    .onFailure { Timber.w(it, "No se pudo pedir visitorData") }
            }
        }
        cloud.start()
        scope.launch(Dispatchers.IO) { Storage.trimDj(File(cacheDir, "dj")) }
    }

    // Las carátulas, con su tope (ver [Storage]): por defecto Coil se queda un 2 % del disco.
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "coil3_disk_cache").toOkioPath()).maxSizeBytes(Storage.IMAGES_BYTES).build() }
        .build()
}
