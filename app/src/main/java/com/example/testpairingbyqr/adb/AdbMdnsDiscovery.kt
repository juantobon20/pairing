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
 * vez, por lo que las peticiones se encolan.
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
        context.applicationContext.getSystemService(NsdManager::class.java)

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
        null
    }

    /** Reintentos tras un onStartDiscoveryFailed (el sistema puede no estar listo todavía). */
    private var retries = 0

    private val pendingResolves = mutableListOf<NsdServiceInfo>()
    private var resolveInFlight = false

    /** Valores de tipo NsdManager.ServiceInfoCallback (API 34+); Any evita referenciar la API en el campo. */
    private val trackedCallbacks = HashMap<String, Any>()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true

        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                started = false
                discoveryListener = null
                releaseMulticastLock()
                listener.onLog(
                    "No se pudo iniciar el descubrimiento de $type: ${errorName(errorCode)}",
                )
                scheduleRetry()
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                listener.onLog("No se pudo detener el descubrimiento de $type (código $errorCode)")
            }

            override fun onDiscoveryStarted(type: String) {
                retries = 0
                listener.onLog("Escuchando $type")
            }

            override fun onDiscoveryStopped(type: String) {
                listener.onLog("Descubrimiento detenido: $type")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                listener.onLog("Detectado: ${serviceInfo.serviceName} ($serviceType)")
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                listener.onLog("Desaparecido: ${serviceInfo.serviceName} ($serviceType)")
                untrack(serviceInfo.serviceName)
                listener.onLost(serviceType, serviceInfo.serviceName)
            }
        }

        discoveryListener = l
        acquireMulticastLock()
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: IllegalArgumentException) {
            started = false
            discoveryListener = null
            releaseMulticastLock()
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
            listener.onLog("Descubrimiento de $serviceType abandonado tras $MAX_RETRIES intentos")
            return
        }
        val delaySeconds = RETRY_DELAYS_SECONDS[retries.coerceAtMost(RETRY_DELAYS_SECONDS.lastIndex)]
        retries++
        listener.onLog("Reintentando $serviceType en ${delaySeconds}s (intento $retries/$MAX_RETRIES)")
        scheduler.schedule({ if (!started) start() }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: return
        try {
            if (!lock.isHeld) lock.acquire()
        } catch (e: Exception) {
            listener.onLog("No se pudo tomar el MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        try {
            while (lock.isHeld) lock.release()
        } catch (e: Exception) {
            // El lock ya estaba liberado.
        }
    }

    fun stop() {
        val l = discoveryListener
        discoveryListener = null
        started = false
        if (l != null) {
            try {
                nsdManager.stopServiceDiscovery(l)
            } catch (e: IllegalArgumentException) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            trackModern(serviceInfo)
        } else {
            enqueueLegacyResolve(serviceInfo)
        }
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
            if (trackedCallbacks.containsKey(name)) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    synchronized(trackedCallbacks) { trackedCallbacks.remove(name) }
                    listener.onLog("No se pudo resolver $name (código $errorCode)")
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    publish(serviceInfo)
                }

                override fun onServiceLost() {
                    listener.onLost(serviceType, name)
                }

                override fun onServiceInfoCallbackUnregistered() {
                    synchronized(trackedCallbacks) { trackedCallbacks.remove(name) }
                }
            }
            trackedCallbacks[name] = callback
            nsdManager.registerServiceInfoCallback(serviceInfo, executor, callback)
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
        pumpLegacyResolves()
    }

    private fun pumpLegacyResolves() {
        val next: NsdServiceInfo
        synchronized(pendingResolves) {
            if (resolveInFlight || pendingResolves.isEmpty()) return
            next = pendingResolves.removeAt(0)
            resolveInFlight = true
        }

        @Suppress("DEPRECATION")
        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                listener.onLog("No se pudo resolver ${serviceInfo.serviceName} (código $errorCode)")
                finishLegacyResolve()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
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
        val host = hostsOf(serviceInfo).firstOrNull()
        if (host == null || port <= 0) {
            listener.onLog("Resuelto ${serviceInfo.serviceName} sin dirección utilizable")
            return
        }
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
