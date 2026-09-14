package com.example.testpairingbyqr.adb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Abre las pantallas de Ajustes relacionadas con la depuración inalámbrica.
 *
 * No hay API pública para llegar a "Vincular con código QR", así que se prueba una lista de
 * candidatos de más específico a más genérico: componente directo de Ajustes, pantalla de
 * depuración inalámbrica resaltando la preferencia del QR y, por último, Opciones de
 * desarrollo. Cada dispositivo/ROM expone unos u otros.
 */
object WirelessDebuggingLauncher {

    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val WIRELESS_DEBUGGING_ACTIVITY = "com.android.settings.Settings\$WirelessDebuggingActivity"
    private const val ADB_QR_ACTIVITY = "com.android.settings.development.AdbQrCodeActivity"

    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    private const val PREF_WIRELESS_DEBUGGING = "toggle_adb_wireless"
    private const val PREF_PAIR_QR = "adb_pair_method_qrcode_pref"

    /** Devuelve un mensaje describiendo qué se abrió (o por qué no se pudo). */
    fun openWirelessDebugging(context: Context): String = launchFirstAvailable(
        context,
        listOf(
            "Depuración inalámbrica" to settingsComponent(WIRELESS_DEBUGGING_ACTIVITY),
            "Opciones de desarrollo (Depuración inalámbrica resaltada)" to
                developerOptions().highlight(PREF_WIRELESS_DEBUGGING),
            "Opciones de desarrollo" to developerOptions(),
            "Ajustes" to Intent(Settings.ACTION_SETTINGS),
        ),
    )

    fun openQrPairing(context: Context): String = launchFirstAvailable(
        context,
        listOf(
            "Vincular con código QR" to settingsComponent(ADB_QR_ACTIVITY),
            "Depuración inalámbrica (opción de QR resaltada)" to
                settingsComponent(WIRELESS_DEBUGGING_ACTIVITY).highlight(PREF_PAIR_QR),
            "Depuración inalámbrica" to settingsComponent(WIRELESS_DEBUGGING_ACTIVITY),
            "Opciones de desarrollo (Depuración inalámbrica resaltada)" to
                developerOptions().highlight(PREF_WIRELESS_DEBUGGING),
            "Opciones de desarrollo" to developerOptions(),
        ),
    )

    private fun developerOptions() = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    private fun settingsComponent(className: String) = Intent().apply {
        component = ComponentName(SETTINGS_PACKAGE, className)
    }

    /** Hace que Ajustes desplace y resalte la preferencia indicada. */
    private fun Intent.highlight(preferenceKey: String): Intent = apply {
        putExtra(EXTRA_FRAGMENT_ARG_KEY, preferenceKey)
        putExtra(
            EXTRA_SHOW_FRAGMENT_ARGS,
            Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, preferenceKey) },
        )
    }

    private fun launchFirstAvailable(context: Context, candidates: List<Pair<String, Intent>>): String {
        val failures = mutableListOf<String>()
        for ((label, intent) in candidates) {
            try {
                context.startActivity(intent)
                return "Abierto: $label"
            } catch (e: RuntimeException) {
                // ActivityNotFoundException (no existe en esta ROM) o SecurityException
                // (actividad interna de Ajustes no exportada).
                failures += label
            }
        }
        return "No se pudo abrir ninguna pantalla (probado: ${failures.joinToString(", ")})"
    }
}
