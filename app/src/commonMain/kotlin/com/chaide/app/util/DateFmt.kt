package com.chaide.app.util

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

/*
 * Reemplazo multiplataforma de SimpleDateFormat/Calendar (solo JVM). Todas
 * las horas se formatean en la zona horaria LOCAL del telefono, igual que
 * hacia SimpleDateFormat sin TimeZone explicita (ver notas en ReportDetailScreen).
 * Nombres de mes/dia: espanol si el telefono esta en espanol, si no ingles.
 */
object DateFmt {
    private val tz get() = TimeZone.currentSystemDefault()
    private val isEs get() = androidx.compose.ui.text.intl.Locale.current.language == "es"

    private val monthsEn = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val monthsEs = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept", "oct", "nov", "dic")
    // ISO: lunes = 1 ... domingo = 7
    private val daysEn = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val daysEs = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")

    fun local(epochSec: Long): LocalDateTime = Instant.fromEpochSeconds(epochSec).toLocalDateTime(tz)

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /** "HH:mm" */
    fun hhmm(epochSec: Long): String = local(epochSec).let { "${two(it.hour)}:${two(it.minute)}" }

    /** Minutos desde medianoche, hora local. */
    fun minutesOfDay(epochSec: Long): Int = local(epochSec).let { it.hour * 60 + it.minute }

    /** "MMM" segun idioma del telefono. */
    fun monthShort(month: Int, english: Boolean = !isEs): String =
        (if (english) monthsEn else monthsEs)[month - 1]

    /** "MMM.yyyy" del mes calendario actual menos [offset] meses (p.ej. "Sep.2026"). */
    fun monthYearLabel(offset: Int): String {
        val first = Clock.System.todayIn(tz).let { LocalDate(it.year, it.month, 1) }.minus(offset, DateTimeUnit.MONTH)
        return "${monthShort(first.monthNumber)}.${first.year}"
    }

    /** "MMM d" en ingles (como Locale.US del codigo original). */
    fun monthDayUs(epochSec: Long): String = local(epochSec).let { "${monthShort(it.monthNumber, english = true)} ${it.dayOfMonth}" }

    /** "EEE" segun idioma del telefono. */
    fun weekdayShort(epochSec: Long): String =
        (if (isEs) daysEs else daysEn)[local(epochSec).dayOfWeek.ordinal]

    /** "d" */
    fun dayOfMonth(epochSec: Long): String = local(epochSec).dayOfMonth.toString()

    /** true si cae en el dia calendario anterior a hoy (zona local). */
    fun isYesterday(epochSec: Long): Boolean =
        local(epochSec).date == Clock.System.todayIn(tz).minus(1, DateTimeUnit.DAY)

    /** Idioma para el endpoint de tips: "es" o "en-us". */
    fun apiLang(): String = if (isEs) "es" else "en-us"
}
