package io.github.iaguito22.oto.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import io.github.iaguito22.oto.dj.AudioAnalysisCache
import io.github.iaguito22.oto.dj.AudioAnalyzer
import io.github.iaguito22.oto.dj.AudioMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Analiza localmente audio ya cacheado o descargado usando `MediaExtractor` + `MediaCodec` (sin red) para obtener
 * BPM, fase de beat/downbeat, loudness RMS, rango dinámico, intro y drop antes incluso de que llegue la transición.
 */
@OptIn(UnstableApi::class)
class AndroidAudioAnalyzer(
    private val audioCache: Cache,
    private val downloads: Downloads,
    private val analysisCache: AudioAnalysisCache,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    // Cuántos bytes había la última vez que se analizó cada una: solo se repite si ahora hay bastante más.
    private val triedBytes = ConcurrentHashMap<String, Long>()

    /**
     * Devuelve métricas cacheadas o analiza la pista desde caché/descargas locales. Si lo guardado salió solo del
     * principio (lo que había al precargar) o de la curva en vivo, se vuelve a analizar cuando hay más audio.
     */
    suspend fun analyzeIfCached(trackId: String, cacheKey: String): AudioMetrics? {
        val existing = analysisCache.get(trackId)
        if (existing?.coversWholeSong == true) return existing
        if (!inFlight.add(trackId)) return analysisCache.get(trackId)
        return try {
            withContext(Dispatchers.Default) {
                val (sourceCache, key) = selectLocalSource(trackId, cacheKey) ?: return@withContext existing
                val have = contiguousCachedPrefix(sourceCache, key)
                val before = triedBytes[trackId]
                val total = ContentMetadata.getContentLength(sourceCache.getContentMetadata(key))
                val whole = total > 0 && have >= total
                if (before != null && !(whole && have > before) && have < before + before / 4) return@withContext existing
                triedBytes[trackId] = have
                val metrics = decodeAndAnalyze(trackId, sourceCache, key) ?: return@withContext existing
                if (existing != null && existing.analyzedMs > metrics.analyzedMs) return@withContext existing
                analysisCache.put(metrics)
                analysisCache.save()
                MixLog.i(
                    "análisis: $trackId ${metrics.analyzedMs / 1000}/${metrics.durationMs / 1000} s, " +
                        "BPM ${metrics.bpm?.let { "%.2f".format(it) } ?: "?"} (fiab. ${"%.2f".format(metrics.bpmConfidence)}), " +
                        "rejilla ${metrics.beatGrid.size}",
                )
                metrics
            }
        } catch (t: Throwable) {
            Timber.d(t, "análisis local omitido para %s", trackId)
            null
        } finally {
            inFlight.remove(trackId)
        }
    }

    private fun selectLocalSource(trackId: String, cacheKey: String): Pair<Cache, String>? {
        if (downloads.has(trackId)) return downloads.cache to trackId
        val prefix = contiguousCachedPrefix(audioCache, cacheKey)
        if (prefix >= MIN_ANALYZE_BYTES) return audioCache to cacheKey
        if (cacheKey != trackId) {
            val fallbackPrefix = contiguousCachedPrefix(audioCache, trackId)
            if (fallbackPrefix >= MIN_ANALYZE_BYTES) return audioCache to trackId
        }
        return null
    }

    private suspend fun decodeAndAnalyze(trackId: String, cache: Cache, key: String): AudioMetrics? {
        val totalLen = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val prefixLen = contiguousCachedPrefix(cache, key)
        if (prefixLen < MIN_ANALYZE_BYTES) return null
        val readableLimit = if (totalLen > 0) minOf(totalLen, prefixLen) else prefixLen

        val dataSource = CacheMediaDataSource(cache, key, readableLimit, totalLen)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(dataSource)
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
                extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            var session = AudioAnalyzer.Session(trackId, sampleRate, channels)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmBytes = ByteArray(0)
            var floatScratch = FloatArray(0)
            var decodedFrames = 0L
            var maxFrames = sampleRate.toLong() * MAX_ANALYZE_SECONDS
            var eosIdleTries = 0

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            val reachedCap = pts >= MAX_ANALYZE_SECONDS * 1_000_000L
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                sampleSize,
                                pts,
                                if (reachedCap) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                            )
                            if (reachedCap) {
                                inputDone = true
                            } else {
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        val newRate = outFmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        val newCh = outFmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = outFmt.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        if ((newRate != sampleRate || newCh != channels) && decodedFrames == 0L) {
                            sampleRate = newRate
                            channels = newCh
                            maxFrames = sampleRate.toLong() * MAX_ANALYZE_SECONDS
                            session = AudioAnalyzer.Session(trackId, sampleRate, channels)
                        } else {
                            sampleRate = newRate
                            channels = newCh
                            maxFrames = sampleRate.toLong() * MAX_ANALYZE_SECONDS
                        }
                    }
                    outIdx >= 0 -> {
                        eosIdleTries = 0
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val fb = outBuf.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                                    val count = fb.remaining()
                                    if (floatScratch.size < count) floatScratch = FloatArray(count)
                                    fb.get(floatScratch, 0, count)
                                    session.feedSamples(floatScratch, count)
                                    decodedFrames += count / channels.coerceAtLeast(1)
                                } else {
                                    if (pcmBytes.size < info.size) pcmBytes = ByteArray(info.size)
                                    outBuf.get(pcmBytes, 0, info.size)
                                    session.feedPcm16(pcmBytes, info.size)
                                    decodedFrames += info.size / (2 * channels.coerceAtLeast(1))
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || decodedFrames >= maxFrames) {
                            outputDone = true
                        }
                    }
                    inputDone && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (++eosIdleTries >= MAX_EOS_IDLE_TRIES) outputDone = true
                    }
                }
            }

            if (decodedFrames < sampleRate * MIN_ANALYZE_SECONDS) return null
            val metrics = session.finish()
            val expectedDurMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                (format.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(metrics.durationMs)
            } else {
                metrics.durationMs
            }
            return metrics.asPartOf(expectedDurMs)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { dataSource.close() }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    private class CacheMediaDataSource(
        cache: Cache,
        private val cacheKey: String,
        private val readableLimit: Long,
        private val totalLength: Long,
    ) : MediaDataSource() {
        private val source = CacheDataSource(
            cache,
            /* upstream = */ null,
            FileDataSource(),
            /* cacheWriteDataSink = */ null,
            CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
            /* eventListener = */ null,
        )
        private var currentPos = -1L

        override fun getSize(): Long = if (totalLength > 0) totalLength else -1L

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position < 0 || position >= readableLimit) return -1
            val toRead = minOf(size.toLong(), readableLimit - position).toInt()
            if (toRead <= 0) return -1
            if (currentPos != position) {
                runCatching { source.close() }
                val spec = DataSpec.Builder()
                    .setUri(cacheKey)
                    .setKey(cacheKey)
                    .setPosition(position)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
                val opened = runCatching { source.open(spec) }.getOrElse { return -1 }
                if (opened == 0L) return -1
                currentPos = position
            }
            val read = runCatching { source.read(buffer, offset, toRead) }.getOrElse { return -1 }
            if (read == C.RESULT_END_OF_INPUT || read < 0) return -1
            currentPos += read
            return read
        }

        @Synchronized
        override fun close() {
            currentPos = -1L
            runCatching { source.close() }
        }
    }

    companion object {
        private const val MIN_ANALYZE_BYTES = 384L * 1024L
        private const val MIN_ANALYZE_SECONDS = 10L
        private const val MAX_ANALYZE_SECONDS = 720L
        private const val TIMEOUT_US = 5_000L
        private const val MAX_EOS_IDLE_TRIES = 400

        fun contiguousCachedPrefix(cache: Cache, key: String): Long {
            var pos = 0L
            for (span in cache.getCachedSpans(key)) {
                if (!span.isCached) continue
                if (span.position > pos) break
                val end = span.position + span.length
                if (end > pos) pos = end
            }
            return pos
        }
    }
}
