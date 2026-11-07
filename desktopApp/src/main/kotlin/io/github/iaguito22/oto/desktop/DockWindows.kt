package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.awt.event.WindowEvent
import kotlin.math.roundToInt
import kotlin.math.abs

internal enum class NativeDockChange { None, Resize, Move }

/** El redondeo DPI de AWT puede diferir un píxel lógico: no es un arrastre del usuario. */
internal fun nativeDockChange(actual: DockRect, expected: DockRect, overlap: Int): NativeDockChange = when {
    abs(actual.width - expected.width - overlap) > 1 || abs(actual.height - expected.height) > 1 -> NativeDockChange.Resize
    abs(actual.x - expected.x) > 1 || abs(actual.y - expected.y) > 1 -> NativeDockChange.Move
    else -> NativeDockChange.None
}

internal fun hyprDesktopArea(raw: String, point: Point): DockRect? {
    val monitors=JSONArray(raw)
    val choices=(0 until monitors.length()).map { index ->
        val monitor=monitors.getJSONObject(index)
        val scale=monitor.getDouble("scale")
        require(scale>0)
        val rotated=monitor.optInt("transform") % 2 == 1
        val w=(monitor.getInt(if(rotated) "height" else "width") / scale).roundToInt()
        val h=(monitor.getInt(if(rotated) "width" else "height") / scale).roundToInt()
        val x=monitor.getInt("x"); val y=monitor.getInt("y")
        val reserved=monitor.getJSONArray("reserved")
        Triple(Rectangle(x,y,w,h),DockRect(x+reserved.getInt(0),y+reserved.getInt(1),
            w-reserved.getInt(0)-reserved.getInt(2),h-reserved.getInt(1)-reserved.getInt(3)),monitor.optBoolean("focused"))
    }
    return (choices.firstOrNull { it.first.contains(point) } ?: choices.firstOrNull { it.third } ?: choices.firstOrNull())?.second
}

/** AWT usa coordenadas lógicas de escritorio, no los píxeles físicos del monitor. */
internal fun desktopArea(point: Point = MouseInfo.getPointerInfo()?.location ?: Point()): DockRect {
    if (System.getenv("HYPRLAND_INSTANCE_SIGNATURE") != null) {
        val native = runCatching {
            val process = ProcessBuilder("hyprctl", "monitors", "-j").start()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) { process.destroy(); return@runCatching null }
            hyprDesktopArea(process.inputStream.bufferedReader().readText(),point)
        }.getOrNull()
        if (native != null) return native
    }
    val configs = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
    val rects = configs.map { val b = it.bounds; DockRect(b.x, b.y, b.width, b.height) }
    val config = configs[rects.indexOf(nearestArea(rects, point.x, point.y))]
    val r = config.bounds; val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    return DockRect(r.x + insets.left, r.y + insets.top,
        r.width - insets.left - insets.right, r.height - insets.top - insets.bottom)
}

/** Las zonas útiles (sin barra de tareas) de todas las pantallas. */
internal fun desktopAreas(): List<DockRect> =
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val b = device.defaultConfiguration.bounds
        desktopArea(Point(b.x + b.width / 2, b.y + b.height / 2))
    }

/**
 * Huella de la disposición de pantallas: posición, tamaño, barra de tareas y escala. Si cambia
 * (conectar o quitar un monitor, cambiar la principal o la escala), Windows recoloca las ventanas.
 */
internal fun screenSignature(): String = runCatching {
    val toolkit = Toolkit.getDefaultToolkit()
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.joinToString("|") { device ->
        val c = device.defaultConfiguration; val b = c.bounds; val i = toolkit.getScreenInsets(c)
        "${b.x},${b.y},${b.width},${b.height};${i.left},${i.top},${i.right},${i.bottom};${c.defaultTransform.scaleX}"
    }
}.getOrDefault("")

internal class DockSession(val layout: DockLayout, val supportsMaximize: Boolean = System.getenv("HYPRLAND_INSTANCE_SIGNATURE") == null, areas: List<DockRect>? = null) {
    var revision by mutableIntStateOf(0)
    var preview by mutableStateOf<DockCandidate?>(null)
    var message by mutableStateOf("")
    var reorderPreview by mutableStateOf<Triple<DockId,DockId,Boolean>?>(null)
    var lastFocused: DockId? = null
    var draggingGroup = false
    var geometrySettlesAt = 0L
    val windows = mutableMapOf<DockId, Window>()
    private var raising = false
    init {
        if(!supportsMaximize) layout.groups.filter { it.maximized }.toList().forEach { g ->
            layout.maximize(g.leader,g.area ?: desktopArea())
        }
        val screens = areas ?: desktopAreas()
        layout.recover(screens)
        layout.changed = { revision++ }
        // Solo con pantallas reales (las pruebas pasan sus propias zonas).
        if (areas == null) javax.swing.Timer(2000) { screensChanged() }.apply { isCoalesce = true; start() }
    }

