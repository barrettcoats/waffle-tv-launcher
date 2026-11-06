package com.barrettcoats.waffle.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** The parts of the day a wallpaper photo can be set to show in. */
@Serializable
enum class DayPart(val label: String) {
    MORNING("Morning"),
    DAY("Day"),
    EVENING("Evening"),
    NIGHT("Night"),
}

/**
 * Where the sun is, from a latitude/longitude and the clock alone (no network): the NOAA solar
 * calculator's equations (https://gml.noaa.gov/grad/solcalc/calcdetails.html), good to about a
 * minute for sunrise and sunset, which is far more than picking a wallpaper needs.
 */
object SunClock {
    /** Sun this far below the horizon or lower is night (end of civil twilight). */
    const val NIGHT_BELOW = -6.0
    /** In the afternoon, a sun lower than this is evening (golden hour, ~45-60 min before sunset). */
    const val EVENING_BELOW = 8.0
    /** Morning lasts this long after sunrise (but never past solar noon). */
    const val MORNING_HOURS = 3.0

    /** Elevation of the sun's centre above the horizon in degrees (no refraction). */
    fun elevation(atMs: Long, latitude: Double, longitude: Double): Double {
        val s = solar(atMs)
        val utcMinutes = Math.floorMod(atMs, DAY_MS) / 60_000.0
        val trueSolarMinutes = utcMinutes + s.equationOfTime + 4 * longitude
        val hourAngle = rad(trueSolarMinutes / 4 - 180)
        val lat = rad(latitude)
        val cosZenith = sin(lat) * sin(s.declination) + cos(lat) * cos(s.declination) * cos(hourAngle)
        return 90 - deg(acos(cosZenith.coerceIn(-1.0, 1.0)))
    }

    /** Solar noon on the UTC day containing [atMs] at [longitude], as epoch ms. */
    fun solarNoon(atMs: Long, longitude: Double): Long {
        val dayStart = floor(atMs.toDouble() / DAY_MS).toLong() * DAY_MS
        // Two passes: the equation of time barely moves in a few hours.
        var noon = dayStart + (720 - 4 * longitude).toLong() * 60_000
        repeat(2) { noon = dayStart + ((720 - 4 * longitude - solar(noon).equationOfTime) * 60_000).toLong() }
        return noon
    }

    /**
     * Sunrise and sunset around the solar noon nearest [atMs], or null for either when the sun
     * stays up (both null, [Day.alwaysUp]) or down all day.
     */
    fun day(atMs: Long, latitude: Double, longitude: Double): Day {
        var noon = solarNoon(atMs, longitude)
        // The solar noon of "this" local day: the one within half a day of the given time.
        if (atMs - noon > DAY_MS / 2) noon += DAY_MS else if (noon - atMs > DAY_MS / 2) noon -= DAY_MS
        val lat = rad(latitude)
        val decl = solar(noon).declination
        // -0.833 degrees: the sun's radius plus typical refraction, the usual sunrise definition.
        val cosH = (cos(rad(90.833)) - sin(lat) * sin(decl)) / (cos(lat) * cos(decl))
        return when {
            cosH > 1 -> Day(noon, null, null, alwaysUp = false) // polar night
            cosH < -1 -> Day(noon, null, null, alwaysUp = true) // midnight sun
            else -> {
                val half = (deg(acos(cosH)) * 4 * 60_000).toLong()
                Day(noon, noon - half, noon + half, alwaysUp = false)
            }
        }
    }

    data class Day(val noon: Long, val sunrise: Long?, val sunset: Long?, val alwaysUp: Boolean)

    /** Which part of the day it is at a place, from where the sun is. */
    fun partAt(atMs: Long, latitude: Double, longitude: Double): DayPart {
        val elevation = elevation(atMs, latitude, longitude)
        if (elevation < NIGHT_BELOW) return DayPart.NIGHT
        val day = day(atMs, latitude, longitude)
        return if (atMs < day.noon) {
            val morningEnds = when {
                day.sunrise != null -> minOf(day.noon, day.sunrise + (MORNING_HOURS * HOUR_MS).toLong())
                day.alwaysUp -> Long.MIN_VALUE // midnight sun: no dawn, it's just day
                else -> day.noon // polar night: twilight before noon is the morning
            }
            if (atMs < morningEnds) DayPart.MORNING else DayPart.DAY
        } else {
            if (elevation < EVENING_BELOW && !day.alwaysUp) DayPart.EVENING else DayPart.DAY
        }
    }

    /** Without a location: plain clock hours (Morning 6-10, Day 10-17, Evening 17-19, Night). */
    fun partByClock(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): DayPart =
        when (Instant.ofEpochMilli(atMs).atZone(zone).hour) {
            in 6..9 -> DayPart.MORNING
            in 10..16 -> DayPart.DAY
            in 17..18 -> DayPart.EVENING
            else -> DayPart.NIGHT
        }

    private class Solar(val declination: Double, val equationOfTime: Double)

    private fun solar(atMs: Long): Solar {
        val julianDay = atMs / DAY_MS.toDouble() + 2440587.5
        val t = (julianDay - 2451545.0) / 36525
        val meanLong = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val m = rad(meanAnomaly)
        val center = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val omega = rad(125.04 - 1934.136 * t)
        val apparentLong = rad(meanLong + center - 0.00569 - 0.00478 * sin(omega))
        val meanObliquity = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val obliquity = rad(meanObliquity + 0.00256 * cos(omega))
        val declination = asin(sin(obliquity) * sin(apparentLong))
        val y = tan(obliquity / 2).let { it * it }
        val l0 = rad(meanLong)
        val eot = 4 * deg(
            y * sin(2 * l0) - 2 * eccentricity * sin(m) + 4 * eccentricity * y * sin(m) * cos(2 * l0) -
                0.5 * y * y * sin(4 * l0) - 1.25 * eccentricity * eccentricity * sin(2 * m),
        )
        return Solar(declination, eot)
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L
}

/** The part of the day at [place] (sun-based), or by clock hours when no place is set. */
fun dayPartNow(place: WeatherPlace?, atMs: Long = System.currentTimeMillis()): DayPart =
    if (place == null) SunClock.partByClock(atMs) else SunClock.partAt(atMs, place.latitude, place.longitude)

/** Sunrise and sunset on [date] at [place] (local [zone]), for showing in settings. */
fun sunTimes(place: WeatherPlace, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): SunClock.Day {
    val midday = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    return SunClock.day(midday, place.latitude, place.longitude)
}
