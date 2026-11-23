package com.chaide.app.net

/**
 * Rutas de la app — SOLO estas dos (decision del equipo, 2026-11-23):
 *
 *  1. API REST (login, perfil, dispositivos, reportes, bind) — guarda los datos:
 *     https://apps.chaide.com/smartChaideTestBA/api/  (HTTPS, Traefik en
 *     SRVWEB2CHAIDE 192.168.1.210, accesible desde cualquier red).
 *
 *  2. Gateway M800 (datos en vivo, TCP binario + HTTP /realtime):
 *     192.168.205.95 — puertos 29010 (discovery), 29011 (datos), 29012 (/realtime).
 *     Vive en GatewaySettings (Api.kt), NO aqui: se queda en la LAN interna
 *     porque el M800 habla TCP crudo (no HTTP/TLS) y abrir ese puerto en el
 *     servidor publico se descarto por riesgo.
 *
 * Se quitaron las opciones LOCAL_LAN / LOCAL_USB (stack Docker local) para que
 * la app no pueda apuntar a ningun otro backend por error.
 */
object AppConfig {
    const val BASE_URL: String = "https://apps.chaide.com/smartChaideTestBA/api/"
}
