package com.example.testpairingbyqr.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Acceso a la red local (mDNS / LNP).
 *
 * No se puede decidir por `Build.VERSION.SDK_INT` si hace falta `ACCESS_LOCAL_NETWORK`: hay ROMs
 * endurecidas (GrapheneOS, por ejemplo) que aplican la restricción de red local antes que AOSP o
 * con un nivel de API distinto al de la versión "oficial". Por eso se pregunta al propio sistema
 * si conoce el permiso y si nos lo ha concedido, en vez de comparar contra 37.
 */
object LocalNetworkAccess {

    /** `Manifest.permission.ACCESS_LOCAL_NETWORK`, por nombre para no atar el código a un SDK. */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** true si esta build de Android conoce el permiso (aunque SDK_INT sea menor que 37). */
    fun isKnownToPlatform(context: Context): Boolean = try {
        context.packageManager.getPermissionInfo(PERMISSION, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Hay que pedirlo si el sistema lo conoce y todavía no lo tenemos. */
    fun needsRequest(context: Context): Boolean =
        isKnownToPlatform(context) && !isGranted(context)

    /**
     * En GrapheneOS el permiso de red ("Network") es revocable por el usuario y, si está
     * denegado, `NsdManager` falla con FAILURE_INTERNAL_ERROR (código 0) en vez de dar un error
     * de red normal.
     */
    fun hasInternetPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.INTERNET) ==
            PackageManager.PERMISSION_GRANTED

    /** Línea de diagnóstico para el registro: permite ver en el propio dispositivo qué falla. */
    fun diagnostics(context: Context): String {
        val known = isKnownToPlatform(context)
        val localNetwork = when {
            !known -> "no existe en esta build"
            isGranted(context) -> "concedido"
            else -> "DENEGADO"
        }
        val internet = if (hasInternetPermission(context)) "concedido" else "DENEGADO"
        return "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}) · ${Build.MODEL} · " +
            "${Build.FINGERPRINT} · ACCESS_LOCAL_NETWORK: $localNetwork · INTERNET: $internet"
    }
}
