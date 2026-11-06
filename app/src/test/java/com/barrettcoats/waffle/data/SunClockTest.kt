package com.barrettcoats.waffle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/** Reference times are from the astral library (NOAA equations, independent implementation). */
class SunClockTest {
    private class Place(val lat: Double, val lon: Double, val zone: String)
    private val portland = Place(45.52, -122.68, "America/Los_Angeles")
    private val sydney = Place(-33.87, 151.21, "Australia/Sydney")
    private val tromso = Place(69.65, 18.96, "Europe/Oslo")
    private val quito = Place(-0.18, -78.47, "America/Guayaquil")

    private fun ms(p: Place, at: String) = LocalDateTime.parse(at).atZone(ZoneId.of(p.zone)).toInstant().toEpochMilli()

    private fun assertNear(p: Place, expected: String, actualMs: Long?, toleranceMin: Int = 3) {
        val diff = abs(actualMs!! - ms(p, expected)) / 60_000.0
        assertTrue("expected $expected, off by $diff min", diff <= toleranceMin)
    }

    private fun checkDay(p: Place, date: String, sunrise: String, noon: String, sunset: String) {
        val d = sunTimes(WeatherPlace("", "", p.lat, p.lon), LocalDate.parse(date), ZoneId.of(p.zone))
        assertNear(p, "${date}T$sunrise", d.sunrise)
        assertNear(p, "${date}T$noon", d.noon, toleranceMin = 1)
        assertNear(p, "${date}T$sunset", d.sunset)
    }

    @Test fun portlandAutumn() = checkDay(portland, "2026-11-05", "06:56", "11:54", "16:51")
    @Test fun sydneySummer() = checkDay(sydney, "2026-01-15", "05:59", "13:04", "20:08")
    @Test fun tromsoEquinox() = checkDay(tromso, "2026-03-20", "05:44", "11:51", "18:01")
    @Test fun quitoEquator() = checkDay(quito, "2026-09-23", "06:03", "12:06", "18:09")

    @Test fun tromsoMidnightSunAndPolarNight() {
        val summer = sunTimes(WeatherPlace("", "", tromso.lat, tromso.lon), LocalDate.parse("2026-06-21"), ZoneId.of(tromso.zone))
        assertTrue(summer.alwaysUp)
        assertNull(summer.sunrise)
        val winter = sunTimes(WeatherPlace("", "", tromso.lat, tromso.lon), LocalDate.parse("2026-12-21"), ZoneId.of(tromso.zone))
        assertTrue(!winter.alwaysUp && winter.sunrise == null)
        // astral (with refraction): +3.66 at midsummer midnight, -3.04 at midwinter noon.
        assertEquals(3.4, SunClock.elevation(ms(tromso, "2026-06-21T00:00"), tromso.lat, tromso.lon), 0.6)
        assertEquals(-3.1, SunClock.elevation(ms(tromso, "2026-12-21T12:00"), tromso.lat, tromso.lon), 0.6)
    }

    private fun part(p: Place, at: String) = SunClock.partAt(ms(p, at), p.lat, p.lon)

    @Test fun portlandPartsThroughTheDay() {
        // Dawn 06:24, sunrise 06:56, sunset 16:51, dusk 17:23.
        assertEquals(DayPart.NIGHT, part(portland, "2026-11-05T06:00"))
        assertEquals(DayPart.MORNING, part(portland, "2026-11-05T06:40"))
        assertEquals(DayPart.MORNING, part(portland, "2026-11-05T09:30"))
        assertEquals(DayPart.DAY, part(portland, "2026-11-05T10:30"))
        assertEquals(DayPart.DAY, part(portland, "2026-11-05T15:30")) // sun at about 11.7 degrees
        assertEquals(DayPart.EVENING, part(portland, "2026-11-05T16:30"))
        assertEquals(DayPart.EVENING, part(portland, "2026-11-05T17:15"))
        assertEquals(DayPart.NIGHT, part(portland, "2026-11-05T17:45"))
        assertEquals(DayPart.NIGHT, part(portland, "2026-11-05T23:59"))
    }

    @Test fun polarPartsDegradeGracefully() {
        assertEquals(DayPart.DAY, part(tromso, "2026-06-21T00:30"))
        assertEquals(DayPart.DAY, part(tromso, "2026-06-21T08:00"))
        assertEquals(DayPart.MORNING, part(tromso, "2026-12-21T11:00"))
        assertEquals(DayPart.EVENING, part(tromso, "2026-12-21T12:45"))
        assertEquals(DayPart.NIGHT, part(tromso, "2026-12-21T18:00"))
    }

    @Test fun clockFallback() {
        val z = ZoneId.of("America/Chicago")
        fun at(h: Int) = LocalDateTime.of(2026, 11, 5, h, 0).atZone(z).toInstant().toEpochMilli()
        assertEquals(DayPart.NIGHT, SunClock.partByClock(at(5), z))
        assertEquals(DayPart.MORNING, SunClock.partByClock(at(6), z))
        assertEquals(DayPart.DAY, SunClock.partByClock(at(12), z))
        assertEquals(DayPart.EVENING, SunClock.partByClock(at(18), z))
        assertEquals(DayPart.NIGHT, SunClock.partByClock(at(19), z))
    }
}
