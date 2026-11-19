# chaide-app — App Android nativa (Kotlin + Compose)

Reconstrucción **100% nativa** de la app Chaide Smart, basada en las pantallas reales del
APK (ver `../app-reference/`). Consume el backend **Node.js** (`chaide-backend-node`, :5500).

## Pantallas (como la app real)
1. **Welcome** — logo luna, fondo azul, botones *Sign Up* / *Log in*
2. **Login** — Email, Password (ojo), checkbox *License Agreement*
3. **Home** — banner "GOOD NIGHT", pestañas Left/Right, reportes Daily/Weekly/Monthly
4. **Device** — "My Device", tarjeta del dispositivo (Smart Mattress)
5. **Me** — perfil (avatar, email), Friends / Account / Configure, cerrar sesión

Navegación con `navigation-compose`; barra inferior Home · Device · Me.

## Stack
Kotlin + Jetpack Compose (Material3) + Navigation + Retrofit/OkHttp (Gson) + Coroutines.
Tema azul marino basado en la paleta real de ChaideSmart. Módulo propio
`m800-kmp-sdk` (Kotlin Multiplatform, ver abajo) para el protocolo BLE/TCP
del dispositivo M800 — reemplaza los jars del fabricante.

## Backend de pruebas
El backend NO vive en este repo (vive en `Smart-Chaide/deploy-test-api/`,
fuera de git — ver sección siguiente). `AppConfig.kt`
(`app/src/main/java/com/chaide/app/net/AppConfig.kt`) selecciona a cuál
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
  IP (gateway de pruebas)") y queda persistido en `SharedPreferences` del
  teléfono — por eso cambiar el default en el código NO actualiza un
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

Esto cubre Android e iOS (cuando exista el proyecto iOS) por igual: ambos
hablan HTTPS al mismo API remoto y TCP plano al mismo gateway LAN — nada de
esto depende del sistema operativo del teléfono, solo de estar en la misma
red que la PC del gateway.

## Correr
1. Android Studio → **Open** → carpeta `chaide-app` → sincronizar Gradle (AGP 8.5.2 / Gradle 8.7).
2. Emulador (minSdk 26) → **Run ▶**.
3. En *Login* viene precargado el email; escribe tu contraseña y entra.

> Emulador → host por `10.0.2.2:<puerto>` (configurado en `net/Api.kt` y `network_security_config.xml`).
> Para dispositivo físico en la misma WiFi, usa la IP LAN de la PC que corre el backend.

## Auth
Login contra `POST /api/auth/login` del backend de pruebas → recibe **JWT**, que se envía como
`Authorization: Bearer <token>` en las demás llamadas (perfil, dispositivos, reportes).

## Nota
El **puntaje/duración** del sueño no se muestra aún: vive en el servicio de algoritmo (:8385)
que no está en el entorno local. La app muestra los reportes reales registrados + el
dispositivo y el perfil reales del usuario.

## Módulo M800 (Kotlin Multiplatform) — Android listo, iOS pendiente
`m800-kmp-sdk/` (incluido en este repo, ver `m800-kmp-sdk/README.md` para el
detalle completo) reemplaza los jars del fabricante (`m800sdk.jar`,
`sdkcore.jar`, `wificonfigsdk.jar`, `wifidevicecommonsdk.jar`) con una
reimplementación 100% Kotlin. El lado Android está integrado, compila y fue
**validado contra hardware real** (escaneo BLE → bind → aprovisionamiento
WiFi → streaming TCP en vivo). El lado iOS está **escrito pero nunca
compilado** — no ha habido Mac disponible en ningún momento de este
proyecto hasta ahora.

## Continuar este proyecto desde otra máquina (p. ej. una Mac, para iOS)

**Qué trae `git clone` de este repo (y qué NO):**
- ✅ `chaide-app/` completo, incluyendo `m800-kmp-sdk/` (el SDK M800 KMP,
  con sus tres source sets `commonMain`/`androidMain`/`iosMain`) — es
  autocontenido, compila solo con Gradle, sin dependencias fuera del repo.
- ❌ El backend de pruebas (`Smart-Chaide/deploy-test-api/`), el gateway TCP
  (`Smart-Chaide/m800-gateway/`), los paneles de administración
  (`Smart-Chaide/admin-web/`, `deploy-test-api/admin-ui/`) y el entorno
  local de réplica de producción (`Smart-Chaide/local-env/`) — todos viven
  en la carpeta raíz `Smart-Chaide/`, que **no es un repositorio git**.
  Esos quedan solo en esta PC Windows a menos que se transfieran aparte
  (zip, otro repo, etc.). Para seguir con iOS no son necesarios de entrada:
  usa el target `REMOTE` de `AppConfig.kt` (arriba) si en algún momento
  quieres probar el flujo completo contra un backend real sin replicar
  nada de esto.

**Pasos recomendados en la Mac:**
1. `git clone https://github.com/TechChaide/Smart-Chaide-APP.git && cd Smart-Chaide-APP`
2. Abrir `m800-kmp-sdk/README.md` — tiene el estado real actualizado, el bug
   bloqueante conocido en iOS (`BleTransport.ios.kt`, `rawRecord` siempre
   vacío — CoreBluetooth no expone los bytes crudos del advertisement BLE
   como sí lo hace Android) y el orden de trabajo recomendado (compilar
   primero sin tocar nada, arreglar `rawRecord`, agregar empaquetado
   `cocoapods {}`/`framework {}`, y solo después decidir el alcance de un
   proyecto iOS/SwiftUI real — que no existe todavía, ni una sola pantalla).
3. Resumen visual (sin credenciales, para repasar sin leer el README crudo):
   https://claude.ai/artifact/JR1Eqt3QmeQMUpPVqL9ZWx
4. Abrir solo `m800-kmp-sdk/` en Xcode/Gradle para compilar los targets
   `iosX64`/`iosSimulatorArm64` primero — no hace falta Android Studio ni
   el resto del repo para ese primer paso.
