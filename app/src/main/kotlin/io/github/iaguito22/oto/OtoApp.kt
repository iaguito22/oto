package io.github.iaguito22.oto

import android.app.Application
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

class OtoApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        val system = Locale.getDefault()
        YouTube.locale = YouTubeLocale(gl = system.country.ifBlank { "ES" }, hl = system.language.ifBlank { "es" })

        // Sin visitorData, YouTube responde como a un visitante nuevo en cada petición y las recomendaciones salen peores.
        scope.launch(Dispatchers.IO) {
            YouTube.visitorData()
                .onSuccess { YouTube.visitorData = it }
                .onFailure { Timber.w(it, "No se pudo pedir visitorData") }
        }
    }
}
