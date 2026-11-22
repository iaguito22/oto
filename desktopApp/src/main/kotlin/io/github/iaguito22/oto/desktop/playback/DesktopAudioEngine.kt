package io.github.iaguito22.oto.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

internal val PCM_FORMAT = AudioFormat(48000f, 16, 2, true, false)

// El Opus/AAC decodificado puede superar 0 dBFS. Conserva margen antes de cuantizar
// y restaura la escala en Float al aplicar el volumen, antes de la salida final.
internal const val PCM_DECODE_GAIN = 0.5f

internal fun ffmpegPcmCommand(executable: String, input: File, offsetMs: Long): List<String> = listOf(
    executable, "-nostdin", "-v", "error",
    "-ss", String.format(java.util.Locale.US, "%.3f", offsetMs / 1000.0),
    "-i", input.absolutePath, "-vn", "-ac", "2", "-ar", "48000",
    "-af", "volume=$PCM_DECODE_GAIN:precision=float", "-f", "s16le", "pipe:1",
)

internal fun canUseFfmpegPcm(
    ffmpegAvailable: Boolean = DesktopTools.find("ffmpeg") != null,
    lineSupported: Boolean = runCatching {
        AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, PCM_FORMAT))
    }.getOrDefault(false),
): Boolean = ffmpegAvailable && lineSupported

