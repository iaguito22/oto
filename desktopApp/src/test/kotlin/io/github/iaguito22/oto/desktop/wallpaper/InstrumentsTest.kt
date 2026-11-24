package io.github.iaguito22.oto.desktop.wallpaper

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cada instrumento del fondo vivo con música sintética: golpes donde toca, sin golpes falsos de los otros
 * (el bajo con glissandos no es bombo, la voz no es caja ni platos) y la energía, las subidas, los drops y los respiros.
 * Se simula como en la app: 120 cuadros por segundo leyendo las últimas 2048 muestras de lo que suena.
 */
class InstrumentsTest {
    private val sr = 48_000

    // ── instrumentos sintéticos ──
    private fun kick(o: FloatArray, at: Int, amp: Float = .9f) {
        var ph = 0.0
        for (i in 0 until (.35 * sr).toInt()) { val k = at + i; if (k >= o.size) break; val t = i.toDouble() / sr
            ph += 2 * PI * (48 + 100 * exp(-t / .03)) / sr; o[k] += (amp * exp(-t / .12) * sin(ph)).toFloat() }
    }
    private fun snare(o: FloatArray, at: Int, r: Random, amp: Float = .55f) {
        var prev = 0f
        for (i in 0 until (.25 * sr).toInt()) { val k = at + i; if (k >= o.size) break; val t = i.toDouble() / sr
            val n = r.nextFloat() * 2 - 1; val hp = n - prev; prev = n
            o[k] += (amp * (exp(-t / .07) * hp * .8 + exp(-t / .05) * sin(2 * PI * 190 * t) * .6)).toFloat() }
    }
    private fun hat(o: FloatArray, at: Int, r: Random, amp: Float = .3f) {
        var a = 0f; var b = 0f; var c = 0f
        for (i in 0 until (.08 * sr).toInt()) { val k = at + i; if (k >= o.size) break; val t = i.toDouble() / sr
            val n = r.nextFloat() * 2 - 1; val d1 = n - a; a = n; val d2 = d1 - b; b = d1; val d3 = d2 - c; c = d2
            o[k] += (amp * exp(-t / .02) * d3 * .35).toFloat() }
    }
    /** Bajo legato con glissandos entre notas (como un 808 que se desliza): no debe sonar a bombo. */
    private fun bass(o: FloatArray, from: Int, to: Int, noteSec: Double = .5, amp: Float = .5f) {
        val notes = doubleArrayOf(55.0, 49.0, 65.4, 58.3, 43.6, 73.4); var ph = 0.0; var f = notes[0]
        for (k in from until minOf(to, o.size)) { val t = (k - from).toDouble() / sr
            val target = notes[((t / noteSec).toInt()) % notes.size]; f += (target - f) * (1 - exp(-1.0 / (sr * .06)))
            ph += 2 * PI * f / sr
            val att = minOf(1.0, (k - from).toDouble() / (sr * .05))
            o[k] += (amp * att * (sin(ph) + .35 * sin(2 * ph) + .15 * sin(3 * ph))).toFloat() }
    }
    /** Voz: tono armónico con formantes y vibrato; notas ligadas que cambian cada [noteSec]. */
    private fun voice(o: FloatArray, from: Int, to: Int, amp: Float = .3f, noteSec: Double = .35) {
        val notes = doubleArrayOf(220.0, 247.0, 262.0, 294.0, 262.0, 196.0); var ph = 0.0
        val formant = DoubleArray(20) { h -> val f = (h + 1) * 240.0; exp(-((f - 700) / 300).let { it * it }) + .6 * exp(-((f - 1200) / 350).let { it * it }) + .35 * exp(-((f - 2600) / 500).let { it * it }) + .05 }
        for (k in from until minOf(to, o.size)) { val t = (k - from).toDouble() / sr
            val f0 = notes[((t / noteSec).toInt()) % notes.size] * (1 + .012 * sin(2 * PI * 5.5 * t)); ph += 2 * PI * f0 / sr
            var v = 0.0; for (h in 0 until 16) v += formant[h] * sin((h + 1) * ph)
            val att = minOf(1.0, (k - from).toDouble() / (sr * .08))
            o[k] += (amp * att * v / 3).toFloat() }
    }

    private class Run(val t: FloatArray, val v: Map<String, FloatArray>)

    /** Pasa [sig] por el espectro a 120 cuadros por segundo y guarda cada instrumento en cada cuadro. */
    private fun run(sig: FloatArray): Run {
        val s = Spectrum(); val fps = 120; val hop = sr / fps; val frames = sig.size / hop
        val names = listOf("kick", "snare", "hat", "bass", "voice", "energy", "build", "drop", "slow")
        val out = names.associateWith { FloatArray(frames) }; val times = FloatArray(frames)
        for (f in 0 until frames) {
            val end = (f + 1) * hop
            for (i in s.samples.indices) { val k = end - s.samples.size + i; s.samples[i] = if (k < 0) 0f else sig[k] }
            s.update(true, 1f / fps, .5f)
            times[f] = end.toFloat() / sr
            out.getValue("kick")[f] = s.kick; out.getValue("snare")[f] = s.snare; out.getValue("hat")[f] = s.hat
            out.getValue("bass")[f] = s.bass; out.getValue("voice")[f] = s.voice; out.getValue("energy")[f] = s.energy
            out.getValue("build")[f] = s.build; out.getValue("drop")[f] = s.drop; out.getValue("slow")[f] = s.slow
        }
        return Run(times, out)
    }