    private var screens = if (areas == null) screenSignature() else ""
    private var displayTimer: javax.swing.Timer? = null
    /**
     * Al cambiar las pantallas, Windows mueve cada ventana por su cuenta y en momentos distintos.
     * Si se reconciliasen una a una, las secundarias se separarían del conjunto (o volverían a una
     * posición que ya no existe). Se ignoran esos movimientos y, cuando paran, se recoloca el
     * conjunto entero en la pantalla donde quedó su ventana principal.
     */
    fun screensChanged(): Boolean {
        if (screens.isEmpty()) return false
        val now = screenSignature()
        if (now.isEmpty() || now == screens) return false
        screens = now
        trace("screens changed: $now")
        geometrySettlesAt = maxOf(geometrySettlesAt, System.nanoTime() + 1_600_000_000L)
        displayTimer?.stop()
        displayTimer = javax.swing.Timer(1200) { recoverScreens() }.apply { isRepeats = false; start() }
        return true
    }
    private fun recoverScreens() {
        if (draggingGroup) { displayTimer?.restart(); return }
        val areas = runCatching { desktopAreas() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        // Lo que Windows dejó en las ventanas principales es la posición que el usuario espera.
        for (g in layout.groups.toList()) {
            val window = windows[g.leader] ?: continue
            val r = layout.bounds[g.leader] ?: continue
            if (window.isShowing && !window.iconified() && !g.maximized) layout.move(g.leader, window.x - r.x, window.y - r.y)
        }
        layout.recover(areas)
        for (g in layout.groups.toList()) syncGroupLocations(g.leader)
        geometrySettlesAt = System.nanoTime() + 400_000_000L
    }
    fun trace(message: String) { if(System.getenv("OTO_DOCK_TRACE") == "1") println("Dock: $message") }
    /** Windows (pantallas reales): arrastre y Snap del propio Windows. Las pruebas pasan sus zonas y no lo usan. */
    val nativeMoves = areas == null && System.getProperty("os.name").startsWith("Windows")
    /** Durante el arrastre las ventanas las mueve Windows ([startNativeDrag]); Compose no debe moverlas. */
    var nativeDrag = false
    /**
     * Mueve ya, en el mismo evento, todas las ventanas del conjunto de [id] a su posición
     * del modelo. Si cada ventana esperase a su propio fotograma de Compose, se moverían en
     * momentos distintos y durante el arrastre se vería el corte entre ellas.
     */
    fun syncGroupLocations(id: DockId) {
        if (id !in layout.bounds) return
        for (member in layout.group(id).members) {
            val window = windows[member] ?: continue
            val r = layout.bounds[member] ?: continue
            if (window.isShowing && !window.iconified() && (window.x != r.x || window.y != r.y)) window.setLocation(r.x, r.y)
        }
    }
    private fun Window.iconified() = this is Frame && extendedState and Frame.ICONIFIED != 0
    private var dragTimer: javax.swing.Timer? = null
    /**
     * Arrastre del conjunto de [id] con el bucle de mover de Windows ([beginSystemMove]): Windows mueve la
     * ventana agarrada, hace su propio Snap (mitades, cuartos, maximizar, diseños de Windows 11) y el cambio
     * de escala entre pantallas. Las demás del conjunto la siguen en píxeles físicos. Al soltar, si Windows
     * la dejó en un hueco suyo, el conjunto entero se ajusta a ese hueco; si no, se acopla o se recoloca
     * dentro de la pantalla que más tapa.
     */
    fun startNativeDrag(id: DockId, grab: Point, screens: List<SnapScreen>) {
        val dragged = windows[id] ?: return
        dragTimer?.stop()
        draggingGroup = true; nativeDrag = true
        // El tamaño de ahora (p. ej. el restaurado al sacarlo de maximizado) antes de que Windows lo coja.
        applyGroupBounds(id)
        if (!beginSystemMove(dragged)) { draggingGroup = false; nativeDrag = false; return }
        trace("system move $id")
        var releasedAt = 0L
        var lastRect: DockRect? = null
        var stable = 0
        fun finish() {
            dragTimer?.stop(); dragTimer = null
            val candidate = preview
            preview = null
            if (id in layout.bounds && dragged.isShowing) {
                val zoomed = isZoomed(dragged)
                val actual = dragged.bounds
                val overlap = if (layout.group(id).members.last() != id) 1 else 0
                val center = Point(actual.x + actual.width / 2, actual.y + actual.height / 2)
                val placed = DockRect(actual.x, actual.y, actual.width - overlap, actual.height)
                // Lo pregunta a Windows: un cambio de tamaño también puede ser el de escala al cambiar de pantalla.
                // Cualquier hueco suyo vale (mitades, cuartos, tercios y demás diseños de Windows 11).
                // Windows conserva «ajustada» al sacarla de un Snap anterior: solo cuenta si además le cambió el tamaño.
                // El maximizado de arriba llega después del bucle: lo recoge el efecto de `placement` de [BindDock].
                val model = layout.bounds.getValue(id)
                val arranged = !zoomed && isArranged(dragged) && (abs(placed.width - model.width) > 2 || abs(placed.height - model.height) > 2)
                trace("system move end $id zoomed=$zoomed arranged=$arranged $actual")
                val snapped = if (!arranged) null else matchSnap(snapScreens(), placed, tolerance = 2)?.rect ?: placed
                setSnappable(dragged, false)
                when {
                    zoomed -> {
                        // Arriba del todo: Windows la maximiza; se maximiza el conjunto entero en esa pantalla.
                        trace("system maximize $id $actual")
                        (dragged as? Frame)?.extendedState = Frame.NORMAL
                        val area = desktopArea(center)
                        if (layout.group(id).maximized) layout.snap(id, area)
                        else if (!layout.maximize(id, area)) { message = "El conjunto no cabe en esta pantalla."; layout.settleInto(id, area) }
                    }
                    snapped != null -> {
                        trace("system snap $id $snapped")
                        if (!layout.snap(id, snapped)) { message = "El conjunto no cabe ahí."; layout.settleInto(id, desktopArea(center)) }
                    }
                    else -> {
                        // La ventana arrastrada manda: el modelo pasa a su posición (en el sistema de AWT de su pantalla).
                        val r = layout.bounds.getValue(id)
                        layout.move(id, actual.x - r.x, actual.y - r.y)
                        val docked = candidate != null && layout.dock(id, candidate.target, candidate.before, desktopArea(center)).also {
                            if (!it) message = "No hay espacio para acoplar el conjunto."
                        }
                        if (!docked) {
                            val members = layout.group(id).members
                            val first = layout.bounds.getValue(members.first())
                            val phys = physicalBounds(dragged)
                            // La pantalla que más tapa el conjunto: Windows le da su escala.
                            val screen = if (phys != null && screens.isNotEmpty()) {
                                val left = phys.x - members.takeWhile { it != id }.sumOf { m -> layout.bounds.getValue(m).width.scaled(windows[m]?.let(::windowScale) ?: 1.0) }
                                dropScreen(screens, left, phys.y, members.sumOf { layout.bounds.getValue(it).width }, first.height)
                            } else null
                            trace("drop $id on ${screen?.bounds} at $actual")
                            layout.settleInto(id, screen?.work ?: desktopArea(center))
                        } else layout.settleInto(id, desktopArea(center))
                    }
                }
                nativeDrag = false
                syncGroupBounds(id)
                javax.swing.Timer(180) { if (id in layout.bounds) raise(id) }.apply { isRepeats = false; start() }
            }
            nativeDrag = false
            draggingGroup = false
            geometrySettlesAt = System.nanoTime() + 400_000_000L
        }
        dragTimer = javax.swing.Timer(8) {
            if (id !in layout.bounds || !dragged.isShowing) { finish(); return@Timer }
            val rect = physicalBounds(dragged) ?: return@Timer
            val ended = systemMoveEnded(dragged)
            if (ended == null && releasedAt == 0L) {
                if (rect != lastRect) followPhysical(id, dragged, rect)
                // El modelo, para la vista previa de acoplar (sistema de AWT de la pantalla de la ventana).
                val b = dragged.bounds
                layout.bounds[id]?.let { r -> if (r.x != b.x || r.y != b.y) layout.move(id, b.x - r.x, b.y - r.y) }
                preview = layout.candidate(id)
                if (!primaryButtonDown()) releasedAt = System.nanoTime()
            } else {
                // Windows aplica su Snap o el maximizado antes de salir del bucle; se espera a que salga
                // y a que la ventana deje de cambiar (si el bucle no llegó a empezar, basta con soltar).
                stable = if (rect == lastRect) stable + 1 else 0
                val now = System.nanoTime()
                if ((ended != null && now - ended > 60_000_000L && stable >= 3) || (releasedAt != 0L && now - releasedAt > 2_000_000_000L)) finish()
            }
            lastRect = rect
        }.apply { isCoalesce = true; start() }
    }
    /** Pega las demás ventanas del conjunto a la que mueve Windows, en píxeles físicos. */
    private fun followPhysical(id: DockId, dragged: Window, rect: DockRect) {
        val members = layout.group(id).members
        val index = members.indexOf(id)
        if (members.size < 2 || index < 0) return
        val moves = mutableListOf<Pair<Window, Point>>()
        var left = rect.x
        for (m in members.subList(0, index).asReversed()) {
            val w = windows[m] ?: continue
            left -= (layout.bounds[m]?.width ?: continue).scaled(windowScale(w))
            if (w.isShowing && !w.iconified()) moves += w to Point(left, rect.y)
        }
        var right = rect.x + (layout.bounds[id]?.width ?: return).scaled(windowScale(dragged))
        for (m in members.subList(index + 1, members.size)) {
            val w = windows[m] ?: continue
            if (w.isShowing && !w.iconified()) moves += w to Point(right, rect.y)
            right += (layout.bounds[m]?.width ?: continue).scaled(windowScale(w))
        }
        placePhysical(moves)
    }
    /** Hasta cuándo un cambio de tamaño de Windows es el ajuste de escala de un cambio de pantalla, no del usuario. */
    var rescaleUntil = 0L
    /**
     * Posición y tamaño exactos del modelo para todo el conjunto, al soltarlo. Al pasar a una pantalla
     * con otra escala, Windows le da a la ventana el tamaño físico de la anterior (medido: 513 en vez de
     * 640 lógicos de 125 % a 100 %); se repite cuando ese ajuste ya ha llegado.
     */
    fun syncGroupBounds(id: DockId) {
        if (id !in layout.bounds) return
        rescaleUntil = System.nanoTime() + 1_500_000_000L
        applyGroupBounds(id)
        if (nativeMoves) for (delay in listOf(200, 600)) javax.swing.Timer(delay) { applyGroupBounds(id) }.apply { isRepeats = false; start() }
    }
    /**
     * Posición y tamaño del modelo en todas las ventanas del conjunto, ya, en el mismo evento. Al arrastrar
     * la unión o un borde, si cada ventana esperase a su fotograma de Compose se verían tamaños a destiempo.
     */
    fun applyGroupBounds(id: DockId) {
        if (id !in layout.bounds) return
        val members = layout.group(id).members
        for (member in members) {
            val window = windows[member] ?: continue
            val r = layout.bounds[member] ?: continue
            val overlap = if (nativeMoves && member != members.last()) 1 else 0
            val want = Rectangle(r.x, r.y, r.width + overlap, r.height)
            if (window.isShowing && !window.iconified() && window.bounds != want) window.bounds = want
        }
    }
    /**
     * Sube el conjunto detrás de [id], que queda delante y con el foco. Nunca saca nada de
     * minimizado: eso solo lo hace [setGroupMinimized] cuando vuelve la ventana principal.
     * (Al minimizar, Windows activa la siguiente ventana; si aquí se restaurase, minimizar
     * y restaurar se dispararían en bucle.)
     */
    fun raise(id: DockId) {
        trace("raise $id, raising=$raising")
        lastFocused = id
        if (raising || id !in layout.bounds) return
        val front = windows[id]
        if (front == null || !front.isShowing || front.iconified()) return
        raising = true
        try {
            layout.group(id).members.filter { it != id }.forEach { member ->
                windows[member]?.let { w ->
                    if (!w.isShowing || w.iconified()) return@let
                    // En Windows, sin activarlas: la ventana elegida conserva el primer plano y el
                    // botón de la barra de tareas puede volver a esconder el conjunto.
                    if (placeBelowWithoutActivation(w, front)) return@let
                    val previous = w.isAutoRequestFocus
                    try { w.isAutoRequestFocus=false; w.toFront() } finally { w.isAutoRequestFocus=previous }
                }
            }
            if (!front.isActive) front.toFront()
        } finally { EventQueue.invokeLater { raising = false } }
    }
    /** Durante un minimizado o restaurado del conjunto llegan eventos propios con retraso: ignorarlos. */
    private var groupChangedAt = 0L
    /** Última vez que se mostró una ventana del conjunto. */
    var attachedAt = 0L
    fun settlingGroup() = System.nanoTime() - groupChangedAt < 800_000_000L
    /**
     * El botón de la barra de tareas es de la ventana principal. Si estaba activa otra del
     * conjunto, Windows activa la principal en vez de minimizar: lo tratamos como «esconder».
     */
    fun taskbarToggle(id: DockId, previous: Window?): Boolean {
        if (id !in layout.bounds || settlingGroup()) return false
        // Al abrir varias ventanas a la vez se activan unas tras otras: no es un clic en la barra de tareas
        // (medido en el arnés: el conjunto recién abierto se minimizaba solo).
        if (System.nanoTime() - attachedAt < 1_500_000_000L) return false
        val g = layout.group(id)
        if (g.leader != id || previous == null || g.members.none { it != id && windows[it] === previous }) return false
        if (previous.iconified() || !previous.isShowing) return false
        val pointer = MouseInfo.getPointerInfo()?.location ?: return false
        if (g.members.any { windows[it]?.let { w -> w.isShowing && w.bounds.contains(pointer) } == true }) return false
        val frame = windows[id] as? Frame ?: return false
        trace("taskbar toggle $id")
        frame.extendedState = frame.extendedState or Frame.ICONIFIED
        return true
    }
    /**
     * Solo la ventana principal tiene botón en la barra de tareas: el resto la sigue.
     * Minimizar cualquier miembro esconde el conjunto; restaurar solo cuenta si vuelve la principal.
     */
    fun setGroupMinimized(id: DockId, minimized: Boolean) {
        if (id !in layout.bounds) return
        val g = layout.group(id)
        if (!minimized && g.leader != id) return
        if (settlingGroup() && g.leader != id) return
        trace("group minimized=$minimized by $id")
        groupChangedAt = System.nanoTime()
        val own = windows[id]
        g.members.filter { it != id }.forEach { member ->
            (windows[member] as? Frame)?.takeIf { it.isShowing }?.let { frame ->
                if (minimized) { if (!frame.iconified()) frame.extendedState = frame.extendedState or Frame.ICONIFIED; return@let }
                if (own != null && restoreBelowWithoutActivation(frame, own)) return@let
                if (frame.iconified()) frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
            }
        }
    }
    fun maximize(id: DockId) {
        if(!supportsMaximize) return
        val g = layout.group(id)
        val r = layout.bounds.getValue(g.members.first())
        // La pantalla del centro del conjunto, no la de su esquina (que puede estar en el hueco entre pantallas).
        val width = g.members.sumOf { layout.bounds[it]?.width ?: 0 }
        val full = desktopArea(Point(r.x + width / 2, r.y + r.height / 2))
        // En media pantalla (Snap), maximizar llena la pantalla; restaurar ya vuelve al tamaño normal.
        if (g.maximized && g.area != null && g.area != full) {
            if (!layout.snap(id, full)) message = "El conjunto no cabe en esta pantalla."
            return
        }
        if (!layout.maximize(id, full)) message = "El conjunto no cabe en esta pantalla."
    }
    /** Vuelve al tamaño normal si está maximizado o en Snap (al arrastrarlo, por ejemplo). */
    fun restore(id: DockId, area: DockRect = desktopArea()) {
        if (layout.group(id).maximized) layout.maximize(id, area)
    }
    fun open(id: DockId, origin: DockId? = lastFocused ?: layout.bounds.keys.maxByOrNull { it.rank }) {
        if (id in layout.bounds) { raise(id); return }
        val anchor = origin?.let { layout.bounds[it] }
        // Cerrada suelta (sin hermanos abiertos con los que volver): vuelve a su pantalla, no a la de la ventana
        // desde la que se abre. Antes se metía en la pantalla del reproductor aunque se cerrase en la otra.
        val home = layout.homes[id]
        val alone = home != null && layout.groups.none { g -> g.members.any { it != id && it in home.siblings } }
        val area = if (alone) desktopArea(Point(home!!.rect.x + home.rect.width / 2, home.rect.y + 20))
            else desktopArea(anchor?.let { Point(it.x,it.y) } ?: MouseInfo.getPointerInfo().location)
        val before = layout.bounds.toMap()
        if (!layout.open(id,origin,area)) message = "La ventana se abre suelta porque no cabe acoplada."
        else message = ""
        // Si para hacer sitio se desplazó el conjunto (sin cambiar tamaños), deslizarlo en
        // vez de saltar; la ventana nueva espera a que termine para entrar.
        val moved = before.filterKeys { it != id }.mapNotNull { (key, old) ->
            val now = layout.bounds[key] ?: return@mapNotNull null
            if (now.x == old.x && now.y == old.y) null else Triple(key, old, now)
        }
        if (moved.isNotEmpty() && moved.all { (key, old, now) -> old.width == now.width && old.height == now.height &&
                windows[key]?.let { it.isShowing && !it.iconified() } == true })
            prepareShift(id, moved.associate { (key, _, now) -> key to (windows.getValue(key).location to Point(now.x, now.y)) })
    }

    private val shifting = mutableSetOf<DockId>()
    private var shiftTimer: javax.swing.Timer? = null
    private var pendingShift: Map<DockId, Pair<Point, Point>>? = null
    private var pendingFor: DockId? = null
    /** Momento en que acaba el desplazamiento animado del conjunto (MAX_VALUE: aún pendiente). */
    var shiftEndsAt = 0L
    fun isShifting(id: DockId) = id in shifting

    /**
     * El desplazamiento espera a que la ventana nueva termine su primera composición: si
     * corriesen a la vez, ese trabajo en el hilo de la interfaz frenaría el deslizamiento.
     */
    private fun prepareShift(newWindow: DockId, moves: Map<DockId, Pair<Point, Point>>) {
        shiftTimer?.stop()
        shifting.clear(); shifting += moves.keys
        pendingShift = moves; pendingFor = newWindow
        shiftEndsAt = Long.MAX_VALUE
        geometrySettlesAt = Long.MAX_VALUE
        javax.swing.Timer(1500) { if (pendingShift === moves) startPendingShift(newWindow) }.apply { isRepeats = false; start() }
    }

    fun startPendingShift(ready: DockId) {
        val moves = pendingShift ?: return
        if (ready != pendingFor) return
        pendingShift = null; pendingFor = null
        val duration = 280_000_000L
        val started = System.nanoTime()
        shiftEndsAt = started + duration
        geometrySettlesAt = shiftEndsAt + 150_000_000L
        val easing = androidx.compose.animation.core.CubicBezierEasing(.2f, 0f, 0f, 1f)
        val native = moves.mapNotNull { (key, path) ->
            windows[key]?.let { nativeShift(it, path.second.x - path.first.x, path.second.y - path.first.y) }
        }.takeIf { it.size == moves.size }
        fun finish() {
            // Dejar AWT y Compose exactamente en la posición del modelo.
            for ((key, path) in moves) windows[key]?.takeIf { it.isShowing }?.let { w ->
                if (w.x != path.second.x || w.y != path.second.y) w.setLocation(path.second.x, path.second.y)
            }
            shifting.clear()
        }
        shiftTimer = javax.swing.Timer(6) { event ->
            val t = ((System.nanoTime() - started).toFloat() / duration).coerceIn(0f, 1f)
            val e = easing.transform(t)
            // Todas en el mismo paso, como en el arrastre, para que no se abra el corte.
            if (native == null || !applyNativeShift(native, e)) for ((key, path) in moves) {
                val (from, to) = path
                val window = windows[key] ?: continue
                if (window.isShowing) window.setLocation(from.x + ((to.x - from.x) * e).roundToInt(), from.y + ((to.y - from.y) * e).roundToInt())
            }
            if (t >= 1f) { (event.source as javax.swing.Timer).stop(); finish() }
        }.apply { isCoalesce = true; start() }
    }
    fun close(id: DockId) { if(id in layout.bounds) layout.close(id) }
    fun toggle(id: DockId, origin: DockId? = lastFocused) {
        if(id in layout.bounds) close(id) else open(id,origin)
    }
    fun openQueue() = open(DockId.Queue, if(DockId.Player in layout.bounds) DockId.Player else DockId.Library)

}

internal class DockVisibility(private val session: DockSession, private val id: DockId) : MutableState<Boolean> {
    override var value: Boolean
        get() { session.revision; return id in session.layout.bounds }
        set(value) { if(value) session.open(id) else session.close(id) }
    override operator fun component1(): Boolean = value
    override operator fun component2(): (Boolean) -> Unit = { value = it }
}

@Composable
internal fun rememberDockState(session: DockSession, id: DockId): WindowState {
    session.revision
    val open = id in session.layout.bounds
    return key(id, open) {
        val rect = session.layout.bounds[id] ?: DockRect(0,0,id.defaultWidth,580)
        rememberWindowState(position=WindowPosition(rect.x.dp,rect.y.dp),
            size=androidx.compose.ui.unit.DpSize(rect.width.dp,rect.height.dp))
    }
}

@Composable
internal fun WindowScope.BindDock(session: DockSession?, id: DockId?, state: WindowState) {
    if (session == null || id == null) return
    val revision = session.revision
    val scope = rememberCoroutineScope()
    val bridge = remember { object { var applying = false; var job: Job? = null; var snappedAt = 0L; var reappliedAt = 0L } }
    fun seamOverlap(): Int = if (System.getProperty("os.name").startsWith("Windows") && id in session.layout.bounds &&
        session.layout.group(id).members.lastOrNull() != id) 1 else 0
    DisposableEffect(session, id, window) {
        session.trace("attach $id frame=${System.identityHashCode(window)} title=${window.name} actual=${window.bounds} model=${session.layout.bounds[id]}")
        session.windows[id] = window
        session.attachedAt = System.nanoTime()
        val listener = object : WindowAdapter() {
            override fun windowStateChanged(e: WindowEvent) {
                // Cualquier miembro (también con Win+↓): las demás no tienen botón propio y se perderían.
                if (id !in session.layout.bounds) return
                val wasMinimized = e.oldState and Frame.ICONIFIED != 0
                val minimized = e.newState and Frame.ICONIFIED != 0
                if (wasMinimized != minimized) session.setGroupMinimized(id, minimized)
            }
            override fun windowActivated(e: WindowEvent) {
                // Clic en la barra de tareas o Alt+Tab activan solo la ventana principal: traer el conjunto entero.
                if (id !in session.layout.bounds || session.draggingGroup) return
                if (session.taskbarToggle(id, e.oppositeWindow)) return
                if (session.settlingGroup()) return
                EventQueue.invokeLater { session.raise(id) }
            }
            override fun windowGainedFocus(e: WindowEvent) {
                if (id in session.layout.bounds) {
                    session.lastFocused = id
                    session.trace("focus $id")
                }
            }
        }
        val geometry = object : ComponentAdapter() {
            private fun reconcile() {
                bridge.job?.cancel()
                bridge.job = scope.launch {
                    delay(16)
                    // Un cambio de pantallas llega como movimientos sueltos: que lo resuelva la sesión.
                    session.screensChanged()
                    while(bridge.applying || session.draggingGroup || System.nanoTime() < session.geometrySettlesAt) delay(16)
                    val expected = session.layout.bounds[id] ?: return@launch
                    if(!window.isShowing || state.placement != WindowPlacement.Floating) return@launch
                    val actual = window.bounds
                    session.trace("reconcile $id frame=${System.identityHashCode(window)} actual=$actual expected=$expected state=${state.size} revision=${session.revision}")
                    val overlap = seamOverlap()
                    val change = nativeDockChange(DockRect(actual.x,actual.y,actual.width,actual.height),expected,overlap)
                    val resized = change == NativeDockChange.Resize
                    // Win+flechas: Windows coloca él la ventana principal en una mitad/cuarto/maximizada.
                    // Se ajusta el conjunto entero a ese hueco en vez de estirar solo esa ventana.
                    val group = session.layout.group(id)
                    val native = if (change != NativeDockChange.None && group.leader == id && session.nativeMoves)
                        matchSnap(snapScreens(), DockRect(actual.x, actual.y, actual.width - overlap, actual.height), tolerance = 2) else null
                    if (native != null && WindowsSnapSettings.arranging) {
                        session.trace("native snap $id ${native.zone} ${native.rect}")
                        if (!session.layout.snap(id, native.rect)) { session.message = "El conjunto no cabe ahí."; session.syncGroupLocations(id) }
                        return@launch
                    }
                    // Oto no deja a Windows ni a Compose redimensionar las acopladas: una diferencia es casi siempre su último
                    // tamaño aún sin llegar (medido al soltar la unión: 550 frente a 554, y se perdía el maximizado). Se vuelve
                    // a aplicar; solo si la ventana insiste en menos de 1 s se acepta como cambio real.
                    val reapply = change != NativeDockChange.None && System.nanoTime() - bridge.reappliedAt > 1_000_000_000L
                    if(reapply) bridge.reappliedAt = System.nanoTime()
                    if((resized || change == NativeDockChange.Move) && (reapply || System.nanoTime() < session.rescaleUntil)) {
                        // Ajuste de escala de Windows tras cambiar de pantalla: el tamaño bueno es el del modelo.
                        session.trace("rescale $id: ${actual.width}x${actual.height} -> $expected")
                        window.bounds = java.awt.Rectangle(expected.x, expected.y, expected.width + overlap, expected.height)
                        return@launch
                    }
                    if(resized) {
                        session.trace("native resize $id: ${actual.width}x${actual.height}")
                        if(group.maximized) session.restore(id)
                        session.layout.resize(id,actual.width - overlap,actual.height,actual.y)
                    }
                    if(change == NativeDockChange.Move && System.nanoTime() < session.rescaleUntil) {
                        session.trace("rescale move $id: back to $expected")
                        window.bounds = java.awt.Rectangle(expected.x, expected.y, expected.width + overlap, expected.height)
                        return@launch
                    }
                    if(change == NativeDockChange.Move) {
                        val group = session.layout.group(id)
                        val now = System.nanoTime()
                        if(group.leader != id && group.members.size > 1 && now - bridge.snappedAt > 1_000_000_000L) {
                            bridge.snappedAt = now
                            // Las secundarias solo se arrastran desde Oto (que ya las separa). Un desfase
                            // aquí es una posición que llegó tarde: devolverla al conjunto, no separarla.
                            session.trace("late move $id: back to $expected")
                            window.setLocation(expected.x, expected.y)
                        } else {
                            // Si Windows la vuelve a desplazar (Win+flechas, otro monitor), es un movimiento real.
                            if(group.leader != id) session.layout.detach(id)
                            val r = session.layout.bounds.getValue(id)
                            session.layout.move(id,actual.x-r.x,actual.y-r.y)
                        }
                    }
                }
            }
            override fun componentResized(e: ComponentEvent) = reconcile()
            override fun componentMoved(e: ComponentEvent) = reconcile()
        }
        window.addWindowFocusListener(listener)
        window.addWindowStateListener(listener)
        window.addWindowListener(listener)
        window.addComponentListener(geometry)
        onDispose {
            bridge.job?.cancel(); window.removeWindowFocusListener(listener); window.removeWindowStateListener(listener); window.removeWindowListener(listener)
            window.removeComponentListener(geometry); session.windows.remove(id)
        }
    }
    val targetBounds = session.layout.bounds[id]
    val overlap = seamOverlap()
    LaunchedEffect(targetBounds, overlap) {
        bridge.applying=true
        try {
            // La posición más reciente del modelo, no la capturada: durante un arrastre la
            // ventana ya se movió con syncGroupLocations y no debe retroceder un evento.
            (session.layout.bounds[id] ?: targetBounds)?.let { r ->
                session.trace("apply $id frame=${System.identityHashCode(window)} rect=$r")
                // Mientras el conjunto se desliza, la posición la lleva animateShift.
                if (!session.isShifting(id) && !session.nativeDrag) state.position = WindowPosition(r.x.dp, r.y.dp)
                state.size = androidx.compose.ui.unit.DpSize((r.width + overlap).dp, r.height.dp)
            }
            delay(32)
        } finally { bridge.applying=false }
    }
    // Al abrir una ventana en un conjunto, el solape de 1 px de la unión no siempre llegaba a la ventana
    // (medido: 1 px de hueco a 125 % hasta mover el conjunto). Cuando todo se asienta, se fija exacto.
    LaunchedEffect(revision, overlap) {
        delay(350)
        while (bridge.applying || session.draggingGroup || session.isShifting(id) || System.nanoTime() < session.geometrySettlesAt) delay(50)
        val r = session.layout.bounds[id] ?: return@LaunchedEffect
        if (!window.isShowing || state.placement != WindowPlacement.Floating) return@LaunchedEffect
        val want = java.awt.Rectangle(r.x, r.y, r.width + seamOverlap(), r.height)
        if (window.bounds == want) return@LaunchedEffect
        session.trace("settle $id ${window.bounds} -> $want")
        bridge.applying = true
        try {
            window.bounds = want
            state.position = WindowPosition(want.x.dp, want.y.dp)
            state.size = androidx.compose.ui.unit.DpSize(want.width.dp, want.height.dp)
            delay(32)
        } finally { bridge.applying = false }
    }
    LaunchedEffect(state.placement) {
        if(session.supportsMaximize && state.placement == WindowPlacement.Maximized) {
            state.placement = WindowPlacement.Floating
            if(!session.layout.group(id).maximized) session.maximize(id)
        }
    }
}

@Composable
internal fun WindowScope.DockDragArea(session: DockSession?, id: DockId?, modifier: Modifier, content: @Composable () -> Unit) {
    if (session == null || id == null) {
        WindowDraggableArea(modifier) { content() }
        return
    }
    // Sin la densidad como clave: al cruzar a una pantalla con otra escala cambia a mitad del
    // arrastre, y reiniciar el gesto soltaba las ventanas sin acoplarlas ni colocarlas.
    val currentDensity by rememberUpdatedState(LocalDensity.current.density)
    Box(modifier.pointerInput(session, id) {
        var lastTap = 0L
        var lastTapPoint = Point()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            session.trace("down $id consumed=${down.isConsumed}")
            if(down.isConsumed) return@awaitEachGesture
            // La ventana puede estar cerrándose (fuera del modelo) mientras aún recibe clics.
            val original = session.layout.bounds[id] ?: return@awaitEachGesture
            val density = currentDensity
            val origin = Point(original.x + (down.position.x / density).roundToInt(), original.y + (down.position.y / density).roundToInt())
            var previous = origin
            var active = false
            val originalGroup = session.layout.groups.firstOrNull { id in it.members } ?: return@awaitEachGesture
            val groupLeft = (session.layout.bounds[originalGroup.members.first()] ?: return@awaitEachGesture).x
            val groupRight = (session.layout.bounds[originalGroup.members.last()] ?: return@awaitEachGesture).let { it.x + it.width }
            val secondary = originalGroup.leader != id
            // En Windows el arrastre va en píxeles físicos (ver [SnapScreen]): con pantallas de escala
            // distinta, el punto de AWT salta al cruzar de una a otra y el conjunto se rompía.
            val screens = if (session.nativeMoves) snapScreens() else emptyList()
            /** El arrastre pasó a [DockSession.startNativeDrag]: este gesto ya no mueve ni suelta nada. */
            var handedOff = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
                    if (id !in session.layout.bounds) break
                    val point = MouseInfo.getPointerInfo().location
                    if (!change.pressed) {
                        if (!active && change.changedToUpIgnoreConsumed()) {
                            val reorder = session.reorderPreview
                            session.trace("release $id reorder=$reorder")
                            if(reorder != null) {
                                val (moving,target,_) = reorder
                                session.layout.reorder(moving,target)
                            } else if(session.supportsMaximize && origin.distance(point) < 8) {
                                val now = System.currentTimeMillis()
                                if(now - lastTap < 350 && lastTapPoint.distance(point) < 8) {
                                    session.maximize(id); lastTap=0L
                                } else { lastTap=now; lastTapPoint=point }
                            }
                        }
                        if (active) {
                            session.preview?.let { candidate ->
                                if (!session.layout.dock(id, candidate.target, candidate.before, desktopArea(Point(point.x,point.y))))
                                    session.message = "No hay espacio para acoplar el conjunto."
                            }
                            session.layout.settleInto(id, desktopArea(point))
                            session.syncGroupLocations(id)
                            javax.swing.Timer(180) { if(id in session.layout.bounds) session.raise(id) }.apply { isRepeats=false; start() }
                        }
                        break
                    }
                    if (!active) {
                        val group = session.layout.group(id)
                        val threshold = if (group.leader != id || group.maximized) 24 else 4
                        val insideLane = secondary && point.y in (original.y - 24)..(original.y + 68) && point.x in (groupLeft - 24)..(groupRight + 24)
                        if (insideLane && origin.distance(point) >= threshold) {
                            val target = group.members.filter { it != id }.firstOrNull { candidate ->
                                val r = session.layout.bounds[candidate] ?: return@firstOrNull false
                                point.x in r.x until r.x + r.width
                            }
                            session.trace("reorder candidate $id -> $target")
                            session.reorderPreview = target?.let {
                                session.layout.bounds[it]?.let { r -> Triple(id,it,point.x < r.x + r.width / 2) }
                            }
                            change.consume()
                        } else if (!insideLane && origin.distance(point) >= threshold) {
                            session.reorderPreview = null
                            val wasMaximized = group.maximized
                            // Windows con «restaurar al arrastrar» apagado: maximizado no se mueve.
                            if (wasMaximized && group.leader == id && screens.isNotEmpty() && !WindowsSnapSettings.dragFromMaximize) {
                                change.consume(); continue
                            }
                            if (group.leader != id) session.layout.detach(id)
                            else if (wasMaximized) session.restore(id, desktopArea(point))
                            val current = session.layout.bounds[id] ?: break
                            val x = point.x - ((origin.x - original.x).toDouble() / original.width * current.width).roundToInt()
                            val y = point.y - (origin.y - original.y).coerceIn(0, 44)
                            session.layout.move(id, x - current.x, y - current.y)
                            previous = point; active = true
                            session.draggingGroup = true
                            if(!wasMaximized) session.syncGroupLocations(id)
                            session.raise(id)
                            // Agarre en unidades lógicas desde la primera ventana del conjunto: en una pantalla
                            // con otra escala el puntero sigue sobre el mismo punto de la ventana.
                            val first = session.layout.bounds[session.layout.group(id).members.first()]
                            session.trace("drag $id: group=" + session.layout.group(id).members + " native=${screens.isNotEmpty()}")
                            if (screens.isNotEmpty() && first != null) {
                                // A partir de aquí lo lleva Windows directamente: al cambiar de escala, Compose
                                // corta los eventos del ratón (medido) y el conjunto se quedaba a medias.
                                change.consume()
                                handedOff = true
                                session.startNativeDrag(id, Point(point.x - first.x, point.y - first.y), screens)
                                break
                            }
                        }
                    } else {
                        session.layout.move(id, point.x - previous.x, point.y - previous.y)
                        session.syncGroupLocations(id)
                        previous = point
                    }
                    if (active) { change.consume(); session.preview = session.layout.candidate(id) }
                }
            } finally {
                if (active && !handedOff) {
                    session.geometrySettlesAt = System.nanoTime() + 400_000_000L
                    session.draggingGroup = false
                    session.nativeDrag = false
                }
                if (!handedOff) session.preview = null
                session.reorderPreview = null
            }
        }
    }) { content() }
}

