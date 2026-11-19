package ru.magazinryadom

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.math.*

object StoreSearch {
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"

    fun search(lat: Double, lon: Double, rawQuery: String): List<Store> {
        val query = rawQuery.trim()
        val category = categoryFilter(query)
        val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
        val around = "around:6000,$lat,$lon"

        val selector = when {
            category != null -> category.replace("__LAT__", lat.toString()).replace("__LON__", lon.toString())
            query.isBlank() || query.equals("магазин", true) ->
                """nwr["shop"]($around);"""
            else ->
                """nwr["shop"]["name"~"$escaped",i]($around);
                   nwr["shop"]["brand"~"$escaped",i]($around);
                   nwr["shop"]["operator"~"$escaped",i]($around);
                   nwr["name"~"$escaped",i]($around);"""
        }

        val overpassQuery = """
            [out:json][timeout:25];
            (
              $selector
              ${if (category == null && query.isNotBlank() && !query.equals("магазин", true)) "" else ""}
            );
            out center tags;
        """.trimIndent()

        val encoded = URLEncoder.encode(overpassQuery, "UTF-8")
        val connection = URL("$OVERPASS?data=$encoded").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12000
        connection.readTimeout = 30000
        connection.setRequestProperty("User-Agent", "MagazinRyadom/1.1 (Android app)")
        try {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val elements = JSONObject(text).optJSONArray("elements") ?: return emptyList()
            val found = mutableListOf<Store>()
            for (i in 0 until elements.length()) {
                val item = elements.optJSONObject(i) ?: continue
                val tags = item.optJSONObject("tags") ?: JSONObject()
                val name = tags.optString("name").ifBlank {
                    tags.optString("brand").ifBlank { tags.optString("shop").ifBlank { "Магазин" } }
                }
                val itemLat = if (item.has("lat")) item.optDouble("lat") else item.optJSONObject("center")?.optDouble("lat") ?: continue
                val itemLon = if (item.has("lon")) item.optDouble("lon") else item.optJSONObject("center")?.optDouble("lon") ?: continue
                if (!itemLat.isFinite() || !itemLon.isFinite()) continue
                val addressParts = listOf(
                    tags.optString("addr:street"),
                    tags.optString("addr:housenumber"),
                    tags.optString("addr:city")
                ).filter { it.isNotBlank() }
                val address = if (addressParts.isEmpty()) "Адрес не указан в OpenStreetMap" else addressParts.joinToString(", ")
                val distance = distanceMeters(lat, lon, itemLat, itemLon)
                found.add(Store(name, address, itemLat, itemLon, distance))
            }
            return found.distinctBy { "${it.lat},${it.lon}" }.sortedBy { it.distanceMeters }.take(30)
        } finally {
            connection.disconnect()
        }
    }

    private fun categoryFilter(query: String): String? {
        val q = query.lowercase()
        return when {
            listOf("аптека", "аптеки").any { q.contains(it) } -> """nwr["amenity"="pharmacy"](around:6000,%LAT%,%LON%);"""
            listOf("продукты", "продуктовый", "супермаркет", "еда").any { q.contains(it) } ->
                """nwr["shop"~"supermarket|convenience|greengrocer|grocery"](around:6000,%LAT%,%LON%);"""
            listOf("одежда", "обувь").any { q.contains(it) } ->
                """nwr["shop"~"clothes|shoes"](around:6000,%LAT%,%LON%);"""
            listOf("электроника", "техника", "компьютер").any { q.contains(it) } ->
                """nwr["shop"~"electronics|computer|mobile_phone"](around:6000,%LAT%,%LON%);"""
            listOf("зоотовары", "зоомагазин").any { q.contains(it) } ->
                """nwr["shop"="pet"](around:6000,%LAT%,%LON%);"""
            listOf("косметика").any { q.contains(it) } ->
                """nwr["shop"~"cosmetics|beauty"](around:6000,%LAT%,%LON%);"""
            else -> null
        }?.replace("%LAT%", "__LAT__")?.replace("%LON%", "__LON__")
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val x = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2.0)
        return 2 * r * asin(sqrt(x))
    }
}
