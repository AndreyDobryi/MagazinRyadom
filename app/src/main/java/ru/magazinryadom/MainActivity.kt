package ru.magazinryadom

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var input: EditText
    private lateinit var info: TextView
    private lateinit var results: LinearLayout
    private lateinit var map: WebView
    private var currentLocation: Location? = null
    private val locationRequestCode = 22

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (hasLocationPermission()) locate() else requestLocationPermission()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(0xFF101114.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(this).apply {
            text = "Магазин рядом"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        input = EditText(this).apply {
            hint = "Название или категория, например DNS"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF999999.toInt())
            setSingleLine(true)
        }
        content.addView(input)

        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply {
            text = "Найти"
            setOnClickListener { searchStores() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(Button(this).apply {
            text = "Моё место"
            setOnClickListener { locate() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(buttons)

        content.addView(Button(this).apply {
            text = "＋ Добавить магазин вручную"
            setOnClickListener { showAddDialog() }
        })

        info = TextView(this).apply {
            text = "Определяем координаты…"
            setTextColor(0xFFD6B45A.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(info)

        map = WebView(this)
        content.addView(map, LinearLayout.LayoutParams(-1, dp(230)))
        content.addView(TextView(this).apply {
            text = "Результаты"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
        })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun searchStores() {
        val location = currentLocation
        if (location == null) {
            info.text = "Координаты ещё не получены — нажми «Моё место»."
            locate()
            return
        }
        val query = input.text.toString().trim()
        if (query.isEmpty()) {
            info.text = "Введи название или категорию."
            return
        }

        info.text = "Ищем через серверы OpenStreetMap…"
        results.removeAllViews()
        executor.execute {
            try {
                val found = StoreRepository.search(location.latitude, location.longitude, query, this)
                runOnUiThread {
                    info.text = if (found.isEmpty()) {
                        "Ничего не найдено рядом. Попробуй другое название."
                    } else {
                        "Найдено: ${found.size}. Выбери магазин."
                    }
                    found.forEach { addStoreCard(it) }
                    MapPage.show(map, location.latitude, location.longitude, found)
                }
            } catch (e: Exception) {
                runOnUiThread { info.text = e.message ?: "Поиск временно недоступен. Повтори позже." }
            }
        }
    }

    private fun addStoreCard(store: Store) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF25282E.toInt())
        }
        card.addView(TextView(this).apply {
            text = store.name
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        })
        card.addView(TextView(this).apply {
            text = "${store.address} · ${formatDistance(store.distance)}"
            setTextColor(0xFFD6B45A.toInt())
        })
        card.addView(Button(this).apply {
            text = "Выбрать для виджета"
            setOnClickListener { chooseStore(store) }
        })
        val params = LinearLayout.LayoutParams(-1, -2)
        params.bottomMargin = dp(8)
        results.addView(card, params)
    }

    private fun chooseStore(store: Store) {
        val location = currentLocation ?: run {
            info.text = "Сначала определи своё местоположение."
            locate()
            return
        }
        info.text = "Обновляем виджет…"
        executor.execute {
            val imageFile = File(cacheDir, "widget-map.png")
            imageFile.delete()
            val bitmap = MapTile.make(location.latitude, location.longitude, store.lat, store.lon)
            if (bitmap != null) {
                imageFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                bitmap.recycle()
            }
            val distance = StoreRepository.distance(location.latitude, location.longitude, store.lat, store.lon)
            getSharedPreferences("chosen", MODE_PRIVATE).edit()
                .putString("name", store.name)
                .putString("address", store.address)
                .putString("distance", formatDistance(distance))
                .putString("map", if (bitmap != null && imageFile.exists()) imageFile.absolutePath else null)
                .apply()
            runOnUiThread {
                StoreWidget.update(this)
                info.text = "Виджет обновлён: ${store.name}"
                val route = "https://www.openstreetmap.org/directions?engine=fossgis_osrm_foot&route=${location.latitude}%2C${location.longitude}%3B${store.lat}%2C${store.lon}"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route)))
            }
        }
    }

    private fun showAddDialog() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val nameInput = EditText(this).apply { hint = "Название магазина" }
        val addressInput = EditText(this).apply { hint = "Полный адрес: улица, дом, город" }
        fields.addView(nameInput)
        fields.addView(addressInput)

        AlertDialog.Builder(this)
            .setTitle("Добавить магазин")
            .setView(fields)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = nameInput.text.toString().trim()
                val address = addressInput.text.toString().trim()
                if (name.isEmpty() || address.isEmpty()) {
                    info.text = "Заполни название и адрес."
                    return@setPositiveButton
                }
                info.text = "Ищем координаты адреса…"
                executor.execute {
                    try {
                        val point = StoreRepository.geocode(address)
                        StoreRepository.save(this, name, address, point.first, point.second)
                        val location = currentLocation
                        val distance = if (location == null) 0.0 else StoreRepository.distance(
                            location.latitude, location.longitude, point.first, point.second
                        )
                        val store = Store(name, address, point.first, point.second, distance)
                        runOnUiThread {
                            addStoreCard(store)
                            info.text = "Магазин сохранён на этом телефоне. Его можно найти по названию."
                            if (location != null) MapPage.show(map, location.latitude, location.longitude, listOf(store))
                        }
                    } catch (e: Exception) {
                        runOnUiThread { info.text = e.message ?: "Не удалось найти адрес." }
                    }
                }
            }
            .show()
    }

    private fun locate() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        info.text = "Получаем координаты…"
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        setCurrentLocation(location)
                    } else {
                        locationClient.lastLocation
                            .addOnSuccessListener { previous ->
                                if (previous != null) setCurrentLocation(previous)
                                else info.text = "Телефон не вернул координаты. Проверь геолокацию и попробуй ещё раз."
                            }
                            .addOnFailureListener { info.text = "Не удалось получить последнее местоположение." }
                    }
                }
                .addOnFailureListener { info.text = "Служба геолокации не ответила. Проверь разрешения." }
        } catch (_: SecurityException) {
            info.text = "Нет разрешения на геолокацию."
        }
    }

    private fun setCurrentLocation(location: Location) {
        currentLocation = location
        info.text = "Координаты получены. Введи магазин или категорию."
        MapPage.show(map, location.latitude, location.longitude, emptyList())
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            locationRequestCode
        )
    }

    @Deprecated("Deprecated in Android API; retained for compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationRequestCode) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) locate()
            else info.text = "Разреши доступ к местоположению в настройках."
        }
    }

    private fun formatDistance(meters: Double): String = if (meters < 1000) {
        "${meters.roundToInt()} м"
    } else {
        String.format(Locale.getDefault(), "%.1f км", meters / 1000)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        map.destroy()
    }
}
