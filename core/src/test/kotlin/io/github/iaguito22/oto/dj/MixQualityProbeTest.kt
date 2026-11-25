package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.lyrics.LyricsQuery
import io.github.iaguito22.oto.lyrics.LyricsStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * No es una prueba: planifica mezclas del DJ (rápido y normal) entre canciones ya analizadas y con letra sincronizada
 * (una copia de la caché del PC) y cuenta lo que Iago oye mal en una escucha: salidas cantando o en un estribillo,
 * entradas cantando o a mitad de estribillo, saltos de intensidad, respaldos y mezclas a tempo. Sirve para comparar
 * versiones del planificador sin escuchar. Solo corre con `OTO_PROBE_CACHE=<carpeta con audio-analysis.json>` y
 * `OTO_PROBE_LYRICS=<carpeta de letras>`; `OTO_PROBE_PAIRS` (por modo), `OTO_PROBE_SHOW` (ejemplos por fallo).
 */
class MixQualityProbeTest {
    @Test
    fun run() = runBlocking {
        val dir = System.getenv("OTO_PROBE_CACHE")?.let(::File)
        val lyricsDir = System.getenv("OTO_PROBE_LYRICS")?.let(::File)
        assumeTrue(dir != null && File(dir, "audio-analysis.json").exists() && lyricsDir != null)
        val pairs = System.getenv("OTO_PROBE_PAIRS")?.toInt() ?: 400
        val show = System.getenv("OTO_PROBE_SHOW")?.toInt() ?: 0
        val cache = AudioAnalysisCache(File(dir, "audio-analysis.json"))
        val store = LyricsStore(lyricsDir!!)
        val all = JSONObject(File(dir, "audio-analysis.json").readText()).let { root ->
            (root.optJSONObject("items") ?: root.optJSONObject("tracks") ?: root).keys().asSequence().toList()
        }.filter { cache.get(it)?.let { m -> m.durationMs > 90_000L && m.coversWholeSong } == true }
        val lyrics = all.associateWith { id ->
            if (File(lyricsDir, "$id.json").exists()) store.get(LyricsQuery(id, "", "", null, cache.get(id)!!.durationMs)).lyrics as? Lyrics.Synced else null
        }
        val ids = all.filter { lyrics[it] != null }
        val sections = ids.associateWith { SongSections.of(lyrics[it]!!.lines) }
        println("canciones analizadas enteras: ${all.size}, con letra sincronizada: ${ids.size}")

        for (mode in listOf(DjMode.Adhd, DjMode.Normal)) {
            val fadeMs = if (mode == DjMode.Adhd) AdhdPlanner.DEFAULT_MIX_MS else 10_000L
            val segs = ids.associateWith { id ->
                if (mode != DjMode.Adhd) null else {
                    val m = cache.get(id)!!
                    Track(id, id, listOf("x"), seconds = (m.durationMs / 1000).toInt())
                        .adhdSegment(m.durationMs, fadeMs, DjMood.Auto, sections[id].orEmpty(), m)
                }
            }
            val random = Random(23)
            val recent = ArrayList<DjBridgeStyle>()
            val count = sortedMapOf<String, Int>()
            val styles = sortedMapOf<String, Int>()
            val examples = HashMap<String, MutableList<String>>()
            fun hit(k: String, ex: String) {
                count[k] = (count[k] ?: 0) + 1
                if (show > 0) examples.getOrPut(k) { ArrayList() }.let { if (it.size < show) it += ex }
            }
            var done = 0
            while (done < pairs) {
                val a = ids.random(random)
                val b = ids.random(random)
                if (a == b) continue
                val ma = cache.get(a)!!
                val mb = cache.get(b)!!
                val la = lyrics[a]!!
                val lb = lyrics[b]!!
                val sa = sections[a].orEmpty()
                val sb = sections[b].orEmpty()
                var lastTrace = ""
                DjBridgePlanner.trace = { lastTrace = it }
                val plan = DjTransitionPlanner.plan(
                    outgoingDurationMs = ma.durationMs,
                    configuredFadeMs = fadeMs,
                    outgoingAudio = ma,
                    incomingAudio = mb,
                    outgoingAdhd = segs[a],
                    incomingAdhd = segs[b],
                    outgoingVocals = sa,
                    incomingVocals = sb,
                    autoLength = true,
                    incomingDurationMs = mb.durationMs,
                    outgoingLyrics = la,
                    incomingLyrics = lb,
                    djMode = mode,
                    isDjSession = true,
                    recentStyles = recent.takeLast(4),
                )
                done++
                val spec = plan.bridgeSpec
                val style = spec?.style?.name ?: if (plan.fadeDurationMs <= 0L) "sin mezcla" else plan.style.name
                styles[style] = (styles[style] ?: 0) + 1
                spec?.style?.let { recent += it }
                val ex = "$a→$b ${spec?.style} A ${spec?.cueOutStartMs}-${spec?.cueOutEndMs} B ${spec?.entryInMs} · ${plan.reason.take(90)}"
                val reason = plan.reason
                if (reason.startsWith("Enlace al final")) hit("enlace al final (corte al acabar A)", ex)
                if (System.getenv("OTO_PROBE_TRACE") != null && (reason.startsWith("Enlace al final") || reason.startsWith("Sin mezcla"))) {
                    println("  ·· ${mode.name} ${reason.take(14)} $a→$b · $lastTrace")
                }
                if (reason.startsWith("Enlace al final") && System.getenv("OTO_PROBE_OUTRO") != null && (count["enlace al final (corte al acabar A)"] ?: 0) <= 4) {
                    println("  ·· enlace $a→$b durA ${ma.durationMs} · ${spec?.tempoNote?.take(200)}")
                    sa.filter { it.endMs > ma.durationMs - 80_000L }.forEach { println("      A sección ${it.startMs}–${it.endMs}${if (it.isChorus) " estribillo" else ""}") }
                    la.lines.filter { it.end > ma.durationMs - 80_000L }.forEach { l -> println("      A línea ${l.start}–${(l.words.maxOfOrNull { w -> w.end } ?: l.end)} ${l.text.take(40)}") }
                    sb.filter { it.startMs < 50_000L }.forEach { println("      B sección ${it.startMs}–${it.endMs}${if (it.isChorus) " estribillo" else ""}") }
                }
                else if (reason.startsWith("Sin mezcla") || reason.startsWith("Fundido tras") || reason.startsWith("Fundido corto") || reason.startsWith("Fundido antes")) hit("respaldo", ex)
                if (plan.phaseLock) hit("a tempo", ex)
                if (spec == null || spec.transitionDurationMs <= 0L) continue
                val dur = spec.transitionDurationMs
                val outStart = spec.cueOutStartMs
                val inSpan = (dur * spec.tempoMatchRatio).roundToLong()
                // Salida: A canta mientras aún se oye (primera mitad de la mezcla) o se corta a mitad de verso.
                val exitSings = when (spec.style) {
                    DjBridgeStyle.EnergyDropCut -> DjLyricMatcher.cutsWordOrPhrase(la, spec.cueOutEndMs, allowChorusSeam = true)
                    DjBridgeStyle.LyricLoopCut -> false
                    else -> DjLyricMatcher.isMidVocal(la, outStart) ||
                        (sungRatio(la, outStart, outStart + dur * 6 / 10) ?: 0.0) > 0.25
                }
                if (exitSings) hit("sale cantando A", ex)
                if (exitSings && System.getenv("OTO_PROBE_DEBUG") != null && (count["sale cantando A"] ?: 0) <= 6) {
                    val sg = segs[a]
                    println("  ·· $a (${mode.name}) trozo ${sg?.startMs}–${sg?.endMs} salida prevista ${sg?.fadeOutStartMs} · elegido ${spec.style} ${spec.cueOutStartMs}–${spec.cueOutEndMs} · ${spec.reason.take(60)} · ${spec.tempoNote.take(120)}")
                    sa.filter { it.endMs > outStart - 25_000L && it.startMs < outStart + 15_000L }.forEach {
                        println("      sección ${it.startMs}–${it.endMs}${if (it.isChorus) " estribillo" else ""}")
                    }
                    la.lines.filter { it.end > outStart - 8_000L && it.start < outStart + 8_000L }.forEach { l ->
                        println("      línea ${l.start}–${(l.words.maxOfOrNull { w -> w.end } ?: l.end)} (fin ${l.end}) ${l.text.take(50)}")
                    }
                }
                val exitEnd = if (spec.style == DjBridgeStyle.EnergyDropCut) spec.cueOutEndMs else outStart + dur
                if (spec.style != DjBridgeStyle.LyricLoopCut && SongSections.overlapsChorus(outStart, exitEnd, sa)) hit("pisa un estribillo de A", ex)
                // Entrada: B canta cuando ya se oye (segunda mitad) o entra a mitad de estribillo.
                // En el corte B entra entera tras el silencio: cuenta su entrada misma.
                val audibleFrom = if (spec.style == DjBridgeStyle.EnergyDropCut) spec.entryInMs else spec.entryInMs + inSpan / 3
                // Entrar justo al empezar una frase (desde su primera palabra) no es entrar a mitad de letra.
                val startsPhrase = lb.lines.any { l -> val w = l.words.minOfOrNull { it.start } ?: l.start; w in (spec.entryInMs - 60L)..(spec.entryInMs + 250L) }
                val entryMid = !startsPhrase && (DjLyricMatcher.isMidVocal(lb, audibleFrom) ||
                    (spec.style != DjBridgeStyle.EnergyDropCut && (sungRatio(lb, spec.entryInMs, audibleFrom) ?: 0.0) > 0.30))
                if (spec.style != DjBridgeStyle.LyricLoopCut && entryMid) { hit("entra a mitad de letra B", ex); hit("  · B a mitad de letra con ${spec.style}", ex) }
                if (entryMid && System.getenv("OTO_PROBE_BDEBUG") != null && (count["entra a mitad de letra B"] ?: 0) <= 5) {
                    println("  ·· B $b (${mode.name}) trozo ${segs[b]?.startMs} entra ${spec.entryInMs} oye desde $audibleFrom ${spec.style} · ${spec.reason.take(50)} · $lastTrace".take(400))
                    lb.lines.filter { it.end > spec.entryInMs - 3_000L && it.start < spec.entryInMs + 8_000L }.forEach { l ->
                        println("      línea ${l.start}–${(l.words.maxOfOrNull { w -> w.end } ?: l.end)} real ${l.real} ${l.text.take(40)}")
                    }
                }
                else if (spec.style != DjBridgeStyle.LyricLoopCut && (sungRatio(lb, audibleFrom, spec.entryInMs + inSpan) ?: 0.0) > 0.40) hit("B empieza a cantar en la mezcla", ex)
                if (sb.any { it.isChorus && spec.entryInMs > it.startMs + 400L && spec.entryInMs < it.endMs }) hit("entra a mitad de estribillo de B", ex)
                if (spec.style != DjBridgeStyle.LyricLoopCut && spec.style != DjBridgeStyle.EnergyDropCut) {
                    val aSing = sungRatio(la, outStart, outStart + dur) ?: 0.0
                    val bSing = sungRatio(lb, spec.entryInMs, spec.entryInMs + inSpan) ?: 0.0
                    if (aSing > 0.3 && bSing > 0.3) hit("cantan las dos a la vez", ex)
                }
                // Intensidad: lo que suena de A al empezar a irse frente a lo que suena de B cuando ya manda.
                val barA = ma.bars.lastOrNull { it.startMs <= outStart } ?: ma.bars.firstOrNull()
                val barB = mb.bars.lastOrNull { it.startMs <= spec.entryInMs + inSpan } ?: mb.bars.firstOrNull()
                if (barA != null && barB != null) {
                    val dLufs = barB.lufs - barA.lufs
                    val dDrum = barB.drumEnergy - barA.drumEnergy
                    if (dLufs > 4.0 || dDrum > 0.35) hit("B entra mucho más fuerte", ex)
                    if (dLufs < -4.0 || dDrum < -0.35) hit("B entra mucho más floja", ex)
                }
            }
            println("== ${if (mode == DjMode.Adhd) "DJ rápido" else "DJ normal"}: $done mezclas (mezcla $fadeMs ms)")
            styles.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("  estilo %-22s %4d  %5.1f %%".format(k, v, 100.0 * v / done)) }
            count.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                println("  %-36s %4d  %5.1f %%".format(k, v, 100.0 * v / done))
                examples[k]?.forEach { println("      $it") }
            }
        }
    }
}

/** Parte de `[from, to]` en la que se canta de verdad: por las palabras (la línea de LRCLIB dura hasta la siguiente). */
private fun sungRatio(l: Lyrics.Synced?, from: Long, to: Long): Double? {
    if (l == null || to <= from) return null
    var covered = 0L
    var prevEnd = from
    for (line in l.lines.sortedBy { it.start }) {
        val words = line.words + line.bg
        val end = words.maxOfOrNull { it.end } ?: continue
        val s = maxOf(from, line.start, prevEnd)
        val e = minOf(to, end)
        if (e > s) { covered += e - s; prevEnd = e }
    }
    return covered.toDouble() / (to - from)
}
