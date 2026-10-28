package io.github.iaguito22.oto.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

/**
 * Motor de audio de escritorio para Linux (PipeWire/PulseAudio/ALSA) y Windows (WASAPI/DirectSound).
 *
 * Estrategia dual sin dependencias nativas frágiles:
 * 1. Descarga por trozos HTTP de 1 MB (`CHUNK_BYTES`), igual que `Chunked.kt` en Android, evitando el
 *    estrangulamiento de ~33 KB/s de YouTube y guardando cada canción en caché de disco (`Tier.key(id)`).
 * 2. Reproducción con `ffplay -nodisp -autoexit -loglevel quiet` cuando `ffplay` está en el `PATH`
 *    (soporta Opus/WebM 251 y AAC/M4A 140 nativos de YouTube Music con seek instantáneo `-ss`), y
 *    respaldo con `javax.sound.sampled.SourceDataLine`.
 */
class DesktopAudioEngine(
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val onEnded: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    data class EngineState(
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val bufferedFraction: Float = 0f,
    )

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var currentProcess: Process? = null
    @Volatile private var currentLine: SourceDataLine? = null
    @Volatile private var playJob: Job? = null
    @Volatile private var downloadJob: Job? = null
    @Volatile private var tickerJob: Job? = null

    @Volatile private var activeFile: File? = null
    @Volatile private var activeDurationMs: Long = 0L
    @Volatile private var startOffsetMs: Long = 0L
    @Volatile private var playStartedWallMs: Long = 0L
    @Volatile private var pausedPositionMs: Long = 0L
    @Volatile private var gainLinear: Float = 1f

    private val hasFfplay: Boolean by lazy {
        runCatching {
            ProcessBuilder("ffplay", "-version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
    }

    init {
        cacheDir.mkdirs()
    }

    fun setGain(linear: Float) {
        gainLinear = linear.coerceIn(0.0f, 2.0f)
        currentLine?.let { line ->
            runCatching {
                if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    val ctrl = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                    val db = if (gainLinear <= 0.0001f) ctrl.minimum
                             else (20f * log10(gainLinear)).coerceIn(ctrl.minimum, ctrl.maximum)
                    ctrl.value = db
                }
            }
        }
    }

    fun loadAndPlay(
        cacheKey: String,
        streamUrl: String,
        headers: Map<String, String>,
        contentLength: Long?,
        durationMs: Long,
        codec: String,
    ) {
        stopInternal()
        activeDurationMs = durationMs.coerceAtLeast(0L)
        pausedPositionMs = 0L
        startOffsetMs = 0L
        _state.value = EngineState(playing = true, buffering = true, positionMs = 0L, durationMs = activeDurationMs, bufferedFraction = 0f)

        val ext = if (codec.contains("opus", ignoreCase = true) || codec.contains("webm", ignoreCase = true)) "webm" else "m4a"
        val targetFile = File(cacheDir, "$cacheKey.$ext")
        activeFile = targetFile

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                if (!targetFile.exists() || targetFile.length() < MIN_PLAYABLE_BYTES) {
                    downloadChunked(streamUrl, headers, contentLength, targetFile) { downloaded, total ->
                        val frac = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
                        _state.value = _state.value.copy(bufferedFraction = frac)
                        if (downloaded >= FIRST_PLAY_BYTES && playJob == null && _state.value.playing) {
                            startPlaybackAt(targetFile, 0L)
                        }
                    }
                } else {
                    _state.value = _state.value.copy(bufferedFraction = 1f)
                }
                if (playJob == null && _state.value.playing && targetFile.exists() && targetFile.length() > 0L) {
                    startPlaybackAt(targetFile, 0L)
                }
            } catch (t: Throwable) {
                Timber.w(t, "Error descargando stream para $cacheKey")
                onError(t)
            }
        }
    }

    private suspend fun downloadChunked(
        streamUrl: String,
        headers: Map<String, String>,
        knownLength: Long?,
        outFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var offset = if (outFile.exists()) outFile.length() else 0L
        var total = knownLength ?: -1L

        RandomAccessFile(outFile, "rw").use { raf ->
            raf.seek(offset)
            while (isActive && (total < 0L || offset < total)) {
                val end = if (total > 0L) minOf(offset + CHUNK_BYTES - 1L, total - 1L) else offset + CHUNK_BYTES - 1L
                val sep = if ('?' in streamUrl) '&' else '?'
                val rangedUrl = "$streamUrl${sep}range=$offset-$end"
                val conn = (URI.create(rangedUrl).toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code en chunk $offset-$end")
                    }
                    val cl = conn.contentLengthLong
                    if (total < 0L && cl > 0L && cl < CHUNK_BYTES) {
                        total = offset + cl
                    }
                    val buf = ByteArray(32 * 1024)
                    var readInChunk = 0L
                    conn.inputStream.use { ins ->
                        while (isActive) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            offset += n
                            readInChunk += n
                            onProgress(offset, if (total > 0L) total else offset + CHUNK_BYTES)
                        }
                    }
                    if (readInChunk < CHUNK_BYTES) {
                        total = offset
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
        onProgress(offset, offset)
    }

    private fun startPlaybackAt(file: File, offsetMs: Long) {
        killDecodeProcess()
        startOffsetMs = offsetMs.coerceAtLeast(0L)
        playStartedWallMs = System.currentTimeMillis()
        _state.value = _state.value.copy(playing = true, buffering = false, positionMs = startOffsetMs)

        startTicker()

        playJob = scope.launch(Dispatchers.IO) {
            try {
                if (hasFfplay) {
                    val sec = String.format(java.util.Locale.US, "%.3f", startOffsetMs / 1000.0)
                    val vol = (gainLinear * 100f).toInt().coerceIn(5, 100)
                    val pb = ProcessBuilder(
                        "ffplay",
                        "-nodisp",
                        "-autoexit",
                        "-loglevel", "quiet",
                        "-ss", sec,
                        "-volume", vol.toString(),
                        file.absolutePath,
                    )
                    val proc = pb.start()
                    currentProcess = proc
                    val code = proc.waitFor()
                    if (isActive && currentProcess === proc && code == 0) {
                        val elapsed = startOffsetMs + (System.currentTimeMillis() - playStartedWallMs)
                        if (activeDurationMs <= 0L || elapsed >= activeDurationMs - 1500L) {
                            _state.value = _state.value.copy(playing = false, positionMs = activeDurationMs)
                            onEnded()
                        }
                    }
                } else {
                    playWithJavaSound(file, startOffsetMs)
                }
            } catch (t: Throwable) {
                if (isActive) Timber.w(t, "Fallo en reproducción de escritorio")
            }
        }
    }

    private fun playWithJavaSound(file: File, offsetMs: Long) {
        BufferedInputStream(file.inputStream()).use { bis ->
            val rawIn: AudioInputStream = AudioSystem.getAudioInputStream(bis)
            val baseFormat = rawIn.format
            val decodedFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.sampleRate,
                16,
                baseFormat.channels,
                baseFormat.channels * 2,
                baseFormat.sampleRate,
                false,
            )
            AudioSystem.getAudioInputStream(decodedFormat, rawIn).use { pcmIn ->
                val info = DataLine.Info(SourceDataLine::class.java, decodedFormat)
                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(decodedFormat)
                currentLine = line
                setGain(gainLinear)
                if (offsetMs > 0L) {
                    val bytesToSkip = ((offsetMs / 1000.0) * decodedFormat.frameRate * decodedFormat.frameSize).toLong()
                    pcmIn.skip(bytesToSkip)
                }
                line.start()
                val buf = ByteArray(8192)
                while (currentLine === line) {
                    val n = pcmIn.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) line.write(buf, 0, n)
                }
                line.drain()
                line.stop()
                line.close()
                if (currentLine === line) {
                    currentLine = null
                    _state.value = _state.value.copy(playing = false, positionMs = activeDurationMs)
                    onEnded()
                }
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                if (_state.value.playing && !_state.value.buffering) {
                    val pos = (startOffsetMs + (System.currentTimeMillis() - playStartedWallMs))
                        .let { if (activeDurationMs > 0L) it.coerceAtMost(activeDurationMs) else it }
                    _state.value = _state.value.copy(positionMs = pos)
                }
                delay(100L)
            }
        }
    }

    fun pause() {
        if (!_state.value.playing) return
        pausedPositionMs = _state.value.positionMs
        _state.value = _state.value.copy(playing = false)
        killDecodeProcess()
    }

    fun resume() {
        if (_state.value.playing) return
        val f = activeFile ?: return
        if (f.exists() && f.length() > 0L) {
            startPlaybackAt(f, pausedPositionMs)
        }
    }

    fun seekTo(ms: Long) {
        val clamped = if (activeDurationMs > 0L) ms.coerceIn(0L, activeDurationMs) else ms.coerceAtLeast(0L)
        pausedPositionMs = clamped
        _state.value = _state.value.copy(positionMs = clamped)
        val f = activeFile ?: return
        if (_state.value.playing && f.exists() && f.length() > 0L) {
            startPlaybackAt(f, clamped)
        }
    }

    fun stop() {
        stopInternal()
        _state.value = EngineState()
    }

    private fun stopInternal() {
        tickerJob?.cancel()
        tickerJob = null
        downloadJob?.cancel()
        downloadJob = null
        killDecodeProcess()
    }

    private fun killDecodeProcess() {
        playJob?.cancel()
        playJob = null
        currentProcess?.let {
            currentProcess = null
            runCatching { it.destroyForcibly() }
        }
        currentLine?.let {
            currentLine = null
            runCatching { it.stop(); it.close() }
        }
    }

    companion object {
        private const val CHUNK_BYTES = 1_048_576L
        private const val FIRST_PLAY_BYTES = 128 * 1024L
        private const val MIN_PLAYABLE_BYTES = 64 * 1024L
    }
}
