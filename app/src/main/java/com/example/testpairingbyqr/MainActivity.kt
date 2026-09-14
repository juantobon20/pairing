package com.example.testpairingbyqr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.testpairingbyqr.adb.AdbDiscoveryRepository
import com.example.testpairingbyqr.adb.AdbDiscoveryService
import com.example.testpairingbyqr.adb.AdbEndpoint
import com.example.testpairingbyqr.adb.LocalNetworkAccess
import com.example.testpairingbyqr.adb.NetworkDiagnostics
import com.example.testpairingbyqr.adb.SERVICE_TYPE_CONNECT
import com.example.testpairingbyqr.adb.SERVICE_TYPE_PAIRING
import com.example.testpairingbyqr.adb.WirelessDebuggingLauncher
import com.example.testpairingbyqr.adb.localIpAddresses
import com.example.testpairingbyqr.ui.theme.TestPairingByQrTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TestPairingByQrTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PairingScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun PairingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by AdbDiscoveryRepository.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val localIps = remember { localIpAddresses() }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            AdbDiscoveryRepository.log("Sin permiso de notificaciones: no se verá el aviso en la barra")
        }
    }

    val localNetworkPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AdbDiscoveryRepository.log(
            if (granted) {
                "Permiso de red local concedido"
            } else {
                "Sin permiso de red local: NsdManager fallará al iniciar el descubrimiento"
            },
        )
        AdbDiscoveryService.start(context)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AdbDiscoveryRepository.log(LocalNetworkAccess.diagnostics(context))
        AdbDiscoveryRepository.log(NetworkDiagnostics.describe(context))
        // Ojo: no se compara contra SDK_INT. Hay ROMs endurecidas (GrapheneOS) que aplican la
        // restricción de red local con un nivel de API distinto al de AOSP, así que se le
        // pregunta al sistema si conoce el permiso en lugar de asumir "API >= 37".
        if (LocalNetworkAccess.needsRequest(context)) {
            AdbDiscoveryRepository.log("Pidiendo ACCESS_LOCAL_NETWORK antes de arrancar")
            localNetworkPermission.launch(LocalNetworkAccess.PERMISSION)
        } else {
            AdbDiscoveryService.start(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Emparejamiento adb por QR",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Abre la depuración inalámbrica y escanea el QR del PC. Mientras esa pantalla " +
                "esté abierta, adbd anuncia por mDNS el servicio de emparejamiento; aquí " +
                "aparecerán la IP y el puerto (también en la notificación, sin salir de Ajustes).",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = { AdbDiscoveryRepository.log(WirelessDebuggingLauncher.openWirelessDebugging(context)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Abrir Depuración inalámbrica")
        }
        OutlinedButton(
            onClick = { AdbDiscoveryRepository.log(WirelessDebuggingLauncher.openQrPairing(context)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Abrir Vincular con código QR")
        }

        DiscoveryControls(state.running, context)

        PermissionCard(
            context = context,
            onRequest = { localNetworkPermission.launch(LocalNetworkAccess.PERMISSION) },
        )

        EndpointSection(
            title = "Emparejamiento",
            serviceType = SERVICE_TYPE_PAIRING,
            emptyHint = "Se anuncia solo mientras la pantalla de vinculación está abierta.",
            endpoints = state.pairingEndpoints,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
        )

        EndpointSection(
            title = "Conexión",
            serviceType = SERVICE_TYPE_CONNECT,
            emptyHint = "Aparece cuando la depuración inalámbrica está activa.",
            endpoints = state.connectEndpoints,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
        )

        if (localIps.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("IPs locales", style = MaterialTheme.typography.titleSmall)
                    localIps.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        LogCard(state.logs) { clipboard.setText(AnnotatedString(it)) }
    }
}

@Composable
private fun PermissionCard(context: Context, onRequest: () -> Unit) {
    val knownToPlatform = remember { LocalNetworkAccess.isKnownToPlatform(context) }
    val hasInternet = remember { LocalNetworkAccess.hasInternetPermission(context) }
    val hasLocalNetwork = LocalNetworkAccess.isGranted(context)
    if (!knownToPlatform && hasInternet) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Permisos de red", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (knownToPlatform && !hasLocalNetwork) {
                Text(
                    text = "Falta el permiso de red local (ACCESS_LOCAL_NETWORK). Sin él, " +
                        "NsdManager responde \"no se pudo iniciar el descubrimiento\" con código 0.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!hasInternet) {
                Text(
                    text = "El permiso de red (INTERNET) está revocado. En GrapheneOS se controla " +
                        "desde Ajustes > Apps > esta app > Permisos > Red.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (knownToPlatform && !hasLocalNetwork) {
                    TextButton(onClick = onRequest) { Text("Conceder") }
                }
                TextButton(onClick = { context.openAppSettings() }) { Text("Abrir ajustes de la app") }
            }
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun DiscoveryControls(running: Boolean, context: Context) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (running) "Escuchando mDNS…" else "Descubrimiento detenido",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "$SERVICE_TYPE_PAIRING · $SERVICE_TYPE_CONNECT",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { AdbDiscoveryService.start(context) }, enabled = !running) {
                    Text("Iniciar")
                }
                TextButton(onClick = { AdbDiscoveryService.stop(context) }, enabled = running) {
                    Text("Detener")
                }
                TextButton(onClick = { AdbDiscoveryRepository.clearEndpoints() }) {
                    Text("Limpiar")
                }
            }
        }
    }
}

@Composable
private fun EndpointSection(
    title: String,
    serviceType: String,
    emptyHint: String,
    endpoints: List<AdbEndpoint>,
    onCopy: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = serviceType,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (endpoints.isEmpty()) {
                Text(emptyHint, style = MaterialTheme.typography.bodySmall)
            } else {
                endpoints.forEachIndexed { index, endpoint ->
                    if (index > 0) HorizontalDivider()
                    EndpointRow(endpoint, onCopy)
                }
            }
        }
    }
}

@Composable
private fun EndpointRow(endpoint: AdbEndpoint, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = endpoint.address,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "IP ${endpoint.host} · puerto ${endpoint.port}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = endpoint.serviceName,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = if (endpoint.active) {
                "Activo · visto a las ${formatTime(endpoint.lastSeenAtMillis)}"
            } else {
                "Ya no se anuncia · último a las ${formatTime(endpoint.lastSeenAtMillis)}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onCopy(endpoint.address) }) { Text("Copiar IP:puerto") }
            TextButton(onClick = { onCopy(endpoint.command) }) { Text("Copiar comando") }
        }
    }
}

@Composable
private fun LogCard(logs: List<String>, onCopy: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Registro (${logs.size})", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = { onCopy(logs.joinToString("\n")) },
                    enabled = logs.isNotEmpty(),
                ) {
                    Text("Copiar registro")
                }
            }
            if (logs.isEmpty()) {
                Text("Sin eventos todavía.", style = MaterialTheme.typography.bodySmall)
            } else {
                logs.asReversed().forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTime(millis: Long): String = timeFormatter.format(Date(millis))
