package com.chaide.app.net

/**
 * Apunta la app al backend de pruebas. Tres opciones:
 *  - REMOTE: desplegado en apps.chaide.com (base de datos REMOTA, 192.168.1.210,
 *    desincronizada de la local desde que empezamos a probar en ambos lados).
 *  - LOCAL_LAN: el stack local (docker-compose.local.yml, puerto 5501) via la
 *    IP de la PC en la red WiFi — el celular fisico lo alcanza por ahi. Esta
 *    es la base que administra admin-web/ (127.0.0.1:13307), asi que usar
 *    esta opcion mantiene app + admin hablando con LOS MISMOS datos.
 *  - LOCAL_USB: 127.0.0.1 + `adb reverse tcp:5500 tcp:5501` (USB).
 */
object AppConfig {
    private enum class Target { REMOTE, LOCAL_LAN, LOCAL_USB }
    private val TARGET = Target.LOCAL_LAN

    private const val REMOTE_TEST_BASE_URL = "https://apps.chaide.com/smartChaideTestBA/api/"

    // IP de la PC en la WiFi (ver `Get-NetIPAddress`) — cambia si se reconecta
    // a otra red; el stack local corre en el puerto 5501 (docker-compose.local.yml).
    private const val LOCAL_LAN_BASE_URL = "http://192.168.206.89:5501/api/"

    private const val LOCAL_USB_BASE_URL = "http://127.0.0.1:5500/api/"

    val BASE_URL: String = when (TARGET) {
        Target.REMOTE -> REMOTE_TEST_BASE_URL
        Target.LOCAL_LAN -> LOCAL_LAN_BASE_URL
        Target.LOCAL_USB -> LOCAL_USB_BASE_URL
    }
}
