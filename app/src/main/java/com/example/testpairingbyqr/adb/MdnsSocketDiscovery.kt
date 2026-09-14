package com.example.testpairingbyqr.adb

import android.content.Context
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException

/**
 * Descubrimiento mDNS hecho a mano sobre un socket UDP atado a la red Wi-Fi.
 *
 * Existe porque [android.net.nsd.NsdManager] no es capaz de descubrir nada mientras hay una VPN
 * levantada: falla con FAILURE_INTERNAL_ERROR incluso pidiéndole explícitamente la red Wi-Fi.
 * Aquí el socket se ata con `Network.bindSocket()`, así que el tráfico sale por wlan0 sin pasar
 * por el túnel, que es justo lo que necesita un multicast de enlace local.
 */
class MdnsSocketDiscovery(
    context: Context,
    private val serviceType: String,
    private val listener: AdbMdnsDiscovery.Listener,
) {

    private val appContext: Context = context.applicationContext

    /** Nombre completo del tipo tal y como viaja por el cable. */
    private val queryName = "$serviceType.local"

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var socket: MulticastSocket? = null

    /** Último endpoint publicado por instancia, para no repetir el mismo log una y otra vez. */
    private val published = HashMap<String, String>()

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "mdns-$serviceType").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
        thread?.interrupt()
        thread = null
        published.clear()
    }

    private fun loop() {
        val network = NetworkDiagnostics.wifiNetwork(appContext)
        if (network == null) {
            logw("[$serviceType/socket] no hay red Wi-Fi a la que atarse")
            listener.onLog("Sin red Wi-Fi: el descubrimiento directo no puede arrancar")
            running = false
            return
        }

        val interfaceName = NetworkDiagnostics.interfaceNameOf(appContext, network)
        val nif = runCatching { interfaceName?.let { NetworkInterface.getByName(it) } }.getOrNull()

        // Escuchar en el 5353 permite recibir las respuestas multicast, que es lo normal. Si el
        // puerto ya está ocupado y no se puede compartir, se cae a un puerto efímero y se pide
        // respuesta unicast (bit QU).
        var unicastResponse = false
        val sock = try {
            openSocket(port = MdnsPacket.PORT)
        } catch (e: Exception) {
            logw("[$serviceType/socket] puerto ${MdnsPacket.PORT} no disponible, se usa efímero", e)
            unicastResponse = true
            try {
                openSocket(port = 0)
            } catch (e2: Exception) {
                logw("[$serviceType/socket] no se pudo abrir el socket", e2)
                listener.onLog("Descubrimiento directo no disponible: ${e2.message}")
                running = false
                return
            }
        }
        socket = sock

        val group = InetAddress.getByName(MdnsPacket.MULTICAST_ADDRESS)
        try {
            network.bindSocket(sock)
            sock.timeToLive = 255
            sock.soTimeout = RECEIVE_TIMEOUT_MILLIS
            if (nif != null) {
                sock.networkInterface = nif
                runCatching { sock.joinGroup(InetSocketAddress(group, MdnsPacket.PORT), nif) }
                    .onFailure { logw("[$serviceType/socket] joinGroup falló", it) }
            }
        } catch (e: Exception) {
            logw("[$serviceType/socket] no se pudo atar el socket a la red Wi-Fi", e)
            listener.onLog("Descubrimiento directo no disponible: ${e.message}")
            sock.close()
            running = false
            return
        }

        logd("[$serviceType/socket] escuchando en ${sock.localPort} vía ${interfaceName ?: "?"} " +
            "(unicast=$unicastResponse)")
        listener.onLog("Descubrimiento directo activo en ${interfaceName ?: "wifi"} ($serviceType)")

        val query = MdnsPacket.query(queryName, unicastResponse)
        val buffer = ByteArray(BUFFER_SIZE)
        var lastQueryAt = 0L

        while (running && !Thread.currentThread().isInterrupted) {
            val now = System.currentTimeMillis()
            if (now - lastQueryAt >= QUERY_INTERVAL_MILLIS) {
                lastQueryAt = now
                try {
                    sock.send(DatagramPacket(query, query.size, group, MdnsPacket.PORT))
                    logd("[$serviceType/socket] consulta enviada")
                } catch (e: Exception) {
                    if (running) logw("[$serviceType/socket] no se pudo enviar la consulta", e)
                }
            }

            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running) logw("[$serviceType/socket] error al recibir", e)
                break
            }
            handle(packet)
        }

        runCatching { if (nif != null) sock.leaveGroup(InetSocketAddress(group, MdnsPacket.PORT), nif) }
        sock.close()
        logd("[$serviceType/socket] bucle terminado")
    }

    private fun openSocket(port: Int): MulticastSocket {
        val sock = MulticastSocket(null as SocketAddress?)
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(port))
        return sock
    }

    private fun handle(packet: DatagramPacket) {
        val response = MdnsPacket.parse(packet.data, packet.length) ?: return

        // Solo interesan las instancias de nuestro tipo de servicio.
        val ours = response.services.values.filter { it.instance.endsWith(queryName) }
        if (ours.isEmpty() && response.pointers.keys.none { it.endsWith(queryName) }) return

        logd(
            "[$serviceType/socket] respuesta de ${packet.address.hostAddress}: " +
                "ptr=${response.pointers.keys} srv=${ours.map { "${it.instance}->${it.target}:${it.port}" }} " +
                "a=${response.addresses}",
        )

        // Un TTL de 0 es un "adiós": la instancia deja de anunciarse.
        response.pointers.forEach { (instance, ttl) ->
            if (ttl == 0L && instance.endsWith(queryName)) {
                val name = instanceLabel(instance)
                published.remove(name)
                listener.onLost(serviceType, name)
            }
        }

        ours.forEach { srv ->
            val name = instanceLabel(srv.instance)
            if (srv.ttlSeconds == 0L) {
                published.remove(name)
                listener.onLost(serviceType, name)
                return@forEach
            }
            // El A del host suele venir en el mismo paquete; si no, la dirección de origen del
            // datagrama es la del propio anunciante.
            val host = response.addresses[srv.target]?.firstOrNull()
                ?: packet.address?.hostAddress
                ?: return@forEach
            if (srv.port <= 0) return@forEach

            val endpoint = "$host:${srv.port}"
            if (published[name] == endpoint) return@forEach
            published[name] = endpoint
            logd("[$serviceType/socket] endpoint '$name' -> $endpoint")
            listener.onEndpoint(serviceType, name, host, srv.port)
        }
    }

    /** "adb-1234._adb-tls-connect._tcp.local" -> "adb-1234" */
    private fun instanceLabel(fullName: String): String = fullName.substringBefore('.')

    private companion object {
        const val BUFFER_SIZE = 8192
        const val RECEIVE_TIMEOUT_MILLIS = 1000
        const val QUERY_INTERVAL_MILLIS = 5000L
    }
}
