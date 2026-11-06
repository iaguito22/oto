package io.github.iaguito22.oto.desktop

import io.github.iaguito22.oto.OtoDirs
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.lang.management.ManagementFactory

/** Diagnóstico optativo: trabajo lento del hilo de interfaz, sin datos de biblioteca. */
internal object WindowWorkTrace {
    val enabled=System.getenv("OTO_MOTION_TRACE")=="1"
    private val threadCpu by lazy { ManagementFactory.getThreadMXBean() }
    private val writer by lazy { CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1)) }
    inline fun <T> measure(label:String,block:()->T):T {
        if(!enabled)return block()
        val started=System.nanoTime()
        val wall=System.currentTimeMillis()
        val cpu=cpuTime()
        try { return block() } finally { record(label,wall,System.nanoTime()-started,cpuTime()-cpu) }
    }
    fun cpuTime():Long=threadCpu.currentThreadCpuTime
    fun record(label:String,wall:Long,nanos:Long,cpu:Long) {
        if(nanos<5_000_000L)return
        val entry=JSONObject().put("label",label).put("atMs",wall).put("elapsedMs",nanos/1e6).put("cpuMs",cpu/1e6).toString()+"\n"
        writer.launch { File(OtoDirs.filesDir,"window-work.jsonl").appendText(entry) }
    }
}

/**
 * Diagnóstico optativo de la letra: intervalos entre fotogramas del reloj y cuántas veces se reancló. Cada 5 s
 * escribe una línea en `lyrics-frames.jsonl`. Son callbacks de Compose, no presentación en pantalla.
 */
internal class LyricsFrameTrace private constructor() {
    private val gaps=ArrayList<Double>(700)
    private var last=-1L
    private var windowStart=-1L
    var resyncs=0
    fun frame(now:Long) {
        if(last>=0) {
            val gap=(now-last)/1e6
            gaps+=gap
            // Los parones, con su hora, para cruzarlos con JFR o window-work.jsonl.
            if(gap>=100) {
                val entry=JSONObject().put("stallMs",gap).put("endedAtMs",System.currentTimeMillis()).toString()+"\n"
                writer.launch { File(OtoDirs.filesDir,"lyrics-frames.jsonl").appendText(entry) }
            }
        }
        last=now
        if(windowStart<0) windowStart=now
        if(now-windowStart>=5_000_000_000L) flush(now)
    }
    private fun flush(now:Long) {
        val sorted=gaps.sorted()
        if(sorted.isNotEmpty()) {
            fun pct(p:Double)=sorted[((sorted.size-1)*p).toInt()]
            val entry=JSONObject().put("atMs",System.currentTimeMillis()).put("frameGapSetting",LYRICS_FRAME_GAP_MS)
                .put("frames",sorted.size).put("fps",sorted.size/((now-windowStart)/1e9))
                .put("avgMs",sorted.average()).put("p50Ms",pct(.5)).put("p95Ms",pct(.95)).put("maxMs",sorted.last())
                .put("over12Ms",sorted.count{it>12.5}).put("over20Ms",sorted.count{it>20.0}).put("resyncs",resyncs).toString()+"\n"
            writer.launch { File(OtoDirs.filesDir,"lyrics-frames.jsonl").appendText(entry) }
        }
        gaps.clear(); resyncs=0; windowStart=now
    }
    companion object {
        private val writer by lazy { CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1)) }
        fun start():LyricsFrameTrace? = if(WindowWorkTrace.enabled) LyricsFrameTrace() else null
    }
}

/** Solo para medir (A/B): espera entre fotogramas del reloj de la letra. 0 = cada fotograma. */
internal val LYRICS_FRAME_GAP_MS:Long = System.getenv("OTO_LYRICS_FRAME_DELAY_MS")?.toLongOrNull()?.coerceIn(0,50) ?: 0L
