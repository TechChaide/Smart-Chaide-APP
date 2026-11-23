package com.chaide.app.net

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.datetime.Clock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Motor HTTP de cada plataforma: OkHttp en Android, Darwin (NSURLSession) en iOS. */
internal expect fun createHttpClient(config: io.ktor.client.HttpClientConfig<*>.() -> Unit): HttpClient

/** Mismo JSON tolerante que Gson: ignora campos desconocidos y acepta ausentes. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

/**
 * Respuesta HTTP no-2xx (equivalente al `retrofit2.HttpException` anterior).
 * [errorBody] es el cuerpo crudo; [errorMessage] intenta sacar `message`/`error`.
 */
class ApiException(val status: Int, val errorBody: String) : Exception("HTTP $status") {
    val errorMessage: String?
        get() = try {
            ApiJson.decodeFromString(ErrorResponse.serializer(), errorBody).let { it.message ?: it.error }
        } catch (_: Exception) { null }
}

/**
 * Sesion persistida (SharedPreferences en Android, NSUserDefaults en iOS, via
 * multiplatform-settings): sin esto, cada reinstalacion/reinicio de la app
 * durante pruebas forzaba re-login y re-agregar el dispositivo.
 */
object Session {
    private const val KEY_TOKEN = "session.token"
    private const val KEY_USER = "session.user"
    private val prefs: Settings by lazy { Settings() }

    var token: String? = null
        set(value) { field = value; loggedIn = value != null; persist() }
    var user: UserDto? = null
        set(value) { field = value; persist() }
    val isLoggedIn get() = token != null

    /**
     * Estado observable por Compose: AppNav lo vigila y, si pasa a false
     * estando dentro de la app (token vencido / 401), vuelve a Login.
     */
    var loggedIn by mutableStateOf(false)
        private set

    // Mientras se restaura desde disco no se persiste: antes, asignar `token`
    // guardaba con `user` todavia en null y BORRABA el usuario guardado.
    private var restoring = false

    /** Llamar una vez al arrancar (MainActivity / MainViewController). */
    fun init() {
        restoring = true
        val savedToken = prefs.getStringOrNull(KEY_TOKEN)
        val savedUser = prefs.getStringOrNull(KEY_USER)?.let {
            try { ApiJson.decodeFromString(UserDto.serializer(), it) } catch (_: Exception) { null }
        }
        token = savedToken
        user = savedUser
        restoring = false
        // JWT vencido: no entrar a Home con una sesion que el backend ya rechaza.
        if (savedToken != null && isJwtExpired(savedToken)) clear()
    }

    fun clear() {
        token = null
        user = null
    }

    /** El backend respondio 401 a una llamada autenticada: la sesion ya no sirve. */
    fun onUnauthorized() {
        if (token != null) clear()
    }

    private fun persist() {
        if (restoring) return
        token?.let { prefs.putString(KEY_TOKEN, it) } ?: prefs.remove(KEY_TOKEN)
        user?.let { prefs.putString(KEY_USER, ApiJson.encodeToString(UserDto.serializer(), it)) } ?: prefs.remove(KEY_USER)
    }
}

/** true si el JWT trae `exp` y ya paso (con 60 s de margen). Sin `exp` -> no vencido. */
@OptIn(ExperimentalEncodingApi::class)
internal fun isJwtExpired(jwt: String): Boolean = try {
    val payload = jwt.split('.').getOrNull(1) ?: return false
    val json = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
    val exp = ApiJson.parseToJsonElement(json).jsonObject["exp"]?.jsonPrimitive?.longOrNull
    exp != null && Clock.System.now().epochSeconds >= exp - 60
} catch (_: Exception) { false }

/**
 * IP/puerto del m800-gateway de pruebas, persistidos: la IP cambia cada vez
 * que se reconfigura el equipo en una red distinta (ver AddDeviceScreen
 * "Server IP"). Home y LiveMonitorScreen lo leen para armar la URL /realtime.
 */
object GatewaySettings {
    private const val KEY_IP = "gateway.ip"
    private const val KEY_HTTP_PORT = "gateway.http_port"
    private val prefs: Settings by lazy { Settings() }

