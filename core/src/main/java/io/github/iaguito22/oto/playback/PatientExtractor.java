package io.github.iaguito22.oto.playback;

import com.metrolist.innertubex.InnerTube;
import com.metrolist.innertubex.InnerTubeLogger;
import com.metrolist.innertubex.extraction.ExtractionCipherService;
import com.metrolist.innertubex.extraction.InnerTubeExtractor;
import com.metrolist.innertubex.extraction.PlayerClientDirector;
import com.metrolist.innertubex.extraction.TokenProvider;
import com.metrolist.innertubex.extraction.YtConfigParser;
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor;
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy;
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog;

import kotlin.time.Clock;

/**
 * El extractor de InnerTubeX con más paciencia por petición que sus 8 s: la respuesta de /player pesa y, con poca
 * cobertura, no llegaba a tiempo; se cortaba, se volvía a pedir y la canción no empezaba nunca.
 *
 * En Java porque el director de clientes y ese constructor son {@code internal} en Kotlin (en el bytecode, públicos).
 * Al no poder usar desde Java los parámetros por defecto, el resto se pasa con los mismos valores que pondría Kotlin.
 */
final class PatientExtractor {
    // Los valores por defecto de InnerTubeX v0.10.0 (leídos de su bytecode): dos peticiones por cliente automático y
    // 8 s para la credencial de TV, que Oto no usa.
    private static final int MAX_PLAYER_REQUESTS = PlaybackClientCatalog.INSTANCE.getAutomaticManifests().size() * 2;
    private static final long TV_BEARER_TIMEOUT_MS = 8_000L;

    private PatientExtractor() {}

    static InnerTubeExtractor create(
            YtConfigParser configParser,
            ExtractionCipherService cipher,
            InnerTube innerTube,
            TokenProvider tokenProvider,
            InnerTubeLogger logger,
            long playerRequestTimeoutMs,
            long visitorDataTimeoutMs) {
        PlayerClientDirector director = new PlayerClientDirector(
                innerTube, new ContentAwareFallbackStrategy(), tokenProvider, ClientHealthMonitor.Companion.getNONE(), logger,
                playerRequestTimeoutMs, visitorDataTimeoutMs, MAX_PLAYER_REQUESTS);
        return new InnerTubeExtractor(
                configParser, director, cipher, innerTube, tokenProvider, logger, TV_BEARER_TIMEOUT_MS, () -> Clock.System.INSTANCE.now());
    }
}
