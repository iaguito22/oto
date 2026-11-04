package io.github.iaguito22.oto.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.channels.Channel
import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Rueda del ratón suave en todas las listas. Cada muesca de la rueda llega como un salto
 * entero; aquí se retiene y se reparte en pasos pequeños, uno por fotograma, con una curva
 * que frena al final. Compose trata esos pasos fraccionarios como un panel táctil y los
 * desplaza sin su propia animación, así que la distancia total por muesca no cambia.
 * Los paneles táctiles, el desplazamiento por páginas y Ctrl/Alt + rueda pasan sin tocar.
 */
internal object SmoothWheel {
    /** Constante de tiempo de la curva: ~95 % del recorrido en 3τ. */
    private const val TAU_MS = 62.0

    private class Motion(val window: Window) {
        var target: Component? = null
        var x = 0; var y = 0; var modifiers = 0; var scrollAmount = 3
        var remaining = 0.0
        val wake = Channel<Unit>(Channel.CONFLATED)
    }

    private class SmoothWheelEvent(source: Component, time: Long, modifiers: Int, x: Int, y: Int, sx: Int, sy: Int, amount: Int, rotation: Double) :
        MouseWheelEvent(source, MOUSE_WHEEL, time, modifiers, x, y, sx, sy, 0, false, WHEEL_UNIT_SCROLL, amount, 0, rotation)

    private val motions = HashMap<Window, Motion>() // solo desde el hilo de AWT
    private var installed = false

    private object Queue : EventQueue() {
        override fun dispatchEvent(event: AWTEvent) {
            if (event is MouseWheelEvent && capture(event)) return
            super.dispatchEvent(event)
        }
    }

    fun install() {
        if (installed) return
        installed = true
        runCatching { Toolkit.getDefaultToolkit().systemEventQueue.push(Queue) }
    }

    private fun capture(e: MouseWheelEvent): Boolean {
        if (e is SmoothWheelEvent || e.scrollType != MouseWheelEvent.WHEEL_UNIT_SCROLL || e.wheelRotation == 0) return false
        if (abs(e.preciseWheelRotation - e.wheelRotation) > .001) return false // panel táctil: ya es suave
        if (e.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK) != 0) return false
        val source = e.component ?: return false
        val window = source as? Window ?: SwingUtilities.getWindowAncestor(source) ?: return false
        val m = motions[window] ?: return false
        // Cambiar de sentido o de eje (Mayús) no arrastra lo que quedaba pendiente.
        val shift = e.modifiersEx and InputEvent.SHIFT_DOWN_MASK
        if (m.target !== source || (m.modifiers and InputEvent.SHIFT_DOWN_MASK) != shift || sign(m.remaining) != sign(e.preciseWheelRotation)) m.remaining = 0.0
        m.target = source; m.x = e.x; m.y = e.y; m.modifiers = e.modifiersEx; m.scrollAmount = e.scrollAmount
        m.remaining += e.preciseWheelRotation
        m.wake.trySend(Unit)
        return true
    }

    private fun step(m: Motion, dtMs: Double) {
        val target = m.target ?: run { m.remaining = 0.0; return }
        var part = m.remaining * (1 - exp(-dtMs.coerceIn(1.0, 50.0) / TAU_MS))
        if (abs(m.remaining) < .02 || abs(part) >= abs(m.remaining)) part = m.remaining
        else if (abs(part) < .004) part = sign(m.remaining) * .004
        m.remaining -= part
        if (abs(part) < .0015) return
        // El puntero puede haberse movido: el paso va donde está ahora si sigue dentro.
        val point = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()?.let { Point(it) }
        var x = m.x; var y = m.y
        if (point != null && target.isShowing) {
            val screen = Point(point)
            SwingUtilities.convertPointFromScreen(point, target)
            if (point.x in 0 until target.width && point.y in 0 until target.height) { x = point.x; y = point.y }
            target.dispatchEvent(SmoothWheelEvent(target, System.currentTimeMillis(), m.modifiers, x, y, screen.x, screen.y, m.scrollAmount, part))
        } else if (target.isShowing) {
            val origin = target.locationOnScreen
            target.dispatchEvent(SmoothWheelEvent(target, System.currentTimeMillis(), m.modifiers, x, y, origin.x + x, origin.y + y, m.scrollAmount, part))
        } else m.remaining = 0.0
    }

    /** Se pone dentro de cada ventana: reparte los pasos al ritmo de sus fotogramas. */
    @Composable
    fun Driver(window: Window) {
        val motion = remember(window) { Motion(window) }
        DisposableEffect(motion) {
            motions[window] = motion
            onDispose { if (motions[window] === motion) motions.remove(window) }
        }
        LaunchedEffect(motion) {
            while (true) {
                motion.wake.receive()
                // El primer paso sale ya, sin esperar un fotograma: la rueda responde al instante.
                var last = System.nanoTime() - 12_000_000L
                while (motion.remaining != 0.0) {
                    val now = System.nanoTime()
                    // Un fallo al repartir un paso no debe tumbar el bucle ni la composición.
                    runCatching { step(motion, (now - last) / 1_000_000.0) }.onFailure { motion.remaining = 0.0 }
                    last = now
                    if (motion.remaining != 0.0) withFrameNanos { }
                }
            }
        }
    }
}
