package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.lyrics.LyricsQuery
import io.github.iaguito22.oto.lyrics.LyricsStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * No es una prueba: planifica mezclas del DJ rápido entre canciones ya analizadas (una copia de la caché del PC) y
 * cuenta los estilos. Sirve para medir sin abrir Oto cuántas salen como fundido simple y cuánto ayudaría elegir por
 * tempo. Solo corre con `OTO_PROBE_CACHE=<carpeta con audio-analysis.json>`; `OTO_PROBE_LYRICS` (letras guardadas),
 * `OTO_PROBE_PAIRS` (por escenario), `OTO_PROBE_FADE_MS` (mezcla configurada).
 */
class AdhdStyleProbeTest {
    @Test
    fun run() = runBlocking {
        val dir = System.getenv("OTO_PROBE_CACHE")?.let(::File)
        assumeTrue(dir != null && File(dir, "audio-analysis.json").exists())
        val lyricsDir = System.getenv("OTO_PROBE_LYRICS")?.let(::File)
        val pairs = System.getenv("OTO_PROBE_PAIRS")?.toInt() ?: 400
        val fadeMs = System.getenv("OTO_PROBE_FADE_MS")?.toLong() ?: AdhdPlanner.DEFAULT_MIX_MS
        val cache = AudioAnalysisCache(File(dir, "audio-analysis.json"))
        val ids = JSONObject(File(dir, "audio-analysis.json").readText()).let { root ->
            (root.optJSONObject("items") ?: root.optJSONObject("tracks") ?: root).keys().asSequence().toList()
        }.filter { cache.get(it)?.let { m -> m.durationMs > 60_000L } == true }
        val store = lyricsDir?.let(::LyricsStore)
        val lyrics = ids.associateWith { id ->
            if (store != null && File(lyricsDir, "$id.json").exists()) {
                store.get(LyricsQuery(id, "", "", null, cache.get(id)!!.durationMs)).lyrics as? Lyrics.Synced
            } else null
        }
        val sections = ids.associateWith { id -> lyrics[id]?.let { SongSections.of(it.lines) }.orEmpty() }
        val segs = ids.associateWith { id ->
            val m = cache.get(id)!!
            Track(id, id, listOf("x"), seconds = (m.durationMs / 1000).toInt())
                .adhdSegment(m.durationMs, fadeMs, DjMood.Auto, sections[id].orEmpty(), m)
        }
        println("canciones: ${ids.size}, con letra sincronizada: ${lyrics.values.count { it != null }}, mezcla $fadeMs ms")

        fun fits(a: String, b: String): Boolean = DjTempoMatch.tempoVerdict(cache.tempoProfile(a), cache.tempoProfile(b), DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT).fit ==
            DjTempoMatch.TempoFit.Fits

        fun key(a: String, b: String): Boolean = DjHarmonic.harmonicCompatibility(cache.get(a), cache.get(b)) >= 0.45
        fun kickIn(b: String): Boolean {
            val start = segs[b]?.startMs ?: 0L
            return DjTempoMatch.kickOnBeat(cache.get(b)!!.bars, start, start + 8_000L) >= DjTempoMatch.MIN_KICK_ON_BEAT
        }
        val pick: Map<String, (String, String) -> Boolean> = linkedMapOf(
            "azar" to { _, _ -> true },
            "tempo" to { a, b -> fits(a, b) },
            "tempo+tono" to { a, b -> fits(a, b) && key(a, b) },
            "tempo+tono+bombo" to { a, b -> fits(a, b) && key(a, b) && kickIn(b) },
        )
        for ((scenario, ok) in pick) {
            val random = Random(11)
            val counts = sortedMapOf<String, Int>()
            val why = sortedMapOf<String, Int>()
            val recent = ArrayList<DjBridgeStyle>()
            var lock = 0
            var late = 0
            var done = 0
            var tries = 0
            while (done < pairs && tries < pairs * 20) {
                tries++
                val a = ids.random(random)
                val b = if (scenario == "azar") ids.random(random) else ids.filter { it != a && ok(a, it) }.randomOrNull(random) ?: continue
                if (a == b) continue
                val ma = cache.get(a)!!
                val mb = cache.get(b)!!
                val segA = segs[a] ?: continue
                val plan = DjTransitionPlanner.plan(
                    outgoingDurationMs = ma.durationMs,
                    configuredFadeMs = fadeMs,
                    outgoingAudio = ma,
                    incomingAudio = mb,
                    outgoingAdhd = segA,
                    incomingAdhd = segs[b],
                    outgoingVocals = sections[a].orEmpty(),
                    incomingVocals = sections[b].orEmpty(),
                    autoLength = true,
                    incomingDurationMs = mb.durationMs,
                    outgoingLyrics = lyrics[a],
                    incomingLyrics = lyrics[b],
                    djMode = DjMode.Adhd,
                    isDjSession = true,
                    recentStyles = recent.takeLast(4),
                )
                done++
                val key = when {
                    plan.fadeDurationMs <= 0L -> "sin mezcla"
                    plan.bridgeSpec == null -> "${plan.style} (sin puente)"
                    else -> "${plan.bridgeSpec.style}${if (plan.phaseLock) " a tempo" else ""}"
                }
                counts[key] = (counts[key] ?: 0) + 1
                if (plan.fadeDurationMs <= 0L && System.getenv("OTO_PROBE_NOMIX") != null) println("    sin mezcla · ${plan.reason} · ${plan.bridgeSpec?.tempoNote.orEmpty()} · A ${segA.startMs}-${segA.endMs} B ${segs[b]?.startMs}")
                if (plan.phaseLock) lock++
                if (!plan.phaseLock && plan.fadeDurationMs > 0L) {
                    val note = plan.bridgeSpec?.tempoNote.orEmpty()
                    val reason = Regex("cuadre cerrado: ([^)(0-9]+)").find(note)?.groupValues?.get(1)?.trim()
                        ?: Regex("descartes de cuadre: (.*)").find(note)?.groupValues?.get(1)?.let { d ->
                            val n = Regex("([a-z]+) ([0-9]+)").findAll(d).map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()
                            val top = n.maxByOrNull { it.second }
                            if (top == null || top.second == 0) "sin candidata de cuadre" else "descarte: ${top.first}"
                        } ?: "otro"
                    why[reason] = (why[reason] ?: 0) + 1
                    if (System.getenv("OTO_PROBE_NOTES") != null && scenario == "tempo") println("    $key · $note")
                }
                if (plan.fadeDurationMs > 0L && plan.outgoingFadeStartMs > segA.endMs) late++
                plan.bridgeSpec?.style?.let { recent += it }
            }
            println("== $scenario: $done mezclas, a tempo $lock, empiezan tras el trozo $late")
            counts.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("  %-34s %4d  %4.1f %%".format(k, v, 100.0 * v / done)) }
            println("  por qué no cuadra:")
            why.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("    %-32s %4d".format(k, v)) }
        }
    }
}
