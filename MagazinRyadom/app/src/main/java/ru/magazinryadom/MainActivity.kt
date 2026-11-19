package ru.magazinryadom

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var queryInput: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private var currentLocation: Location? = null
    private val requestCode = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        if (hasLocationPermission()) getLocationAndSearchSaved() else requestLocationPermission()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(0xFF101114.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = TextView(this).apply {
            text = "Магазин рядом"
            textSize = 27f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Найди нужное место поблизости"
            textSize = 14f
            setTextColor(0xFFAAADB5.toInt())
            setPadding(0, dp(5), 0, dp(18))
        }
        queryInput = EditText(this).apply {
            hint = "Название или категория: например, DNS"
            textSize = 15f
            singleLine = true
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF777B85.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF22252B.toInt())
        }
        val searchButton = Button(this).apply {
            text = "Найти рядом"
            setOnClickListener { findStores() }
        }
        val refreshButton = Button(this).apply {
            text = "Обновить местоположение"
            setOnClickListener { getLocationAndSearchSaved() }
        }
        status = TextView(this).apply {
            text = "Для поиска нужен доступ к местоположению"
            textSize = 13f
            setTextColor(0xFFD6B45A.toInt())
            setPadding(0, dp(12), 0, dp(10))
        }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(heading)
        content.addView(subtitle)
        content.addView(queryInput, matchWrap())
        content.addView(searchButton, matchWrap())
        content.addView(refreshButton, matchWrap())
        content.addView(status)
        content.addView(results)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun findStores() {
        val location = currentLocation
        if (location == null) {
            status.text = "Сначала определяем местоположение…"
            getLocationAndSearchSaved()
            return
        }
        val query = queryInput.text.toString().trim()
        if (query.isBlank()) {
            status.text = "Введи название магазина или категорию товаров"
            return
        }
        status.text = "Ищем магазины поблизости…"
        results.removeAllViews()
        executor.execute {
            try {
                val stores = StoreSearch.search(location.latitude, location.longitude, query)
                runOnUiThread {
                    if (stores.isEmpty()) {
                        status.text = "Ничего не найдено. Попробуй другое название или категорию."
                    } else {
                        status.text = "Найдено: ${stores.size}. Выбери нужный магазин."
                        stores.forEachIndexed { index, store -> addStoreCard(store, index == 0) }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Не удалось выполнить поиск. Проверь интернет и попробуй ещё раз." }
            }
        }
    }

    private fun addStoreCard(store: Store, nearest: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF202329.toInt())
        }
        val title = TextView(this).apply {
            text = (if (nearest) "БЛИЖАЙШИЙ · " else "") + store.name
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val address = TextView(this).apply {
            text = store.address
            textSize = 12f
            setTextColor(0xFFB7BBC3.toInt())
            setPadding(0, dp(4), 0, dp(3))
        }
        val distance = TextView(this).apply {
            text = formatDistance(store.distanceMeters)
            textSize = 13f
            setTextColor(0xFFD6B45A.toInt())
        }
        val choose = Button(this).apply {
            text = "Выбрать и открыть маршрут"
            setOnClickListener { selectStore(store) }
        }
        card.addView(title)
        card.addView(address)
        card.addView(distance)
        card.addView(choose)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(10)
        results.addView(card, lp)
    }

    private fun selectStore(store: Store) {
        val user = currentLocation ?: return
        val distanceText = formatDistance(store.distanceMeters)
        val mapFile = File(cacheDir, "widget_map.png")
        status.text = "Сохраняем выбранный магазин для виджета…"
        executor.execute {
            val bitmap = MapTile.create(user.latitude, user.longitude, store.lat, store.lon)
            if (bitmap != null) {
                mapFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                bitmap.recycle()
            }
            getSharedPreferences("store_data", MODE_PRIVATE).edit()
                .putString("name", store.name)
                .putString("address", store.address)
                .putString("distance", distanceText)
                .putString("map_path", if (mapFile.exists()) mapFile.absolutePath else null)
                .putString("store_lat", store.lat.toString())
                .putString("store_lon", store.lon.toString())
                .putString("user_lat", user.latitude.toString())
                .putString("user_lon", user.longitude.toString())
                .apply()
            runOnUiThread {
                StoreWidget.updateAll(this)
                status.text = "Выбран: ${store.name}. Виджет обновлён."
                val uri = "https://www.openstreetmap.org/directions?engine=fossgis_osrm_foot&route=${user.latitude}%2C${user.longitude}%3B${store.lat}%2C${store.lon}"
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
            }
        }
    }

    private fun getLocationAndSearchSaved() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        status.text = "Определяем местоположение…"
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        currentLocation = location
                        status.text = "Местоположение определено. Введи магазин или категорию."
                        val savedQuery = queryInput.text.toString()
                        if (savedQuery.isNotBlank()) findStores()
                    } else {
                        locationClient.lastLocation.addOnSuccessListener { last ->
                            if (last != null) {
                                currentLocation = last
                                status.text = "Используем последнее известное местоположение."
                                if (queryInput.text.isNotBlank()) findStores()
                            } else {
                                status.text = "Координаты пока недоступны. Проверь геолокацию и включи точное местоположение для приложения."
                            }
                        }.addOnFailureListener {
                            status.text = "Не удалось получить координаты. Проверь разрешения геолокации."
                        }
                    }
                }
                .addOnFailureListener {
                    status.text = "Ошибка определения координат. Проверь разрешения и настройки геолокации."
                }
        } catch (_: SecurityException) {
            status.text = "Нет разрешения на геолокацию. Разреши доступ в настройках приложения."
        }
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), requestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) getLocationAndSearchSaved()
            else status.text = "Без разрешения на геолокацию поиск ближайших магазинов невозможен."
        }
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.roundToInt()} м от вас"
        else String.format(Locale.getDefault(), "%.1f км от вас", meters / 1000.0)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
}