    // IP de la PC que corre el gateway en la LAN de pruebas (ver README,
    // "topologia hibrida"). Si un telefono ya tiene un valor guardado, este
    // default NO lo pisa: se cambia en "Add Device" -> "Server IP" (se
    // guarda al escribir una IPv4 valida).
    var ip: String = "192.168.205.95"
        set(value) { field = value; prefs.putString(KEY_IP, value) }
    var httpPort: Int = 29012
        set(value) { field = value; prefs.putInt(KEY_HTTP_PORT, value) }

    /** Valida "a.b.c.d" con cada parte en 0..255. */
    fun isValidIp(value: String): Boolean {
        val parts = value.trim().split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255 }
    }

    fun init() {
        prefs.getStringOrNull(KEY_IP)?.let { ip = it }
        prefs.getIntOrNull(KEY_HTTP_PORT)?.let { httpPort = it }
    }
}

// ---------- DTOs ----------
@Serializable
data class LoginRequest(val email: String, val password: String)
@Serializable
data class RegisterRequest(val email: String, val password: String, val password2: String)
@Serializable
data class RegisterResponse(val message: String? = null, val token: String? = null, val user: UserDto? = null)
@Serializable
data class ErrorResponse(val message: String? = null, val error: String? = null, val code: String? = null)
@Serializable
data class UserDto(
    val userId: Long? = null, val email: String? = null, val mobile: String? = null,
    val nickname: String? = null, val avatar: String? = null, val gender: Int? = null, val lan: String? = null
)
@Serializable
data class LoginResponse(val message: String? = null, val token: String? = null, val user: UserDto? = null)

@Serializable
data class InfoDto(
    val nickname: String? = null, val gender: Int? = null, val avatar: String? = null, val birthday: String? = null,
    val height: Int? = null, val weight: Int? = null, val lan: String? = null
)
@Serializable
data class ProfileDto(
    val userId: Long? = null, val email: String? = null, val mobile: String? = null, val channelId: String? = null,
    val countryCode: String? = null, val timeZone: String? = null, val createTime: String? = null, val info: InfoDto? = null
)
@Serializable
data class ProfileResponse(val data: ProfileDto? = null)

@Serializable
data class DeviceDto(
    val deviceId: String? = null, val deviceName: String? = null, val deviceType: Int? = null, val isMaster: Int? = null,
    val leftRight: Int? = null, val macAddr: String? = null, val deviceChannel: JsonElement? = null, val createTime: String? = null,
    val wifiName: String? = null, val firmwareVersion: String? = null
)
@Serializable
data class DeviceListResponse(val data: List<DeviceDto>? = null, val length: Int? = null)
@Serializable
data class DeviceDetailResponse(val data: DeviceDto? = null)

@Serializable
data class ReportDto(val seqid: Long? = null, val userId: Long? = null, val startTime: Long? = null, val type: Int? = null, val createTime: String? = null)
@Serializable
data class SummaryDto(val daily: ReportDto? = null, val weekly: ReportDto? = null, val monthly: ReportDto? = null)
@Serializable
data class ReportSummaryResponse(val data: SummaryDto? = null)

@Serializable
data class SleepDto(
    val startTime: Long? = null, val durationMin: Int? = null, val deepMin: Int? = null, val lightMin: Int? = null,
    val remMin: Int? = null, val awakeTimes: Int? = null, val score: Int? = null,
    val avgHeartRate: Int? = null, val avgBreathRate: Int? = null, val snoreNumber: Int? = null
)
@Serializable
data class SleepResponse(val data: SleepDto? = null)

@Serializable
data class StageDto(val code: Int? = null, val stage: String? = null)
@Serializable
data class SeriesDto(
    val heartRate: List<Int?>? = null, val breathRate: List<Int?>? = null,
    val eTemp: List<Double?>? = null, val eHumidity: List<Double?>? = null,
    val sleepCurve: List<Double?>? = null, val movement: List<Int?>? = null,
    val sleepStates: List<StageDto?>? = null, val intervalSec: Int? = null
)
@Serializable
data class AdviceItemDto(val key: String? = null, val label: String? = null, val deduct: Int? = null, val labelVerified: Boolean? = null)
@Serializable
data class AdviceDto(val comment: String? = null, val items: List<AdviceItemDto>? = null)
@Serializable
data class DayReportDto(
    val startTime: Long? = null, val hasNewer: Boolean? = null, val advice: AdviceDto? = null,
    val durationMin: Int? = null, val deepMin: Int? = null, val lightMin: Int? = null,
    val midMin: Int? = null, val wakeMin: Int? = null, val wakeTimes: Int? = null, val asleepMin: Int? = null,
    val outOfBedCount: Int? = null, val score: Int? = null,
    val avgHeartRate: Int? = null, val minHeartRate: Int? = null, val maxHeartRate: Int? = null,
    val avgBreathRate: Int? = null, val minBreathRate: Int? = null, val maxBreathRate: Int? = null,
    val series: SeriesDto? = null
)
@Serializable
data class DayReportResponse(val data: DayReportDto? = null)

