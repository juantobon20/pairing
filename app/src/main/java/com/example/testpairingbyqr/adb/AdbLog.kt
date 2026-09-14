package com.example.testpairingbyqr.adb

import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/** Un único tag para poder filtrar todo el flujo: `adb logcat -s AdbMdns`. */
const val TAG = "AdbMdns"

fun logd(message: String) {
    Log.d(TAG, message)
}

fun logw(message: String, t: Throwable? = null) {
    if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message)
}

/** Volcado completo de lo que entrega NSD, que es donde suelen estar las sorpresas. */
fun describe(info: NsdServiceInfo): String {
    val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        info.hostAddresses.map { it.hostAddress ?: it.toString() }
    } else {
        @Suppress("DEPRECATION")
        listOfNotNull(info.host?.hostAddress)
    }
    val attributes = runCatching {
        info.attributes.entries.joinToString(",") { (k, v) ->
            "$k=${v?.let { String(it, Charsets.UTF_8) } ?: ""}"
        }
    }.getOrDefault("?")
    val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${info.network}" else "n/a"
    return "name='${info.serviceName}' type='${info.serviceType}' port=${info.port} " +
        "addrs=$addresses net=$network attrs=[$attributes]"
}
