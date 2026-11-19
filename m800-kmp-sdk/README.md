# m800-kmp-sdk

Módulo Kotlin Multiplatform (Android + iOS) para el protocolo M800 de Sleepace:
TCP gateway (login, streaming de ritmo cardiaco/respiración) + aprovisionamiento
WiFi por BLE. Reemplaza `m800sdk.jar`/`sdkcore.jar`/`wificonfigsdk.jar`/
`wifidevicecommonsdk.jar` (los jars del fabricante, solo JVM/Android) con una
reimplementación en Kotlin puro, decompilada y portada clase por clase desde
esos jars con CFR.

## Estado real (actualizado 2026-11-19)

**Android: integrado, compilando y validado end-to-end contra hardware real.**
`chaide-app` ya no tiene los 4 jars del fabricante (eliminados de
`app/libs/`) — usa este módulo vía `implementation(project(":m800-kmp-sdk"))`.
Verificado en un teléfono físico con un M800 real: escaneo BLE, resolución de
identidad (`identifyDevice`/`GET_SYSTEM_INFO`), `bindDevice` al backend,
aprovisionamiento WiFi real (`WiFiConfigHelper.bleWiFiConfig`), y streaming de
datos en vivo por TCP (gateway propio) mostrándose en la UI. El flujo completo
de "Agregar dispositivo" de la app funciona de punta a punta con este módulo.

**iOS: compila (iosX64, iosArm64, iosSimulatorArm64) desde 2026-11-19 — falta
validarlo contra el M800 real.** Cambios hechos en la primera compilación en Mac:

- `BleTransport.ios.kt`: el delegado CoreBluetooth vive en `IosBleCentral`
  (NSObject) y `IosBleTransport` es la fachada Kotlin (Kotlin/Native no permite
  que una subclase de NSObject implemente una interfaz Kotlin). Igual que en
  Android: una sola instancia compartida, `scan(ms)` se detiene solo y avisa
  `onScanStopped()`, `connect()` usa el `CBPeripheral.identifier` que entregó
  el escaneo (iOS no expone la MAC), y espera a `poweredOn` si se escanea
  antes de que el radio esté listo.
- **Bug de `rawRecord` vacío: resuelto (opción 1).** Se reconstruyen las
  estructuras AD `[len][type][data]` (0x09 nombre local, 0xFF datos de
  fabricante) desde el diccionario de CoreBluetooth, sin tocar código común.
- `RawTcpSocket.ios.kt`: sin `FD_SET`/`htons`/`inet_pton` (no expuestos a
  Kotlin/Native); usa `poll()` + `SO_ERROR`, y `SO_NOSIGPIPE` para que un
  socket cerrado por el gateway no mate la app.
- `BackgroundThread.ios.kt`: `kotlin.concurrent.AtomicInt`.

### Otro hallazgo de esta sesión que también aplica a iOS

El M800 ya aprovisionado (luz verde fija, conectado a WiFi) **sí anuncia por
BLE, pero en ráfagas intermitentes, no de forma continua** — confirmado contra
hardware real en Android: escaneos de 10s fallaban al azar, uno de 45s lo
encontró. Esto es comportamiento del firmware del equipo, no específico de
Android — la misma espera larga va a hacer falta en el lado iOS del flujo de
"Agregar dispositivo" cuando se construya.

### Empaquetado para Xcode

El módulo lo consume la app (`:app`), que lo exporta dentro de
`ChaideApp.framework` (ver `app/build.gradle.kts`). El proyecto Xcode está en
`iosApp/` en la raíz del repo.

## Qué tan portable era el SDK original (hallazgo del port inicial)

Al decompilar los 4 jars, de 63 clases, 46 no tenían **ninguna** referencia a
`android.*`. De las 17 restantes, 16 solo tocaban Android de forma trivial
(`TextUtils.isEmpty`, `SystemClock.sleep`, `Log`, un `ConnectivityManager`
opcional) — reemplazable con `expect`/`actual` de pocas líneas. Solo
`BleHelper.java` (GATT real) es irreductiblemente específico de plataforma.

Lo que esa primera pasada NO detectó (visible solo al decompilar, no al mirar
imports): `java.nio.ByteBuffer` y `java.util.zip.CRC32` — usados intensamente
en `SocketFrame`, `SocketManager`, `M800Manager`, `WiFiConfigHelper` — son
JVM-only, no disponibles en Kotlin/Native. Se reemplazaron con
`util/PortableBuffer.kt` y `util/PortableCRC32.kt` (mismo algoritmo CRC-32
estándar, mismo subset de API de `ByteBuffer`).

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
src/androidMain/  actual de BLE (GATT real) y socket (java.net.Socket) — PROBADO
src/iosMain/      actual de BLE (CoreBluetooth) y socket (POSIX) — compila, falta probar en hardware
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
