package ru.magazinryadom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Поиск филиалов по бренду/оператору и данным OpenStreetMap. */
object StoreRepository {
    private const val PHOTON = "https://photon.komoot.io/api/"
    private const val USER_AGENT = "MagazinRyadom/1.8.0 (Android; OpenStreetMap client)"
    private const val SEARCH_RADIUS = 12000.0
    private const val MAX_RESULTS = 60
    private val overpassEndpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    private val networkAliases = mapOf(
        "пятерочка" to listOf("Пятёрочка", "Пятерочка"),
        "магнит" to listOf("Магнит", "Magnit"),
        "перекресток" to listOf("Перекрёсток", "Перекресток", "Perekrestok"),
        "дикси" to listOf("Дикси", "Dixy"),
        "лента" to listOf("Лента", "Lenta"),
        "ашан" to listOf("Ашан", "Auchan"),
        "вкусвилл" to listOf("ВкусВилл", "Вкус Вилл", "VkusVill"),
        "верный" to listOf("Верный", "Verniy"),
        "монетка" to listOf("Монетка", "Monetka"),
        "светофор" to listOf("Светофор", "Svetofor"),
        "чижик" to listOf("Чижик", "Chizhik"),
        "окей" to listOf("О'КЕЙ", "Окей", "Okey"),
        "метро" to listOf("METRO", "Метро"),
        "самокат" to listOf("Самокат", "Samokat"),
        "аптека" to listOf("аптека", "pharmacy")
    )

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun aliasesFor(query: String): List<String> {
        val key = normalize(query)
        val known = networkAliases[key]
        return (known ?: listOf(query.trim(), query.trim().replace('ё', 'е'), query.trim().replace('е', 'ё')))
            .filter { it.isNotBlank() }.distinct()
    }

    private fun isGenericCategory(query: String): Boolean = normalize(query) in setOf(
        "магазин", "магазины", "супермаркет", "супермаркеты", "продуктовый", "продуктовый магазин",
        "аптека", "аптеки", "pharmacy"
    )

    private fun matchesAnyTag(tags: JSONObject, query: String): Boolean {
        if (isGenericCategory(query)) return true
        val aliases = aliasesFor(query).map(::normalize).filter { it.isNotBlank() }
        val values = listOf("name", "name:ru", "brand", "brand:ru", "brand:en", "operator", "official_name", "alt_name")
            .map { normalize(tags.optString(it)) }.filter { it.isNotBlank() }
        return values.any { value -> aliases.any { alias -> value == alias || value.contains(alias) || alias.contains(value) } }
    }

    fun search(lat: Double, lon: Double, term: String, ctx: Context): List<Store> {
        val query = term.trim()
        require(query.isNotEmpty()) { "Выбери сеть магазинов или введи её название." }
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
            "Местоположение не определено. Обнови GPS или задай точку вручную."
        }

        val osm = runCatching { overpassSearch(query, lat, lon, SEARCH_RADIUS.toInt()) }
        val osmStores = osm.getOrDefault(emptyList())
        val local = custom(ctx, lat, lon).filter { matchesQuery(it, query) && it.distance <= SEARCH_RADIUS }
        val photon = mutableListOf<Store>()
        var photonAvailable = false
        // Photon is queried only when OSM did not return a branch; this avoids slow duplicate requests.
        if (osmStores.isEmpty()) {
            for (alias in aliasesFor(query).take(2)) {
                val result = runCatching { photonSearch(alias, lat, lon, MAX_RESULTS) }
                if (result.isSuccess) {
                    val matched = result.getOrDefault(emptyList()).filter { it.distance <= SEARCH_RADIUS && matchesQuery(it, query) }
                    if (matched.isNotEmpty()) photonAvailable = true
                    photon += matched
                }
            }
        }
        if (osm.isFailure && !photonAvailable && local.isEmpty()) {
            throw Exception("Картографические серверы сейчас не отвечают. Проверь интернет и попробуй ещё раз через минуту.")
        }

