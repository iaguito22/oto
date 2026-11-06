package io.github.iaguito22.oto.desktop

import io.github.iaguito22.oto.OtoDirs

/**
 * Qué gráfica dibuja las ventanas. En portátiles con dos (p. ej. AMD integrada + NVIDIA), la integrada se para
 * ~250 ms cada 10–20 s con ventanas transparentes, y todas las de Oto lo son (ver docs/agents/rendimiento.md). La
 * dedicada no, pero gasta más batería: por eso es opcional y viene apagada.
 *
 * Skiko elige la gráfica una sola vez, al crear la primera ventana: se guarda en un archivo que se lee a mano al
 * arrancar (no en DataStore, que es asíncrono) y el cambio se aplica al volver a abrir Oto.
 */
internal object DesktopGraphics {
    private const val KEY = "gpu"
    private val store get() = OtoDirs.keyValue("desktop_graphics")

    /** Lo que se usa en esta sesión (fijado en [apply]). */
    var activeDedicated = false
        private set

    var dedicated: Boolean
        get() = store.getString(KEY) == "discrete"
        set(value) = store.putString(KEY, if (value) "discrete" else null)

    /** Antes de crear ninguna ventana. Un `-Dskiko.gpu.priority` puesto a mano (pruebas) manda sobre el ajuste. */
    fun apply() {
        val explicit = System.getProperty("skiko.gpu.priority")
        if (explicit == null && isWindowsDesktop && runCatching { dedicated }.getOrDefault(false)) {
            System.setProperty("skiko.gpu.priority", "discrete")
        }
        activeDedicated = System.getProperty("skiko.gpu.priority") == "discrete"
    }
}
