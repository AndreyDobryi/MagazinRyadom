package ru.magazinryadom

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    // Список обычных продуктовых сетей. Поиск ограничен продуктовыми магазинами.
    private val stores = listOf("Пятёрочка", "Магнит", "Перекрёсток", "Лента", "Дикси", "Лидл", "Aldi", "REWE", "EDEKA")
    private lateinit var selected: Spinner
    private lateinit var status: TextView
    private lateinit var distance: TextView
    private lateinit var mapButton: Button
    private var nearestLat = 0.0
    private var nearestLon = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("store", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            setBackgroundColor(0xFF101010.toInt())
        }
        fun label(value: String, size: Float, color: Int = 0xFFFFFFFF.toInt()) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); setPadding(0, 12, 0, 12)
        }
        fun add(view: android.view.View) {
            layout.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        add(label("Магазин рядом", 28f))
        add(label("Выбери продуктовую сеть. Приложение найдёт ближайшую отмеченную точку.", 15f, 0xFFAAAAAA.toInt()))
        selected = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, stores)
            setSelection(stores.indexOf(prefs.getString("name", stores[0])).coerceAtLeast(0))
        }
        add(selected)
        val findButton = Button(this).apply { text = "Найти ближайший магазин" }
        add(findButton)
        distance = label(prefs.getString("distance", "Расстояние пока не рассчитано") ?: "Расстояние пока не рассчитано", 25f)
        add(distance)
        status = label("Поиск использует данные OpenStreetMap. Точность зависит от заполненности карты.", 14f, 0xFFAAAAAA.toInt())
        add(status)
        mapButton = Button(this).apply { text = "Открыть маршрут"; isEnabled = false }
        add(mapButton)

        findButton.setOnClickListener {
            val name = selected.selectedItem.toString()
            prefs.edit().putString("name", name).apply()
            requestOrSearch(name)
        }
        mapButton.setOnClickListener {
            if (nearestLat != 0.0 && nearestLon != 0.0) {
                val uri = Uri.parse("geo:$nearestLat,$nearestLon?q=$nearestLat,$nearestLon(${Uri.encode("Ближайший магазин")})")
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
        setContentView(ScrollView(this).apply { addView(layout) })

        // Восстановить последнюю найденную точку для кнопки маршрута.
        nearestLat = prefs.getString("lat", "0")?.toDoubleOrNull() ?: 0.0
        nearestLon = prefs.getString("lon", "0")?.toDoubleOrNull() ?: 0.0
        mapButton.isEnabled = nearestLat != 0.0 && nearestLon != 0.0
    }

    private fun requestOrSearch(name: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
        } else findNearest(name)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            val name = getSharedPreferences("store", MODE_PRIVATE).getString("name", stores[0]) ?: stores[0]
            findNearest(name)
        } else status.text = "Разреши доступ к местоположению, чтобы найти магазин рядом."
    }

    private fun findNearest(name: String) {
        status.text = "Определяю местоположение…"
        mapButton.isEnabled = false
        val client = LocationServices.getFusedLocationProviderClient(this)
        try {
            client.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { current ->
                    if (current != null) searchOpenStreetMap(name, current) else client.lastLocation.addOnSuccessListener { last ->
                        if (last == null) status.text = "Не удалось получить координаты. Включи геолокацию и попробуй ещё раз."
                        else searchOpenStreetMap(name, last)
                    }
                }
                .addOnFailureListener { status.text = "Не удалось получить местоположение: ${it.localizedMessage}" }
        } catch (e: SecurityException) {
            status.text = "Нет разрешения на геолокацию. Разреши доступ в настройках Android."
        }
    }

    private fun searchOpenStreetMap(name: String, location: Location) {
        status.text = "Ищу точки поблизости…"
        thread {
            try {
                val query = """[out:json][timeout:25];(node[shop](around:7000,${location.latitude},${location.longitude});way[shop](around:7000,${location.latitude},${location.longitude}););out center tags;"""
                val url = URL("https://overpass-api.de/api/interpreter?data=" + java.net.URLEncoder.encode(query, "UTF-8"))
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000; requestMethod = "GET"
                    setRequestProperty("User-Agent", "MagazinRyadomAndroid/1.0")
                }
                val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("elements")
                var bestDistance = Float.MAX_VALUE
                var bestLat = 0.0; var bestLon = 0.0; var bestName = ""
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val tags = item.optJSONObject("tags") ?: continue
                    val shopName = tags.optString("name", "")
                    val brand = tags.optString("brand", "")
                    val operator = tags.optString("operator", "")
                    val haystack = "$shopName $brand $operator".lowercase()
                    if (!haystack.contains(name.lowercase())) continue
                    val lat = if (item.has("lat")) item.getDouble("lat") else item.optJSONObject("center")?.optDouble("lat") ?: continue
                    val lon = if (item.has("lon")) item.getDouble("lon") else item.optJSONObject("center")?.optDouble("lon") ?: continue
                    val result = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, lat, lon, result)
                    if (result[0] < bestDistance) { bestDistance = result[0]; bestLat = lat; bestLon = lon; bestName = shopName.ifBlank { brand.ifBlank { name } } }
                }
                runOnUiThread {
                    if (bestDistance == Float.MAX_VALUE) {
                        status.text = "«$name» не найдена в OpenStreetMap в радиусе 7 км. Попробуй позже или проверь карту."
                        distance.text = "Нет результатов"
                    } else {
                        nearestLat = bestLat; nearestLon = bestLon
                        val shownDistance = if (bestDistance < 1000) "${bestDistance.toInt()} м" else String.format("%.1f км", bestDistance / 1000f)
                        distance.text = shownDistance
                        status.text = "$bestName • ближайшая отмеченная точка"
                        mapButton.isEnabled = true
                        getSharedPreferences("store", MODE_PRIVATE).edit()
                            .putString("distance", shownDistance).putString("address", bestName)
                            .putString("lat", "$bestLat").putString("lon", "$bestLon").apply()
                        StoreWidget.update(this)
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка поиска. Проверь интернет и попробуй ещё раз." }
            }
        }
    }
}
