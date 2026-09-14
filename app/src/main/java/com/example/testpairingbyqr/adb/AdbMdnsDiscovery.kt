package com.example.testpairingbyqr.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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

    interface Listener {
        fun onEndpoint(serviceType: String, serviceName: String, host: String, port: Int)
        fun onLost(serviceType: String, serviceName: String)
        fun onLog(message: String)
    }

    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(NsdManager::class.java)

    private val executor: Executor = Executors.newSingleThreadExecutor()

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
                listener.onLog("No se pudo iniciar el descubrimiento de $type (código $errorCode)")
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                listener.onLog("No se pudo detener el descubrimiento de $type (código $errorCode)")
            }

            override fun onDiscoveryStarted(type: String) {
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
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: IllegalArgumentException) {
            started = false
            discoveryListener = null
            listener.onLog("Error al registrar el descubrimiento de $serviceType: ${e.message}")
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
}
