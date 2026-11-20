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
    private const val USER_AGENT = "MagazinRyadom/1.4 (Android)"

    fun search(lat: Double, lon: Double, term: String, ctx: Context): List<Store> {
        val query = term.trim()
        require(query.isNotEmpty()) { "Введи название магазина или категорию." }
        val remote = photonSearch(query, lat, lon, 30)
        val local = custom(ctx, lat, lon).filter {
            it.name.contains(query, true) || it.address.contains(query, true) || query.equals("магазины", true)
        }
        return (remote + local)
            .distinctBy { "${it.lat},${it.lon}" }
            .sortedBy { it.distance }
            .take(50)
    }

    fun suggest(lat: Double, lon: Double, term: String): List<Store> {
        val query = term.trim()
        if (query.length < 2) return emptyList()
        return photonSearch(query, lat, lon, 7)
    }

    private fun photonSearch(query: String, lat: Double, lon: Double, limit: Int): List<Store> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("${PHOTON}?q=${encoded}&lat=${lat}&lon=${lon}&limit=${limit}&lang=ru")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw Exception("Сервис поиска временно недоступен (HTTP $code).")
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val features = root.optJSONArray("features") ?: JSONArray()
            val output = mutableListOf<Store>()
            for (index in 0 until features.length()) {
                val feature = features.optJSONObject(index) ?: continue
                val geometry = feature.optJSONObject("geometry") ?: continue
                val coordinates = geometry.optJSONArray("coordinates") ?: continue
                if (coordinates.length() < 2) continue
                val properties = feature.optJSONObject("properties") ?: JSONObject()
                val itemName = properties.optString("name").ifBlank {
                    properties.optString("street").ifBlank { properties.optString("type").ifBlank { "Место" } }
                }
                val addressParts = listOf(
                    properties.optString("street"),
                    properties.optString("housenumber"),
                    properties.optString("district"),
                    properties.optString("city"),
                    properties.optString("state")
                ).filter { it.isNotBlank() }.distinct()
                val address = addressParts.joinToString(", ").ifBlank {
                    properties.optString("country").ifBlank { "Адрес не указан в OpenStreetMap" }
                }
                val itemLon = coordinates.optDouble(0, Double.NaN)
                val itemLat = coordinates.optDouble(1, Double.NaN)
                if (!itemLat.isFinite() || !itemLon.isFinite()) continue
                output.add(Store(itemName, address, itemLat, itemLon, distance(lat, lon, itemLat, itemLon)))
            }
            return output
        } finally {
            connection.disconnect()
        }
    }

    fun geocode(address: String): Pair<Double, Double> {
        val encoded = URLEncoder.encode(address, "UTF-8")
        val connection = (URL("${PHOTON}?q=$encoded&limit=1&lang=ru").openConnection() as HttpURLConnection)
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (connection.responseCode !in 200..299) throw Exception("Сервис адресов временно недоступен.")
            val features = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONArray("features") ?: JSONArray()
            if (features.length() == 0) throw Exception("Адрес не найден. Укажи улицу, дом и город.")
            val coordinates = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
            return coordinates.getDouble(1) to coordinates.getDouble(0)
        } finally {
            connection.disconnect()
        }
    }

    fun save(ctx: Context, name: String, address: String, lat: Double, lon: Double) {
        val preferences = ctx.getSharedPreferences("custom", Context.MODE_PRIVATE)
        val items = try { JSONArray(preferences.getString("items", "[]")) } catch (_: Exception) { JSONArray() }
        items.put(JSONObject().put("n", name).put("a", address).put("lat", lat).put("lon", lon))
        preferences.edit().putString("items", items.toString()).apply()
    }

    private fun custom(ctx: Context, lat: Double, lon: Double): List<Store> {
        val items = try {
            JSONArray(ctx.getSharedPreferences("custom", Context.MODE_PRIVATE).getString("items", "[]"))
        } catch (_: Exception) { JSONArray() }
        return (0 until items.length()).mapNotNull { index ->
            try {
                val item = items.getJSONObject(index)
                val itemLat = item.getDouble("lat")
                val itemLon = item.getDouble("lon")
                Store(item.getString("n"), item.getString("a"), itemLat, itemLon, distance(lat, lon, itemLat, itemLon))
            } catch (_: Exception) { null }
        }
    }

    fun distance(a: Double, b: Double, c: Double, d: Double): Double {
        val p = Math.toRadians(c - a)
        val q = Math.toRadians(d - b)
        val x = sin(p / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(q / 2).pow(2)
        return 12742000.0 * asin(sqrt(x))
    }
}
