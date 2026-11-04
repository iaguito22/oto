package io.github.iaguito22.oto.desktop

import kotlin.math.abs
import kotlin.math.max

/** Coordenadas lógicas de escritorio; las mismas unidades que las ventanas AWT. */
internal data class DockRect(val x: Int, val y: Int, val width: Int, val height: Int)
internal enum class DockId(val rank: Int, val defaultWidth: Int, val minWidth: Int, val minHeight: Int) {
    Library(3, 960, 640, 480), Player(2, 320, 260, 370), Lyrics(1, 480, 220, 250), Queue(1, 420, 240, 250)
}
internal data class DockGroup(
    val members: MutableList<DockId>, var leader: DockId,
    var maximized: Boolean = false, var area: DockRect? = null,
    val restored: MutableMap<DockId, DockRect> = mutableMapOf(),
    val beforeAttach: MutableMap<DockId, Map<DockId, Int>> = mutableMapOf()
)
internal data class DockHome(val rect: DockRect, val siblings: List<DockId>, val index: Int)
internal data class DockCandidate(val moving: DockId, val target: DockId, val before: Boolean)

/** No conoce Compose ni AWT: cada operación deja una distribución completa y consistente. */
internal class DockLayout {
    val bounds = mutableMapOf<DockId, DockRect>()
    val groups = mutableListOf<DockGroup>()
    val homes = mutableMapOf<DockId, DockHome>()
    /** Orden relativo recordado de todas las ventanas, también las cerradas, para reabrirlas en su sitio. */
    val memory = mutableListOf<DockId>()
    internal fun rememberOrder(members: List<DockId>) {
        val slots = memory.indices.filter { memory[it] in members }
        slots.zip(members.filter { it in memory }).forEach { (slot, id) -> memory[slot] = id }
        members.forEachIndexed { i, id ->
            if (id in memory) return@forEachIndexed
            if (i > 0) memory.add(memory.indexOf(members[i - 1]) + 1, id)
            else memory.add(members.getOrNull(1)?.let { memory.indexOf(it) }?.takeIf { it >= 0 } ?: 0, id)
        }
    }
    val minimums = mutableMapOf<DockId, Pair<Int, Int>>()
    val defaults = mutableMapOf<DockId, Int>()
    private fun minWidth(id: DockId) = minimums[id]?.first ?: id.minWidth
    private fun minHeight(id: DockId) = minimums[id]?.second ?: id.minHeight
    fun setMinimum(id: DockId, width: Int, height: Int, defaultWidth: Int) {
        minimums[id] = width to height; defaults[id] = defaultWidth
        if (id in bounds) {
            val g = group(id)
            if (g.maximized) fit(g) else {
                val r = bounds.getValue(id)
                resize(id, max(width,r.width), max(height,r.height))
            }
        }
    }
    var changed: () -> Unit = {}
    fun group(id: DockId) = groups.first { id in it.members }
    fun create(id: DockId, rect: DockRect) {
        bounds[id] = rect
        groups += DockGroup(mutableListOf(id), id)
        changed()
    }
    private fun elect(g: DockGroup) {
        if (g.members.isNotEmpty()) g.leader = g.members.maxBy { it.rank }
    }
    private fun flow(g: DockGroup, x: Int, y: Int, height: Int) {
        var next = x
        g.members.forEach { id ->
            val r = bounds.getValue(id)
            bounds[id] = DockRect(next, y, r.width, height)
            next += r.width
        }
    }
    fun move(id: DockId, dx: Int, dy: Int) {
        val g = group(id)
        g.members.forEach { bounds[it] = bounds.getValue(it).let { r -> r.copy(x = r.x + dx, y = r.y + dy) } }
        changed()
    }
    fun resize(id: DockId, width: Int, height: Int, top: Int? = null) {
        val g = group(id)
        if (g.maximized) return
        val first = bounds.getValue(g.members.first())
        bounds[id] = bounds.getValue(id).copy(width = max(minWidth(id), width))
        val newHeight = max(height, g.members.maxOf { minHeight(it) })
        val y = if(top == null) first.y else top + height - newHeight
        flow(g, first.x, y, newHeight)
        changed()
    }
    /** Mueve una unión sin desplazar el conjunto ni cambiar su anchura total. */
    fun resizeDivider(leftId: DockId, delta: Int) {
        val g = group(leftId)
        val index = g.members.indexOf(leftId)
        val rightId = g.members.getOrNull(index + 1) ?: return
        val left = bounds.getValue(leftId)
        val right = bounds.getValue(rightId)
        val movement = delta.coerceIn(minWidth(leftId) - left.width, right.width - minWidth(rightId))
        if (movement == 0) return
        g.beforeAttach.clear()
        val first = bounds.getValue(g.members.first())
        bounds[leftId] = left.copy(width = left.width + movement)
        bounds[rightId] = right.copy(width = right.width - movement)
        flow(g, first.x, first.y, first.height)
        changed()
    }
    /** Los dependientes son las piezas exteriores hasta el siguiente módulo de igual o mayor rango. */
    fun detach(id: DockId) {
        val g = group(id)
        if (g.leader == id) return
        val at = g.members.indexOf(id)
        val step = if (at < g.members.indexOf(g.leader)) -1 else 1
        val carried = mutableListOf(id)
        var i = at + step
        while (i in g.members.indices && g.members[i].rank < id.rank) {
            carried += g.members[i]; i += step
        }
        carried.sortBy { g.members.indexOf(it) }
        val old = carried.associateWith { g.restored[it] ?: bounds.getValue(it) }
        val start = bounds.getValue(carried.first())
        g.members.removeAll(carried.toSet())
        val detached = DockGroup(carried, id)
        groups += detached
        carried.forEach { bounds[it] = old.getValue(it) }
        flow(detached, start.x, start.y, old.getValue(id).height.coerceAtLeast(carried.maxOf { minHeight(it) }))
        if (g.maximized) fit(g) else {
            val chief=bounds.getValue(g.leader)
            val start=chief.x-g.members.take(g.members.indexOf(g.leader)).sumOf { bounds.getValue(it).width }
            flow(g,start,chief.y,chief.height)
        }
        changed()
    }
    private fun fit(g: DockGroup): Boolean {
        val area = g.area ?: return true
        if (area.height < g.members.maxOf { minHeight(it) }) return false
        val flexible = if (DockId.Library in g.members) DockId.Library else if (DockId.Lyrics in g.members) DockId.Lyrics else if (DockId.Queue in g.members) DockId.Queue else DockId.Player
        val widths = g.members.associateWith { bounds.getValue(it).width }.toMutableMap()
        var excess = widths.values.sum() - area.width
        g.members.filter { it != flexible }.forEach { id ->
            val reduction = excess.coerceAtLeast(0).coerceAtMost((widths.getValue(id) - (defaults[id] ?: id.defaultWidth)).coerceAtLeast(0))
            widths[id] = widths.getValue(id) - reduction; excess -= reduction
        }
        val others = widths.filterKeys { it != flexible }.values.sum()
        val floor = if(flexible == DockId.Library) minWidth(flexible)
            else minOf(widths.getValue(flexible), defaults[flexible] ?: flexible.defaultWidth).coerceAtLeast(minWidth(flexible))
        if (area.width - others < floor) return false
        widths[flexible] = area.width - others
        widths.forEach { (id, w) -> bounds[id] = bounds.getValue(id).copy(width = w) }
        flow(g, area.x, area.y, area.height)
        return true
    }
    fun dock(moving: DockId, target: DockId, before: Boolean, available: DockRect? = null): Boolean {
        val a = group(moving); val b = group(target)
        if (a === b) return false
        val oldBounds = bounds.toMap()
        val oldMembers = b.members.toList()
        val oldLeader = b.leader
        val first = bounds.getValue(b.members.first())
        // El conjunto ya visible conserva su altura; la pieza entrante se adapta a él.
        val height = bounds.getValue(b.leader).height
        val index = b.members.indexOf(target) + if (before) 0 else 1
        b.members.addAll(index, a.members)
        if (a.leader.rank > b.leader.rank) b.leader = a.leader
        val success = if (available != null && available.height < b.members.maxOf { minHeight(it) }) false
        else if (b.maximized) fit(b) else if (available != null && b.members.sumOf { bounds.getValue(it).width } > available.width) {
            b.area = available
            val fits = fit(b)
            b.area = null
            if(fits) flow(b,first.x,first.y,max(height,b.members.maxOf { minHeight(it) }).coerceAtMost(available.height))
            fits
        } else {
            flow(b, first.x - if (before && index == 0) a.members.sumOf { bounds.getValue(it).width } else 0,
                first.y, max(height, b.members.maxOf { minHeight(it) }).let { if(available == null) it else it.coerceAtMost(available.height) }); true
        }
        if (!success) {
            b.members.clear(); b.members.addAll(oldMembers); b.leader = oldLeader
            bounds.clear(); bounds.putAll(oldBounds)
            return false
        }
        if (b.maximized) a.members.forEach {
            b.restored[it] = a.restored[it] ?: oldBounds.getValue(it)
            b.beforeAttach[it] = oldMembers.associateWith { member -> oldBounds.getValue(member).width }
        }
        groups.remove(a)
        rememberOrder(b.members)
        changed(); return true
    }
    /** Margen generoso para acoplar sin tener que afinar al píxel; gana el borde más cercano en total. */
    fun candidate(moving: DockId, proximity: Int = 24): DockCandidate? {
        val a = group(moving)
        val left = bounds.getValue(a.members.first()); val right = bounds.getValue(a.members.last())
        fun gap(c: DockCandidate): Int { val r = bounds.getValue(c.target); return abs(if (c.before) right.x + right.width - r.x else left.x - r.x - r.width) }
        return groups.filter { it !== a }.flatMap { g ->
            val first = g.members.first(); val last = g.members.last()
            listOf(DockCandidate(moving, first, true), DockCandidate(moving, last, false))
        }.filter { c ->
            abs(left.y - bounds.getValue(c.target).y) <= 48 && gap(c) <= proximity
        }.minByOrNull { c -> abs(left.y - bounds.getValue(c.target).y) + gap(c) }
    }
    fun maximize(id: DockId, area: DockRect): Boolean {
        val g = group(id)
        if (g.maximized) {
            g.members.forEach { g.restored[it]?.let { r -> bounds[it] = r } }
            val first = bounds.getValue(g.members.first())
            flow(g, first.x, first.y, bounds.getValue(g.leader).height.coerceAtLeast(g.members.maxOf { minHeight(it) }))
            g.maximized = false; g.area = null; g.restored.clear(); g.beforeAttach.clear()
            if (g.members.sumOf { bounds.getValue(it).width } > area.width) {
                // Los módulos abiertos durante la maximización no estaban en el ancho
                // normal original. Ajustarlo evita restaurar el reproductor fuera del monitor.
                val normal = bounds.getValue(g.members.first())
                g.area = area.copy(y = normal.y.coerceIn(area.y, max(area.y, area.y + area.height - normal.height)),
                    height = minOf(normal.height, area.height))
                fit(g)
                g.area = null
            }
            constrain(id, area)
        } else {
            g.restored.putAll(g.members.associateWith { bounds.getValue(it) })
            g.area = area
            if (!fit(g)) { g.area = null; g.restored.clear(); return false }
            g.maximized = true
        }
        changed(); return true
    }
    fun close(id: DockId) {
        val g = group(id)
        val previousWidths = g.beforeAttach.remove(id)
        rememberOrder(g.members)
        homes[id] = DockHome(g.restored[id] ?: bounds.getValue(id), g.members.toList(), g.members.indexOf(id))
        g.members.remove(id); bounds.remove(id); g.restored.remove(id)
        if (g.members.isEmpty()) groups.remove(g) else {
            elect(g)
            if (g.maximized) {
                if(previousWidths != null && previousWidths.keys == g.members.toSet()) previousWidths.forEach { (member,width) ->
                    bounds[member] = bounds.getValue(member).copy(width=width)
                }
                fit(g)
            } else {
                val chief=bounds.getValue(g.leader)
                val start=chief.x-g.members.take(g.members.indexOf(g.leader)).sumOf { bounds.getValue(it).width }
                flow(g,start,chief.y,chief.height)
            }
        }
        changed()
    }
    fun open(id: DockId, origin: DockId?, area: DockRect): Boolean {
        if (id in bounds) return true
        val home = homes[id]
        val rect = home?.rect ?: DockRect(area.x + 40, area.y + 40, id.defaultWidth, if(id == DockId.Library) 740 else 580)
        create(id, rect.copy(width = rect.width.coerceIn(minWidth(id), max(area.width,minWidth(id))),
            height = rect.height.coerceIn(minHeight(id), max(area.height,minHeight(id)))))
        val siblings = home?.siblings
        var before = false
        val joined = siblings?.let { s -> groups.firstOrNull { gr -> gr.members.any { it != id && it in s } } }
        val target = if (siblings != null && joined != null) {
            // Orden recordado: detrás de la ventana que le precedía, o delante de la que le seguía.
            val mine = memory.indexOf(id)
            val others = joined.members.filter { it != id && it in bounds }
            val order = { m: DockId -> memory.indexOf(m) }
            val prev = if (mine < 0) null else others.filter { order(it) in 0 until mine }.maxByOrNull(order)
            val next = if (mine < 0) null else others.filter { order(it) > mine }.minByOrNull(order)
            if (prev != null) prev.also { before = false }
            else if (next != null) next.also { before = true }
            else others.minByOrNull { kotlin.math.abs(siblings.indexOf(it) - home.index) }?.also { before = home.index < siblings.indexOf(it) }
        } else if (siblings != null) null else origin?.takeIf { it in bounds }?.let {
            val g = group(it)
            val chief = g.members.indexOf(g.leader)
            val left = g.members.take(chief).sumOf { member -> bounds.getValue(member).width }
            val right = g.members.drop(chief+1).sumOf { member -> bounds.getValue(member).width }
            before = left < right
            if(before) g.members.first() else g.members.last()
        }
        val fits = target == null || dock(id, target, before, area)
        constrain(id, area)
        return fits
    }
    /** Intercambia únicamente las dos ventanas señaladas, conservando el resto del conjunto. */
    fun reorder(id: DockId, target: DockId) {
        val g = group(id)
        if (id == g.leader || id == target || target !in g.members) return
        val from = g.members.indexOf(id); val to = g.members.indexOf(target)
        val left = bounds.getValue(g.members.first()).x
        g.members[from] = target; g.members[to] = id
        rememberOrder(g.members)
        if (g.maximized) fit(g) else {
            val chief = bounds.getValue(g.leader)
            flow(g, left, chief.y, chief.height)
        }
        changed()
    }
    fun recover(areas: List<DockRect>) {
        require(areas.isNotEmpty())
        val pending = groups.toMutableList()
        while(pending.isNotEmpty()) {
            val g = pending.removeAt(0)
            val leader = bounds.getValue(g.leader)
            val area = areas.firstOrNull { leader.x in it.x until it.x+it.width && leader.y in it.y until it.y+it.height } ?: areas.first()
            val first = bounds.getValue(g.members.first())
            if(g.maximized || g.members.sumOf { bounds.getValue(it).width } > area.width) {
                g.area = area
                while(!fit(g) && g.members.size > 1) {
                    val leaf = listOf(g.members.first(),g.members.last()).filter { it != g.leader }.minBy { it.rank }
                    detach(leaf)
                    pending += group(leaf)
                }
                if(!g.maximized) { g.area = null; flow(g,first.x,first.y,first.height.coerceAtMost(area.height).coerceAtLeast(g.members.maxOf { minHeight(it) })) }
            } else {
                flow(g,first.x,first.y,first.height.coerceAtMost(area.height).coerceAtLeast(g.members.maxOf { minHeight(it) }))
            }
            if(!g.maximized) constrain(g.leader,area)
        }
        changed()
    }
    fun organize(area: DockRect) {
        val ids = bounds.keys.sortedByDescending { it.rank }
        groups.clear()
        ids.forEachIndexed { index,id ->
            bounds[id] = bounds.getValue(id).copy(x=area.x+24+index*24,y=area.y+24+index*24)
            groups += DockGroup(mutableListOf(id),id)
        }
        val root = ids.firstOrNull() ?: return
        ids.drop(1).forEach { id ->
            val g=group(root);val chief=g.members.indexOf(g.leader)
            val left=g.members.take(chief).sumOf { bounds.getValue(it).width }
            val right=g.members.drop(chief+1).sumOf { bounds.getValue(it).width }
            val before=left<right
            dock(id,if(before)g.members.first() else g.members.last(),before,area)
        }
        constrain(root,area); changed()
    }
    fun constrain(id: DockId, area: DockRect) {
        val g = group(id)
        if (g.maximized) return
        val first = bounds.getValue(g.members.first())
        val width = g.members.sumOf { bounds.getValue(it).width }
        val x = first.x.coerceIn(area.x - (width - area.width).coerceAtLeast(0), max(area.x, area.x + area.width - width))
        val y = first.y.coerceIn(area.y, max(area.y, area.y + area.height - first.height))
        move(id, x - first.x, y - first.y)
    }
}
