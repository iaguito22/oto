package io.github.iaguito22.oto.remote

import io.github.iaguito22.oto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID

data class LinkedLibraryState(val lists: List<LocalPlaylist>, val likes: List<Heard>, val folders: Set<String>, val pinned: Set<String>, val flipped: Set<String>, val likeChanges: Map<String, LikeChange> = emptyMap())

/** Registros por campo y por canción: dos adiciones simultáneas no se sobrescriben. */
class LibraryReplica(private val prefs: Prefs, private val file: File) {
    private val lock = Mutex()
    private var records = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
    private val noticeMutable = MutableStateFlow<String?>(records.optString("notice").takeIf { it.isNotBlank() })
    val notice = noticeMutable.asStateFlow()
    private val device = records.optString("device").ifBlank { UUID.randomUUID().toString() }.also { records.put("device",it) }
    private val entries get() = records.optJSONObject("entries") ?: JSONObject().also { records.put("entries", it) }
    private var previous: JSONObject? = null
    private var observedVersion: List<Any?>? = null
    private var cachedDigest: String? = null
    private var needsPersist = true
    private var clock = entries.keys().asSequence().map { entries.getJSONObject(it).optLong("t") }.maxOrNull() ?: 0L
    private fun key(vararg fields:String) = JSONArray(fields).toString()
    private fun song(h:Heard) = JSONObject().put("id",h.id).put("title",h.title).put("artist",h.artist).put("artwork",h.artwork).put("artistId",h.artistId)
    private lateinit var observed: LinkedLibraryState
    private suspend fun read():JSONObject {
        observed = prefs.linkedLibrarySnapshot()
        val out=JSONObject()
        observed.lists.forEach { l ->
            out.put(key("list",l.id),true)
            out.put(key("name",l.id),l.name);l.folder?.let { out.put(key("folder",l.id),it) };l.from?.let { out.put(key("from",l.id),it) }
            out.put(key("order",l.id),JSONArray(l.songs.map { it.id }))
            l.songs.forEach { out.put(key("song",l.id,it.id),song(it)) }
        }
        observed.likes.forEach { out.put(key("liked",it.id),song(it)) }
        observed.folders.forEach { out.put(key("folders",it),true) }
        observed.pinned.forEach { out.put(key("pinned",it),true) }
        observed.flipped.forEach { out.put(key("flipped",it),true) }
        return out
    }
    private fun canonical(value: Any?): String = when(value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix="{",postfix="}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix="[",postfix="]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun same(a:Any?,b:Any?)=canonical(a)==canonical(b)
    private suspend fun capture() {
        val version = prefs.linkedLibraryVersion()
        if (version == observedVersion) return
        observedVersion = version
        val now=read()
        val before=previous ?: materialize()
        val first=entries.length()==0
        // Las decisiones se fechan al tocar el corazón, no al reconectar. Incluye
        // desmarcados de canciones ausentes y cambios hechos antes de la primera conexión.
        observed.likeChanges.forEach { (id, change) ->
            val k=key("liked",id)
            val old=entries.optJSONObject(k)
            if(old==null || change.time>old.getLong("t") || (change.time==old.getLong("t") && change.token>old.getString("d"))) {
                entries.put(k,JSONObject().put("t",change.time).put("d",change.token).put("v",now.opt(k) ?: JSONObject.NULL))
                cachedDigest=null;needsPersist=true
            }
            clock=maxOf(clock,change.time)
        }
        (now.keys().asSequence().toSet()+before.keys().asSequence().toSet()).forEach { k ->
            val fields=JSONArray(k)
            if(fields.getString(0)=="liked" && fields.getString(1) in observed.likeChanges) return@forEach
            if(!same(now.opt(k),before.opt(k))) {
                cachedDigest = null; needsPersist = true
                clock=maxOf(clock+1,System.currentTimeMillis())
                entries.put(k,JSONObject().put("t",if(first) 0 else clock).put("d",device).put("v",now.opt(k) ?: JSONObject.NULL))
            }
        }
        previous=now
    }
    private fun materialize():JSONObject=JSONObject().also { out ->
        entries.keys().forEach { k -> val e=entries.getJSONObject(k);if(!e.isNull("v"))out.put(k,e.get("v")) }
        // Decodificar una sola vez: volver a parsear toda la biblioteca por cada lista
        // bloqueaba la sincronización inicial en teléfonos con colecciones grandes.
        val keys=out.keys().asSequence().map { JSONArray(it) }.toList()
        val songsByList=keys.filter { it.getString(0)=="song" }.groupBy { it.getString(1) }
        keys.filter { it.getString(0)=="list" }.forEach { list ->
            val id=list.getString(1)
            val members=songsByList[id].orEmpty().map { it.getString(2) }.toSet()
            val order=out.optJSONArray(key("order",id))?:JSONArray()
            val known=(0 until order.length()).map { order.getString(it) }.filter { it in members }
            out.put(key("order",id),JSONArray(known+(members-known.toSet()).sorted()))
        }
    }
    private var persisted = ""
    private fun persist() {
        if (!needsPersist) return
        val text = records.toString()
        if (text == persisted) { needsPersist = false; return }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        // renameTo no reemplaza un destino existente en Windows. Conservar la copia
        // anterior hasta que la nueva esté escrita y sustituirla en una sola operación.
        try {
            Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING)
        }
        persisted = text
        needsPersist = false
    }
    suspend fun export():JSONObject=withContext(Dispatchers.IO) { lock.withLock { capture();persist();JSONObject(entries.toString()) } }
    suspend fun digest():String=withContext(Dispatchers.IO) { lock.withLock {
        capture();persist()
        cachedDigest ?: MessageDigest.getInstance("SHA-256").digest(canonical(entries).toByteArray()).joinToString("") { "%02x".format(it) }.also { cachedDigest=it }
    } }

