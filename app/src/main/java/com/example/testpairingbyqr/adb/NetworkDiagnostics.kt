package com.example.testpairingbyqr.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * Estado de red y de la depuración inalámbrica. Sirve para descartar las causas "de entorno"
 * antes de culpar a NSD: sin Wi-Fi no hay mDNS, y sin depuración inalámbrica activa adbd no
 * anuncia `_adb-tls-connect._tcp` (el de emparejamiento solo existe mientras el diálogo del QR
 * está abierto).
 */
object NetworkDiagnostics {

    fun describe(context: Context): String {
        val app = context.applicationContext
        return buildString {
            append("Red: ").append(transports(app))
            append(" · Wi-Fi ").append(wifiState(app))
            append(" · Depuración inalámbrica: ").append(wirelessDebuggingState(app))
            append(" · IPs ").append(localIpAddresses())
            if (hasVpn(app)) {
                append(" · VPN activa: el descubrimiento se acota al transporte Wi-Fi")
            }
        }
    }

    /** Una VPN al frente convierte el túnel en la red por defecto de la app, y mDNS no pasa por él. */
    fun hasVpn(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun transports(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "sin ConnectivityManager"
        val network = cm.activeNetwork ?: return "sin red activa"
        val caps = cm.getNetworkCapabilities(network) ?: return "sin capacidades"
        val names = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELULAR")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "${names.ifEmpty { listOf("?") }.joinToString("+")}${if (validated) "" else " (no validada)"}"
    }

    private fun wifiState(context: Context): String = try {
        val wifi = context.getSystemService(WifiManager::class.java)
        when {
            wifi == null -> "sin WifiManager"
            wifi.isWifiEnabled -> "activado"
            else -> "DESACTIVADO"
        }
    } catch (e: Exception) {
        "desconocido (${e.message})"
    }

    /** Clave de Ajustes para el interruptor de depuración inalámbrica. */
    const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /**
     * Si vale 0, adbd no anuncia `_adb-tls-connect._tcp` y no hay absolutamente nada que
     * descubrir, por muy bien que funcione NSD.
     *
     * @return null si el valor no se puede leer.
     */
    fun isWirelessDebuggingEnabled(context: Context): Boolean? = try {
        when (Settings.Global.getInt(context.contentResolver, SETTING_ADB_WIFI_ENABLED, -1)) {
            1 -> true
            0 -> false
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun wirelessDebuggingState(context: Context): String =
        when (isWirelessDebuggingEnabled(context)) {
            true -> "activada"
            false -> "DESACTIVADA"
            null -> "no legible"
        }
}
