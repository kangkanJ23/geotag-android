package com.geostamp.camera

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Looks up hourly weather from Open-Meteo (free, no API key) for a place and moment. */
object Weather {

    data class Result(val text: String)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun fetch(lat: Double, lng: Double, atMillis: Long): Result? {
        val utcDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val utcHour = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val day = utcDay.format(atMillis)
        val hourKey = utcHour.format(atMillis + 30 * 60 * 1000) // round to nearest hour

        val now = System.currentTimeMillis()
        // Forecast API covers roughly the last 3 months and the next 2 weeks; older dates use the archive
        val base = when {
            atMillis > now + 15 * DAY_MS -> return null
            atMillis >= now - 85 * DAY_MS -> "https://api.open-meteo.com/v1/forecast"
            else -> "https://archive-api.open-meteo.com/v1/archive"
        }
        val url = String.format(
            Locale.US,
            "%s?latitude=%.4f&longitude=%.4f&hourly=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&timezone=GMT&start_date=%s&end_date=%s",
            base, lat, lng, day, day
        )

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val hourly = JSONObject(body).getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            var idx = -1
            for (i in 0 until times.length()) {
                if (times.getString(i) == hourKey) { idx = i; break }
            }
            if (idx < 0) return null
            val temp = hourly.getJSONArray("temperature_2m").optDouble(idx, Double.NaN)
            val hum = hourly.getJSONArray("relative_humidity_2m").optDouble(idx, Double.NaN)
            val wind = hourly.getJSONArray("wind_speed_10m").optDouble(idx, Double.NaN)
            val code = hourly.getJSONArray("weather_code").optInt(idx, -1)
            if (temp.isNaN()) return null

            val parts = mutableListOf<String>()
            describe(code)?.let { parts += it }
            parts += String.format(Locale.US, "%.1f°C", temp)
            if (!hum.isNaN()) parts += "Humidity ${hum.roundToInt()}%"
            if (!wind.isNaN()) parts += "Wind ${wind.roundToInt()} km/h"
            return Result(parts.joinToString("  ·  "))
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(code: Int): String? = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81 -> "Rain showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> null
    }
}