    /** Golpes: cuando el valor pasa de menos de .3 a más de .55 (como las ondas del bombo). */
    private fun hits(r: Run, name: String, from: Float = 1f): List<Float> {
        val v = r.v.getValue(name); val out = ArrayList<Float>(); var armed = true
        for (i in v.indices) {   // (un golpe que empezó antes de [from] no cuenta al llegar a [from])
            if (armed && v[i] > .55f) { if (r.t[i] >= from) out += r.t[i]; armed = false } else if (v[i] < .3f) armed = true }
        return out
    }

    private data class Score(val found: Int, val truth: Int, val extra: Int, val lagMs: Float) {
        val recall get() = if (truth == 0) 1f else found.toFloat() / truth
        override fun toString() = "aciertos $found/$truth, falsos $extra, retraso medio ${lagMs.toInt()} ms"
    }
    private fun score(got: List<Float>, truth: List<Float>, from: Float = 1f): Score {
        val tr = truth.filter { it >= from }; val used = BooleanArray(got.size); var found = 0; var lag = 0f
        for (t in tr) { val i = got.indices.firstOrNull { !used[it] && got[it] - t in -.02f..(.07f) } ?: continue; used[i] = true; found++; lag += got[i] - t }
        return Score(found, tr.size, used.count { !it }, if (found > 0) lag / found * 1000 else 0f)
    }

    private fun beatsAt(bpm: Double, secs: Double, every: Double = 1.0, offset: Double = 0.0): List<Int> =
        generateSequence(offset * 60 / bpm) { it + every * 60 / bpm }.takeWhile { it < secs }.map { (it * sr).toInt() }.toList()

    @Test fun cadaGolpeEnSuSitioConTodoSonando() {
        val secs = 10.0; val sig = FloatArray((secs * sr).toInt()); val r = Random(1)
        val kicks = beatsAt(120.0, secs); val snares = beatsAt(120.0, secs, 2.0, 1.0); val hats = beatsAt(120.0, secs, .5, .5)
        kicks.forEach { kick(sig, it) }; snares.forEach { snare(sig, it, r) }; hats.forEach { hat(sig, it, r) }
        bass(sig, 0, sig.size, .5, .35f); voice(sig, 0, sig.size, .22f)
        val run = run(sig)
        val k = score(hits(run, "kick"), kicks.map { it / sr.toFloat() }); val s = score(hits(run, "snare"), snares.map { it / sr.toFloat() })
        val h = score(hits(run, "hat"), hats.map { it / sr.toFloat() })
        println("TODO · bombo $k · caja $s · platos $h")
        assertTrue("bombo $k", k.recall >= .9f && k.extra <= 1)
        assertTrue("caja $s", s.recall >= .85f && s.extra <= 1)
        assertTrue("platos $h", h.recall >= .75f && h.extra <= 2)
    }

    @Test fun elBajoConGlissandosNoEsBombo() {
        val sig = FloatArray(8 * sr); bass(sig, 0, sig.size, .25)
        val run = run(sig); val k = hits(run, "kick"); val b = run.v.getValue("bass").drop(240).average()
        println("BAJO · golpes de bombo ${k.size} · nivel de bajo ${"%.2f".format(b)} · voz ${"%.2f".format(run.v.getValue("voice").drop(240).average())}")
        assertTrue("golpes de bombo con solo bajo: ${k.size}", k.size <= 2)
        assertTrue("nivel de bajo $b", b > .45f)
    }

    @Test fun laVozNoEsCajaNiPlatos() {
        val sig = FloatArray(8 * sr); voice(sig, 0, sig.size, .35f, .25)
        val run = run(sig); val s = hits(run, "snare"); val h = hits(run, "hat"); val k = hits(run, "kick")
        val v = run.v.getValue("voice").drop(240).average(); val b = run.v.getValue("bass").drop(240).average()
        println("VOZ · caja ${s.size} · platos ${h.size} · bombo ${k.size} · voz ${"%.2f".format(v)} · bajo ${"%.2f".format(b)}")
        assertTrue("caja ${s.size} platos ${h.size} bombo ${k.size} con solo voz", s.size <= 2 && h.size <= 2 && k.isEmpty())
        assertTrue("voz $v bajo $b", v > .4f && b < .25f)
    }

