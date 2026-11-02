package io.github.iaguito22.oto.remote

import kotlinx.coroutines.*
import java.net.*

/** Descubrimiento por broadcast; la sesión se autentica después con el secreto del QR. */
class LocalDiscovery(private val scope: CoroutineScope) : AutoCloseable {
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    fun advertise(id: String, port: Int) {
        val udp = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)); soTimeout = 1000 } }.getOrNull() ?: return
        socket = udp
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val bytes = ByteArray(512);val packet = DatagramPacket(bytes, bytes.size)
                try {
                    udp.receive(packet)
                    if(String(bytes,0,packet.length,Charsets.UTF_8)=="OTO1:$id") {
                        val reply="OTO1:$id:$port".toByteArray()
                        udp.send(DatagramPacket(reply,reply.size,packet.address,packet.port))
                    }
                } catch (_:SocketTimeoutException) {} catch (_:Exception) { break }
            }
        }
    }
    suspend fun find(id:String):Pair<String,Int>?=withContext(Dispatchers.IO) {
        DatagramSocket().use { udp ->
            udp.broadcast=true;udp.soTimeout=700
            val bytes="OTO1:$id".toByteArray()
            val addresses=runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast } }.getOrDefault(emptyList())+InetAddress.getByName("255.255.255.255")
            addresses.distinct().forEach { address -> runCatching { udp.send(DatagramPacket(bytes,bytes.size,address,PORT)) } }
            val buffer=ByteArray(512);val packet=DatagramPacket(buffer,buffer.size)
            runCatching { udp.receive(packet);val text=String(buffer,0,packet.length,Charsets.UTF_8);require(text.startsWith("OTO1:$id:"));val port=text.substringAfterLast(':').toInt();require(port in 1..65535);packet.address.hostAddress to port }.getOrNull()
        }
    }
    override fun close() { job?.cancel();socket?.close() }
    companion object { private const val PORT=48732 }
}
