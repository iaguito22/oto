package io.github.iaguito22.oto.desktop.playback

import java.io.File
import java.io.BufferedInputStream
import kotlin.math.*

/** El mismo detector de graves que AudioLevels del móvil, colocado en la línea temporal del audio. */
internal object DesktopBeat {
    class Detector(private val emit: (Float) -> Unit) {
        private val alpha = (1 - exp(-2 * PI * 150 / 48000)).toFloat()
        private var lp1=0f; private var lp2=0f; private var sum=0.0; private var frames=0;private var average=0f
        fun sample(value: Float) {
            lp1 += alpha*(value-lp1);lp2 += alpha*(lp1-lp2);sum += lp2*lp2
            if(++frames==512) {
                val rms=sqrt(sum/512).toFloat();average += (rms-average)*.006f
                val ratio=if(average>1e-4f) rms/average else 0f
                val t=((ratio-1.05f)/1.1f).coerceIn(0f,1f)
                emit(t*t*(3-2*t));sum=0.0;frames=0
            }
        }
    }
    fun analyze(file: File, cancelled: () -> Boolean): FloatArray {
        val values=ArrayList<Float>()
        val detector=Detector { values.add(it) }
        val process=ProcessBuilder("ffmpeg","-v","error","-i",file.absolutePath,"-vn","-ac","1","-ar","48000","-f","s16le","pipe:1").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            BufferedInputStream(process.inputStream).use { input ->
                while(!cancelled()) {
                    val low=input.read();val high=input.read();if(low<0||high<0)break
                    detector.sample(((high shl 8) or low).toShort()/32768f)
                }
            }
        } finally { process.destroyForcibly() }
        return values.toFloatArray()
    }
}
