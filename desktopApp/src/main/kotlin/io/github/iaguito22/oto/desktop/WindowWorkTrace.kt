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
