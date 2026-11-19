# chaide-app — App multiplataforma Android + iOS (Kotlin + Compose Multiplatform)

Reconstrucción **100% Kotlin** de la app Chaide Smart, basada en las pantallas reales del
APK (ver `../app-reference/`). Un solo código genera la app Android y la app iOS.
Consume el backend **Node.js** de pruebas (ver "Backend de pruebas").

## Pantallas (como la app real)
1. **Welcome** — logo luna, fondo azul, botones *Sign Up* / *Log in*
2. **Login** — Email, Password (ojo), checkbox *License Agreement*
3. **Home** — banner "GOOD NIGHT", pestañas Left/Right, reportes Daily/Weekly/Monthly
4. **Device** — "My Device", tarjeta del dispositivo (Smart Mattress)
5. **Me** — perfil (avatar, email), Friends / Account / Configure, cerrar sesión

Navegación con `navigation-compose`; barra inferior Home · Device · Me.

## Stack
Kotlin 2.1 + **Compose Multiplatform 1.7** (Material3) + Navigation multiplataforma +
**Ktor 3** (OkHttp en Android, NSURLSession en iOS) + kotlinx.serialization +
kotlinx-datetime + multiplatform-settings + Coroutines. Tema azul marino basado en
la paleta real de ChaideSmart. Módulo propio `m800-kmp-sdk` (Kotlin Multiplatform)
para el protocolo BLE/TCP del dispositivo M800 — reemplaza los jars del fabricante.

## Estructura multiplataforma

```
app/src/commonMain/   TODO lo compartido: pantallas Compose, navegación, red (Ktor),
                      sesión, protocolo M800 (ble/), aprovisionamiento BLE, fechas,
                      recursos (composeResources/: imágenes y fuentes Gotham)
app/src/androidMain/  MainActivity, permisos Android, MediaPlayer, motor OkHttp,
                      AndroidManifest + res/ (ícono, tema, network_security_config)
app/src/iosMain/      MainViewController (entrada iOS), AVPlayer, motor Darwin
iosApp/               Proyecto Xcode (SwiftUI mínimo que muestra la UI Compose)
m800-kmp-sdk/         SDK M800: BLE (GATT Android / CoreBluetooth iOS) + TCP
```

Lo que cambia por plataforma está declarado en
`app/src/commonMain/.../platform/Platform.kt` (`expect`) e implementado en
`androidMain`/`iosMain` (`actual`): log, permisos BLE/ubicación, SSID actual,
reproductor de audio, motor HTTP. Diferencias visibles en iOS:
- El nombre de la red WiFi se escribe a mano en "Ajustes de Wi-Fi" (iOS no deja
  leer el SSID sin un entitlement especial de Apple).
- El permiso de Bluetooth lo pide el sistema la primera vez que se escanea.

## Backend de pruebas
El backend NO vive en este repo (vive en `Smart-Chaide/deploy-test-api/`,
fuera de git — ver sección siguiente). `AppConfig.kt`
(`app/src/commonMain/kotlin/com/chaide/app/net/AppConfig.kt`) selecciona a cuál
apuntar, con tres opciones ya escritas:
- `REMOTE` — `https://apps.chaide.com/smartChaideTestBA/api/`, backend de
  pruebas ya desplegado, accesible desde cualquier máquina sin VPN/túnel.
  **Es la opción más simple para arrancar en una máquina nueva** (Mac
  incluida) porque no requiere replicar el stack Docker local.
- `LOCAL_LAN` — stack Docker local (`docker-compose.local.yml`, puerto
  5501) vía la IP WiFi de la PC que lo corre; el celular físico la alcanza
  por ahí. Es la que mantiene la app y `admin-web/`/`deploy-test-api/admin-ui/`
  viendo los mismos datos.
- `LOCAL_USB` — `127.0.0.1:5500` + `adb reverse tcp:5500 tcp:5501` (USB).

Hoy el target activo es **`REMOTE`** (`apps.chaide.com/smartChaideTestBA`).

## Gateway de pruebas (M800, TCP crudo) — topología híbrida a propósito

