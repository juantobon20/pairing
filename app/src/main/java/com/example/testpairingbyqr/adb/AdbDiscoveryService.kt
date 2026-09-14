package com.example.testpairingbyqr.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.testpairingbyqr.MainActivity
import com.example.testpairingbyqr.R

/**
 * Mantiene el descubrimiento mDNS vivo mientras el usuario está en Ajustes escaneando el QR,
 * y refleja en la notificación la IP/puerto de emparejamiento y de conexión en cuanto aparecen.
 */
class AdbDiscoveryService : Service(), AdbMdnsDiscovery.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var discoveries: List<AdbMdnsDiscovery> = emptyList()

    /**
     * El usuario enciende la depuración inalámbrica desde Ajustes, fuera de esta app. Observar
     * el ajuste evita el caso en que todo funciona pero no aparece nada porque adbd no anuncia.
     */
    private val wirelessDebuggingObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            AdbDiscoveryRepository.setWirelessDebugging(
                NetworkDiagnostics.isWirelessDebuggingEnabled(this@AdbDiscoveryService),
            )
        }
    }
    @Volatile
    private var lastNotificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        logd("Service.onCreate")
        createNotificationChannel()
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(NetworkDiagnostics.SETTING_ADB_WIFI_ENABLED),
                false,
                wirelessDebuggingObserver,
            )
        }.onFailure { logw("No se pudo observar adb_wifi_enabled", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logd("Service.onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        if (intent?.action == ACTION_STOP) {
            logd("Service: parada solicitada")
            stopSelf()
            return START_NOT_STICKY
        }

        // El tipo de servicio en primer plano solo es obligatorio (y "specialUse" solo existe)
        // a partir de API 34.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(DEFAULT_TEXT),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(DEFAULT_TEXT))
        }

        AdbDiscoveryRepository.log(LocalNetworkAccess.diagnostics(this))
        AdbDiscoveryRepository.log(NetworkDiagnostics.describe(this))
        AdbDiscoveryRepository.setWirelessDebugging(NetworkDiagnostics.isWirelessDebuggingEnabled(this))
        if (discoveries.isEmpty()) {
            discoveries = listOf(
                AdbMdnsDiscovery(this, SERVICE_TYPE_PAIRING, this),
                AdbMdnsDiscovery(this, SERVICE_TYPE_CONNECT, this),
            )
        } else {
            logd("Service: reusando los descubrimientos existentes")
        }
        // start() es idempotente: relanza solo los que no estén escuchando (p. ej. tras agotar
        // los reintentos), así el botón "Iniciar" siempre sirve para volver a intentarlo.
        discoveries.forEach { it.start() }
        AdbDiscoveryRepository.setRunning(true)
        return START_STICKY
    }

    override fun onDestroy() {
        logd("Service.onDestroy")
        runCatching { contentResolver.unregisterContentObserver(wirelessDebuggingObserver) }
        discoveries.forEach { it.stop() }
        discoveries = emptyList()
        AdbDiscoveryRepository.setRunning(false)
        AdbDiscoveryRepository.log("Descubrimiento detenido")
        super.onDestroy()
    }

    // ------------------------------------------------- AdbMdnsDiscovery.Listener

    override fun onEndpoint(serviceType: String, serviceName: String, host: String, port: Int) {
        AdbDiscoveryRepository.onEndpointResolved(serviceType, serviceName, host, port)
        refreshNotification()
    }

    override fun onLost(serviceType: String, serviceName: String) {
        AdbDiscoveryRepository.onEndpointLost(serviceType, serviceName)
        refreshNotification()
    }

    override fun onLog(message: String) {
        AdbDiscoveryRepository.log(message)
    }

    // -------------------------------------------------------------- notificación

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Descubrimiento adb (mDNS)",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Muestra la IP y el puerto anunciados por adbd"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AdbDiscoveryService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_adb)
            .setContentTitle("Buscando servicios adb")
            .setContentText(text.replace('\n', ' '))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .addAction(0, "Detener", stop)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Se llama desde los callbacks de NSD (hilo cualquiera). */
    private fun refreshNotification() {
        val endpoints = AdbDiscoveryRepository.state.value.endpoints
        val pairing = endpoints.firstOrNull { it.active && it.isPairing }
        val connect = endpoints.firstOrNull { it.active && !it.isPairing }
        val text = "Emparejamiento: ${pairing?.address ?: "—"}\nConexión: ${connect?.address ?: "—"}"
        if (text == lastNotificationText) return
        lastNotificationText = text
        handler.post {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    companion object {
        private const val CHANNEL_ID = "adb_discovery"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_TEXT = "Emparejamiento: —\nConexión: —"
        const val ACTION_STOP = "com.example.testpairingbyqr.action.STOP_DISCOVERY"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AdbDiscoveryService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AdbDiscoveryService::class.java))
        }
    }
}
