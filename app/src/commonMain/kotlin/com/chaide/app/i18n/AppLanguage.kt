package com.chaide.app.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings

enum class AppLanguage(val code: String) { ES("es"), EN("en") }

/**
 * Idioma elegido por el usuario, persistido (SharedPreferences en Android,
 * NSUserDefaults en iOS, vía multiplatform-settings — mismo patrón que
 * Session/GatewaySettings en net/Api.kt). Por defecto ESPAÑOL, sin importar
 * el idioma del sistema del teléfono — pedido explícito: el usuario lo
 * cambia a mano desde Configuración si quiere inglés.
 *
 * Respaldado por `mutableStateOf` (no un `var` plano) para que cambiarlo
 * desde SettingsScreen recomponga toda la app al instante, vía [LocalStrings]
 * en App.kt.
 */
object LocaleSettings {
    private const val KEY_LANG = "locale.language"
    private val prefs: Settings by lazy { Settings() }

    private var _language by mutableStateOf(AppLanguage.ES)
    var language: AppLanguage
        get() = _language
        set(value) { _language = value; prefs.putString(KEY_LANG, value.code) }

    /** Llamar una vez al arrancar (initApp), junto a Session.init()/GatewaySettings.init(). */
    fun init() {
        val code = prefs.getStringOrNull(KEY_LANG)
        _language = AppLanguage.entries.firstOrNull { it.code == code } ?: AppLanguage.ES
    }
}
