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

Hoy el target activo es `LOCAL_LAN` apuntando a la IP WiFi de la PC Windows
donde se desarrolló esto — **no va a resolver desde otra red/máquina**;
hay que cambiar `TARGET` a `REMOTE` (o montar el stack local propio, ver
siguiente sección) antes de probar "Agregar dispositivo" en la Mac.

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
