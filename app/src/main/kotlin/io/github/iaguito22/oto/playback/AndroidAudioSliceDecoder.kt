package io.github.iaguito22.oto.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import io.github.iaguito22.oto.dj.DjDsp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class DecodedAudioSlice(
    val stereoPcm: FloatArray,
    val originMs: Long,
    val sampleRate: Int,
    val mime: String = "",
    val sourceSampleRate: Int = sampleRate,
    val encoderDelay: Int = 0,
)

/**
 * Decodificador preciso por ventanas (`[startMs..endMs]`) para generar puentes pre-renderizados en Android.
 * Usa `Downloads` / `audioCache` de forma prioritaria y solo recurre a red si [allowNetworkSlice] lo permite.
 */
@OptIn(UnstableApi::class)
class AndroidAudioSliceDecoder(
    private val audioCache: Cache,
    private val downloads: Downloads,
    private val dataSourceFactory: (() -> DataSource)? = null,
    private val allowNetworkSlice: () -> Boolean = { true },
) {
    fun resolveLocalKey(trackId: String, cacheKey: String): Pair<Cache, String>? {
        if (downloads.has(trackId)) return downloads.cache to trackId
        if (audioCache.getCachedBytes(cacheKey, 0L, C.LENGTH_UNSET.toLong()) > 0L) return audioCache to cacheKey
        if (cacheKey != trackId && audioCache.getCachedBytes(trackId, 0L, C.LENGTH_UNSET.toLong()) > 0L) {
            return audioCache to trackId
        }
        return null
    }

    fun hasWindowCachedLocally(
        trackId: String,
        cacheKey: String,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
    ): Boolean {
        if (downloads.has(trackId)) return true
        val (_, key) = resolveLocalKey(trackId, cacheKey) ?: return false
        val totalLen = ContentMetadata.getContentLength(audioCache.getContentMetadata(key))
        if (totalLen <= 0L) return false
        if (audioCache.isCached(key, 0L, totalLen)) return true
        if (durationMs <= 0L) return false
        val estLo = ((startMs.coerceAtLeast(0L).toDouble() / durationMs) * totalLen * 0.85).toLong().coerceIn(0L, totalLen)
        val estHi = ((endMs.coerceAtLeast(startMs).toDouble() / durationMs) * totalLen * 1.15).toLong().coerceIn(estLo, totalLen)
        return audioCache.isCached(key, 0L, min(64_000L, totalLen)) &&
            audioCache.isCached(key, estLo, max(1L, estHi - estLo))
    }

    suspend fun decodeWindow(
        trackId: String,
        cacheKey: String,
        startMs: Long,
        endMs: Long,
        targetSampleRate: Int = DjDsp.DJ_SAMPLE_RATE,
        allowNetwork: Boolean = allowNetworkSlice(),
    ): DecodedAudioSlice? {
        if (endMs <= startMs) return null
        return try {
            withContext(Dispatchers.IO) {
                val dataSource = createMediaDataSource(trackId, cacheKey, allowNetwork) ?: return@withContext null
                decodeWithExtractor(dataSource, startMs.coerceAtLeast(0L), endMs, targetSampleRate)
            }
        } catch (t: Throwable) {
            Timber.d(t, "decodificación de ventana fallida (%s [%d..%d])", trackId, startMs, endMs)
            null
        }
    }

    private fun createMediaDataSource(trackId: String, cacheKey: String, allowNetwork: Boolean): MediaDataSource? {
        if (downloads.has(trackId)) {
            val totalLen = ContentMetadata.getContentLength(downloads.cache.getContentMetadata(trackId))
            val readLen = if (totalLen > 0) totalLen else AndroidAudioAnalyzer.contiguousCachedPrefix(downloads.cache, trackId)
            if (readLen <= 0L) return null
            return CacheOnlyMediaDataSource(downloads.cache, trackId, readLen, totalLen)
        }
        val activeKey = when {
            audioCache.getCachedBytes(cacheKey, 0L, C.LENGTH_UNSET.toLong()) > 0L -> cacheKey
            cacheKey != trackId && audioCache.getCachedBytes(trackId, 0L, C.LENGTH_UNSET.toLong()) > 0L -> trackId
            else -> cacheKey
        }
        val totalLen = ContentMetadata.getContentLength(audioCache.getContentMetadata(activeKey))
        if (!allowNetwork || dataSourceFactory == null) {
            if (totalLen <= 0L) return null
            val prefix = AndroidAudioAnalyzer.contiguousCachedPrefix(audioCache, activeKey)
            if (prefix <= 0L && !audioCache.isCached(activeKey, 0L, min(64_000L, totalLen))) return null
            return SparseCacheOnlyMediaDataSource(audioCache, activeKey, totalLen)
        }
        return UpstreamDataSourceMediaDataSource(trackId, activeKey, totalLen, dataSourceFactory)
    }

    private suspend fun decodeWithExtractor(
        dataSource: MediaDataSource,
        reqStartMs: Long,
        reqEndMs: Long,
        targetSampleRate: Int,
    ): DecodedAudioSlice? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(dataSource)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
                extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val encoderDelay = format.getIntegerOrDefault("encoder-delay", 0)
            val formatDurUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE
            var sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, targetSampleRate)
            val sourceSampleRate = sampleRate
            var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val reqStartUs = (reqStartMs * 1_000L).coerceIn(0L, max(0L, formatDurUs - 200_000L))
            val reqEndUs = (reqEndMs * 1_000L).coerceAtMost(formatDurUs)
            if (reqEndUs <= reqStartUs) return null

            if (reqStartUs > 0L) {
                extractor.seekTo(reqStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val stereoChunks = ArrayList<FloatArray>()
            var totalStereoFloats = 0
            var actualOriginUs = -1L
            var inputDone = false
            var outputDone = false
            var eosIdleTries = 0
            var pcmBytes = ByteArray(0)
            var floatScratch = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            val stopUsWithPad = reqEndUs + 120_000L

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
                            val pastEnd = pts > stopUsWithPad + 150_000L
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                sampleSize,
                                pts,
                                if (pastEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                            )
                            if (pastEnd) inputDone = true else extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        sampleRate = outFmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = outFmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = outFmt.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    outIdx >= 0 -> {
                        eosIdleTries = 0
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val ch = channels.coerceAtLeast(1)
                                val bufPtsUs = info.presentationTimeUs
                                val framesInBuf = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    (info.size / 4) / ch
                                } else {
                                    (info.size / 2) / ch
                                }
                                val bufDurUs = ((framesInBuf.toDouble() / sampleRate) * 1_000_000.0).roundToLong()

                                if (bufPtsUs + bufDurUs > reqStartUs && bufPtsUs <= stopUsWithPad) {
                                    val skipFrames = if (actualOriginUs < 0L && bufPtsUs < reqStartUs) {
                                        (((reqStartUs - bufPtsUs) / 1_000_000.0) * sampleRate).roundToInt()
                                            .coerceIn(0, framesInBuf - 1)
                                    } else 0
                                    if (actualOriginUs < 0L) {
                                        actualOriginUs = bufPtsUs + ((skipFrames.toDouble() / sampleRate) * 1_000_000.0).roundToLong()
                                    }
                                    val keepFrames = framesInBuf - skipFrames
                                    val outChunk = FloatArray(keepFrames * 2)
                                    if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                        val fb = outBuf.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                                        val count = fb.remaining()
                                        if (floatScratch.size < count) floatScratch = FloatArray(count)
                                        fb.get(floatScratch, 0, count)
                                        for (f in 0 until keepFrames) {
                                            val base = (skipFrames + f) * ch
                                            val l = floatScratch[base]
                                            val r = if (ch > 1) floatScratch[base + 1] else l
                                            outChunk[f * 2] = l
                                            outChunk[f * 2 + 1] = r
                                        }
                                    } else {
                                        if (pcmBytes.size < info.size) pcmBytes = ByteArray(info.size)
                                        outBuf.get(pcmBytes, 0, info.size)
                                        for (f in 0 until keepFrames) {
                                            val bBase = (skipFrames + f) * ch * 2
                                            val l = (((pcmBytes[bBase + 1].toInt() shl 8) or (pcmBytes[bBase].toInt() and 0xFF)).toShort()) / 32768f
                                            val r = if (ch > 1) {
                                                (((pcmBytes[bBase + 3].toInt() shl 8) or (pcmBytes[bBase + 2].toInt() and 0xFF)).toShort()) / 32768f
                                            } else l
                                            outChunk[f * 2] = l
                                            outChunk[f * 2 + 1] = r
                                        }
                                    }
                                    stereoChunks += outChunk
                                    totalStereoFloats += outChunk.size
                                }
                                if (bufPtsUs + bufDurUs >= stopUsWithPad) outputDone = true
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                    inputDone && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (++eosIdleTries >= MAX_EOS_IDLE_TRIES) outputDone = true
                    }
                }
            }

            if (totalStereoFloats < sampleRate / 4 || actualOriginUs < 0L) return null
            val merged = FloatArray(totalStereoFloats)
            var dst = 0
            for (c in stereoChunks) {
                System.arraycopy(c, 0, merged, dst, c.size)
                dst += c.size
            }
            val resampled = if (sampleRate != targetSampleRate) {
                resampleStereoCubic(merged, sampleRate, targetSampleRate)
            } else merged
            return DecodedAudioSlice(
                stereoPcm = resampled,
                originMs = (actualOriginUs / 1_000.0).roundToLong().coerceAtLeast(0L),
                sampleRate = targetSampleRate,
                mime = mime,
                sourceSampleRate = sourceSampleRate,
                encoderDelay = encoderDelay,
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { dataSource.close() }
        }
    }

    private fun resampleStereoCubic(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        val srcFrames = src.size / 2
        if (srcFrames <= 1 || srcRate == dstRate) return src
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstFrames = ((srcFrames.toDouble() * dstRate) / srcRate).roundToInt().coerceAtLeast(1)
        val out = FloatArray(dstFrames * 2)
        val last = srcFrames - 1
        fun hermite(ym1: Float, y0: Float, y1: Float, y2: Float, t: Float): Float {
            val a0 = -0.5f * ym1 + 1.5f * y0 - 1.5f * y1 + 0.5f * y2
            val a1 = ym1 - 2.5f * y0 + 2.0f * y1 - 0.5f * y2
            val a2 = -0.5f * ym1 + 0.5f * y1
            return ((a0 * t + a1) * t + a2) * t + y0
        }
        for (i in 0 until dstFrames) {
            val pos = i * ratio
            val idx = pos.toInt().coerceIn(0, last)
            val i0 = (idx - 1).coerceAtLeast(0)
            val i2 = min(last, idx + 1)
            val i3 = min(last, idx + 2)
            val frac = (pos - idx).toFloat()
            out[i * 2] = hermite(src[i0 * 2], src[idx * 2], src[i2 * 2], src[i3 * 2], frac).coerceIn(-1.2f, 1.2f)
            out[i * 2 + 1] = hermite(src[i0 * 2 + 1], src[idx * 2 + 1], src[i2 * 2 + 1], src[i3 * 2 + 1], frac).coerceIn(-1.2f, 1.2f)
        }
        return out
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    private class CacheOnlyMediaDataSource(
        cache: Cache,
        private val cacheKey: String,
        private val readableLimit: Long,
        private val totalLength: Long,
    ) : MediaDataSource() {
        private val source = CacheDataSource(cache, null, FileDataSource(), null, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR, null)
        private var currentPos = -1L

        override fun getSize(): Long = if (totalLength > 0) totalLength else readableLimit

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position < 0 || position >= readableLimit) return -1
            val toRead = min(size.toLong(), readableLimit - position).toInt()
            if (toRead <= 0) return -1
            if (currentPos != position) {
                runCatching { source.close() }
                val spec = DataSpec.Builder().setUri(cacheKey).setKey(cacheKey).setPosition(position).setLength(C.LENGTH_UNSET.toLong()).build()
                if (runCatching { source.open(spec) }.getOrElse { return -1 } == 0L) return -1
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

    private class SparseCacheOnlyMediaDataSource(
        private val cache: Cache,
        private val cacheKey: String,
        private val totalLength: Long,
    ) : MediaDataSource() {
        private val source = CacheDataSource(cache, null, FileDataSource(), null, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR, null)
        private var currentPos = -1L

        override fun getSize(): Long = totalLength

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position < 0 || position >= totalLength) return -1
            val toRead = min(size.toLong(), totalLength - position).toInt()
            val span = cache.getCachedSpans(cacheKey).firstOrNull { s ->
                s.isCached && position >= s.position && position < s.position + s.length
            } ?: return -1
            val cachedSpan = min(toRead.toLong(), span.position + span.length - position).toInt()
            if (cachedSpan <= 0) return -1
            if (currentPos != position) {
                runCatching { source.close() }
                val spec = DataSpec.Builder().setUri(cacheKey).setKey(cacheKey).setPosition(position).setLength(C.LENGTH_UNSET.toLong()).build()
                if (runCatching { source.open(spec) }.getOrElse { return -1 } == 0L) return -1
                currentPos = position
            }
            val read = runCatching { source.read(buffer, offset, cachedSpan) }.getOrElse { return -1 }
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

    private class UpstreamDataSourceMediaDataSource(
        private val trackId: String,
        private val cacheKey: String,
        initialLength: Long,
        private val factory: () -> DataSource,
    ) : MediaDataSource() {
        private var source: DataSource? = null
        private var currentPos = -1L
        private var knownLength = initialLength

        @Synchronized
        override fun getSize(): Long {
            if (knownLength > 0L) return knownLength
            val ds = source ?: factory().also { source = it }
            val spec = DataSpec.Builder().setUri(trackId).setKey(cacheKey).setPosition(0L).setLength(C.LENGTH_UNSET.toLong()).build()
            val len = runCatching { ds.open(spec) }.getOrElse { return -1L }
            currentPos = 0L
            if (len > 0L && len != C.LENGTH_UNSET.toLong()) knownLength = len
            return if (knownLength > 0L) knownLength else -1L
        }

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position < 0) return -1
            if (knownLength > 0L && position >= knownLength) return -1
            val toRead = if (knownLength > 0L) min(size.toLong(), knownLength - position).toInt() else size
            if (toRead <= 0) return -1
            val ds = source ?: factory().also { source = it }
            if (currentPos != position) {
                runCatching { ds.close() }
                val spec = DataSpec.Builder().setUri(trackId).setKey(cacheKey).setPosition(position).setLength(C.LENGTH_UNSET.toLong()).build()
                val opened = runCatching { ds.open(spec) }.getOrElse { return -1 }
                if (opened == 0L) return -1
                if (knownLength <= 0L && opened != C.LENGTH_UNSET.toLong()) knownLength = position + opened
                currentPos = position
            }
            val read = runCatching { ds.read(buffer, offset, toRead) }.getOrElse { return -1 }
            if (read == C.RESULT_END_OF_INPUT || read < 0) return -1
            currentPos += read
            return read
        }

        @Synchronized
        override fun close() {
            currentPos = -1L
            runCatching { source?.close() }
            source = null
        }
    }

    private companion object {
        const val TIMEOUT_US = 5_000L
        const val MAX_EOS_IDLE_TRIES = 400
    }
}
