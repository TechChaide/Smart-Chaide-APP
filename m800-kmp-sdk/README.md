# m800-kmp-sdk

Módulo Kotlin Multiplatform (Android + iOS) para el protocolo M800 de Sleepace:
TCP gateway (login, streaming de ritmo cardiaco/respiración) + aprovisionamiento
WiFi por BLE. Construido reemplazando `m800sdk.jar`/`sdkcore.jar`/`wificonfigsdk.jar`
(vendor jars de `chaide-app/app/libs/`, solo JVM/Android) por una reimplementación
en Kotlin puro, decompilada y portada clase por clase desde esos jars con CFR
(ver `../kmp-sdk-research/decompiled/`).

## Qué tan portable era el SDK original (hallazgo clave)

Al decompilar los 4 jars, de 63 clases, 46 no tenían **ninguna** referencia a
`android.*`. De las 17 restantes, 16 solo tocaban Android de forma trivial
(`TextUtils.isEmpty`, `SystemClock.sleep`, `Log`, un `ConnectivityManager`
opcional) — reemplazable con `expect`/`actual` de pocas líneas. **Solo
`BleHelper.java` (GATT real) es irreductiblemente específico de plataforma.**

Lo que esa primera pasada NO detectó (visible solo al decompilar, no al
mirar imports): `java.nio.ByteBuffer` y `java.util.zip.CRC32` — usados
intensamente en `SocketFrame`, `SocketManager`, `M800Manager`,
`WiFiConfigHelper` — son JVM-only, no están disponibles en Kotlin/Native.
Se reemplazaron con `util/PortableBuffer.kt` y `util/PortableCRC32.kt`
(mismo algoritmo CRC-32 estándar, mismo subset de API de `ByteBuffer`).

## Estado real por plataforma

- **Android — puerto fiel, mismo comportamiento que el jar original.**
  `java.net.Socket` igual que antes, `BleHelper` (GATT) portado método a
  método a `AndroidBleTransport`. Esto es lo que hoy usa `chaide-app` vía
  los jars; aquí es código Kotlin equivalente, listo para compilar y probar
  contra un M800 real igual que la app actual.
- **iOS — implementación real, NO probada en este entorno.** Esta máquina
  es Windows sin Xcode/Mac, así que los targets `iosArm64`/
  `iosSimulatorArm64` no se pudieron compilar ni ejecutar aquí. El socket
  TCP usa sockets POSIX reales (`platform.posix`, sin dependencias extra) y
  el BLE usa CoreBluetooth real (`CBCentralManager`/`CBPeripheral`) — **no
  es un stub**, pero tampoco está validado contra hardware. Antes de usarlo
  en producción: compilar en una Mac, correr contra un M800 real, y revisar
  especialmente `connect(address, ...)` en `BleTransport.ios.kt` (iOS no
  expone MAC address, solo un UUID de `CBPeripheral.identifier` — hay que
  adaptar el flujo de `WiFiConfigHelper.bleWiFiConfig()` para escanear y
  resolver ese UUID en vez de reusar la MAC que usa Android).

## Qué quedó fuera (a propósito, no por descuido)

- `com.sleepace.sdk.m800.domain.Analysis` (y su `ScoreDeduction`) — la clase
  más grande del SDK (19KB), puntuación de reportes de sueño post-hoc, no
  tocada por el flujo en vivo BLE/TCP. No se adivinaron sus campos.
- `WiFiDeviceSdkHelper` — cliente REST (Gson + HTTP) para login/bind de
  *otra* familia de dispositivos, protocolo completamente distinto al M800.

## Estructura

```
src/commonMain/   protocolo, modelo de datos, SocketManager/M800Manager,
                   WiFiConfigHelper — 100% Kotlin compartido
src/androidMain/  actual de BLE (GATT real) y socket (java.net.Socket)
src/iosMain/      actual de BLE (CoreBluetooth) y socket (POSIX)
```

## Uso

```kotlin
// Android, una vez, en Application.onCreate:
M800SdkAndroid.init(applicationContext)

// Cualquier plataforma:
val wifiConfig = M800Sdk.wifiConfigHelper()
wifiConfig.bleWiFiConfig(deviceType, btAddress, serverIp, serverPort, ssidRaw, pwd) { result -> ... }

val gateway = M800Sdk.m800Helper()
gateway.login(ip, port, sid, deviceId) { result -> ... }
```

## Pendiente antes de reemplazar los jars en `chaide-app`

1. Compilar este módulo (`./gradlew :m800-kmp-sdk:assemble` una vez tenga
   wrapper, o vía el mismo flujo `GradleWrapperMain` que usa `chaide-app`)
   y corregir lo que el compilador señale — este código se escribió sin
   acceso a un compilador Kotlin en esta sesión.
2. Probar el path Android contra un M800 físico (mismo hardware/firmware
   que ya funciona con los jars) antes de quitar las dependencias viejas.
3. Para iOS: compilar en Mac, resolver el tema de identidad de dispositivo
   (MAC vs `CBPeripheral.identifier`) y validar contra hardware real.
