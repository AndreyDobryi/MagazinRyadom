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
    private const val USER_AGENT = "MagazinRyadom/1.8.4 (Android; OpenStreetMap client)"
    private const val SEARCH_RADIUS = 3000.0
    private const val MAX_RESULTS = 100
    // Prefer the VK Maps / Mail.ru public Overpass instance, which explicitly
    // welcomes app usage. Keep other instances as sequential fallbacks only.
    private val overpassEndpoints = listOf(
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass-api.de/api/interpreter"
    )

    private val networkAliases = mapOf(
        "пятерочка" to listOf("Пятёрочка", "Пятерочка"),
        "магнит" to listOf("Магнит", "Magnit"),
        "магнит косметик" to listOf("Магнит Косметик", "Магнит Косметик"),
        "магнит аптека" to listOf("Магнит Аптека"),
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
        "спортмастер" to listOf("Спортмастер", "Sportmaster"),
        "детский мир" to listOf("Детский мир", "Детский Мир", "Detsky Mir"),
        "fix price" to listOf("Fix Price", "Фикс Прайс"),
        "фикс прайс" to listOf("Fix Price", "Фикс Прайс"),
        "dns" to listOf("DNS", "ДНС"),
        "м видео" to listOf("М.Видео", "М Видео", "M.Video"),
        "эльдорадо" to listOf("Эльдорадо", "Eldorado"),
        "золотое яблоко" to listOf("Золотое Яблоко", "Gold Apple"),
        "подружка" to listOf("Подружка", "Podruzhka"),
        "леруа мерлен" to listOf("Лемана ПРО", "Леруа Мерлен", "Leroy Merlin"),
        "лемана про" to listOf("Лемана ПРО", "Леруа Мерлен", "Leroy Merlin"),
        "obi" to listOf("OBI", "ОБИ"),
        "петрович" to listOf("Петрович", "Petrovich"),
        "максидом" to listOf("Максидом", "Maxidom"),
        "касторама" to listOf("Castorama", "Касторама"),
        "яндекс лавка" to listOf("Яндекс Лавка", "Yandex Lavka"),
        "wildberries" to listOf("Wildberries", "Вайлдберриз"),
        "вайлдберриз" to listOf("Wildberries", "Вайлдберриз"),
        "ozon" to listOf("Ozon", "Озон"),
        "озон" to listOf("Ozon", "Озон"),
        "kari" to listOf("Kari", "Кари"),
        "глория джинс" to listOf("Gloria Jeans", "Глория Джинс"),
        "familia" to listOf("Familia", "Фамилия"),
        "летуаль" to listOf("Л'Этуаль", "Летуаль", "L'Etoile"),
        "рив гош" to listOf("РИВ ГОШ", "Rive Gauche"),
        "читай город" to listOf("Читай-город", "Читай город"),
        "буквоед" to listOf("Буквоед", "Bookvoed"),
        "комус" to listOf("Комус", "Komus"),
        "все инструменты" to listOf("ВсеИнструменты.ру", "Все инструменты"),
        "585 золотой" to listOf("585 Золотой", "585 Gold"),
        "sunlight" to listOf("SUNLIGHT", "Санлайт"),
        "санлайт" to listOf("SUNLIGHT", "Санлайт"),
        "леонардо" to listOf("Леонардо", "Leonardo"),
        "четыре лапы" to listOf("Четыре Лапы", "4 Лапы"),
        "зоозавр" to listOf("Зоозавр", "Zoozavr"),
        "petshop" to listOf("Petshop", "Петшоп"),
        "эконика" to listOf("Эконика", "Ekonika"),
        "befree" to listOf("Befree", "Бифри"),
        "sela" to listOf("Sela", "Села"),
        "henderson" to listOf("HENDERSON", "Хендерсон"),
        "перекресток экспресс" to listOf("Перекрёсток Экспресс", "Перекресток Экспресс"),
        "горздрав" to listOf("Горздрав", "Gorzdrav"),
        "ригла" to listOf("Ригла", "Rigla"),
        "36 6" to listOf("36,6", "36.6"),
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
        require(!isRestrictedQuery(query)) { "Этот тип торговой точки недоступен для поиска." }
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
            "Местоположение не определено. Обнови GPS или задай точку вручную."
        }

        // Пользовательские точки остаются дополнительным источником, но не заменяют поиск филиалов сети.
        val local = custom(ctx, lat, lon)
            .filter { matchesQuery(it, query) && it.distance <= SEARCH_RADIUS }

        // Overpass — основной источник именно объектов OSM. Photon не используем
        // как каталог филиалов: это геокодер, его выдача может быть неполной.
        var osmResponded = false
        var osmError: String? = null
        val osmResults = try {
            overpassSearch(query, lat, lon, SEARCH_RADIUS.toInt()).also { osmResponded = true }
        } catch (e: Exception) {
            osmError = e.message ?: "Overpass: ошибка подключения"
            emptyList()
        }

        // Дополняем OSM-выдачу геокодером Photon: иногда в Overpass отсутствует
        // ближайший филиал, хотя более дальний уже размечен. Оставляем только
        // результаты, совпадающие с запросом и попадающие в радиус поиска.
        val photonResults = runCatching {
            photonSearch(query, lat, lon, MAX_RESULTS)
        }.getOrDefault(emptyList())
            .filter { it.distance.isFinite() && it.distance <= SEARCH_RADIUS }
            .filter { matchesQuery(it, query) }
            .filterNot { isRestrictedStore(it) }

        // Nominatim остаётся последним резервом, если оба основных источника пусты.
        var nominatimError: String? = null
        val fallbackResults = if (osmResults.isEmpty() && photonResults.isEmpty()) {
            try {
                nominatimSearch(query, lat, lon)
            } catch (e: Exception) {
                nominatimError = e.message ?: "Nominatim: ошибка подключения"
                emptyList()
            }
        } else emptyList()

        val combined = (osmResults + photonResults + fallbackResults + local)
            .filter { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
            .filterNot { isRestrictedStore(it) }
            .distinctBy {
                "${String.format(Locale.US, "%.5f", it.lat)},${String.format(Locale.US, "%.5f", it.lon)}"
            }
            // Recalculate every candidate from the exact GPS coordinates used for this search.
            // Do not trust distance values cached by an upstream provider or custom-point record.
            .map { it.copy(distance = distance(lat, lon, it.lat, it.lon)) }
            .filter { it.distance.isFinite() && it.distance <= SEARCH_RADIUS }
            .sortedWith(compareBy<Store> { it.distance }.thenBy { it.name.lowercase(Locale.ROOT) })
            .take(MAX_RESULTS)

        if (combined.isNotEmpty()) return combined

        if (!osmResponded && osmResults.isEmpty() && fallbackResults.isEmpty() && local.isEmpty()) {
            val details = listOfNotNull(osmError, nominatimError).distinct().joinToString("; ")
            throw Exception(
                if (details.isBlank()) "Сервисы поиска магазинов временно недоступны. Попробуй ещё раз."
                else "Поиск магазинов временно недоступен. Причины: $details"
            )
        }
        return emptyList()
    }

    private fun matchesQuery(store: Store, query: String): Boolean {
        val aliases = aliasesFor(query).map(::normalize)
        // Match the retail brand/name only. Street or city text must never make a branch a false match.
        val haystack = normalize(store.name)
        return aliases.any { it.isNotBlank() && (haystack.contains(it) || it.split(' ').all { word -> word in haystack }) }
    }

    fun suggest(term: String): List<Store> {
        if (term.trim().length < 2 || isRestrictedQuery(term)) return emptyList()
        return runCatching { photonSearch(term.trim(), null, null, 6) }
            .getOrDefault(emptyList()).filterNot { isRestrictedStore(it) }
    }

    private fun isRestrictedQuery(query: String): Boolean {
        val q = normalize(query).replace(" ", "")
        return listOf(
            "красноебелое", "кб", "алкоголь", "алкомаркет", "винныймагазин",
            "liquorstore", "alcoholshop", "liquor"
        ).any { q.contains(it) }
    }

    private fun isRestrictedStore(store: Store): Boolean {
        val name = normalize(store.name)
        return listOf("красное и белое", "красное белое", "алкомаркет", "алкогольный магазин")
            .any { name.contains(normalize(it)) }
    }

    /**
     * Ограниченный резервный поиск POI. Выполняется один запрос на действие
     * пользователя, только если Overpass не вернул филиалы.
     */
    private fun nominatimSearch(query: String, lat: Double, lon: Double): List<Store> {
        val latDelta = SEARCH_RADIUS / 111320.0
        val lonScale = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.15)
        val lonDelta = SEARCH_RADIUS / (111320.0 * lonScale)
        val left = lon - lonDelta
        val right = lon + lonDelta
        val top = lat + latDelta
        val bottom = lat - latDelta
        val encoded = URLEncoder.encode(aliasesFor(query).first(), "UTF-8")
        val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&limit=50" +
            "&viewbox=$left,$top,$right,$bottom&bounded=1&addressdetails=1&accept-language=ru"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw Exception("Nominatim HTTP $code")
            val items = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val found = mutableListOf<Store>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val itemLat = item.optString("lat").toDoubleOrNull() ?: continue
                val itemLon = item.optString("lon").toDoubleOrNull() ?: continue
                if (!itemLat.isFinite() || !itemLon.isFinite()) continue
                val display = item.optString("display_name")
                val name = item.optString("name").ifBlank { display.substringBefore(",").trim() }
                if (name.isBlank()) continue
                val address = display.ifBlank { "Адрес не указан" }
                val distance = distance(lat, lon, itemLat, itemLon)
                val candidate = Store(name, address, itemLat, itemLon, distance)
                if (distance <= SEARCH_RADIUS && matchesQuery(candidate, query) && !isRestrictedStore(candidate)) {
                    found += candidate
                }
            }
            return found.sortedBy { it.distance }
        } finally {
            connection.disconnect()
        }
    }

    private fun photonSearch(query: String, lat: Double?, lon: Double?, limit: Int): List<Store> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val safeLimit = limit.coerceIn(1, MAX_RESULTS)
        val urls = linkedSetOf<String>()
        if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
            // Location bias keeps results close to the user's GPS point.
            urls += "${PHOTON}?q=$encoded&lat=$lat&lon=$lon&zoom=14&limit=$safeLimit&lang=ru"
            urls += "${PHOTON}?q=$encoded&lat=$lat&lon=$lon&limit=$safeLimit&lang=ru"
        }
        urls += "${PHOTON}?q=$encoded&limit=$safeLimit&lang=ru"

        var lastError: Exception? = null
        for (url in urls) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 6000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/geo+json, application/json")
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw Exception("Photon HTTP $code")
                }
                return parsePhotonResults(
                    connection.inputStream.bufferedReader().use { it.readText() }, lat, lon
                )
            } catch (e: Exception) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }
        throw Exception(lastError?.message ?: "Photon: нет ответа от сервера", lastError)
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
                  nwr(around:$radius,$lat,$lon)["brand"~"$regex",i];
                  nwr(around:$radius,$lat,$lon)["brand:ru"~"$regex",i];
                  nwr(around:$radius,$lat,$lon)["operator"~"$regex",i];
                  nwr(around:$radius,$lat,$lon)["name"~"$regex",i];
                  nwr(around:$radius,$lat,$lon)["name:ru"~"$regex",i];
                );
                out center tags;
            """.trimIndent()
        }

        var lastError: Exception? = null
        var successfulResponseReceived = false
        val endpointErrors = mutableListOf<String>()
        val allFound = mutableListOf<Store>()
        for (endpoint in overpassEndpoints) {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 6000
                connection.readTimeout = 28000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val payload = "data=" + URLEncoder.encode(queryText, "UTF-8")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val label = when (code) {
                        429 -> "ограничение частоты запросов (HTTP 429)"
                        502, 503, 504 -> "сервер перегружен (HTTP $code)"
                        403, 406 -> "сервер отклонил запрос (HTTP $code)"
                        else -> "HTTP $code"
                    }
                    throw Exception(label)
                }
                val elements = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("elements") ?: JSONArray()
                successfulResponseReceived = true
                for (i in 0 until elements.length()) {
                    val element = elements.optJSONObject(i) ?: continue
                    val tags = element.optJSONObject("tags") ?: continue
                    if (!matchesAnyTag(tags, query)) continue
                    val center = element.optJSONObject("center")
                    val itemLat = center?.optDouble("lat", Double.NaN) ?: element.optDouble("lat", Double.NaN)
                    val itemLon = center?.optDouble("lon", Double.NaN) ?: element.optDouble("lon", Double.NaN)
                    if (!itemLat.isFinite() || !itemLon.isFinite()) continue
                    // Prefer the tag that actually matches the requested retail chain.
                    // Some OSM objects have a generic building/site name but the correct
                    // store brand is stored separately in brand/operator.
                    val matchingName = listOf("brand:ru", "brand", "name:ru", "name", "operator", "official_name", "alt_name")
                        .map { tags.optString(it) }
                        .firstOrNull { value ->
                            val normalized = normalize(value)
                            value.isNotBlank() && aliasesFor(query).any { alias ->
                                val key = normalize(alias)
                                normalized == key || normalized.contains(key) || key.contains(normalized)
                            }
                        }
                    val name = matchingName
                        ?: listOf("name:ru", "name", "brand:ru", "brand", "operator", "official_name")
                            .map { tags.optString(it) }.firstOrNull { it.isNotBlank() }
                        ?: query
                    val address = listOf(
                        tags.optString("addr:city"), tags.optString("addr:street"), tags.optString("addr:housenumber")
                    ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Адрес уточняется по карте" }
                    val distance = distance(lat, lon, itemLat, itemLon)
                    if (distance <= radius) allFound += Store(name, address, itemLat, itemLon, distance)
                }
                // Объединяем ответы всех доступных зеркал: первое зеркало может быть неполным.
            } catch (e: Exception) {
                lastError = e
                endpointErrors += "${endpoint.substringAfter("https://").substringBefore("/")}: ${e.message ?: "ошибка подключения"}"
            } finally { connection.disconnect() }
        }
        if (successfulResponseReceived) return allFound.distinctBy { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }.sortedBy { it.distance }
        val detail = endpointErrors.distinct().joinToString("; ")
        throw Exception(
            if (detail.isBlank()) "Не удалось получить ответ от серверов поиска магазинов. Попробуй позже."
            else "Сервисы поиска магазинов временно недоступны. Причины: $detail"
        , lastError)
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

    /** Пешеходная геометрия с повторными попытками при временной недоступности сервера. */
    fun walkingRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<Pair<Double, Double>> {
        require(fromLat.isFinite() && fromLon.isFinite() && toLat.isFinite() && toLon.isFinite()) { "Некорректные координаты маршрута." }
        require(fromLat in -90.0..90.0 && toLat in -90.0..90.0 && fromLon in -180.0..180.0 && toLon in -180.0..180.0) { "Координаты маршрута вне допустимого диапазона." }
        val url = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true"
        var lastError: Exception? = null
        for (attempt in 0..2) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw Exception("Сервис маршрутов ответил HTTP $code.")
                val response = JSONObject(body)
                val status = response.optString("code")
                if (status.isNotBlank() && status != "Ok") {
                    val message = response.optString("message").ifBlank { status }
                    throw Exception("Пеший маршрут не найден: $message")
                }
                val routes = response.optJSONArray("routes") ?: throw Exception("Сервис маршрутов вернул пустой ответ.")
                if (routes.length() == 0) throw Exception("Между этими точками не найден пеший маршрут.")
                val coordinates = routes.optJSONObject(0)?.optJSONObject("geometry")?.optJSONArray("coordinates")
                    ?: throw Exception("В ответе сервиса отсутствует геометрия маршрута.")
                val points = (0 until coordinates.length()).mapNotNull { i ->
                    val pair = coordinates.optJSONArray(i) ?: return@mapNotNull null
                    if (pair.length() < 2) return@mapNotNull null
                    val lon = pair.optDouble(0, Double.NaN)
                    val lat = pair.optDouble(1, Double.NaN)
                    if (lat.isFinite() && lon.isFinite()) lat to lon else null
                }
                if (points.size < 2) throw Exception("Сервис вернул слишком мало точек маршрута.")
                return points
            } catch (e: Exception) {
                lastError = e
                // Повторяем только временные сетевые/серверные ошибки; NoRoute повторять бессмысленно.
                val message = e.message.orEmpty()
                if (message.contains("маршрут не найден", ignoreCase = true) || message.contains("между этими точками", ignoreCase = true)) break
                if (attempt < 2) {
                    try { Thread.sleep(700L * (attempt + 1)) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw Exception(lastError?.message ?: "Не удалось связаться с сервисом пеших маршрутов после трёх попыток.", lastError)
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
