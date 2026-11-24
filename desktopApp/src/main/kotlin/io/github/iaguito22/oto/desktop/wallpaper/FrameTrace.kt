package io.github.iaguito22.oto.desktop.wallpaper

import timber.log.Timber
import java.lang.management.ManagementFactory

/**
 * Traza de cuadros del fondo vivo, solo con `OTO_FONDO_TRAZA=<archivo>` (pruebas; las líneas se añaden a ese archivo). Cada 5 s escribe los cuadros por segundo,
 * el intervalo típico y los peores, cuántos cuadros han llegado tarde (más de 1,5 veces lo esperado), el tiempo de
 * cálculo y de dibujo y las pausas del recolector de memoria. Apagada no hace nada.
 */
internal object FrameTrace {
    private val target = System.getenv("OTO_FONDO_TRAZA")
    val enabled = target != null
    private val gaps = LongArray(4096); private var n = 0
    private var late = 0; private var workSum = 0L; private var workMax = 0L; private var drawSum = 0L; private var drawMax = 0L; private var draws = 0
    private var since = System.nanoTime(); private var expected = 0L
    private var gcCount = 0L; private var gcMs = 0L

    fun reset() { if (enabled) { n = 0; late = 0; workSum = 0; workMax = 0; drawSum = 0; drawMax = 0; draws = 0; since = System.nanoTime(); gcBase() } }

    fun frame(gapNs: Long, intervalNs: Long) {
        if (!enabled) return
        if (n < gaps.size) gaps[n++] = gapNs
        expected = if (intervalNs > 0) intervalNs else expected.takeIf { it > 0 } ?: 8_333_333L
        if (gapNs > expected * 3 / 2) late++
        if (gapNs > 60_000_000L) note("cuadro de %.0f ms".format(gapNs / 1e6))
        if (System.nanoTime() - since > 5_000_000_000L) report()
    }

    /** Apunta algo suelto con la hora (para ver qué coincide con un cuadro largo). */
    fun note(what: String) { if (enabled) target?.takeIf { it.length > 1 }?.let { runCatching { java.io.File(it).appendText("%tT.%<tL · %s\n".format(System.currentTimeMillis(), what)) } } }

    fun work(ns: Long) { if (enabled) { workSum += ns; workMax = maxOf(workMax, ns) } }
    fun draw(ns: Long) { if (enabled) { drawSum += ns; drawMax = maxOf(drawMax, ns); draws++ } }

    private fun gcBase() {
        val beans = ManagementFactory.getGarbageCollectorMXBeans()
        gcCount = beans.sumOf { it.collectionCount.coerceAtLeast(0) }; gcMs = beans.sumOf { it.collectionTime.coerceAtLeast(0) }
    }

    private fun report() {
        val secs = (System.nanoTime() - since) / 1e9
        val sorted = gaps.copyOf(n).also { it.sort() }
        fun pct(q: Double) = if (n == 0) 0.0 else sorted[((n - 1) * q).toInt()] / 1e6
        val beans = ManagementFactory.getGarbageCollectorMXBeans()
        val gc = beans.sumOf { it.collectionCount.coerceAtLeast(0) } - gcCount; val gcT = beans.sumOf { it.collectionTime.coerceAtLeast(0) } - gcMs
        val line = "TRAZA · %.1f fps · intervalo p50 %.1f p95 %.1f p99 %.1f máx %.1f ms · tarde %d de %d · cálculo %.2f (máx %.1f) ms · dibujo %.2f (máx %.1f) ms · GC %d (%d ms)".format(
            n / secs, pct(.5), pct(.95), pct(.99), pct(1.0), late, n, workSum / 1e6 / maxOf(1, n), workMax / 1e6, drawSum / 1e6 / maxOf(1, draws), drawMax / 1e6, gc, gcT)
        println(line); Timber.i(line)
        target?.takeIf { it.length > 1 }?.let { runCatching { java.io.File(it).appendText(line + "\n") } }
        reset()
    }
}
