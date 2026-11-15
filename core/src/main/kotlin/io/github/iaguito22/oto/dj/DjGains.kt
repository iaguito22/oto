package io.github.iaguito22.oto.dj

import kotlin.math.PI
import kotlin.math.cos

/**
 * Curvas de volumen de las mezclas del DJ, compartidas: las usa el reproductor de Android (`SmartMixMath`) para sonar y
 * el planificador ([DjTransitionPlanner]) para saber cuándo se oye cada canción (y así no pisar dos voces).
 */
object DjGains {
    /** Curva S (coseno elevado) con pendiente cero en ambos extremos. */
    fun smoothStep(progress: Float): Float {
        val x = progress.coerceIn(0f, 1f)
        return (0.5f * (1f - cos(x * PI.toFloat()))).coerceIn(0f, 1f)
    }

    fun ramp(p: Float, from: Float, to: Float): Float =
        if (to <= from) (if (p >= to) 1f else 0f) else smoothStep((p - from) / (to - from))

    /**
     * Entrante en una mezcla de DJ: sube sin graves hasta ~80 % en el relevo ([swapAt]) y llega entera poco después.
     * Con los beats cuadrados ([matched]) entra antes, para que las dos se oigan juntas un buen rato.
     */
    fun djIncoming(progress: Float, swapAt: Float, matched: Boolean): Float {
        val p = progress.coerceIn(0f, 1f)
        val reach = if (matched) 0.80f else 0.70f
        return if (p <= swapAt) {
            reach * ramp(p, if (matched) 0f else 0.08f, swapAt)
        } else {
            reach + (1f - reach) * ramp(p, swapAt, (swapAt + 0.22f).coerceAtMost(1f))
        }
    }

    /**
     * Saliente en una mezcla de DJ: entera hasta el relevo de graves (apenas baja un poco para hacer sitio) y después se
     * va con curva de igual potencia. Con eco ([echo]) se queda un poco más, para que el eco tenga de qué salir.
     */
    fun djOutgoing(progress: Float, swapAt: Float, echo: Boolean): Float {
        val p = progress.coerceIn(0f, 1f)
        if (p <= swapAt) return 1f - 0.12f * ramp(p, 0f, swapAt)
        val end = if (echo) 1f else 0.95f
        return 0.88f * cos(ramp(p, swapAt, end) * PI.toFloat() / 2f)
    }

    /** Bucle con roll: la que se va se queda entera hasta el 60 % y se apaga al final. */
    fun loopRollOutgoing(progress: Float): Float = cos(ramp(progress.coerceIn(0f, 1f), 0.6f, 1f) * PI.toFloat() / 2f)

    /** Bucle con roll: la nueva empieza a oírse al 30 % y está entera al 80 %. */
    fun loopRollIncoming(progress: Float): Float = ramp(progress.coerceIn(0f, 1f), 0.3f, 0.8f)

    /** Volumen de cada una en [p] según el estilo del plan (para planificar; el reproductor usa las mismas curvas). */
    fun outgoing(style: DjTransitionStyle, p: Float, swapAt: Float, echo: Boolean): Float = when (style) {
        DjTransitionStyle.LoopRollOut -> loopRollOutgoing(p)
        DjTransitionStyle.SmoothEqualPower -> cos(smoothStep(p) * PI.toFloat() / 2f)
        else -> djOutgoing(p, swapAt, echo)
    }

    fun incoming(style: DjTransitionStyle, p: Float, swapAt: Float): Float = when (style) {
        DjTransitionStyle.LoopRollOut -> loopRollIncoming(p)
        DjTransitionStyle.SmoothEqualPower -> kotlin.math.sin(smoothStep(p) * PI.toFloat() / 2f)
        else -> djIncoming(p, swapAt, style == DjTransitionStyle.BeatMatchLongMix)
    }
}