    @Test fun losPlatosNoSonBomboNiCaja() {
        val secs = 8.0; val sig = FloatArray((secs * sr).toInt()); val r = Random(2); val hats = beatsAt(120.0, secs, .5)
        hats.forEach { hat(sig, it, r) }
        val run = run(sig); val h = score(hits(run, "hat"), hats.map { it / sr.toFloat() })
        println("PLATOS · platos $h · caja ${hits(run, "snare").size} · bombo ${hits(run, "kick").size}")
        assertTrue("platos $h", h.recall >= .85f)
        assertTrue(hits(run, "kick").isEmpty() && hits(run, "snare").size <= 2)
    }

    @Test fun laCajaNoEsBombo() {
        val secs = 8.0; val sig = FloatArray((secs * sr).toInt()); val r = Random(3); val sn = beatsAt(120.0, secs, 1.0)
        sn.forEach { snare(sig, it, r) }
        val run = run(sig); val s = score(hits(run, "snare"), sn.map { it / sr.toFloat() })
        println("CAJA · caja $s · bombo ${hits(run, "kick").size} · platos ${hits(run, "hat").size}")
        assertTrue("caja $s", s.recall >= .85f)
        assertTrue("bombo con solo caja: ${hits(run, "kick").size}", hits(run, "kick").size <= 1)
    }

    @Test fun elBomboSolo() {
        val secs = 8.0; val sig = FloatArray((secs * sr).toInt()); val kk = beatsAt(128.0, secs)
        kk.forEach { kick(sig, it) }
        val run = run(sig); val k = score(hits(run, "kick"), kk.map { it / sr.toFloat() })
        println("BOMBO · $k · caja ${hits(run, "snare").size} · platos ${hits(run, "hat").size}")
        assertTrue("bombo $k", k.recall >= .9f && k.extra == 0)
        assertTrue(hits(run, "snare").size <= 1 && hits(run, "hat").isEmpty())
    }

    /** Estrofa tranquila → subida → drop → respiro: cada momento se nota cuando toca y solo entonces. */
    @Test fun subidaDropYRespiro() {
        val sr = this.sr; val sig = FloatArray(40 * sr); val r = Random(4)
        // 0-10 s estrofa (voz y platos flojos) · 10-18 s subida (redoble cada vez más denso y fuerte) · 18-30 s drop (todo) · 30-40 s respiro (solo voz baja)
        voice(sig, 0, 10 * sr, .12f); beatsAt(100.0, 10.0, .5).forEach { hat(sig, it, r, .08f) }
        for (b in 0 until 32) { val at = 10 * sr + b * sr / 4; val g = b / 32f; snare(sig, at, r, .1f + .4f * g); if (b % 2 == 0) hat(sig, at, r, .1f + .15f * g) }
        voice(sig, 10 * sr, 18 * sr, .15f)
        val drop = 18 * sr
        beatsAt(128.0, 12.0).forEach { kick(sig, drop + it) }; beatsAt(128.0, 12.0, 2.0, 1.0).forEach { snare(sig, drop + it, r) }
        beatsAt(128.0, 12.0, .5, .5).forEach { hat(sig, drop + it, r) }; bass(sig, drop, 30 * sr, .47, .4f); voice(sig, drop, 30 * sr, .25f)
        voice(sig, 30 * sr, 40 * sr, .08f)
        val run = run(sig)
        fun at(name: String, a: Float, b: Float): Float { val v = run.v.getValue(name); var m = 0f; for (i in v.indices) if (run.t[i] in a..b) m = maxOf(m, v[i]); return m }
        fun avg(name: String, a: Float, b: Float): Float { val v = run.v.getValue(name); var s = 0f; var n = 0; for (i in v.indices) if (run.t[i] in a..b) { s += v[i]; n++ }; return s / n }
        val drops = hits(run, "drop", 3f)
        println("MOMENTOS · energía estrofa ${"%.2f".format(avg("energy", 4f, 10f))} drop ${"%.2f".format(avg("energy", 20f, 30f))} respiro ${"%.2f".format(avg("energy", 33f, 40f))}" +
            " · subida máx ${"%.2f".format(at("build", 12f, 18.5f))} (estrofa ${"%.2f".format(at("build", 4f, 10f))}, drop ${"%.2f".format(at("build", 21f, 30f))})" +
            " · drops en ${drops.map { "%.1f".format(it) }} · respiro ${"%.2f".format(at("slow", 33f, 40f))} (drop ${"%.2f".format(at("slow", 21f, 30f))})")
        assertTrue("energía: el drop debe ser claramente más fuerte que la estrofa", avg("energy", 20f, 30f) > avg("energy", 4f, 10f) + .25f)
        assertTrue("subida", at("build", 12f, 18.5f) > .35f && at("build", 4f, 10f) < .2f)
        assertTrue("un drop al llegar el drop: $drops", drops.size == 1 && abs(drops[0] - 18f) < 1.2f)
        assertTrue("respiro", at("slow", 33f, 40f) > .4f && at("slow", 21f, 30f) < .15f)
    }
}
