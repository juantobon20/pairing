package com.example.testpairingbyqr.adb

import java.net.Inet4Address
import java.net.NetworkInterface

/** IPs locales no-loopback, para contrastarlas con lo que anuncia mDNS. */
fun localIpAddresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces()
        .toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif ->
            nif.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .map { "${nif.name}: $it" }
        }
} catch (e: Exception) {
    emptyList()
}
