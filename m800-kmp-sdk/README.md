# m800-kmp-sdk

Módulo Kotlin Multiplatform (Android + iOS) para el protocolo M800 de Sleepace:
TCP gateway (login, streaming de ritmo cardiaco/respiración) + aprovisionamiento
WiFi por BLE. Reemplaza `m800sdk.jar`/`sdkcore.jar`/`wificonfigsdk.jar`/
`wifidevicecommonsdk.jar` (los jars del fabricante, solo JVM/Android) con una
reimplementación en Kotlin puro, decompilada y portada clase por clase desde
esos jars con CFR.

## Estado real (actualizado 2026-11-18)

**Android: integrado, compilando y validado end-to-end contra hardware real.**
`chaide-app` ya no tiene los 4 jars del fabricante (eliminados de
`app/libs/`) — usa este módulo vía `implementation(project(":m800-kmp-sdk"))`.
Verificado en un teléfono físico con un M800 real: escaneo BLE, resolución de
identidad (`identifyDevice`/`GET_SYSTEM_INFO`), `bindDevice` al backend,
aprovisionamiento WiFi real (`WiFiConfigHelper.bleWiFiConfig`), y streaming de
datos en vivo por TCP (gateway propio) mostrándose en la UI. El flujo completo
de "Agregar dispositivo" de la app funciona de punta a punta con este módulo.

**iOS: implementación real, escrita pero NUNCA compilada ni probada.** No ha
habido Mac/Xcode disponible en ningún momento de este proyecto. El código no
es un stub — usa CoreBluetooth real (`CBCentralManager`/`CBPeripheral`) y
sockets POSIX reales (`platform.posix`) — pero nadie lo ha pasado por el
compilador de Kotlin/Native todavía. Hay que asumir que la primera compilación
en Mac va a sacar errores de interop que aquí no se pudieron ver.

### Bug conocido y bloqueante en iOS — arreglar ANTES de probar "Agregar dispositivo"

`src/iosMain/kotlin/com/chaide/m800sdk/ble/BleTransport.ios.kt`, método
`centralManager(... didDiscoverPeripheral ...)`:

```kotlin
override val rawRecord: ByteArray = ByteArray(0)
```

Esto SIEMPRE es un array vacío. En Android, `BleScanResult.rawRecord` trae los
bytes crudos del advertisement BLE (estructura AD: `[len][type][data...]`
repetida), y `M800Protocol.parseAdField`/`parseSerial`/`parseLocalName`
(en `chaide-app/app/src/main/java/com/chaide/app/ble/M800Protocol.kt`)
dependen de esos bytes crudos para sacar el nombre corto (AD 0x09) y el
serial real del equipo (AD 0xFF, datos de fabricante).

CoreBluetooth en iOS **no expone esos bytes crudos** — entrega un diccionario
ya parseado (`advertisementData: Map<Any?, *>`, con llaves como
`CBAdvertisementDataLocalNameKey` y `CBAdvertisementDataManufacturerDataKey`).
Por eso, tal como está hoy, el escaneo en iOS SÍ encontraría periféricos BLE
cercanos, pero nunca podría identificar cuál es el M800 ni sacarle el serial
— `parseSerial` siempre recibiría un array vacío.

**Dos formas de arreglarlo (elegir una antes de seguir):**
1. Reconstruir un array de bytes AD sintético desde el diccionario de
   CoreBluetooth (tomar `CBAdvertisementDataLocalNameKey` y
   `CBAdvertisementDataManufacturerDataKey`, volver a armar los `[len][type][data]`
   manualmente) para que `rawRecord` traiga algo que `M800Protocol` sepa leer
   sin tocar ese archivo común.
2. Refactorizar `M800Protocol`/`BleScanResult` para que cada plataforma
   entregue los campos YA separados (nombre, datos de fabricante) en vez de
   bytes crudos, y mover el parseo AD-structure solo al lado Android.

La opción 1 es menos invasiva (no toca código común ni Android, que ya está
probado). La opción 2 es más limpia a largo plazo pero obliga a retocar
`M800BleProvisioner.kt` del lado Android también.

### Otro hallazgo de esta sesión que también aplica a iOS

El M800 ya aprovisionado (luz verde fija, conectado a WiFi) **sí anuncia por
BLE, pero en ráfagas intermitentes, no de forma continua** — confirmado contra
hardware real en Android: escaneos de 10s fallaban al azar, uno de 45s lo
encontró. Esto es comportamiento del firmware del equipo, no específico de
Android — la misma espera larga va a hacer falta en el lado iOS del flujo de
"Agregar dispositivo" cuando se construya.

### Sin empaquetado para Xcode todavía

`build.gradle.kts` de este módulo no tiene ningún bloque `binaries { framework
{...} }` ni `cocoapods {}` — faltan antes de que un proyecto Xcode pueda
consumir esto como `.framework`/XCFramework. Tampoco existe ningún proyecto
iOS en este repo (ni Xcode project, ni una sola pantalla SwiftUI) — todas las
~20 pantallas de `chaide-app` (Compose) habría que reconstruirlas del lado
iOS; este módulo es solo la capa de lógica BLE/TCP, no trae UI.

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
src/iosMain/      actual de BLE (CoreBluetooth) y socket (POSIX) — SIN PROBAR
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

## Orden recomendado de trabajo en la Mac

1. **Primero, compilar tal cual está** (`iosX64`/`iosSimulatorArm64`, el
   target más rápido de iterar) antes de tocar nada — para ver qué errores de
   interop/compilador salen realmente, en vez de adivinar.
2. Arreglar el bug de `rawRecord` (ver arriba) — sin esto, "Agregar
   dispositivo" nunca va a poder identificar el M800 en iOS aunque todo lo
   demás compile y corra.
3. Agregar el empaquetado `binaries { framework {...} }` (o `cocoapods {}`)
   para poder consumir el módulo desde un proyecto Xcode real.
4. Recién ahí decidir el alcance del proyecto iOS en sí (qué pantallas de
   `chaide-app` hacen falta primero) — eso es un esfuerzo aparte, comparable
   al que ya se invirtió en el lado Android.