    suspend fun merge(remote:JSONObject)=withContext(Dispatchers.IO) { lock.withLock {
      repeat(4) {
        capture()
        val expected = observed
        remote.keys().forEach { k ->
            val incoming=remote.getJSONObject(k);val old=entries.optJSONObject(k)
            val field=JSONArray(k).getString(0)
            if(old!=null && field in setOf("name","folder","from","order") && old.optString("d")!=incoming.optString("d") && !same(old.opt("v"),incoming.opt("v"))) {
                val label=when(field) { "name" -> "el nombre de una lista"; "folder" -> "la carpeta de una lista"; "order" -> "el orden de una lista"; else -> "una lista" }
                val note="Se recibieron cambios distintos en $label. Se han conservado las canciones de ambos dispositivos y una copia de las dos versiones."
                val conflicts=records.optJSONObject("conflicts")?:JSONObject().also { records.put("conflicts",it) }
                conflicts.put(k,JSONObject().put("local",old).put("remote",incoming))
                noticeMutable.value=note;records.put("notice",note);needsPersist=true
            }
            if(old==null || incoming.getLong("t")>old.getLong("t") || (incoming.getLong("t")==old.getLong("t") && incoming.getString("d")>old.getString("d"))) { entries.put(k,incoming);cachedDigest=null;needsPersist=true }
            clock=maxOf(clock,incoming.getLong("t"))
        }
        val result=materialize()
        val all=result.keys().asSequence().associateWith { JSONArray(it) }
        fun get(kind:String,id:String)=result.opt(key(kind,id))?.takeUnless { it==JSONObject.NULL }
        fun heard(o:JSONObject)=Heard(o.getString("id"),o.getString("title"),o.getString("artist"),o.optString("artwork").takeUnless { it.isBlank()||it=="null" },o.optString("artistId").takeUnless { it.isBlank()||it=="null" })
        val lists=all.filter { (_,k)->k.getString(0)=="list" }.map { (_,k)->
            val id=k.getString(1)
            val songs=all.filter { (_,k2)->k2.getString(0)=="song" && k2.getString(1)==id }.map { (s,_)->heard(result.getJSONObject(s)) }
            val order=get("order",id) as? JSONArray
            val positions=(0 until (order?.length()?:0)).associate { order!!.getString(it) to it }
            LocalPlaylist(id,get("name",id)?.toString()?:"Lista",songs.sortedWith(compareBy({positions[it.id]?:Int.MAX_VALUE},{it.id})),get("from",id)?.toString(),get("folder",id)?.toString())
        }
        val likeKeys=entries.keys().asSequence().filter { JSONArray(it).getString(0)=="liked" }.toList()
        val likeChanges=likeKeys.associate { k ->
            val e=entries.getJSONObject(k)
            JSONArray(k).getString(1) to LikeChange(e.getLong("t"),e.getString("d"),!e.isNull("v"))
        }
        val likes=likeKeys.filter { !entries.getJSONObject(it).isNull("v") }
            .sortedWith(compareByDescending<String> { entries.getJSONObject(it).getLong("t") }.thenBy { it })
            .map { heard(entries.getJSONObject(it).getJSONObject("v")) }
        fun set(kind:String)=all.values.filter { it.getString(0)==kind }.map { it.getString(1) }.toSet()
        if (prefs.applyLinkedLibrary(expected,lists,likes,set("folders"),set("pinned"),set("flipped"),likeChanges)) {
            previous=read();persist();return@withLock
        }
      }
      error("La biblioteca está cambiando; se reintentará la sincronización")
    } }
}