/** Serial del equipo (el impreso en la caja, p.ej. "M800424600184"). El
 * servidor resuelve contra `z1_manage.device_random` el deviceId real. */
@Serializable
data class BindDeviceRequest(val deviceName: String)
@Serializable
data class BindDeviceResponse(val data: DeviceDto? = null)

@Serializable
data class UnbindResultDto(val deviceId: String? = null, val unbound: Boolean? = null)
@Serializable
data class UnbindResponse(val data: UnbindResultDto? = null)

@Serializable
data class FriendDto(val userId: Long? = null, val name: String? = null, val avatar: String? = null, val gender: Int? = null, val status: Int? = null)
@Serializable
data class FriendsDto(val friends: List<FriendDto>? = null, val pendingRequests: List<FriendDto>? = null, val newRequestCount: Int? = null)
@Serializable
data class FriendsResponse(val data: FriendsDto? = null)

@Serializable
data class TrendPointDto(val startTime: Long? = null, val score: Int? = null)
@Serializable
data class NightDataDto(
    val startTime: Long? = null, val durationMin: Int? = null, val deepSleepMin: Int? = null,
    val sleepEfficiencyPct: Int? = null, val deepSleepPct: Int? = null,
    val fellAsleepTime: Long? = null, val wokeUpTime: Long? = null,
    val avgHeartRate: Int? = null, val avgBreathRate: Int? = null, val timeInBedMin: Int? = null,
    val wakeTimes: Int? = null, val outOfBedCount: Int? = null, val score: Int? = null,
)
@Serializable
data class QualityDistributionDto(
    val excellent: Int? = null, val good: Int? = null, val fair: Int? = null, val poor: Int? = null, val unrecorded: Int? = null,
)
@Serializable
data class TrendDto(
    val period: String? = null, val days: Int? = null, val score: Int? = null, val nights: Int? = null,
    val avgSleepEfficiency: Int? = null, val avgDeepSleepMin: Int? = null, val avgLightSleepMin: Int? = null,
    val avgRemSleepMin: Int? = null, val avgTimeAsleepMin: Int? = null, val avgFallAsleepMin: Int? = null,
    val avgWakeTimes: Int? = null, val avgWakeMin: Int? = null, val avgSnoringMin: Int? = null, val avgApneaMin: Int? = null,
    val points: List<TrendPointDto>? = null, val nightsData: List<NightDataDto>? = null,
    val qualityDistribution: QualityDistributionDto? = null,
    val notFinished: Boolean? = null,
)
@Serializable
data class TrendResponse(val data: TrendDto? = null)

@Serializable
data class SleepTipsResponse(val data: List<String>? = null)

@Serializable
data class MusicTrackDto(val id: Int? = null, val name: String? = null, val durationSec: Int? = null, val url: String? = null)
@Serializable
data class MusicResponse(val data: List<MusicTrackDto>? = null)

/**
 * Sesión de monitoreo en vivo recién terminada (ver LiveMonitorScreen.kt,
 * que junta estas listas mientras la pantalla está abierta). [intervalSec]
 * es el paso real entre muestras — hoy el gateway se consulta cada 1000ms,
 * pero el backend guarda/lee en pasos de 60s (igual que `history_detail_NN`
 * real), así que Kotlin debe mandar ya reducido a ese paso, no cada muestra
 * cruda del polling.
 */
@Serializable
data class UploadSleepSessionRequest(
    val startTime: Long, val intervalSec: Int = 60,
    val heartRate: List<Int>, val breathRate: List<Int>,
    val eTemp: List<Int>, val eHumidity: List<Int>,
)
@Serializable
data class UploadSleepSessionResponse(val data: UploadSleepSessionResult? = null, val message: String? = null)
@Serializable
data class UploadSleepSessionResult(val startTime: Long? = null, val durationMin: Int? = null, val samples: Int? = null)

