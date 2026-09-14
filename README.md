# zi0n-pairing-qr

App de pruebas para descubrir por mDNS los puertos que anuncia `adbd` durante el
emparejamiento inalámbrico de Android.

## Qué hace

1. Botón **Abrir Depuración inalámbrica**: abre esa pantalla de Ajustes.
2. Botón **Abrir Vincular con código QR**: intenta abrir directamente el escáner de QR y,
   si la ROM no lo permite, cae en la pantalla de depuración inalámbrica con esa opción
   resaltada.
3. Un servicio en primer plano descubre con `NsdManager` los servicios
   `_adb-tls-pairing._tcp` y `_adb-tls-connect._tcp` y muestra **IP y puerto** de cada uno,
   tanto en la pantalla principal como en la notificación (visible sin salir de Ajustes).

El servicio de emparejamiento solo se anuncia mientras la pantalla de vinculación está
abierta; el de conexión aparece cuando la depuración inalámbrica está activa.

## Estructura

| Fichero | Rol |
| --- | --- |
| `adb/AdbMdnsDiscovery.kt` | Envoltorio de `NsdManager`: descubre y resuelve un tipo de servicio |
| `adb/AdbDiscoveryRepository.kt` | Estado compartido (endpoints + registro) entre servicio y UI |
| `adb/AdbDiscoveryService.kt` | Servicio en primer plano + notificación con IP:puerto |
| `adb/WirelessDebuggingLauncher.kt` | Intents hacia las pantallas de Ajustes |
| `MainActivity.kt` | UI Compose |

## Compilar

```
./gradlew assembleDebug
```

## Requisitos en el dispositivo

- Opciones de desarrollo y **Depuración inalámbrica** activadas.
- Teléfono y PC en la misma red Wi-Fi.
