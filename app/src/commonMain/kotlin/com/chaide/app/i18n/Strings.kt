package com.chaide.app.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Textos de la UI en los idiomas soportados. Es una INTERFAZ (no un mapa de
 * claves sueltas tipo `strings["key"]`) a propósito: si se agrega un campo
 * aquí, el compilador obliga a traducirlo en los dos objetos de abajo — no
 * se puede olvidar un idioma a medio migrar.
 *
 * Cobertura actual (ver AddDeviceScreen y el resto de pantallas para texto
 * aún sin migrar — esto se extiende pantalla por pantalla, no es exhaustivo
 * todavía): Welcome, Login, Sign Up, pestañas de MainScreen, Configure.
 */
interface AppStrings {
    // Welcome / navegación de autenticación
    val signUp: String
    val logIn: String

    // Login / Sign Up (campos y mensajes compartidos)
    val forgotPassword: String
    val email: String
    val password: String
    val confirmPassword: String
    val passwordHint: String
    val acceptPrefix: String
    val licenseAgreement: String
    val loginError: String
    val loginErrorCredentials: String
    val signUpError: String
    val noConnection: String
    fun serverError(status: Int): String

    // Pestañas de MainScreen
    val tabHome: String
    val tabDevice: String
    val tabMe: String

    // Configure (SettingsScreen)
    val configure: String
    val unit: String
    val notification: String
    val versionUpdates: String
    val lastestVersion: String
    fun currentVersion(version: String): String
    val language: String
    val languageSpanish: String
    val languageEnglish: String
}

object EsStrings : AppStrings {
    override val signUp = "Regístrate"
    override val logIn = "Iniciar sesión"
    override val forgotPassword = "¿Olvidaste tu contraseña?"
    override val email = "Correo electrónico"
    override val password = "Contraseña"
    override val confirmPassword = "Confirmar contraseña"
    override val passwordHint = "Usa de 6 a 16 caracteres (letras, números o símbolos)"
    override val acceptPrefix = "Acepto ChaideSmart "
    override val licenseAgreement = "Acuerdo de Licencia"
    override val loginError = "No se pudo iniciar sesión"
    override val loginErrorCredentials = "Correo o contraseña incorrectos"
    override val signUpError = "No se pudo crear la cuenta"
    override val noConnection = "Sin conexión con el servidor"
    override fun serverError(status: Int) = "Error del servidor ($status). Intenta más tarde."
    override val tabHome = "Inicio"
    override val tabDevice = "Dispositivo"
    override val tabMe = "Yo"
    override val configure = "Configuración"
    override val unit = "Unidad"
    override val notification = "Notificaciones"
    override val versionUpdates = "Actualizaciones de versión"
    override val lastestVersion = "Última versión"
    override fun currentVersion(version: String) = "Versión actual: V$version"
    override val language = "Idioma"
    override val languageSpanish = "Español"
    override val languageEnglish = "Inglés"
}

object EnStrings : AppStrings {
    override val signUp = "Sign Up"
    override val logIn = "Log in"
    override val forgotPassword = "Forgot Password?"
    override val email = "Email"
    override val password = "Password"
    override val confirmPassword = "Confirm Password"
    override val passwordHint = "Use 6-16 characters (letters numbers symbols)"
    override val acceptPrefix = "Accept ChaideSmart "
    override val licenseAgreement = "License Agreement"
    override val loginError = "Could not log in"
    override val loginErrorCredentials = "Incorrect email or password"
    override val signUpError = "Could not create the account"
    override val noConnection = "No connection to server"
    override fun serverError(status: Int) = "Server error ($status). Try again later."
    override val tabHome = "Home"
    override val tabDevice = "Device"
    override val tabMe = "Me"
    override val configure = "Configure"
    override val unit = "Unit"
    override val notification = "Notification"
    override val versionUpdates = "Version Updates"
    override val lastestVersion = "Latest Version"
    override fun currentVersion(version: String) = "Current Version: V$version"
    override val language = "Language"
    override val languageSpanish = "Spanish"
    override val languageEnglish = "English"
}

fun stringsFor(language: AppLanguage): AppStrings = when (language) {
    AppLanguage.ES -> EsStrings
    AppLanguage.EN -> EnStrings
}

/** Default solo por si algo lee [LocalStrings] fuera del árbol de [com.chaide.app.App] (no debería pasar). */
val LocalStrings = staticCompositionLocalOf<AppStrings> { EsStrings }
