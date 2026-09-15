package com.example.testpairingbyqr.adb

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AdbEndpoint(
    val serviceType: String,
    val serviceName: String,
    val host: String,
    val port: Int,
    val active: Boolean,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
) {
    val key: String get() = "$serviceType/$serviceName"
    val address: String get() = "$host:$port"
    val isPairing: Boolean get() = serviceType == SERVICE_TYPE_PAIRING

    /** Comando listo para pegar en el PC. */
    val command: String
        get() = if (isPairing) "adb pair $address" else "adb connect $address"
}

data class DiscoveryState(
    val running: Boolean = false,
    val endpoints: List<AdbEndpoint> = emptyList(),
    val logs: List<String> = emptyList(),
    /** null mientras no se haya podido leer el ajuste. */
    val wirelessDebugging: Boolean? = null,
    /** Una VPN al frente bloquea el descubrimiento mDNS a nivel de sistema. */
    val vpnActive: Boolean = false,
) {
    val pairingEndpoints: List<AdbEndpoint> get() = endpoints.filter { it.isPairing }
    val connectEndpoints: List<AdbEndpoint> get() = endpoints.filterNot { it.isPairing }
}

/**
 * Estado compartido entre el servicio en primer plano (que hace el descubrimiento incluso
 * mientras Ajustes está encima) y la UI.
 */
object AdbDiscoveryRepository {

    private const val MAX_LOGS = 200
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    @Synchronized
    fun setRunning(running: Boolean) {
        val current = _state.value
        _state.value = if (running) {
            current.copy(running = true)
        } else {
            // Al parar, nada sigue anunciándose: todo lo visto queda como histórico.
            current.copy(running = false, endpoints = current.endpoints.map { it.copy(active = false) })
        }
    }

    @Synchronized
    fun onEndpointResolved(serviceType: String, serviceName: String, host: String, port: Int) {
        val now = System.currentTimeMillis()
        val key = "$serviceType/$serviceName"
        val current = _state.value.endpoints
        val existing = current.firstOrNull { it.key == key }
        val updated = AdbEndpoint(
            serviceType = serviceType,
            serviceName = serviceName,
            host = host,
            port = port,
            active = true,
            firstSeenAtMillis = existing?.firstSeenAtMillis ?: now,
            lastSeenAtMillis = now,
        )
        val endpoints = current.filterNot { it.key == key } + updated
        _state.value = _state.value.copy(endpoints = endpoints.sortedByDescending { it.lastSeenAtMillis })
        if (existing == null || existing.address != updated.address) {
            log("${if (updated.isPairing) "Emparejamiento" else "Conexión"} en ${updated.address} ($serviceName)")
        }
    }

    @Synchronized
    fun onEndpointLost(serviceType: String, serviceName: String) {
        val key = "$serviceType/$serviceName"
        val endpoints = _state.value.endpoints.map {
            if (it.key == key) it.copy(active = false) else it
        }
        _state.value = _state.value.copy(endpoints = endpoints)
    }

    @Synchronized
    fun setWirelessDebugging(enabled: Boolean?) {
        if (_state.value.wirelessDebugging == enabled) return
        _state.value = _state.value.copy(wirelessDebugging = enabled)
        log(
            when (enabled) {
                true -> "Depuración inalámbrica activada: adbd ya puede anunciarse"
                false -> "Depuración inalámbrica desactivada: adbd no anuncia nada"
                null -> "Estado de la depuración inalámbrica no legible"
            },
        )
    }

    @Synchronized
    fun setVpnActive(active: Boolean) {
        if (_state.value.vpnActive == active) return
        _state.value = _state.value.copy(vpnActive = active)
        log(
            if (active) {
                "VPN conectada: el sistema bloqueará el descubrimiento mDNS"
            } else {
                "VPN desconectada: el descubrimiento mDNS vuelve a estar disponible"
            },
        )
    }

    @Synchronized
    fun clearEndpoints() {
        _state.value = _state.value.copy(endpoints = emptyList())
        log("Resultados borrados")
    }

    @Synchronized
    fun log(message: String) {
        // Todo lo que se ve en la UI va también a logcat, para poder pedir un `adb logcat -s AdbMdns`.
        Log.i(TAG, message)
        val line = "${timeFormat.format(Date())}  $message"
        val logs = (_state.value.logs + line).takeLast(MAX_LOGS)
        _state.value = _state.value.copy(logs = logs)
    }
}