/**
 * Bordes del conjunto para redimensionarlo: las ventanas no tienen marco y antes solo valía el tirador de la
 * esquina, que no hacía nada con el conjunto maximizado. Se sigue el puntero en coordenadas de pantalla porque
 * el borde izquierdo y el superior mueven la propia ventana durante el gesto.
 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.DockEdges(session: DockSession, id: DockId, first: Boolean, last: Boolean) {
    @Composable fun Edge(edges: List<DockEdge>, modifier: Modifier, cursor: Int) {
        Box(modifier.pointerHoverIcon(PointerIcon(Cursor(cursor))).pointerInput(session, id, edges) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val start = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                val g = session.layout.groups.firstOrNull { id in it.members } ?: return@awaitEachGesture
                val a = session.layout.bounds[g.members.first()] ?: return@awaitEachGesture
                val z = session.layout.bounds[g.members.last()] ?: return@awaitEachGesture
                // Distancia del puntero al borde al empezar: el borde no salta hasta el puntero.
                val grab = edges.associateWith { e -> when (e) {
                    DockEdge.Left -> start.x - a.x; DockEdge.Right -> start.x - (z.x + z.width)
                    DockEdge.Top -> start.y - a.y; DockEdge.Bottom -> start.y - (a.y + a.height)
                } }
                session.draggingGroup = true
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.first()
                        if (!change.pressed || id !in session.layout.bounds) break
                        change.consume()
                        val p = MouseInfo.getPointerInfo()?.location ?: continue
                        for (e in edges) session.layout.resizeEdge(id, e, (if (e == DockEdge.Left || e == DockEdge.Right) p.x else p.y) - grab.getValue(e))
                        session.applyGroupBounds(id)
                    }
                } finally {
                    session.geometrySettlesAt = System.nanoTime() + 250_000_000L
                    session.draggingGroup = false
                    session.syncGroupBounds(id)
                }
            }
        })
    }
    val t = 6.dp
    Edge(listOf(DockEdge.Top), Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 14.dp).height(4.dp), Cursor.N_RESIZE_CURSOR)
    Edge(listOf(DockEdge.Bottom), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp).height(t), Cursor.S_RESIZE_CURSOR)
    if (first) {
        Edge(listOf(DockEdge.Left), Modifier.align(Alignment.CenterStart).fillMaxHeight().padding(vertical = 14.dp).width(t), Cursor.W_RESIZE_CURSOR)
        Edge(listOf(DockEdge.Left, DockEdge.Top), Modifier.align(Alignment.TopStart).size(14.dp), Cursor.NW_RESIZE_CURSOR)
        Edge(listOf(DockEdge.Left, DockEdge.Bottom), Modifier.align(Alignment.BottomStart).size(14.dp), Cursor.SW_RESIZE_CURSOR)
    }
    if (last) {
        Edge(listOf(DockEdge.Right), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 14.dp).width(t), Cursor.E_RESIZE_CURSOR)
        Edge(listOf(DockEdge.Right, DockEdge.Top), Modifier.align(Alignment.TopEnd).size(14.dp), Cursor.NE_RESIZE_CURSOR)
        Edge(listOf(DockEdge.Right, DockEdge.Bottom), Modifier.align(Alignment.BottomEnd).size(16.dp), Cursor.SE_RESIZE_CURSOR)
    }
}
