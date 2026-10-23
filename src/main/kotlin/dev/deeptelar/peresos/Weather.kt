package dev.deeptelar.peresos

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/** The weather in the window's habitat. */
enum class Weather { CLEAR, CLOUDY, FOG, RAIN, SNOW, STORM }

/** The season in the window's habitat: it colors the leaves. */
enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

/** The season on [date], in the southern half of the world when [southern]. */
fun season(
    date: LocalDate,
    southern: Boolean = false,
): Season {
    val north =
        when (date.monthValue) {
            3, 4, 5 -> Season.SPRING
            6, 7, 8 -> Season.SUMMER
            9, 10, 11 -> Season.AUTUMN
            else -> Season.WINTER
        }
    return if (southern) Season.entries[(north.ordinal + 2) % 4] else north
}

/** A town the person chose, for the weather of its window. */
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
) {
    val southern: Boolean get() = latitude < 0
}

/**
 * The weather from [Open-Meteo](https://open-meteo.com), which needs no key. Only the town the
 * person typed, and then its coordinates, are sent; nothing of the pile.
 */
class OpenMeteo(
    private val client: HttpClient,
) {
    /** The town called [name], or `null` when Open-Meteo knows none. */
    suspend fun find(name: String): Place? {
        val answer = client.get("https://geocoding-api.open-meteo.com/v1/search") {
            parameter("name", name.trim())
            parameter("count", 1)
        }
        val found = Json.parseToJsonElement(answer.bodyAsText()).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val country = found["country"]?.jsonPrimitive?.content
        return Place(
            name = listOfNotNull(found["name"]?.jsonPrimitive?.content, country).joinToString(", "),
            latitude = found.getValue("latitude").jsonPrimitive.double,
            longitude = found.getValue("longitude").jsonPrimitive.double,
        )
    }

    /** The weather at [place] now. */
    suspend fun now(place: Place): Weather {
        val answer = client.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", place.latitude)
            parameter("longitude", place.longitude)
            parameter("current", "weather_code")
        }
        val current = Json.parseToJsonElement(answer.bodyAsText()).jsonObject.getValue("current") as JsonObject
        return weather(current.getValue("weather_code").jsonPrimitive.int)
    }
}

/** The weather of a WMO weather code, as Open-Meteo gives it. */
fun weather(code: Int): Weather =
    when (code) {
        0, 1 -> Weather.CLEAR
        2, 3 -> Weather.CLOUDY
        45, 48 -> Weather.FOG
        in 71..77, 85, 86 -> Weather.SNOW
        in 95..99 -> Weather.STORM
        in 51..67, in 80..82 -> Weather.RAIN
        else -> Weather.CLOUDY
    }
