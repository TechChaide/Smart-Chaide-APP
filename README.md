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
Tema azul marino basado en la paleta real de ChaideSmart.

## Requisitos
- Backend Node corriendo: `cd ../chaide-backend-node && npm run dev` (http://localhost:5500).
- Réplica MySQL (contenedor `chaide-mysql`) arriba.

## Correr
1. Android Studio → **Open** → carpeta `chaide-app` → sincronizar Gradle (AGP 8.5.2 / Gradle 8.7).
2. Emulador (minSdk 26) → **Run ▶**.
3. En *Login* viene precargado el email; escribe tu contraseña y entra.

> Emulador → host por `10.0.2.2:5500` (configurado en `net/Api.kt` y `network_security_config.xml`).
> Para dispositivo físico, cambia `Api.BASE_URL` por la IP LAN de tu PC.

## Auth
Login contra `POST /api/auth/login` del backend Node → recibe **JWT**, que se envía como
`Authorization: Bearer <token>` en las demás llamadas (perfil, dispositivos, reportes).

## Nota
El **puntaje/duración** del sueño no se muestra aún: vive en el servicio de algoritmo (:8385)
que no está en el entorno local. La app muestra los reportes reales registrados + el
dispositivo y el perfil reales del usuario.