// ---------- API ----------
private suspend inline fun <reified T> HttpResponse.unwrap(): T {
    if (!status.isSuccess()) {
        // 401 en una llamada que llevaba token = sesion vencida o revocada.
        if (status.value == 401 && call.request.headers["Authorization"] != null) Session.onUnauthorized()
        throw ApiException(status.value, bodyAsText())
    }
    return body()
}

class ChaideApi internal constructor(private val http: HttpClient) {
    private fun HttpRequestBuilder.auth() { Session.token?.let { bearerAuth(it) } }
    private fun HttpRequestBuilder.jsonBody(b: Any) { contentType(ContentType.Application.Json); setBody(b) }

    suspend fun login(body: LoginRequest): LoginResponse = http.post("auth/login") { jsonBody(body) }.unwrap()
    suspend fun register(body: RegisterRequest): RegisterResponse = http.post("auth/register") { jsonBody(body) }.unwrap()
    suspend fun profile(): ProfileResponse = http.get("user/profile") { auth() }.unwrap()
    suspend fun friends(): FriendsResponse = http.get("user/friends") { auth() }.unwrap()
    suspend fun devices(): DeviceListResponse = http.get("device") { auth() }.unwrap()
    suspend fun bindDevice(body: BindDeviceRequest): BindDeviceResponse =
        http.post("device/bind") { auth(); jsonBody(body) }.unwrap()
    suspend fun unbindDevice(deviceId: String): UnbindResponse = http.delete("device/$deviceId") { auth() }.unwrap()
    suspend fun deviceDetail(deviceId: String): DeviceDetailResponse = http.get("device/$deviceId") { auth() }.unwrap()
    suspend fun reportSummary(): ReportSummaryResponse = http.get("report/summary") { auth() }.unwrap()
    suspend fun sleepLatest(): SleepResponse = http.get("sleep/latest") { auth() }.unwrap()
    suspend fun sleepReportDay(before: Long? = null, after: Long? = null): DayReportResponse =
        http.get("sleep/report/day") { auth(); parameter("before", before); parameter("after", after) }.unwrap()
    suspend fun sleepReportTrend(period: String, offset: Int): TrendResponse =
        http.get("sleep/report/trend") { auth(); parameter("period", period); parameter("offset", offset) }.unwrap()
    suspend fun uploadSleepSession(body: UploadSleepSessionRequest): UploadSleepSessionResponse =
        http.post("sleep/report") { auth(); jsonBody(body) }.unwrap()
    suspend fun sleepTips(lang: String): SleepTipsResponse = http.get("content/sleeptips") { auth(); parameter("lang", lang) }.unwrap()
    suspend fun music(): MusicResponse = http.get("content/music") { auth() }.unwrap()
}

/** Lectura en vivo expuesta por nuestro gateway TCP propio (m800-gateway,
 * endpoint HTTP /realtime en el puerto 29012). heartRate/breathRate llegan en
 * 255 cuando el equipo aun no tiene una lectura valida (replica exacta del
 * sentinela real encontrado en el bundle H5 de produccion). */
@Serializable
data class GatewayRealtimeDto(
    val deviceId: String? = null, val heartRate: Int? = null, val breathRate: Int? = null,
    val status: Int? = null, val temp: Int? = null, val humidity: Int? = null, val ageMs: Long? = null
)

class GatewayApi internal constructor(private val http: HttpClient) {
    // URL armada en cada llamada: GatewaySettings.ip cambia cuando se
    // reaprovisiona el equipo en otra red (ver AddDeviceScreen).
    suspend fun realtime(): GatewayRealtimeDto =
        http.get("http://${GatewaySettings.ip}:${GatewaySettings.httpPort}/realtime").unwrap()
}

object GatewayApiClient {
    private val http by lazy {
        createHttpClient {
            install(ContentNegotiation) { json(ApiJson) }
            install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 5_000 }
        }
    }
    val service: GatewayApi by lazy { GatewayApi(http) }
}

object Api {
    // Ver AppConfig.kt: apunta al backend de pruebas remoto (apps.chaide.com/smartChaideTestBA).
    val BASE_URL = AppConfig.BASE_URL

    private val http by lazy {
        createHttpClient {
            install(ContentNegotiation) { json(ApiJson) }
            install(HttpTimeout) { connectTimeoutMillis = 20_000; requestTimeoutMillis = 30_000 }
            install(Logging) { level = LogLevel.INFO }
            defaultRequest { url(BASE_URL) }
        }
    }

    val service: ChaideApi by lazy { ChaideApi(http) }
}
