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
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
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
 * 1. Descarga por trozos HTTP de 1 MB (`downloadChunked`), igual que `Chunked.kt` en Android, evitando el
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

    @Volatile private var beats = FloatArray(0)
    @Volatile private var beatFile: String? = null
    @Volatile private var beatLength = 0L
    private var beatJob: Job? = null
    fun beat(positionMs: Long): Float = if (_state.value.playing) beats.getOrElse((positionMs * 48000 / 512000).toInt()) { 0f } else 0f
    private fun analyzeBeat(file: File) {
        if (beatFile == file.absolutePath && beatLength == file.length()) return
        beatJob?.cancel(); beats = FloatArray(0); beatFile = file.absolutePath;beatLength = file.length()
        beatJob = scope.launch(Dispatchers.IO) {
            val result = runCatching { DesktopBeat.analyze(file) { !isActive } }.getOrNull()
            if(isActive && result != null) beats = result
        }
    }
    @Volatile private var currentProcess: Process? = null
    @Volatile private var currentLine: SourceDataLine? = null
    @Volatile private var playJob: Job? = null
    @Volatile private var downloadJob: Job? = null
    @Volatile private var tickerJob: Job? = null
    private var volumeJob: Job? = null

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
        Runtime.getRuntime().addShutdownHook(Thread { currentProcess?.destroyForcibly(); currentLine?.close() })
    }

    fun setGain(linear: Float) {
        val previous = gainLinear
        gainLinear = linear.coerceIn(0.0f, 2.0f)
        val process = currentProcess
        if (process != null && previous != gainLinear) {
            volumeJob?.cancel()
            volumeJob = scope.launch {
                delay(120)
                val file = activeFile
                if (currentProcess === process && _state.value.playing && file != null && file.exists()) startPlaybackAt(file, _state.value.positionMs)
            }
        }
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
        playWhenReady: Boolean = true,
        initialPositionMs: Long = 0L,
    ) {
        stopInternal()
        activeDurationMs = durationMs.coerceAtLeast(0L)
        pausedPositionMs = initialPositionMs
        startOffsetMs = initialPositionMs
        _state.value = EngineState(playing = playWhenReady, buffering = true, positionMs = 0L, durationMs = activeDurationMs, bufferedFraction = 0f)

        val ext = if (codec.contains("opus", ignoreCase = true) || codec.contains("webm", ignoreCase = true)) "webm" else "m4a"
        val targetFile = File(cacheDir, "$cacheKey.$ext")
        activeFile = targetFile

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                if (!targetFile.exists() || targetFile.length() < MIN_PLAYABLE_BYTES || (contentLength != null && contentLength > 0L && targetFile.length() < contentLength)) {
                    downloadChunked(streamUrl, headers, contentLength, targetFile) { downloaded, total ->
                        val frac = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
                        _state.value = _state.value.copy(bufferedFraction = frac)
                    }
                } else {
                    _state.value = _state.value.copy(bufferedFraction = 1f)
                }
                updateDuration(targetFile)
                if (playJob == null && _state.value.playing && targetFile.exists() && targetFile.length() > 0L) {
                    startPlaybackAt(targetFile, initialPositionMs)
                }
                analyzeBeat(targetFile)
                if (!playWhenReady) _state.value = _state.value.copy(buffering = false, positionMs = initialPositionMs)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Timber.w(t, "Error descargando stream para $cacheKey")
                onError(t)
            }
        }
    }

    /**
     * Reproduce un archivo que ya está en disco (una descarga), sin pedir nada a la red.
     * Devuelve false si el archivo no sirve.
     */
    fun loadLocalFile(file: File, durationMs: Long, playWhenReady: Boolean = true, initialPositionMs: Long = 0L): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        stopInternal()
        activeFile = file
        activeDurationMs = durationMs.coerceAtLeast(0L)
        pausedPositionMs = 0L
        startOffsetMs = 0L
        _state.value = EngineState(
            playing = true,
            buffering = false,
            positionMs = 0L,
            durationMs = activeDurationMs,
            bufferedFraction = 1f,
        )
        pausedPositionMs = initialPositionMs
        _state.value = _state.value.copy(playing = playWhenReady, positionMs = initialPositionMs)
        downloadJob = scope.launch(Dispatchers.IO) {
            updateDuration(file)
            if (isActive && activeFile == file && _state.value.playing) startPlaybackAt(file, initialPositionMs)
        }
        return true
    }

    private fun updateDuration(file: File) {
        val actual = runCatching {
            val process = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath)
                .redirectErrorStream(true).start()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); return@runCatching null }
            process.inputStream.bufferedReader().use { it.readText().trim().toDoubleOrNull()?.times(1000)?.toLong() }
        }.getOrNull()?.takeIf { it > 0 } ?: return
        if (activeFile != file) return
        activeDurationMs = actual
        _state.value = _state.value.copy(durationMs = actual)
        Timber.d("Duración de audio: %d ms", actual)
    }

    private fun startPlaybackAt(file: File, offsetMs: Long) {
        analyzeBeat(file)
        killDecodeProcess()
        startOffsetMs = offsetMs.coerceAtLeast(0L)
        playStartedWallMs = System.currentTimeMillis()
        _state.value = _state.value.copy(playing = true, buffering = hasFfplay, positionMs = startOffsetMs)

        if (!hasFfplay) startTicker()

        playJob = scope.launch(Dispatchers.IO) {
            try {
                if (hasFfplay) {
                    val sec = String.format(java.util.Locale.US, "%.3f", startOffsetMs / 1000.0)
                    val vol = (gainLinear * 100f).toInt().coerceIn(0, 100)
                    val pb = ProcessBuilder(
                        "ffplay",
                        "-nodisp",
                        "-autoexit",
                        "-loglevel", "quiet",
                        "-stats",
                        "-ss", sec,
                        "-af", "atrim=start=$sec,asetpts=PTS-STARTPTS",
                        "-volume", vol.toString(),
                        file.absolutePath,
                    )
                    val proc = pb.start()
                    currentProcess = proc
                    if (!isActive) { proc.destroyForcibly(); return@launch }
                    val status = StringBuilder()
                    val clockPattern = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s+(?:M-A|:)")
                    proc.errorStream.bufferedReader().use { reader ->
                        while (isActive && currentProcess === proc) {
                            val ch = reader.read()
                            if (ch < 0) break
                            if (ch == '\r'.code || ch == '\n'.code) {
                                val seconds = clockPattern.find(status.toString())?.groupValues?.get(1)?.toDoubleOrNull()
                                if (seconds != null && currentProcess === proc && _state.value.playing) {
                                    val position = (startOffsetMs + (seconds * 1000).toLong()).coerceAtLeast(startOffsetMs)
                                        .let { if (activeDurationMs > 0) it.coerceAtMost(activeDurationMs) else it }
                                    _state.value = _state.value.copy(positionMs = position, buffering = false)
                                }
                                status.setLength(0)
                            } else if (status.length < 1024) status.append(ch.toChar())
                        }
                    }
                    val code = proc.waitFor()
                    if (isActive && currentProcess === proc && code == 0) {
                        _state.value = _state.value.copy(playing = false, positionMs = activeDurationMs)
                        onEnded()
                    } else if (isActive && currentProcess === proc && code != 0) {
                        _state.value = _state.value.copy(playing = false, buffering = false)
                        onError(IllegalStateException("No se pudo reproducir el audio (ffplay: $code)"))
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
        volumeJob?.cancel()
        volumeJob = null
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
        private const val MIN_PLAYABLE_BYTES = 64 * 1024L
    }
}