/**
 * Motor de audio de escritorio para Linux (PipeWire/PulseAudio/ALSA) y Windows (WASAPI/DirectSound).
 *
 * Descarga y reproducción:
 * 1. Descarga por trozos HTTP de 1 MB (`downloadChunked`), igual que `Chunked.kt` en Android, evitando el
 *    estrangulamiento de ~33 KB/s de YouTube y guardando cada canción en caché de disco (`Tier.key(id)`).
 * 2. En Windows y Linux (cuando FFmpeg y una línea PCM Java Sound están disponibles), FFmpeg decodifica a PCM
 *    y Java Sound entrega las muestras al dispositivo, permitiendo volumen en vivo, ajuste suave de tempo (WSOLA),
 *    barrido pasa-altos / eco de salida y fundidos con EQ/bass-swap sin cortes ni chasquidos.
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

    internal interface PcmAnalysisConsumer {
        fun onSamples(samples: FloatArray, count: Int)
        fun onFinish(bassPulses: FloatArray, durationMs: Long)
    }

    internal var createAnalysisConsumer: ((trackId: String, file: File) -> PcmAnalysisConsumer?)? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var beats = FloatArray(0)
    @Volatile private var beatFile: String? = null
    @Volatile private var beatLength = 0L
    private var beatJob: Job? = null

    /**
     * Golpe de graves de lo que suena. La carátula lo lee cada ~20 ms y un bombo dura uno o dos trozos de
     * 10,7 ms: con un solo trozo muchos caían entre dos lecturas. Se da el más fuerte de los últimos ~32 ms.
     */
    fun beat(positionMs: Long): Float {
        if (!_state.value.playing || _state.value.buffering || activeFile?.absolutePath != beatFile) return 0f
        val position = if (useFfmpegPcm) {
            currentLine?.let { startOffsetMs + lineFrames(it) * 1000 / 48000 } ?: positionMs
        } else positionMs
        return DesktopBeat.peak(beats, position, BEAT_WINDOW)
    }

    @Volatile private var clockFrames = -1L
    @Volatile private var clockNanos = 0L

    /** La posición de la línea avanza a saltos de 10 ms (medido): entre salto y salto se sigue con el reloj. */
    private fun lineFrames(line: SourceDataLine): Long {
        val frames = line.longFramePosition
        val now = System.nanoTime()
        if (frames != clockFrames) {
            clockFrames = frames
            clockNanos = now
            return frames
        }
        return frames + ((now - clockNanos) * 48 / 1_000_000).coerceIn(0L, 480L)
    }

    private fun analyzeBeat(file: File, trackId: String?) {
        if (beatFile == file.absolutePath && beatLength == file.length()) return
        beatJob?.cancel()
        beats = FloatArray(0)
        beatFile = file.absolutePath
        beatLength = file.length()
        beatJob = scope.launch(Dispatchers.IO) {
            val consumer = trackId?.let { id -> createAnalysisConsumer?.invoke(id, file) }
            val result = runCatching {
                DesktopBeat.analyze(
                    file = file,
                    cancelled = { !isActive },
                    onChunk = consumer?.let { c -> { samples, count -> c.onSamples(samples, count) } },
                )
            }.getOrNull()
            if (isActive && result != null) {
                beats = result
                consumer?.onFinish(result, activeDurationMs)
            }
        }
    }

    /** Analiza de forma síncrona (para preparar métricas antes del fundido si aún no existen). */
    internal suspend fun analyzeSync(file: File, trackId: String): FloatArray? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val consumer = createAnalysisConsumer?.invoke(trackId, file)
            val result = runCatching {
                DesktopBeat.analyze(
                    file = file,
                    cancelled = { !isActive },
                    onChunk = consumer?.let { c -> { samples, count -> c.onSamples(samples, count) } },
                )
            }.getOrNull()
            if (result != null) {
                if (activeFile?.absolutePath == file.absolutePath) {
                    beats = result
                    beatFile = file.absolutePath
                    beatLength = file.length()
                }
                consumer?.onFinish(result, activeDurationMs)
            }
            result
        }

    fun cachedAudioFile(): File? = activeFile?.takeIf { it.exists() && it.length() > 0L }

    @Volatile private var currentProcess: Process? = null
    @Volatile private var currentLine: SourceDataLine? = null
    @Volatile private var playJob: Job? = null
    @Volatile private var downloadJob: Job? = null
    @Volatile private var tickerJob: Job? = null
    private var volumeJob: Job? = null

    @Volatile private var activeFile: File? = null
    @Volatile private var activeTrackId: String? = null
    @Volatile private var activeDurationMs: Long = 0L
    @Volatile private var startOffsetMs: Long = 0L
    @Volatile private var playStartedWallMs: Long = 0L
    @Volatile private var pausedPositionMs: Long = 0L
    @Volatile private var gainLinear: Float = 1f
    @Volatile private var targetTempo: Float = 1f
    @Volatile private var mixBands: MixBands = MixBands.NEUTRAL
    private val bandFilter = StereoBandFilter()
    private val timeStretch = WsolaTimeStretch()

    private val hasFfplay: Boolean by lazy {
        runCatching {
            DesktopTools.find("ffplay") != null
        }.getOrDefault(false)
    }
    private val hasFfmpegPcm: Boolean by lazy {
        canUseFfmpegPcm()
    }
    @Volatile private var pcmLineUnavailable = false
    private val useFfmpegPcm: Boolean get() = hasFfmpegPcm && !pcmLineUnavailable
    val supportsCrossfade: Boolean get() = useFfmpegPcm

    // La línea puede cerrarse a la vez desde el hilo de audio; sin línea no queda nada pendiente.
    internal fun pendingOutputMs(): Long = currentLine?.let { line ->
        runCatching { (line.bufferSize - line.available()).coerceAtLeast(0) * 1000L / 192_000L }.getOrDefault(0L)
    } ?: 0L

    init {
        cacheDir.mkdirs()
        Runtime.getRuntime().addShutdownHook(Thread { currentProcess?.destroyForcibly(); runCatching { currentLine?.close() } })
    }

    internal fun setMixBands(bands: MixBands) {
        mixBands = bands
    }

    internal fun resetMixBands() {
        mixBands = MixBands.NEUTRAL
    }

    fun setTempo(tempo: Float) {
        // Hasta ±8 % del cuadre (`DjTempoMatch`) más ±4 % de corrección de fase, sin recortar por un lado.
        val safe = if (tempo.isFinite()) tempo.coerceIn(0.85f, 1.15f) else 1f
        targetTempo = safe
        timeStretch.targetTempo = safe
    }

    fun setGain(linear: Float) {
        val previous = gainLinear
        val safe = if (linear.isFinite()) linear.coerceIn(0.0f, 2.0f) else 1f
        gainLinear = safe
        val process = currentProcess
        if (process != null && previous != gainLinear && !useFfmpegPcm) {
            volumeJob?.cancel()
            volumeJob = scope.launch {
                delay(120)
                val file = activeFile
                if (currentProcess === process && _state.value.playing && file != null && file.exists()) {
                    startPlaybackAt(file, _state.value.positionMs)
                }
            }
        }
        currentLine?.takeUnless { useFfmpegPcm }?.let { line ->
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
        trackId: String? = null,
        initialTempo: Float = 1f,
        resolveInitialPositionMs: ((durationMs: Long) -> Long)? = null,
    ) {
        stopInternal()
        activeTrackId = trackId
        activeDurationMs = durationMs.coerceAtLeast(0L)
        pausedPositionMs = initialPositionMs.coerceAtLeast(0L)
        startOffsetMs = initialPositionMs.coerceAtLeast(0L)
        setTempo(initialTempo)
        _state.value = EngineState(
            playing = playWhenReady,
            buffering = true,
            positionMs = startOffsetMs,
            durationMs = activeDurationMs,
            bufferedFraction = 0f,
        )

        val ext = if (codec.contains("opus", ignoreCase = true) || codec.contains("webm", ignoreCase = true)) "webm" else "m4a"
        val targetFile = File(cacheDir, "$cacheKey.$ext")
        activeFile = targetFile

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                if (!targetFile.exists() || targetFile.length() < MIN_PLAYABLE_BYTES || (contentLength != null && contentLength > 0L && targetFile.length() < contentLength)) {
                    val partial = File.createTempFile("oto-audio-", ".part", cacheDir)
                    try {
                        downloadChunked(streamUrl, headers, contentLength, partial) { downloaded, total ->
                            val frac = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
                            _state.value = _state.value.copy(bufferedFraction = frac)
                        }
                        currentCoroutineContext().ensureActive()
                        java.nio.file.Files.move(partial.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        partial.delete()
                    }
                } else {
                    _state.value = _state.value.copy(bufferedFraction = 1f)
                }
                currentCoroutineContext().ensureActive()
                if (activeFile != targetFile) return@launch
                if (!targetFile.exists() || targetFile.length() <= 0L) {
                    _state.value = _state.value.copy(playing = false, buffering = false)
                    onError(IllegalStateException("Archivo de audio vacío para $cacheKey"))
                    return@launch
                }
                _state.value = _state.value.copy(bufferedFraction = 1f)
                updateDuration(targetFile)
                val resolvedStart = if (initialPositionMs == 0L && resolveInitialPositionMs != null && activeDurationMs > 0L) {
                    clampStartPosition(resolveInitialPositionMs(activeDurationMs), activeDurationMs)
                } else {
                    clampStartPosition(initialPositionMs, activeDurationMs)
                }
                pausedPositionMs = resolvedStart
                startOffsetMs = resolvedStart
                if (playJob == null && _state.value.playing) {
                    startPlaybackAt(targetFile, resolvedStart)
                } else {
                    analyzeBeat(targetFile, activeTrackId)
                    _state.value = _state.value.copy(buffering = false, positionMs = resolvedStart)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.value = _state.value.copy(playing = false, buffering = false)
                Timber.w(t, "Error descargando stream para $cacheKey")
                onError(t)
            }
        }
    }

    /**
     * Reproduce un archivo que ya está en disco (una descarga), sin pedir nada a la red.
     * Devuelve false si el archivo no sirve.
     */
    fun loadLocalFile(
        file: File,
        durationMs: Long,
        playWhenReady: Boolean = true,
        initialPositionMs: Long = 0L,
        trackId: String? = null,
        initialTempo: Float = 1f,
        resolveInitialPositionMs: ((durationMs: Long) -> Long)? = null,
    ): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        stopInternal()
        activeFile = file
        activeTrackId = trackId
        activeDurationMs = durationMs.coerceAtLeast(0L)
        pausedPositionMs = initialPositionMs.coerceAtLeast(0L)
        startOffsetMs = initialPositionMs.coerceAtLeast(0L)
        setTempo(initialTempo)
        _state.value = EngineState(
            playing = playWhenReady,
            buffering = playWhenReady && (hasFfplay || useFfmpegPcm),
            positionMs = pausedPositionMs,
            durationMs = activeDurationMs,
            bufferedFraction = 1f,
        )
        downloadJob = scope.launch(Dispatchers.IO) {
            updateDuration(file)
            if (!isActive || activeFile != file) return@launch
            val resolvedStart = if (initialPositionMs == 0L && resolveInitialPositionMs != null && activeDurationMs > 0L) {
                clampStartPosition(resolveInitialPositionMs(activeDurationMs), activeDurationMs)
            } else {
                clampStartPosition(initialPositionMs, activeDurationMs)
            }
            pausedPositionMs = resolvedStart
            startOffsetMs = resolvedStart
            if (_state.value.playing) {
                startPlaybackAt(file, resolvedStart)
            } else {
                analyzeBeat(file, activeTrackId)
                _state.value = _state.value.copy(buffering = false, positionMs = resolvedStart)
            }
        }
        return true
    }

    private fun clampStartPosition(posMs: Long, durMs: Long): Long {
        val pos = posMs.coerceAtLeast(0L)
        return if (durMs > 1_000L) pos.coerceIn(0L, durMs - 500L) else pos
    }

    private fun updateDuration(file: File) {
        val actual = runCatching {
            val process = ProcessBuilder(
                DesktopTools.require("ffprobe"),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.absolutePath,
            ).redirectErrorStream(true).start()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            process.inputStream.bufferedReader().use { it.readText().trim().toDoubleOrNull()?.times(1000)?.toLong() }
        }.getOrNull()?.takeIf { it > 0 } ?: return
        if (activeFile != file) return
        activeDurationMs = actual
        _state.value = _state.value.copy(durationMs = actual)
        Timber.d("Duración de audio: %d ms", actual)
    }

    private fun startPlaybackAt(file: File, offsetMs: Long) {
        analyzeBeat(file, activeTrackId)
        killDecodeProcess()
        bandFilter.reset()
        timeStretch.reset(targetTempo)
        clockFrames = -1L
        clockNanos = 0L
        val safeOffset = clampStartPosition(offsetMs, activeDurationMs)
        startOffsetMs = safeOffset
        playStartedWallMs = System.currentTimeMillis()
        _state.value = _state.value.copy(
            playing = true,
            buffering = hasFfplay || useFfmpegPcm,
            positionMs = startOffsetMs,
        )

        if (!hasFfplay && !useFfmpegPcm) startTicker()

        playJob = scope.launch(Dispatchers.IO) {
            try {
                if (useFfmpegPcm) {
                    playWithFfmpeg(file, startOffsetMs)
                } else if (hasFfplay) {
                    playWithFfplay(file, startOffsetMs)
                } else {
                    playWithJavaSound(file, startOffsetMs)
                }
            } catch (t: Throwable) {
                if (isActive) {
                    _state.value = _state.value.copy(playing = false, buffering = false)
                    Timber.w(t, "Fallo en reproducción de escritorio")
                    onError(t)
                }
            }
        }
    }

    private suspend fun playWithFfplay(file: File, offsetMs: Long) {
        val sec = String.format(java.util.Locale.US, "%.3f", offsetMs / 1000.0)
        val vol = (gainLinear * 100f).toInt().coerceIn(0, 100)
        // `-ss` antes de `-i` ya posiciona en `sec` y reinicia PTS a 0; añadir `atrim=start=$sec` hacía doble salto.
        val pb = ProcessBuilder(
            DesktopTools.require("ffplay"),
            "-nodisp",
            "-autoexit",
            "-loglevel", "quiet",
            "-stats",
            "-ss", sec,
            "-volume", vol.toString(),
            file.absolutePath,
        )
        val proc = pb.start()
        currentProcess = proc
        if (!currentCoroutineContext().isActive) {
            proc.destroyForcibly()
            return
        }
        val status = StringBuilder()
        var decodedAudio = false
        val clockPattern = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s+(?:M-A|:)")
        proc.errorStream.bufferedReader().use { reader ->
            while (currentCoroutineContext().isActive && currentProcess === proc) {
                val ch = reader.read()
                if (ch < 0) break
                if (ch == '\r'.code || ch == '\n'.code) {
                    val seconds = clockPattern.find(status.toString())?.groupValues?.get(1)?.toDoubleOrNull()
                    if (seconds != null && currentProcess === proc && _state.value.playing) {
                        decodedAudio = true
                        val position = (offsetMs + (seconds * 1000).toLong()).coerceAtLeast(offsetMs)
                            .let { if (activeDurationMs > 0) it.coerceAtMost(activeDurationMs) else it }
                        _state.value = _state.value.copy(positionMs = position, buffering = false)
                    }
                    status.setLength(0)
                } else if (status.length < 1024) {
                    status.append(ch.toChar())
                }
            }
        }
        val code = proc.waitFor()
        if (currentCoroutineContext().isActive && currentProcess === proc && code == 0 && decodedAudio) {
            _state.value = _state.value.copy(playing = false, buffering = false, positionMs = activeDurationMs)
            onEnded()
        } else if (currentCoroutineContext().isActive && currentProcess === proc) {
            _state.value = _state.value.copy(playing = false, buffering = false)
            onError(IllegalStateException("No se pudo reproducir el audio (ffplay: $code)"))
        }
    }

    /**
     * FFmpeg decodifica a PCM s16le y Java Sound entrega las muestras al dispositivo.
     * Prefija los primeros trozos antes de arrancar la línea para evitar underruns iniciales y aplica un
     * micro-fade de 4 ms al arrancar/buscar para evitar chasquidos.
     */
    private suspend fun playWithFfmpeg(file: File, offsetMs: Long) {
        val line = try {
            AudioSystem.getSourceDataLine(PCM_FORMAT).also { it.open(PCM_FORMAT, LINE_BUFFER_BYTES) }
        } catch (err: Exception) {
            if (err is LineUnavailableException || err is IllegalArgumentException) {
                Timber.w(err, "Línea PCM no disponible; usando respaldo de escritorio")
                pcmLineUnavailable = true
                if (hasFfplay) {
                    playWithFfplay(file, offsetMs)
                } else {
                    startTicker()
                    playWithJavaSound(file, offsetMs)
                }
                return
            }
            throw err
        }
        var process: Process? = null
        try {
            currentLine = line
            val proc = ProcessBuilder(ffmpegPcmCommand(DesktopTools.require("ffmpeg"), file, offsetMs))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process = proc
            currentProcess = proc
            currentCoroutineContext().ensureActive()

            var lineStarted = false
            var prefillBytes = 0
            var emittedFrames = 0L
            var microFadeRemaining = if (gainLinear > 0.05f) MICRO_FADE_IN_FRAMES else 0
            var decoded = false

            fun writeChunk(pcm: ByteArray, len: Int) {
                if (len <= 0 || currentProcess !== proc || currentLine !== line) return
                if (microFadeRemaining > 0) {
                    applyMicroFadeIn(pcm, len, microFadeRemaining)
                    microFadeRemaining = (microFadeRemaining - len / 4).coerceAtLeast(0)
                }
                bandFilter.processPcm16(pcm, len, mixBands, gainLinear, inputScale = 1f / PCM_DECODE_GAIN)
                line.write(pcm, 0, len)
                emittedFrames += len / 4
                decoded = true
                if (!lineStarted) {
                    prefillBytes += len
                    if (prefillBytes >= PREFILL_BYTES) {
                        line.start()
                        lineStarted = true
                        _state.value = _state.value.copy(buffering = false, positionMs = offsetMs)
                    }
                } else {
                    val hwFrames = line.longFramePosition
                    val queuedFrames = (emittedFrames - hwFrames).coerceAtLeast(0L)
                    val playedSourceFrames = (timeStretch.sourceFramesConsumed - queuedFrames).coerceAtLeast(0L)
                    val position = offsetMs + playedSourceFrames * 1000L / 48_000L
                    _state.value = _state.value.copy(
                        buffering = false,
                        positionMs = if (activeDurationMs > 0L) position.coerceAtMost(activeDurationMs) else position,
                    )
                }
            }

            proc.inputStream.buffered().use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                while (currentCoroutineContext().isActive && currentProcess === proc && currentLine === line) {
                    val count = input.readNBytes(buffer, 0, buffer.size)
                    if (count == 0) break
                    currentCoroutineContext().ensureActive()
                    if (currentProcess !== proc || currentLine !== line) return
                    val aligned = count - (count % PCM_FORMAT.frameSize)
                    if (aligned <= 0) continue
                    timeStretch.process(buffer, aligned) { outBytes, outLen ->
                        writeChunk(outBytes, outLen)
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            if (currentProcess !== proc || currentLine !== line) return
            timeStretch.flush { outBytes, outLen -> writeChunk(outBytes, outLen) }
            if (!lineStarted && decoded) {
                line.start()
                lineStarted = true
                _state.value = _state.value.copy(buffering = false)
            }
            if (lineStarted) line.drain()
            check(proc.waitFor() == 0 && decoded) { "No se pudo decodificar el audio con FFmpeg" }
            currentCoroutineContext().ensureActive()
            if (currentProcess !== proc || currentLine !== line) return
            _state.value = _state.value.copy(playing = false, buffering = false, positionMs = activeDurationMs)
            onEnded()
        } finally {
            if (currentProcess === process) currentProcess = null
            if (currentLine === line) currentLine = null
            runCatching { process?.destroyForcibly() }
            runCatching { line.stop(); line.close() }
        }
    }

    private fun applyMicroFadeIn(buffer: ByteArray, byteCount: Int, remainingFrames: Int) {
        val total = MICRO_FADE_IN_FRAMES
        val doneStart = total - remainingFrames
        val frames = byteCount / 4
        var i = 0
        for (f in 0 until frames) {
            val step = doneStart + f
            if (step >= total) break
            val scale = step.toFloat() / total.toFloat()
            val l = (((buffer[i + 1].toInt() and 255) shl 8) or (buffer[i].toInt() and 255)).toShort().toFloat() * scale
            val r = (((buffer[i + 3].toInt() and 255) shl 8) or (buffer[i + 2].toInt() and 255)).toShort().toFloat() * scale
            val sL = l.toInt().coerceIn(-32768, 32767)
            val sR = r.toInt().coerceIn(-32768, 32767)
            buffer[i] = sL.toByte()
            buffer[i + 1] = (sL shr 8).toByte()
            buffer[i + 2] = sR.toByte()
            buffer[i + 3] = (sR shr 8).toByte()
            i += 4
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
        } else if (downloadJob?.isActive == true) {
            _state.value = _state.value.copy(playing = true)
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
        gainLinear = 1f
        targetTempo = 1f
        activeTrackId = null
        _state.value = EngineState()
    }

    private fun stopInternal() {
        mixBands = MixBands.NEUTRAL
        bandFilter.reset()
        timeStretch.reset(1f)
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
        private const val LINE_BUFFER_BYTES = 24576 // 128 ms a 48 kHz estéreo 16-bit
        private const val CHUNK_BYTES = 2048 // 10,67 ms (512 frames) por bloque
        private const val PREFILL_BYTES = 12288 // ~64 ms precargados antes de arrancar la línea (evita underruns al abrir mezcla)
        private const val MICRO_FADE_IN_FRAMES = 192 // 4 ms de entrada suave al arrancar/buscar
        /** Trozos de 10,7 ms que mira [beat]: ~32 ms, algo más que lo que tarda la carátula entre lecturas. */
        private const val BEAT_WINDOW = 3
    }
}
