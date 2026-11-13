package com.chaide.app.net

import android.content.Context
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * Backend Node de Chaide (chaide-backend-node) corriendo en el host (:5500).
 * Desde el emulador el host es 10.0.2.2.
 *
 * Persistido en SharedPreferences (no solo en memoria): sin esto, cada
 * reinstalacion/reinicio de la app durante pruebas forzaba re-login y
 * re-agregar el dispositivo, aunque el backend ya tuviera todo guardado.
 */
object Session {
    private const val PREFS = "chaide_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER = "user"
    private lateinit var prefs: android.content.SharedPreferences
    private val gson = Gson()

    @Volatile var token: String? = null
        set(value) { field = value; persist() }
    @Volatile var user: UserDto? = null
        set(value) { field = value; persist() }
    val isLoggedIn get() = token != null

    /** Llamar una vez en Application/MainActivity.onCreate antes de usar la sesion. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        token = prefs.getString(KEY_TOKEN, null)
        user = prefs.getString(KEY_USER, null)?.let {
            try { gson.fromJson(it, UserDto::class.java) } catch (_: Exception) { null }
        }
    }

    fun clear() {
        token = null
        user = null
    }

    private fun persist() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER, user?.let { gson.toJson(it) })
            .apply()
    }
}

/**
 * IP/puerto del m800-gateway de pruebas, persistidos (no hardcodeados): la
 * IP cambia cada vez que se reconfigura el equipo en una red distinta (ver
 * AddDeviceScreen "Server IP"), y antes GatewayApiClient apuntaba a un valor
 * fijo en el codigo que quedaba obsoleto en cuanto cambiaba la red.
 * AddDeviceScreen actualiza esto mismo al reaprovisionar el equipo; Home y
 * LiveMonitorScreen lo leen para armar la URL del endpoint /realtime.
 */
object GatewaySettings {
    private const val PREFS = "chaide_gateway"
    private const val KEY_IP = "ip"
    private const val KEY_HTTP_PORT = "http_port"
    private lateinit var prefs: android.content.SharedPreferences

    // IP de esta PC en la WiFi actual (ver `Get-NetIPAddress`) — corregida
    // tras confirmar en el log de m800-gateway que el valor viejo (.122) no
    // correspondia a ninguna interfaz real: el M800 se unia al WiFi pero
    // nunca llegaba a reportarse (luz roja intermitente).
    @Volatile var ip: String = "192.168.206.89"
        set(value) { field = value; persist() }
    @Volatile var httpPort: Int = 29012
        set(value) { field = value; persist() }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ip = prefs.getString(KEY_IP, null) ?: ip
        httpPort = prefs.getInt(KEY_HTTP_PORT, httpPort)
    }

    private fun persist() {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_IP, ip).putInt(KEY_HTTP_PORT, httpPort).apply()
    }
}

// ---------- DTOs ----------
data class LoginRequest(val email: String, val password: String)
data class RegisterRequest(val email: String, val password: String)
data class RegisterResponse(val message: String?, val token: String?, val user: UserDto?)
data class ErrorResponse(val message: String?, val error: String?)
data class UserDto(
    val userId: Long?, val email: String?, val mobile: String?,
    val nickname: String?, val avatar: String?, val gender: Int?, val lan: String?
)
data class LoginResponse(val message: String?, val token: String?, val user: UserDto?)

data class InfoDto(
    val nickname: String?, val gender: Int?, val avatar: String?, val birthday: String?,
    val height: Int?, val weight: Int?, val lan: String?
)
data class ProfileDto(
    val userId: Long?, val email: String?, val mobile: String?, val channelId: String?,
    val countryCode: String?, val timeZone: String?, val createTime: String?, val info: InfoDto?
)
data class ProfileResponse(val data: ProfileDto?)

data class DeviceDto(
    val deviceId: String?, val deviceName: String?, val deviceType: Int?, val isMaster: Int?,
    val leftRight: Int?, val macAddr: String?, val deviceChannel: Any?, val createTime: String?,
    val wifiName: String? = null, val firmwareVersion: String? = null
)
data class DeviceListResponse(val data: List<DeviceDto>?, val length: Int?)
data class DeviceDetailResponse(val data: DeviceDto?)

data class ReportDto(val seqid: Long?, val userId: Long?, val startTime: Long?, val type: Int?, val createTime: String?)
data class SummaryDto(val daily: ReportDto?, val weekly: ReportDto?, val monthly: ReportDto?)
data class ReportSummaryResponse(val data: SummaryDto?)

data class SleepDto(
    val startTime: Long?, val durationMin: Int?, val deepMin: Int?, val lightMin: Int?,
    val remMin: Int?, val awakeTimes: Int?, val score: Int?,
    val avgHeartRate: Int?, val avgBreathRate: Int?, val snoreNumber: Int?
)
data class SleepResponse(val data: SleepDto?)

data class StageDto(val code: Int?, val stage: String?)
data class SeriesDto(
    val heartRate: List<Int?>?, val breathRate: List<Int?>?,
    val eTemp: List<Double?>?, val eHumidity: List<Double?>?,
    val sleepCurve: List<Double?>?, val movement: List<Int?>?,
    val sleepStates: List<StageDto?>?, val intervalSec: Int?
)
data class AdviceItemDto(val key: String?, val label: String?, val deduct: Int?, val labelVerified: Boolean?)
data class AdviceDto(val comment: String?, val items: List<AdviceItemDto>?)
data class DayReportDto(
    val startTime: Long?, val hasNewer: Boolean?, val advice: AdviceDto?,
    val durationMin: Int?, val deepMin: Int?, val lightMin: Int?,
    val midMin: Int?, val wakeMin: Int?, val wakeTimes: Int?, val asleepMin: Int?,
    val outOfBedCount: Int?, val score: Int?,
    val avgHeartRate: Int?, val minHeartRate: Int?, val maxHeartRate: Int?,
    val avgBreathRate: Int?, val minBreathRate: Int?, val maxBreathRate: Int?,
    val series: SeriesDto?
)
data class DayReportResponse(val data: DayReportDto?)

