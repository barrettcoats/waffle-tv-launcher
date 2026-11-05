package com.barrettcoats.waffle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale

/** A place picked in Settings → Weather. */
@Serializable
data class WeatherPlace(
    val name: String,
    /** "Oregon, United States": shown under the name so the right Portland can be picked. */
    val region: String = "",
    val latitude: Double,
    val longitude: Double,
)

@Serializable
enum class TemperatureUnit { AUTO, CELSIUS, FAHRENHEIT }

/** Fahrenheit for the few countries that use it, Celsius everywhere else. */
fun TemperatureUnit.resolve(locale: Locale = Locale.getDefault()): TemperatureUnit = when (this) {
    TemperatureUnit.AUTO -> if (locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) {
        TemperatureUnit.FAHRENHEIT
    } else {
        TemperatureUnit.CELSIUS
    }
    else -> this
}

/** What the weather looks like, collapsed from WMO weather codes into what we draw. */
enum class Sky(val label: String) {
    CLEAR("Clear"),
    PARTLY_CLOUDY("Partly cloudy"),
    CLOUDY("Cloudy"),
    FOG("Fog"),
    DRIZZLE("Drizzle"),
    RAIN("Rain"),
    SNOW("Snow"),
    STORM("Thunderstorms");

    companion object {
        /** https://open-meteo.com/en/docs (WMO weather interpretation codes). */
        fun fromWmo(code: Int): Sky = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> STORM
            else -> CLOUDY
        }
    }
}

data class DayForecast(val date: LocalDate, val sky: Sky, val high: Int, val low: Int)

data class WeatherReport(
    val place: WeatherPlace,
    val unit: TemperatureUnit,
    val temperature: Int,
    val sky: Sky,
    val isDay: Boolean,
    val days: List<DayForecast>,
    val fetchedAt: Long,
)

/**
 * Open-Meteo (https://open-meteo.com): free, no account or API key, CC BY 4.0 data. Blocking
 * calls; use from a background dispatcher.
 */
class WeatherClient {
    private val json = Json { ignoreUnknownKeys = true }

    fun searchPlaces(query: String): List<WeatherPlace> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val lang = Locale.getDefault().language.ifBlank { "en" }
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=$lang&format=json")
        val results = json.decodeFromString<GeocodingResponse>(body).results.orEmpty()
        return results.map {
            WeatherPlace(
                name = it.name,
                region = listOfNotNull(it.admin1, it.country).distinct().joinToString(", "),
                latitude = it.latitude,
                longitude = it.longitude,
            )
        }
    }

    fun forecast(place: WeatherPlace, unit: TemperatureUnit): WeatherReport {
        val resolved = unit.resolve()
        val tempUnit = if (resolved == TemperatureUnit.FAHRENHEIT) "fahrenheit" else "celsius"
        val body = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=6&temperature_unit=$tempUnit",
        )
        val r = json.decodeFromString<ForecastResponse>(body)
        val d = r.daily
        return WeatherReport(
            place = place,
            unit = resolved,
            temperature = Math.round(r.current.temperature).toInt(),
            sky = Sky.fromWmo(r.current.weatherCode),
            isDay = r.current.isDay == 1,
            days = d.time.indices.map { i ->
                DayForecast(
                    date = LocalDate.parse(d.time[i]),
                    sky = Sky.fromWmo(d.weatherCode[i]),
                    high = Math.round(d.max[i]).toInt(),
                    low = Math.round(d.min[i]).toInt(),
                )
            },
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "Waffle (Android TV home screen)")
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code from ${URL(url).host}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    @Serializable
    private class GeocodingResponse(val results: List<GeoResult>? = null)

    @Serializable
    private class GeoResult(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val country: String? = null,
        val admin1: String? = null,
    )

    @Serializable
    private class ForecastResponse(val current: Current, val daily: Daily)

    @Serializable
    private class Current(
        @SerialName("temperature_2m") val temperature: Double,
        @SerialName("weather_code") val weatherCode: Int,
        @SerialName("is_day") val isDay: Int = 1,
    )

    @Serializable
    private class Daily(
        val time: List<String>,
        @SerialName("weather_code") val weatherCode: List<Int>,
        @SerialName("temperature_2m_max") val max: List<Double>,
        @SerialName("temperature_2m_min") val min: List<Double>,
    )
}
