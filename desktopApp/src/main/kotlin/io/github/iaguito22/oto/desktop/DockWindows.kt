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
    fun raise(id: DockId) {
        trace("raise $id, raising=$raising")
        lastFocused = id
        if (raising) return
        raising = true
        try {
            layout.group(id).members.filter { it != id }.forEach { member ->
                windows[member]?.let { w ->
                    val previous = w.isAutoRequestFocus
                    try { w.isAutoRequestFocus=false; w.toFront() } finally { w.isAutoRequestFocus=previous }
                }
            }
            windows[id]?.toFront()
        } finally { EventQueue.invokeLater { raising = false } }
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
        if (!layout.open(id,origin,area)) message = "La ventana se abre suelta porque no cabe acoplada."
        else message = ""
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
    val bridge = remember { object { var applying = false; var job: Job? = null } }
    fun seamOverlap(): Int = if (System.getProperty("os.name").startsWith("Windows") && id in session.layout.bounds &&
        session.layout.group(id).members.lastOrNull() != id) 1 else 0
    DisposableEffect(session, id, window) {
        session.trace("attach $id frame=${System.identityHashCode(window)} title=${window.name} actual=${window.bounds} model=${session.layout.bounds[id]}")
        session.windows[id] = window
        val listener = object : WindowAdapter() {
            override fun windowStateChanged(e: WindowEvent) {
                if (id !in session.layout.bounds || session.layout.group(id).leader != id) return
                val wasMinimized = e.oldState and Frame.ICONIFIED != 0
                val minimized = e.newState and Frame.ICONIFIED != 0
                if (wasMinimized == minimized) return
                session.layout.group(id).members.filter { it != id }.forEach { member ->
                    (session.windows[member] as? Frame)?.let { frame ->
                        frame.extendedState = if (minimized) frame.extendedState or Frame.ICONIFIED
                        else frame.extendedState and Frame.ICONIFIED.inv()
                    }
                }
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
                        if(session.layout.group(id).leader != id) session.layout.detach(id)
                        val r = session.layout.bounds.getValue(id)
                        session.layout.move(id,actual.x-r.x,actual.y-r.y)
                    }
                }
            }
            override fun componentResized(e: ComponentEvent) = reconcile()
            override fun componentMoved(e: ComponentEvent) = reconcile()
        }
        window.addWindowFocusListener(listener)
        window.addWindowStateListener(listener)
        window.addComponentListener(geometry)
        onDispose {
            bridge.job?.cancel(); window.removeWindowFocusListener(listener); window.removeWindowStateListener(listener)
            window.removeComponentListener(geometry); session.windows.remove(id)
        }
    }
    val targetBounds = session.layout.bounds[id]
    val overlap = seamOverlap()
    LaunchedEffect(targetBounds, overlap) {
        bridge.applying=true
        try {
            targetBounds?.let { r ->
                session.trace("apply $id frame=${System.identityHashCode(window)} rect=$r")
                state.position = WindowPosition(r.x.dp, r.y.dp)
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
            val original = session.layout.bounds.getValue(id)
            val origin = Point(original.x + (down.position.x / density).roundToInt(), original.y + (down.position.y / density).roundToInt())
            var previous = origin
            var active = false
            val originalGroup = session.layout.group(id)
            val groupLeft = session.layout.bounds.getValue(originalGroup.members.first()).x
            val groupRight = session.layout.bounds.getValue(originalGroup.members.last()).let { it.x + it.width }
            val secondary = originalGroup.leader != id
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
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
                                val r = session.layout.bounds.getValue(candidate)
                                point.x in r.x until r.x + r.width
                            }
                            session.trace("reorder candidate $id -> $target")
                            session.reorderPreview = target?.let {
                                Triple(id,it,point.x < session.layout.bounds.getValue(it).let { r -> r.x + r.width / 2 })
                            }
                            change.consume()
                        } else if (!insideLane && origin.distance(point) >= threshold) {
                            session.reorderPreview = null
                            val wasMaximized = group.maximized
                            if (group.leader != id) session.layout.detach(id)
                            else if (wasMaximized) session.maximize(id)
                            val current = session.layout.bounds.getValue(id)
                            val x = point.x - ((origin.x - original.x).toDouble() / original.width * current.width).roundToInt()
                            val y = point.y - (origin.y - original.y).coerceIn(0, 44)
                            session.layout.move(id, x - current.x, y - current.y)
                            previous = point; active = true
                            session.draggingGroup = true
                            session.raise(id)
                            session.trace("drag $id: group=" + session.layout.group(id).members)
                        }
                    } else {
                        session.layout.move(id, point.x - previous.x, point.y - previous.y)
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
