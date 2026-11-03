package io.github.iaguito22.oto.desktop

import org.json.JSONArray
import org.json.JSONObject
import io.github.iaguito22.oto.desktop.ui.PlayerForm
import kotlin.math.roundToInt

internal fun DockLayout.encode(): String {
    fun rect(r: DockRect) = JSONArray(listOf(r.x,r.y,r.width,r.height))
    return JSONObject().put("version",1)
        .put("bounds",JSONObject().apply { bounds.forEach { (id,r) -> put(id.name,rect(r)) } })
        .put("groups",JSONArray(groups.map { g -> JSONObject()
            .put("members",JSONArray(g.members.map { it.name })).put("leader",g.leader.name)
            .put("maximized",g.maximized).put("area",g.area?.let(::rect))
            .put("restored",JSONObject().apply { g.restored.forEach { (id,r) -> put(id.name,rect(r)) } })
            .put("beforeAttach",JSONObject().apply { g.beforeAttach.forEach { (id,widths) -> put(id.name,JSONObject().apply { widths.forEach { (member,width) -> put(member.name,width) } }) } }) }))
        .put("homes",JSONObject().apply { homes.forEach { (id,h) -> put(id.name,JSONObject()
            .put("rect",rect(h.rect)).put("siblings",JSONArray(h.siblings.map { it.name })).put("index",h.index)) } })
        .toString()
}

internal fun decodeDockLayout(raw: String?): DockLayout? = runCatching {
    if (raw == null) return null
    val json = JSONObject(raw)
    require(json.getInt("version") == 1)
    fun rect(a: JSONArray): DockRect {
        require(a.length() == 4)
        return DockRect(a.getInt(0).coerceIn(-100000,100000), a.getInt(1).coerceIn(-100000,100000),
            a.getInt(2).coerceIn(96,10000),a.getInt(3).coerceIn(108,10000))
    }
    fun ids(a: JSONArray) = (0 until a.length()).map { DockId.valueOf(a.getString(it)) }
    DockLayout().apply {
        val savedBounds = json.getJSONObject("bounds")
        savedBounds.keys().forEach { name -> bounds[DockId.valueOf(name)] = rect(savedBounds.getJSONArray(name)) }
        val savedGroups = json.getJSONArray("groups")
        for (i in 0 until savedGroups.length()) {
            val g = savedGroups.getJSONObject(i)
            val members = ids(g.getJSONArray("members"))
            require(members.isNotEmpty() && members.distinct() == members && members.all { it in bounds })
            val leader = DockId.valueOf(g.getString("leader"))
            require(leader in members && leader.rank == members.maxOf { it.rank })
            val restored = mutableMapOf<DockId,DockRect>()
            val previous = g.getJSONObject("restored")
            previous.keys().forEach { name -> restored[DockId.valueOf(name)] = rect(previous.getJSONArray(name)) }
            val beforeAttach = mutableMapOf<DockId,Map<DockId,Int>>()
            val savedWidths = g.optJSONObject("beforeAttach") ?: JSONObject()
            savedWidths.keys().forEach { name ->
                val widths = savedWidths.getJSONObject(name)
                beforeAttach[DockId.valueOf(name)] = widths.keys().asSequence().associate { member -> DockId.valueOf(member) to widths.getInt(member).coerceIn(96,10000) }
            }
            groups += DockGroup(members.toMutableList(),leader,g.getBoolean("maximized"),g.optJSONArray("area")?.let(::rect),restored,beforeAttach)
        }
        require(groups.flatMap { it.members }.let { it.size == bounds.size && it.toSet() == bounds.keys })
        val savedHomes = json.optJSONObject("homes") ?: JSONObject()
        savedHomes.keys().forEach { name ->
            val h = savedHomes.getJSONObject(name)
            val siblings = ids(h.getJSONArray("siblings"))
            val index = h.getInt("index")
            val id = DockId.valueOf(name)
            require(index in siblings.indices && siblings[index] == id)
            homes[id] = DockHome(rect(h.getJSONArray("rect")),siblings,index)
        }
    }
}.getOrNull()

internal fun initialDockLayout(area: DockRect = desktopArea()): DockLayout {
    val layout = DockLayout()
    val height = minOf(PlayerForm.Full.height,area.height - 40).coerceAtLeast(480)
    val playerWidth = (height * 9f / 16f).roundToInt()
    val y = area.y + (area.height - height) / 2
    val libraryWidth = (area.width - playerWidth - DockId.Lyrics.defaultWidth - 40).coerceIn(640,960)
    layout.create(DockId.Player,DockRect(area.x + (area.width-playerWidth)/2,y,playerWidth,height))
    val order = listOf(DockId.Lyrics, DockId.Library, DockId.Player)
    layout.homes[DockId.Library] = DockHome(DockRect(area.x+20+DockId.Lyrics.defaultWidth,y,libraryWidth,height),order,1)
    layout.homes[DockId.Lyrics] = DockHome(DockRect(area.x+20,y,DockId.Lyrics.defaultWidth,height),order,0)
    // Una cola sin hermanos abre independiente, en vez de unirse al último módulo enfocado.
    layout.homes[DockId.Queue] = DockHome(DockRect(area.x+32,area.y+48,DockId.Queue.defaultWidth,minOf(580,area.height)),listOf(DockId.Queue),0)
    return layout
}
