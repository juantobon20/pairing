package com.example.testpairingbyqr.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Servicio mDNS que anuncia adbd mientras la pantalla de emparejamiento está abierta. */
const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp"

/** Servicio mDNS que anuncia adbd cuando la depuración inalámbrica está activa. */
const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp"

/**
 * Envoltorio sobre [NsdManager] que descubre un tipo de servicio concreto y resuelve
 * cada instancia encontrada a IP + puerto.
 *
 * En API 34+ usa `registerServiceInfoCallback` (permite varias resoluciones en paralelo y
 * notifica cambios). En API 30..33 usa `resolveService`, que solo admite una resolución a la
 * vez, por lo que las peticiones se encolan. Si la vía moderna falla, se cae a la antigua.
 *
 * Todo el flujo se traza con el tag [TAG]: `adb logcat -s AdbMdns`.
 */
class AdbMdnsDiscovery(
    context: Context,
    private val serviceType: String,
    private val listener: Listener,
) {

    private val appContext: Context = context.applicationContext

    interface Listener {
        fun onEndpoint(serviceType: String, serviceName: String, host: String, port: Int)
        fun onLost(serviceType: String, serviceName: String)
        fun onLog(message: String)
    }

    private val nsdManager: NsdManager =
        appContext.getSystemService(NsdManager::class.java)

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val executor: Executor = scheduler

    /**
     * Algunas ROMs/chipsets filtran el tráfico multicast del Wi-Fi si nadie sostiene un
     * MulticastLock, y entonces las respuestas mDNS nunca llegan a la app.
     */
    private val multicastLock: WifiManager.MulticastLock? = try {
        appContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("adb-mdns-$serviceType")
            ?.apply { setReferenceCounted(true) }
    } catch (e: SecurityException) {
        logw("[$serviceType] sin MulticastLock: ${e.message}", e)
        null
    }

    /** Plan B cuando NsdManager no puede con la VPN; se crea solo si hace falta. */
    private var socketDiscovery: MdnsSocketDiscovery? = null

    /** Reintentos tras un onStartDiscoveryFailed (el sistema puede no estar listo todavía). */
    private var retries = 0

    private val pendingResolves = mutableListOf<NsdServiceInfo>()
    private var resolveInFlight = false

    /** Valores de tipo NsdManager.ServiceInfoCallback (API 34+); Any evita referenciar la API en el campo. */
    private val trackedCallbacks = HashMap<String, Any>()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var started = false

    /** @param isRetry true solo cuando lo llama [scheduleRetry]; un arranque manual borra la cuenta. */
    fun start(isRetry: Boolean = false) {
        if (!isRetry) retries = 0
        if (started) {
            logd("[$serviceType] start() ignorado: ya estaba arrancado")
            return
        }
        started = true
        logd("[$serviceType] start() · multicastLock=${multicastLock != null} · api=${Build.VERSION.SDK_INT}")

        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                started = false
                discoveryListener = null
                logw("[$type] onStartDiscoveryFailed ${errorName(errorCode)}")
                listener.onLog("No se pudo iniciar el descubrimiento de $type: ${errorName(errorCode)}")
                // Antes de soltar el lock: el fallback lo necesita para recibir multicast.
                startSocketFallback()
                releaseMulticastLock()
                scheduleRetry()
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                logw("[$type] onStopDiscoveryFailed ${errorName(errorCode)}")
                listener.onLog("No se pudo detener el descubrimiento de $type (${errorName(errorCode)})")
            }

            override fun onDiscoveryStarted(type: String) {
                retries = 0
                logd("[$type] onDiscoveryStarted")
                listener.onLog("Escuchando $type")
            }

            override fun onDiscoveryStopped(type: String) {
                logd("[$type] onDiscoveryStopped")
                listener.onLog("Descubrimiento detenido: $type")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                logd("[$serviceType] onServiceFound ${describe(serviceInfo)}")
                listener.onLog("Detectado: ${serviceInfo.serviceName} ($serviceType)")
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                logd("[$serviceType] onServiceLost ${describe(serviceInfo)}")
                listener.onLog("Desaparecido: ${serviceInfo.serviceName} ($serviceType)")
                untrack(serviceInfo.serviceName)
                listener.onLost(serviceType, serviceInfo.serviceName)
            }
        }

        discoveryListener = l
        acquireMulticastLock()
        try {
            // Se pasa la red Wi-Fi concreta, no un NetworkRequest: el overload con
            // NetworkRequest reporta onDiscoveryStarted sin haber arrancado nada y se traga el
            // fallo real de cada red (solo lo escribe en logcat), así que no habría forma de
            // enterarse de que no funciona. El overload con Network existe desde API 33.
            val wifi = NetworkDiagnostics.wifiNetwork(appContext)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && wifi != null) {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, wifi, executor, l)
                logd("[$serviceType] discoverServices(red=$wifi) enviado, esperando callback")
            } else {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l)
                logd("[$serviceType] discoverServices(red por defecto) enviado, esperando callback")
            }
        } catch (e: IllegalArgumentException) {
            started = false
            discoveryListener = null
            releaseMulticastLock()
            logw("[$serviceType] discoverServices() lanzó excepción", e)
            listener.onLog("Error al registrar el descubrimiento de $serviceType: ${e.message}")
        }
    }

    /**
     * FAILURE_INTERNAL_ERROR llega, entre otros motivos, cuando el sistema deniega el acceso a la
     * red local o el demonio mDNS aún no está arriba. Reintentar con espera creciente cubre el
     * segundo caso; el primero se ve en el texto del log.
     */
    private fun scheduleRetry() {
        if (retries >= MAX_RETRIES) {
            logw("[$serviceType] sin más reintentos")
            listener.onLog(
                "Descubrimiento de $serviceType abandonado tras $MAX_RETRIES intentos. " +
                    "Si hay una VPN activa, desconéctala y pulsa Iniciar.",
            )
            return
        }
        val delaySeconds = RETRY_DELAYS_SECONDS[retries.coerceAtMost(RETRY_DELAYS_SECONDS.lastIndex)]
        retries++
        listener.onLog("Reintentando $serviceType en ${delaySeconds}s (intento $retries/$MAX_RETRIES)")
        scheduler.schedule({ if (!started) start(isRetry = true) }, delaySeconds, TimeUnit.SECONDS)
    }

    /**
     * NsdManager no funciona con una VPN levantada (falla incluso pidiéndole la red Wi-Fi), así
     * que en cuanto se niega se levanta el descubrimiento por socket propio. Los dos pueden
     * convivir: el repositorio deduplica por nombre de instancia.
     */
    private fun startSocketFallback() {
        if (socketDiscovery != null) return
        logd("[$serviceType] arrancando descubrimiento directo por socket")
        socketDiscovery = MdnsSocketDiscovery(appContext, serviceType, listener).also { it.start() }
        acquireMulticastLock()
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: return
        try {
            if (!lock.isHeld) {
                lock.acquire()
                logd("[$serviceType] MulticastLock tomado")
            }
        } catch (e: Exception) {
            logw("[$serviceType] no se pudo tomar el MulticastLock", e)
            listener.onLog("No se pudo tomar el MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        // Mientras el descubrimiento por socket esté vivo, el lock sigue haciendo falta.
        if (socketDiscovery != null) return
        val lock = multicastLock ?: return
        try {
            while (lock.isHeld) lock.release()
        } catch (e: Exception) {
            // El lock ya estaba liberado.
        }
    }

    fun stop() {
        logd("[$serviceType] stop()")
        socketDiscovery?.stop()
        socketDiscovery = null
        val l = discoveryListener
        discoveryListener = null
        started = false
        if (l != null) {
            try {
                nsdManager.stopServiceDiscovery(l)
            } catch (e: IllegalArgumentException) {
                logw("[$serviceType] stopServiceDiscovery: ya estaba detenido", e)
                listener.onLog("Descubrimiento de $serviceType ya estaba detenido")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            clearModernCallbacks()
        }
        synchronized(pendingResolves) {
            pendingResolves.clear()
            resolveInFlight = false
        }
        releaseMulticastLock()
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        val normalized = normalize(serviceInfo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            trackModern(normalized)
        } else {
            enqueueLegacyResolve(normalized)
        }
    }

    /**
     * NSD devuelve a veces el tipo con punto final (`_adb-tls-pairing._tcp.`) o con el dominio
     * pegado, y algunas builds rechazan esa misma instancia al resolverla. Se reescribe al tipo
     * que pedimos nosotros, que siempre es válido.
     */
    private fun normalize(serviceInfo: NsdServiceInfo): NsdServiceInfo {
        val reported = serviceInfo.serviceType
        if (reported != null && reported.trimEnd('.') != serviceType) {
            logd("[$serviceType] tipo reportado '$reported' != solicitado; se reescribe")
            runCatching { serviceInfo.serviceType = serviceType }
                .onFailure { logw("[$serviceType] no se pudo reescribir el tipo", it) }
        }
        return serviceInfo
    }

    private fun untrack(serviceName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            unregisterModern(serviceName)
        }
    }

    // ---------------------------------------------------------------- API 34+

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun trackModern(serviceInfo: NsdServiceInfo) {
        val name = serviceInfo.serviceName
        synchronized(trackedCallbacks) {
            if (trackedCallbacks.containsKey(name)) {
                logd("[$serviceType] '$name' ya tenía callback registrado")
                return
            }
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    synchronized(trackedCallbacks) { trackedCallbacks.remove(name) }
                    logw("[$serviceType] registro de '$name' falló: ${errorName(errorCode)}; se prueba la vía antigua")
                    listener.onLog("No se pudo resolver $name (${errorName(errorCode)}), reintento con resolveService")
                    enqueueLegacyResolve(serviceInfo)
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    logd("[$serviceType] onServiceUpdated ${describe(serviceInfo)}")
                    publish(serviceInfo)
                }

                override fun onServiceLost() {
                    logd("[$serviceType] onServiceLost (callback) '$name'")
                    listener.onLost(serviceType, name)
                }

                override fun onServiceInfoCallbackUnregistered() {
                    logd("[$serviceType] callback de '$name' dado de baja")
                    synchronized(trackedCallbacks) { trackedCallbacks.remove(name) }
                }
            }
            trackedCallbacks[name] = callback
            try {
                nsdManager.registerServiceInfoCallback(serviceInfo, executor, callback)
                logd("[$serviceType] registerServiceInfoCallback('$name') enviado")
            } catch (e: Exception) {
                trackedCallbacks.remove(name)
                logw("[$serviceType] registerServiceInfoCallback('$name') lanzó excepción; vía antigua", e)
                listener.onLog("Resolución moderna no disponible para $name: ${e.message}")
                enqueueLegacyResolve(serviceInfo)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterModern(serviceName: String) {
        val callback = synchronized(trackedCallbacks) { trackedCallbacks.remove(serviceName) } ?: return
        try {
            nsdManager.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback)
        } catch (e: IllegalArgumentException) {
            // Ya estaba dado de baja.
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearModernCallbacks() {
        val callbacks = synchronized(trackedCallbacks) {
            val copy = trackedCallbacks.values.toList()
            trackedCallbacks.clear()
            copy
        }
        callbacks.forEach { callback ->
            try {
                nsdManager.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback)
            } catch (e: IllegalArgumentException) {
                // Ya estaba dado de baja.
            }
        }
    }

    // ------------------------------------------------------------- API 30..33

    private fun enqueueLegacyResolve(serviceInfo: NsdServiceInfo) {
        synchronized(pendingResolves) { pendingResolves.add(serviceInfo) }
        logd("[$serviceType] encolado resolveService de '${serviceInfo.serviceName}'")
        pumpLegacyResolves()
    }

    private fun pumpLegacyResolves() {
        val next: NsdServiceInfo
        synchronized(pendingResolves) {
            if (resolveInFlight || pendingResolves.isEmpty()) return
            next = pendingResolves.removeAt(0)
            resolveInFlight = true
        }

        logd("[$serviceType] resolveService('${next.serviceName}')")
        @Suppress("DEPRECATION")
        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                logw("[$serviceType] onResolveFailed '${serviceInfo.serviceName}': ${errorName(errorCode)}")
                listener.onLog("No se pudo resolver ${serviceInfo.serviceName} (${errorName(errorCode)})")
                finishLegacyResolve()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                logd("[$serviceType] onServiceResolved ${describe(serviceInfo)}")
                publish(serviceInfo)
                finishLegacyResolve()
            }
        })
    }

    private fun finishLegacyResolve() {
        synchronized(pendingResolves) { resolveInFlight = false }
        pumpLegacyResolves()
    }

    // ------------------------------------------------------------------ común

    private fun publish(serviceInfo: NsdServiceInfo) {
        val port = serviceInfo.port
        val hosts = hostsOf(serviceInfo)
        val host = hosts.firstOrNull()
        if (host == null || port <= 0) {
            // Con registerServiceInfoCallback es normal recibir una primera actualización sin
            // direcciones todavía; la siguiente suele traerlas.
            logw("[$serviceType] descartado '${serviceInfo.serviceName}': hosts=$hosts port=$port")
            listener.onLog(
                "Resuelto ${serviceInfo.serviceName} sin dirección utilizable " +
                    "(hosts=$hosts, puerto=$port)",
            )
            return
        }
        logd("[$serviceType] endpoint '${serviceInfo.serviceName}' -> $host:$port (candidatos=$hosts)")
        listener.onEndpoint(serviceType, serviceInfo.serviceName, host, port)
    }

    /** Direcciones del servicio, con IPv4 primero (es la que espera `adb pair`/`adb connect`). */
    private fun hostsOf(serviceInfo: NsdServiceInfo): List<String> {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(serviceInfo.host)
        }
        return addresses
            .mapNotNull { it.hostAddress }
            .sortedBy { if (it.contains(':')) 1 else 0 }
    }

    companion object {
        private const val MAX_RETRIES = 5
        private val RETRY_DELAYS_SECONDS = longArrayOf(2, 5, 10, 20, 30)

        /** Nombres de los códigos de NsdManager, que el sistema solo entrega como enteros. */
        fun errorName(errorCode: Int): String = when (errorCode) {
            NsdManager.FAILURE_INTERNAL_ERROR ->
                "FAILURE_INTERNAL_ERROR (0) — normalmente acceso a la red local denegado " +
                    "o servicio mDNS del sistema no disponible"
            NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE (3)"
            NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT (4)"
            6 -> "FAILURE_BAD_PARAMETERS (6)"
            7 -> "FAILURE_OPERATION_NOT_RUNNING (7)"
            else -> "código $errorCode"
        }
    }
}