/** Serial del equipo (el impreso en la caja, p.ej. "M800424600184"). El
 * servidor resuelve contra `z1_manage.device_random` el deviceId real. */
data class BindDeviceRequest(val deviceName: String)
data class BindDeviceResponse(val data: DeviceDto?)

data class UnbindResultDto(val deviceId: String?, val unbound: Boolean?)
data class UnbindResponse(val data: UnbindResultDto?)

data class FriendDto(val userId: Long?, val name: String?, val avatar: String?, val gender: Int?, val status: Int?)
data class FriendsDto(val friends: List<FriendDto>?, val pendingRequests: List<FriendDto>?, val newRequestCount: Int?)
data class FriendsResponse(val data: FriendsDto?)

data class TrendPointDto(val startTime: Long?, val score: Int?)
data class NightDataDto(
    val startTime: Long?, val durationMin: Int?, val deepSleepMin: Int?,
    val sleepEfficiencyPct: Int?, val deepSleepPct: Int?,
    val fellAsleepTime: Long?, val wokeUpTime: Long?,
    val avgHeartRate: Int?, val avgBreathRate: Int?, val timeInBedMin: Int?,
    val wakeTimes: Int?, val outOfBedCount: Int?, val score: Int?,
)
data class QualityDistributionDto(
    val excellent: Int?, val good: Int?, val fair: Int?, val poor: Int?, val unrecorded: Int?,
)
data class TrendDto(
    val period: String?, val days: Int?, val score: Int?, val nights: Int?,
    val avgSleepEfficiency: Int?, val avgDeepSleepMin: Int?, val avgLightSleepMin: Int?,
    val avgRemSleepMin: Int?, val avgTimeAsleepMin: Int?, val avgFallAsleepMin: Int?,
    val avgWakeTimes: Int?, val avgWakeMin: Int?, val avgSnoringMin: Int?, val avgApneaMin: Int?,
    val points: List<TrendPointDto>?, val nightsData: List<NightDataDto>?,
    val qualityDistribution: QualityDistributionDto?,
    val notFinished: Boolean? = null,
)
data class TrendResponse(val data: TrendDto?)

data class SleepTipsResponse(val data: List<String>?)

data class MusicTrackDto(val id: Int?, val name: String?, val durationSec: Int?, val url: String?)
data class MusicResponse(val data: List<MusicTrackDto>?)

// ---------- API ----------
interface ChaideApi {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @GET("user/profile")
    suspend fun profile(): ProfileResponse

    @GET("user/friends")
    suspend fun friends(): FriendsResponse

    @GET("device")
    suspend fun devices(): DeviceListResponse

    @POST("device/bind")
    suspend fun bindDevice(@Body body: BindDeviceRequest): BindDeviceResponse

    @retrofit2.http.DELETE("device/{deviceId}")
    suspend fun unbindDevice(@retrofit2.http.Path("deviceId") deviceId: String): UnbindResponse

    @GET("device/{deviceId}")
    suspend fun deviceDetail(@retrofit2.http.Path("deviceId") deviceId: String): DeviceDetailResponse

    @GET("report/summary")
    suspend fun reportSummary(): ReportSummaryResponse

    @GET("sleep/latest")
    suspend fun sleepLatest(): SleepResponse

    @GET("sleep/report/day")
    suspend fun sleepReportDay(
        @retrofit2.http.Query("before") before: Long? = null,
        @retrofit2.http.Query("after") after: Long? = null,
    ): DayReportResponse

    @GET("sleep/report/trend")
    suspend fun sleepReportTrend(
        @retrofit2.http.Query("period") period: String,
        @retrofit2.http.Query("offset") offset: Int
    ): TrendResponse

    @GET("content/sleeptips")
    suspend fun sleepTips(@retrofit2.http.Query("lang") lang: String): SleepTipsResponse

    @GET("content/music")
    suspend fun music(): MusicResponse
}

/** Lectura en vivo expuesta por nuestro gateway TCP propio (m800-gateway,
 * endpoint HTTP /realtime en el puerto 29012). heartRate/breathRate llegan en
 * 255 cuando el equipo aun no tiene una lectura valida (replica exacta del
 * sentinela real encontrado en el bundle H5 de produccion). */
data class GatewayRealtimeDto(
    val deviceId: String?, val heartRate: Int?, val breathRate: Int?,
    val status: Int?, val temp: Int?, val humidity: Int?, val ageMs: Long?
)

interface GatewayApi {
    @GET("realtime")
    suspend fun realtime(): GatewayRealtimeDto
}

object GatewayApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    // Se reconstruye en cada llamada: GatewaySettings.ip cambia cuando se
    // reaprovisiona el equipo en otra red (ver AddDeviceScreen), y Retrofit
    // no permite cambiar el baseUrl de una instancia ya creada.
    val service: GatewayApi
        get() = Retrofit.Builder()
            .baseUrl("http://${GatewaySettings.ip}:${GatewaySettings.httpPort}/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        .create(GatewayApi::class.java)
}

object Api {
    // Ver AppConfig.kt: por defecto apunta al backend de pruebas remoto
    // (apps.chaide.com/smartChaideTestBA), con datos reales de produccion.
    val BASE_URL = AppConfig.BASE_URL

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val b = chain.request().newBuilder()
            Session.token?.let { b.header("Authorization", "Bearer $it") }
            chain.proceed(b.build())
        }
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val service: ChaideApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ChaideApi::class.java)
}
