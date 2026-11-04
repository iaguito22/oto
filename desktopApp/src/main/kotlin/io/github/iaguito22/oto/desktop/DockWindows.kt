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
    val config = configs.firstOrNull { it.bounds.contains(point) } ?: configs.first()
    val r = config.bounds; val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    return DockRect(r.x + insets.left, r.y + insets.top,
        r.width - insets.left - insets.right, r.height - insets.top - insets.bottom)
}

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
        val screens = areas ?: GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { config ->
            val b=config.defaultConfiguration.bounds
            desktopArea(Point(b.x+b.width/2,b.y+b.height/2))
        }
        layout.recover(screens)
        layout.changed = { revision++ }
    }
    fun trace(message: String) { if(System.getenv("OTO_DOCK_TRACE") == "1") println("Dock: $message") }
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
    fun settlingGroup() = System.nanoTime() - groupChangedAt < 800_000_000L
    /**
     * El botón de la barra de tareas es de la ventana principal. Si estaba activa otra del
     * conjunto, Windows activa la principal en vez de minimizar: lo tratamos como «esconder».
     */
    fun taskbarToggle(id: DockId, previous: Window?): Boolean {
        if (id !in layout.bounds || settlingGroup()) return false
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
        val r = layout.bounds.getValue(id)
        if (!layout.maximize(id, desktopArea(Point(r.x, r.y)))) message = "El conjunto no cabe en esta pantalla."
    }
    fun open(id: DockId, origin: DockId? = lastFocused ?: layout.bounds.keys.maxByOrNull { it.rank }) {
        if (id in layout.bounds) { raise(id); return }
        val anchor = origin?.let { layout.bounds[it] }
        val area = desktopArea(anchor?.let { Point(it.x,it.y) } ?: MouseInfo.getPointerInfo().location)
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
    val bridge = remember { object { var applying = false; var job: Job? = null; var snappedAt = 0L } }
    fun seamOverlap(): Int = if (System.getProperty("os.name").startsWith("Windows") && id in session.layout.bounds &&
        session.layout.group(id).members.lastOrNull() != id) 1 else 0
    DisposableEffect(session, id, window) {
        session.trace("attach $id frame=${System.identityHashCode(window)} title=${window.name} actual=${window.bounds} model=${session.layout.bounds[id]}")
        session.windows[id] = window
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
                    while(bridge.applying || session.draggingGroup || System.nanoTime() < session.geometrySettlesAt) delay(16)
                    val expected = session.layout.bounds[id] ?: return@launch
                    if(!window.isShowing || state.placement != WindowPlacement.Floating) return@launch
                    val actual = window.bounds
                    session.trace("reconcile $id frame=${System.identityHashCode(window)} actual=$actual expected=$expected state=${state.size} revision=${session.revision}")
                    val overlap = seamOverlap()
                    val change = nativeDockChange(DockRect(actual.x,actual.y,actual.width,actual.height),expected,overlap)
                    val resized = change == NativeDockChange.Resize
                    if(resized) {
                        session.trace("native resize $id: ${actual.width}x${actual.height}")
                        if(session.layout.group(id).maximized) session.maximize(id)
                        session.layout.resize(id,actual.width - overlap,actual.height,actual.y)
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
                if (!session.isShifting(id)) state.position = WindowPosition(r.x.dp, r.y.dp)
                state.size = androidx.compose.ui.unit.DpSize((r.width + overlap).dp, r.height.dp)
            }
            delay(32)
        } finally { bridge.applying=false }
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
    val density = LocalDensity.current.density
    Box(modifier.pointerInput(session, id, density) {
        var lastTap = 0L
        var lastTapPoint = Point()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            session.trace("down $id consumed=${down.isConsumed}")
            if(down.isConsumed) return@awaitEachGesture
            // La ventana puede estar cerrándose (fuera del modelo) mientras aún recibe clics.
            val original = session.layout.bounds[id] ?: return@awaitEachGesture
            val origin = Point(original.x + (down.position.x / density).roundToInt(), original.y + (down.position.y / density).roundToInt())
            var previous = origin
            var active = false
            val originalGroup = session.layout.groups.firstOrNull { id in it.members } ?: return@awaitEachGesture
            val groupLeft = (session.layout.bounds[originalGroup.members.first()] ?: return@awaitEachGesture).x
            val groupRight = (session.layout.bounds[originalGroup.members.last()] ?: return@awaitEachGesture).let { it.x + it.width }
            val secondary = originalGroup.leader != id
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
                        if (active && change.changedToUpIgnoreConsumed()) {
                            session.preview?.let { candidate ->
                                if (!session.layout.dock(id, candidate.target, candidate.before, desktopArea(Point(point.x,point.y))))
                                    session.message = "No hay espacio para acoplar el conjunto."
                            }
                            session.layout.constrain(id, desktopArea(point))
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
                            if (group.leader != id) session.layout.detach(id)
                            else if (wasMaximized) session.maximize(id)
                            val current = session.layout.bounds[id] ?: break
                            val x = point.x - ((origin.x - original.x).toDouble() / original.width * current.width).roundToInt()
                            val y = point.y - (origin.y - original.y).coerceIn(0, 44)
                            session.layout.move(id, x - current.x, y - current.y)
                            previous = point; active = true
                            session.draggingGroup = true
                            if(!wasMaximized) session.syncGroupLocations(id)
                            session.raise(id)
                            session.trace("drag $id: group=" + session.layout.group(id).members)
                        }
                    } else {
                        session.layout.move(id, point.x - previous.x, point.y - previous.y)
                        session.syncGroupLocations(id)
                        previous = point
                    }
                    if (active) { change.consume(); session.preview = session.layout.candidate(id) }
                }
            } finally {
                if (active) {
                    session.geometrySettlesAt = System.nanoTime() + 250_000_000L
                    session.draggingGroup = false
                }
                session.preview = null; session.reorderPreview = null
            }
        }
    }) { content() }
}