        return (osmStores + photon + local)
            .distinctBy { "${String.format(Locale.US, "%.5f", it.lat)},${String.format(Locale.US, "%.5f", it.lon)}" }
            .sortedBy { it.distance }
            .take(MAX_RESULTS)
    }

    private fun matchesQuery(store: Store, query: String): Boolean {
        val aliases = aliasesFor(query).map(::normalize)
        val haystack = normalize("${store.name} ${store.address}")
        return aliases.any { it.isNotBlank() && (haystack.contains(it) || it.split(' ').all { word -> word in haystack }) }
    }

    fun suggest(term: String): List<Store> {
        if (term.trim().length < 2) return emptyList()
        return runCatching { photonSearch(term.trim(), null, null, 6) }.getOrDefault(emptyList())
    }

    private fun photonSearch(query: String, lat: Double?, lon: Double?, limit: Int): List<Store> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (lat != null && lon != null) {
            "${PHOTON}?q=$encoded&lat=$lat&lon=$lon&limit=${limit.coerceIn(1, MAX_RESULTS)}&lang=ru"
        } else "${PHOTON}?q=$encoded&limit=${limit.coerceIn(1, MAX_RESULTS)}&lang=ru"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/geo+json, application/json")
            if (connection.responseCode !in 200..299) throw Exception("Photon HTTP ${connection.responseCode}")
            return parsePhotonResults(connection.inputStream.bufferedReader().use { it.readText() }, lat, lon)
        } finally { connection.disconnect() }
    }

    private fun escapeOverpass(value: String): String = buildString {
        value.forEach { ch ->
            if (ch == '"' || ch in "\\.^$|?*+()[]{}") append('\\')
            append(ch)
        }
    }

    private fun overpassSearch(query: String, lat: Double, lon: Double, radius: Int): List<Store> {
        val regex = aliasesFor(query).joinToString("|") { escapeOverpass(it) }
        val queryText = if (isGenericCategory(query)) {
            val categoryQuery = if (normalize(query) in setOf("аптека", "аптеки", "pharmacy")) {
                """nwr(around:$radius,$lat,$lon)["amenity"="pharmacy"]; nwr(around:$radius,$lat,$lon)["shop"="chemist"];"""
            } else {
                """nwr(around:$radius,$lat,$lon)["shop"~"supermarket|convenience|department_store|general|greengrocer|discount|wholesale|cash_and_carry",i];"""
            }
            "[out:json][timeout:22];($categoryQuery);out center tags;"
        } else {
            """
                [out:json][timeout:22];
                (
                  nwr(around:$radius,$lat,$lon)["shop"~"supermarket|convenience|department_store|general|greengrocer|discount|wholesale|cash_and_carry",i]["brand"~"$regex",i];
                  nwr(around:$radius,$lat,$lon)["shop"~"supermarket|convenience|department_store|general|greengrocer|discount|wholesale|cash_and_carry",i]["brand:ru"~"$regex",i];
                  nwr(around:$radius,$lat,$lon)["shop"~"supermarket|convenience|department_store|general|greengrocer|discount|wholesale|cash_and_carry",i]["operator"~"$regex",i];
                  nwr(around:$radius,$lat,$lon)["shop"~"supermarket|convenience|department_store|general|greengrocer|discount|wholesale|cash_and_carry",i]["name"~"$regex",i];
                  nwr(around:$radius,$lat,$lon)["shop"]["name:ru"~"$regex",i];
                );
                out center tags;
            """.trimIndent()
        }

        var lastError: Exception? = null
        for (endpoint in overpassEndpoints) {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 7000
                connection.readTimeout = 18000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val payload = "data=" + URLEncoder.encode(queryText, "UTF-8")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) throw Exception("Overpass HTTP $code")
                val elements = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("elements") ?: JSONArray()
                val found = mutableListOf<Store>()
                for (i in 0 until elements.length()) {
                    val element = elements.optJSONObject(i) ?: continue
                    val tags = element.optJSONObject("tags") ?: continue
                    if (!matchesAnyTag(tags, query)) continue
                    val center = element.optJSONObject("center")
                    val itemLat = center?.optDouble("lat", Double.NaN) ?: element.optDouble("lat", Double.NaN)
                    val itemLon = center?.optDouble("lon", Double.NaN) ?: element.optDouble("lon", Double.NaN)
                    if (!itemLat.isFinite() || !itemLon.isFinite()) continue
                    val name = listOf("name:ru", "name", "brand:ru", "brand", "operator", "official_name")
                        .map { tags.optString(it) }.firstOrNull { it.isNotBlank() } ?: query
                    val address = listOf(
                        tags.optString("addr:city"), tags.optString("addr:street"), tags.optString("addr:housenumber")
                    ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Адрес уточняется по карте" }
                    val distance = distance(lat, lon, itemLat, itemLon)
                    if (distance <= radius) found += Store(name, address, itemLat, itemLon, distance)
                }
                return found
            } catch (e: Exception) {
                lastError = e
            } finally { connection.disconnect() }
        }
        throw lastError ?: Exception("Серверы поиска магазинов не ответили")
    }

    private fun parsePhotonResults(body: String, lat: Double?, lon: Double?): List<Store> {
        val features = JSONObject(body).optJSONArray("features") ?: JSONArray()
        val output = mutableListOf<Store>()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 2) continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val name = properties.optString("name")
            if (name.isBlank()) continue
            val address = listOf(properties.optString("city"), properties.optString("street"), properties.optString("housenumber"))
                .filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Адрес не указан" }
            val itemLon = coordinates.optDouble(0, Double.NaN)
            val itemLat = coordinates.optDouble(1, Double.NaN)
            if (!itemLat.isFinite() || !itemLon.isFinite()) continue
            val d = if (lat != null && lon != null) distance(lat, lon, itemLat, itemLon) else 0.0
            output += Store(name, address, itemLat, itemLon, d)
        }
        return output
    }

    /** Пешеходная геометрия с публичного OSM foot-routing сервера. */
    fun walkingRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<Pair<Double, Double>> {
        val url = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 18000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode !in 200..299) throw Exception("Сервис пеших маршрутов временно не ответил.")
            val routes = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("routes") ?: JSONArray()
            if (routes.length() == 0) return emptyList()
            val coordinates = routes.optJSONObject(0)?.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return emptyList()
            return (0 until coordinates.length()).mapNotNull { i ->
                val pair = coordinates.optJSONArray(i) ?: return@mapNotNull null
                if (pair.length() < 2) return@mapNotNull null
                val lon = pair.optDouble(0, Double.NaN)
                val lat = pair.optDouble(1, Double.NaN)
                if (lat.isFinite() && lon.isFinite()) lat to lon else null
            }
        } finally { connection.disconnect() }
    }

    fun geocode(address: String): Pair<Double, Double> {
        val encoded = URLEncoder.encode(address, "UTF-8")
        val photonUrl = "${PHOTON}?q=$encoded&limit=1&lang=ru"
        runCatching {
            val connection = URL(photonUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (connection.responseCode !in 200..299) throw Exception("Photon unavailable")
                val features = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("features") ?: JSONArray()
                val coords = features.optJSONObject(0)?.optJSONObject("geometry")?.optJSONArray("coordinates") ?: throw Exception("Адрес не найден")
                val lon = coords.optDouble(0, Double.NaN); val lat = coords.optDouble(1, Double.NaN)
                if (lat.isFinite() && lon.isFinite()) lat to lon
                else throw Exception("Photon returned invalid coordinates")
            } finally { connection.disconnect() }
        }.getOrNull()?.let { return it }

        val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&limit=1&accept-language=ru"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 9000
            connection.readTimeout = 14000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode !in 200..299) throw Exception("Не удалось определить адрес. Укажи город, улицу и номер дома.")
            val items = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            if (items.length() == 0) throw Exception("Адрес не найден. Уточни город, улицу и номер дома.")
            val item = items.getJSONObject(0)
            val lat = item.optString("lat").toDoubleOrNull() ?: throw Exception("Некорректная широта")
            val lon = item.optString("lon").toDoubleOrNull() ?: throw Exception("Некорректная долгота")
            return lat to lon
        } finally { connection.disconnect() }
    }

    fun reverseGeocode(lat: Double, lon: Double): String? {
        val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=jsonv2&accept-language=ru"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode !in 200..299) return null
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optString("display_name").takeIf { it.isNotBlank() }
        } finally { connection.disconnect() }
    }

    fun save(ctx: Context, name: String, address: String, lat: Double, lon: Double) {
        val prefs = ctx.getSharedPreferences("custom", Context.MODE_PRIVATE)
        val items = runCatching { JSONArray(prefs.getString("items", "[]") ?: "[]") }.getOrDefault(JSONArray())
        items.put(JSONObject().put("n", name).put("a", address).put("lat", lat).put("lon", lon))
        prefs.edit().putString("items", items.toString()).apply()
    }

    private fun custom(ctx: Context, lat: Double, lon: Double): List<Store> {
        val items = runCatching {
            JSONArray(ctx.getSharedPreferences("custom", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        return (0 until items.length()).mapNotNull { index ->
            runCatching {
                val item = items.getJSONObject(index)
                val itemLat = item.getDouble("lat"); val itemLon = item.getDouble("lon")
                Store(item.getString("n"), item.getString("a"), itemLat, itemLon, distance(lat, lon, itemLat, itemLon))
            }.getOrNull()
        }
    }

    fun distance(a: Double, b: Double, c: Double, d: Double): Double {
        val p = Math.toRadians(c - a); val q = Math.toRadians(d - b)
        val x = sin(p / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(q / 2).pow(2)
        return 12742000.0 * asin(sqrt(x.coerceIn(0.0, 1.0)))
    }
}
