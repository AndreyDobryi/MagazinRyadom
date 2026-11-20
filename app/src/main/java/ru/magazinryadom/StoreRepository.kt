package ru.magazinryadom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object StoreRepository {

    private const val PHOTON = "https://photon.komoot.io/api/"
    private const val USER_AGENT = "MagazinRyadom/1.5.1 (Android)"
    private const val SEARCH_RADIUS = 5000.0
    private const val MAX_RESULTS = 50

    fun search(
        lat: Double,
        lon: Double,
        term: String,
        ctx: Context
    ): List<Store> {

        val query = term.trim()

        require(query.isNotEmpty()) {
            "Введи название магазина или категорию."
        }

        require(
            lat.isFinite() &&
            lon.isFinite() &&
            lat in -90.0..90.0 &&
            lon in -180.0..180.0
        ) {
            "Не удалось определить корректное местоположение. Обнови геолокацию и повтори поиск."
        }

        // Ищем через OSM.
        val osmResult = runCatching {
            overpassSearch(query, lat, lon, SEARCH_RADIUS.toInt())
        }

        // Дополнительно ищем через Photon.
        // Это выполняется независимо от результатов OSM.
        val photonResults = mutableListOf<Store>()
        var photonWorked = false

        val variants = listOf(
            query,
            query.replace('ё', 'е'),
            query.replace('е', 'ё')
        ).distinct()

        for (variant in variants) {
            val result = runCatching {
                photonSearch(variant, lat, lon, MAX_RESULTS)
            }

            if (result.isSuccess) {
                photonWorked = true

                photonResults.addAll(
                    result.getOrDefault(emptyList())
                        .filter { it.distance <= SEARCH_RADIUS }
                )
            }
        }

        // Если оба сервиса не сработали, сообщаем об ошибке.
        if (osmResult.isFailure && !photonWorked) {
            throw Exception(
                "Сервисы поиска временно недоступны. Попробуй ещё раз."
            )
        }

        val nearbyOsm = osmResult.getOrDefault(emptyList())

        // Пользовательские магазины.
        val local = custom(ctx, lat, lon).filter {
            (
                it.name.contains(query, true) ||
                it.address.contains(query, true)
            ) && it.distance <= SEARCH_RADIUS
        }

        return (nearbyOsm + photonResults + local)
            .distinctBy {
                "${it.lat},${it.lon}"
            }
            .sortedBy {
                it.distance
            }
            .take(MAX_RESULTS)
    }

    fun suggest(term: String): List<Store> {
        val query = term.trim()

        if (query.length < 2) return emptyList()

        return photonSearch(query, null, null, 7)
    }

    private fun photonSearch(
        query: String,
        lat: Double?,
        lon: Double?,
        limit: Int
    ): List<Store> {

        val encoded = URLEncoder.encode(query, "UTF-8")
        val safeLimit = limit.coerceIn(1, MAX_RESULTS)

        val urls = linkedSetOf<String>()

        if (
            lat != null &&
            lon != null &&
            lat.isFinite() &&
            lon.isFinite() &&
            lat in -90.0..90.0 &&
            lon in -180.0..180.0
        ) {
            urls += "${PHOTON}?q=${encoded}&lat=${lat}&lon=${lon}&limit=${safeLimit}&lang=ru"
        }

        urls += "${PHOTON}?q=${encoded}&limit=${safeLimit}&lang=ru"
        urls += "${PHOTON}?q=${encoded}&limit=${safeLimit}"
        urls += "${PHOTON}?q=${encoded}"

        var lastErrorCode = 0

        for (address in urls) {
            val connection =
                URL(address).openConnection() as HttpURLConnection

            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"

            connection.setRequestProperty(
                "User-Agent",
                USER_AGENT
            )

            connection.setRequestProperty(
                "Accept",
                "application/geo+json, application/json"
            )

            try {
                val code = connection.responseCode

                if (code in 200..299) {
                    val body = connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                    return parsePhotonResults(body, lat, lon)
                }

                lastErrorCode = code

                if (code == 400) {
                    continue
                }

                throw Exception(
                    "Сервис поиска временно недоступен (HTTP $code)."
                )

            } finally {
                connection.disconnect()
            }
        }

        throw Exception(
            "Сервис Photon не смог выполнить поиск (HTTP $lastErrorCode)."
        )
    }

    private fun buildOverpassNameRegex(query: String): String {
        return query.trim()
            .lowercase()
            .map { char ->
                when (char) {
                    'е', 'ё' -> "[её]"
                    '.', '^', '$', '|', '?', '*',
                    '+', '(', ')', '[', ']', '{',
                    '}', '\\' -> "\\$char"
                    else -> char.toString()
                }
            }
            .joinToString("")
    }

    private fun overpassSearch(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int
    ): List<Store> {

        val regexPattern = buildOverpassNameRegex(query)

        val overpassQuery = """
            [out:json][timeout:20];
            (
              nwr(around:$radiusMeters,$lat,$lon)
              ["name"~"$regexPattern",i];
            );
            out center tags;
        """.trimIndent()

        val connection = (
            URL("https://overpass-api.de/api/interpreter")
                .openConnection() as HttpURLConnection
        )

        connection.connectTimeout = 10000
        connection.readTimeout = 25000
        connection.requestMethod = "POST"
        connection.doOutput = true

        connection.setRequestProperty(
            "User-Agent",
            USER_AGENT
        )

        connection.setRequestProperty(
            "Content-Type",
            "application/x-www-form-urlencoded; charset=UTF-8"
        )

        try {
            val payload =
                "data=" + URLEncoder.encode(overpassQuery, "UTF-8")

            connection.outputStream.use {
                it.write(payload.toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode !in 200..299) {
                throw Exception("OpenStreetMap search unavailable")
            }

            val root = JSONObject(
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }
            )

            val elements =
                root.optJSONArray("elements") ?: JSONArray()

            val found = mutableListOf<Store>()

            for (i in 0 until elements.length()) {
                val element = elements.optJSONObject(i) ?: continue
                val tags = element.optJSONObject("tags") ?: continue

                val name = tags.optString("name")
                    .takeIf { it.isNotBlank() } ?: continue

                val center = element.optJSONObject("center")

                val itemLat = if (center != null) {
                    center.optDouble("lat", Double.NaN)
                } else {
                    element.optDouble("lat", Double.NaN)
                }

                val itemLon = if (center != null) {
                    center.optDouble("lon", Double.NaN)
                } else {
                    element.optDouble("lon", Double.NaN)
                }

                if (!itemLat.isFinite() || !itemLon.isFinite()) {
                    continue
                }

                val address = listOf(
                    tags.optString("addr:street"),
                    tags.optString("addr:housenumber"),
                    tags.optString("addr:city")
                )
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .ifBlank {
                        "Адрес не указан в OpenStreetMap"
                    }

                val distanceMeters = distance(
                    lat,
                    lon,
                    itemLat,
                    itemLon
                )

                if (distanceMeters <= radiusMeters) {
                    found.add(
                        Store(
                            name,
                            address,
                            itemLat,
                            itemLon,
                            distanceMeters
                        )
                    )
                }
            }

            return found

        } finally {
            connection.disconnect()
        }
    }

    private fun parsePhotonResults(
        body: String,
        lat: Double?,
        lon: Double?
    ): List<Store> {

        val root = JSONObject(body)

        val features =
            root.optJSONArray("features") ?: JSONArray()

        val output = mutableListOf<Store>()

        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue

            val geometry =
                feature.optJSONObject("geometry") ?: continue

            val coordinates =
                geometry.optJSONArray("coordinates") ?: continue

            if (coordinates.length() < 2) continue

            val properties =
                feature.optJSONObject("properties") ?: JSONObject()

            val itemName = properties.optString("name")
                .ifBlank {
                    properties.optString("street")
                        .ifBlank {
                            properties.optString("type")
                                .ifBlank { "Место" }
                        }
                }

            val addressParts = listOf(
                properties.optString("street"),
                properties.optString("housenumber"),
                properties.optString("district"),
                properties.optString("city"),
                properties.optString("state")
            )
                .filter { it.isNotBlank() }
                .distinct()

            val address = addressParts.joinToString(", ")
                .ifBlank {
                    properties.optString("country")
                        .ifBlank {
                            "Адрес не указан в OpenStreetMap"
                        }
                }

            val itemLon = coordinates.optDouble(0, Double.NaN)
            val itemLat = coordinates.optDouble(1, Double.NaN)

            if (!itemLat.isFinite() || !itemLon.isFinite()) {
                continue
            }

            val itemDistance =
                if (
                    lat != null &&
                    lon != null &&
                    lat.isFinite() &&
                    lon.isFinite()
                ) {
                    distance(lat, lon, itemLat, itemLon)
                } else {
                    0.0
                }

            output.add(
                Store(
                    itemName,
                    address,
                    itemLat,
                    itemLon,
                    itemDistance
                )
            )
        }

        return output
    }

    /** Пеший маршрут от публичного OSM foot-router. */
    fun walkingRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): List<Pair<Double, Double>> {

        val url =
            "https://routing.openstreetmap.de/routed-foot/route/v1/driving/" +
            "$fromLon,$fromLat;$toLon,$toLat" +
            "?overview=full&geometries=geojson&steps=true"

        val connection =
            URL(url).openConnection() as HttpURLConnection

        connection.connectTimeout = 12000
        connection.readTimeout = 20000

        connection.setRequestProperty(
            "User-Agent",
            USER_AGENT
        )

        try {
            if (connection.responseCode !in 200..299) {
                throw Exception(
                    "Сервис пеших маршрутов временно недоступен."
                )
            }

            val root = JSONObject(
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }
            )

            val routes =
                root.optJSONArray("routes") ?: JSONArray()

            if (routes.length() == 0) {
                throw Exception("Пешеходный маршрут не найден.")
            }

            val geometry =
                routes.getJSONObject(0)
                    .optJSONObject("geometry")
                    ?: return emptyList()

            val coordinates =
                geometry.optJSONArray("coordinates")
                    ?: return emptyList()

            return (0 until coordinates.length()).mapNotNull { i ->
                val pair = coordinates.optJSONArray(i)
                    ?: return@mapNotNull null

                if (pair.length() < 2) {
                    return@mapNotNull null
                }

                val lon = pair.optDouble(0, Double.NaN)
                val lat = pair.optDouble(1, Double.NaN)

                if (lat.isFinite() && lon.isFinite()) {
                    lat to lon
                } else {
                    null
                }
            }

        } finally {
            connection.disconnect()
        }
    }

    fun geocode(address: String): Pair<Double, Double> {
        val encoded = URLEncoder.encode(address, "UTF-8")

        val connection = (
            URL("${PHOTON}?q=$encoded&limit=1&lang=ru")
                .openConnection() as HttpURLConnection
        )

        connection.connectTimeout = 10000
        connection.readTimeout = 15000

        connection.setRequestProperty(
            "User-Agent",
            USER_AGENT
        )

        try {
            if (connection.responseCode !in 200..299) {
                throw Exception(
                    "Сервис адресов временно недоступен."
                )
            }

            val features = JSONObject(
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }
            )
                .optJSONArray("features") ?: JSONArray()

            if (features.length() == 0) {
                throw Exception(
                    "Адрес не найден. Укажи улицу, дом и город."
                )
            }

            val coordinates = features.getJSONObject(0)
                .getJSONObject("geometry")
                .getJSONArray("coordinates")

            return coordinates.getDouble(1) to
                coordinates.getDouble(0)

        } finally {
            connection.disconnect()
        }
    }

    fun save(
        ctx: Context,
        name: String,
        address: String,
        lat: Double,
        lon: Double
    ) {
        val preferences =
            ctx.getSharedPreferences("custom", Context.MODE_PRIVATE)

        val items = try {
            JSONArray(
                preferences.getString("items", "[]")
            )
        } catch (_: Exception) {
            JSONArray()
        }

        items.put(
            JSONObject()
                .put("n", name)
                .put("a", address)
                .put("lat", lat)
                .put("lon", lon)
        )

        preferences.edit()
            .putString("items", items.toString())
            .apply()
    }

    private fun custom(
        ctx: Context,
        lat: Double,
        lon: Double
    ): List<Store> {

        val items = try {
            JSONArray(
                ctx.getSharedPreferences("custom", Context.MODE_PRIVATE)
                    .getString("items", "[]")
            )
        } catch (_: Exception) {
            JSONArray()
        }

        return (0 until items.length()).mapNotNull { index ->
            try {
                val item = items.getJSONObject(index)

                val itemLat = item.getDouble("lat")
                val itemLon = item.getDouble("lon")

                Store(
                    item.getString("n"),
                    item.getString("a"),
                    itemLat,
                    itemLon,
                    distance(lat, lon, itemLat, itemLon)
                )

            } catch (_: Exception) {
                null
            }
        }
    }

    fun distance(
        a: Double,
        b: Double,
        c: Double,
        d: Double
    ): Double {

        val p = Math.toRadians(c - a)
        val q = Math.toRadians(d - b)

        val x =
            sin(p / 2).pow(2) +
            cos(Math.toRadians(a)) *
            cos(Math.toRadians(c)) *
            sin(q / 2).pow(2)

        return 12742000.0 * asin(sqrt(x))
    }
}