El API (REST) y el gateway M800 (TCP binario, protocolo propio con frames
`$_@-`) son **dos servidores independientes**, configurados por separado:
- **API** → `AppConfig.TARGET` (arriba). Hoy `REMOTE`, por HTTPS.
- **Gateway** → `GatewaySettings` (`app/.../net/Api.kt`), **no** sigue a
  `AppConfig`. Se edita en vivo en la pantalla "Add Device" (campo "Server
  IP (gateway de pruebas)") y queda persistido en el teléfono
  (`SharedPreferences` en Android, `NSUserDefaults` en iOS) — por eso cambiar el default en el código NO actualiza un
  teléfono que ya guardó un valor antes.

**Por qué están separados y por qué el gateway NO va a `apps.chaide.com`:**
el M800 real habla TCP crudo, no HTTP/TLS — no se puede meter en el mismo
`:443` que usa Traefik para el API (no hay SNI que enrutar si el cliente no
hace handshake TLS). Abrir un puerto nuevo en el servidor público
(`192.168.1.210` / borde `200.41.89.180`) se descartó: ese servidor comparte
firewall con otras apps que tienen `.env` sensibles, y un puerto TCP crudo
nuevo expuesto al mundo es superficie de ataque injustificada para lo que
se necesita.

**La solución real: no hace falta exponerlo a internet.** Los equipos de
prueba (M800 físicos + celulares Android/iOS) están en la misma red interna
que la PC que corre el gateway — así que el gateway se queda en la LAN:

| Pieza | Dónde corre | Cómo se llega |
|---|---|---|
| API (login, perfil, dispositivos, reportes, bind) | `apps.chaide.com/smartChaideTestBA` | HTTPS, desde cualquier red (`AppConfig.TARGET = REMOTE`) |
| Gateway M800 (discovery `29010`, datos `29011`, HTTP `/realtime` `29012`) | Esta PC (`docker-compose.local.yml` en `deploy-test-api/gateway-smart-chaide/`), contenedor `smart_chaide_test_gateway_service` | TCP plano, **solo LAN** — IP WiFi actual de la PC, puesta en "Add Device" → "Server IP" |

**Pasos para dejarlo apuntando bien (cada vez que la IP de la PC cambie):**
1. `Get-NetIPAddress` en la PC que corre el gateway → anotar la IP WiFi actual.
2. Confirmar que el contenedor sigue arriba: `docker ps` → `smart_chaide_test_gateway_service`
   publicado en `0.0.0.0:29010-29012`.
3. Firewall de Windows: ya hay reglas creadas ("m800-gateway Chaide pruebas",
   puertos 29010-29012, `Allow`/`Profile=Any`) — no se necesita nada nuevo
   salvo que se reinstale Windows/Docker Desktop.
4. En el teléfono de prueba, pantalla "Add Device" → campo "Server IP
   (gateway de pruebas)" → escribir esa IP (el puerto por defecto, `29010`,
   no cambia). El default en código (`GatewaySettings.ip` en `Api.kt`) es
   solo el valor inicial para una instalación nueva de la app; si el
   teléfono ya tenía uno guardado, hay que retipearlo ahí o borrar datos de
   la app.
5. Verificar en `docker logs smart_chaide_test_gateway_service -f` que
   llega la conexión del equipo al aprovisionar WiFi.

Esto cubre Android e iOS por igual: ambos
hablan HTTPS al mismo API remoto y TCP plano al mismo gateway LAN — nada de
esto depende del sistema operativo del teléfono, solo de estar en la misma
red que la PC del gateway.

## Correr

Requisitos: JDK 17. En la Mac de desarrollo están instalados en la carpeta del
usuario (`~/Library/Java/jdk-17*`, Android SDK en `~/Library/Android/sdk`).

**Android**
```bash
export JAVA_HOME=$(ls -d ~/Library/Java/jdk-17*/Contents/Home | head -1)
./gradlew :app:assembleDebug          # APK en app/build/outputs/apk/debug/
./gradlew :app:installDebug           # instala en el teléfono conectado por adb
```
O desde Android Studio: **Open** → raíz del repo → configuración `app` → **Run ▶**.

**iOS**
1. Editar `iosApp/Config.xcconfig`: `TEAM_ID` (tu equipo de Apple) y, si hace
   falta, `BUNDLE_ID`.
2. Abrir `iosApp/iosApp.xcodeproj` en Xcode → elegir el iPhone o un simulador → **Run ▶**.
   La fase "Compile Kotlin Framework" llama a
   `./gradlew :app:embedAndSignAppleFrameworkForXcode` (la primera vez tarda varios minutos).

## Auth
Login contra `POST /api/auth/login` del backend de pruebas → recibe **JWT**, que se envía como
`Authorization: Bearer <token>` en las demás llamadas (perfil, dispositivos, reportes).

## Nota
El **puntaje/duración** del sueño no se muestra aún: vive en el servicio de algoritmo (:8385)
que no está en el entorno local. La app muestra los reportes reales registrados + el
dispositivo y el perfil reales del usuario.

## Módulo M800 (Kotlin Multiplatform)
`m800-kmp-sdk/` (ver `m800-kmp-sdk/README.md`) reemplaza los jars del fabricante
(`m800sdk.jar`, `sdkcore.jar`, `wificonfigsdk.jar`, `wifidevicecommonsdk.jar`) con
una reimplementación 100% Kotlin.
- **Android**: validado contra hardware real (escaneo BLE → bind →
  aprovisionamiento WiFi → streaming TCP en vivo).
- **iOS**: compila (simulador y dispositivo). Pendiente validarlo contra el M800
  real. El `rawRecord` del advertisement se reconstruye desde el diccionario de
  CoreBluetooth, así que `M800Protocol.parseLocalName`/`parseSerial` funcionan igual.

## Qué NO trae este repo
El backend de pruebas (`Smart-Chaide/deploy-test-api/`), el gateway TCP
(`Smart-Chaide/m800-gateway/`), los paneles de administración y el entorno local
viven fuera de git, en la PC Windows. Para probar no hacen falta: el API está
desplegado (`REMOTE`) y el gateway corre en la LAN de pruebas (ver arriba).
