package ru.magazinryadom

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
    private val handler = Handler(Looper.getMainLooper())
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var input: EditText
    private lateinit var info: TextView
    private lateinit var results: LinearLayout
    private lateinit var suggestions: LinearLayout
    private lateinit var routeButton: Button
    private lateinit var mapPage: MapPage
    private var currentLocation: Location? = null
    private var selectedStore: Store? = null
    private var suggestionTask: Runnable? = null
    private var lastSuggestionRequest = 0L
    private val locationRequestCode = 22

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi(savedInstanceState)
        val savedManual = getSharedPreferences("manual_location", MODE_PRIVATE)
        val savedLat = savedManual.getString("lat", null)?.toDoubleOrNull()
        val savedLon = savedManual.getString("lon", null)?.toDoubleOrNull()
        if (savedLat != null && savedLon != null) {
            val saved = Location("manual").apply {
                latitude = savedLat
                longitude = savedLon
                accuracy = 0f
                time = System.currentTimeMillis()
            }
            setCurrentLocation(saved, manual = true)
        } else if (hasLocationPermission()) locate() else requestLocationPermission()
    }

    private fun buildUi(savedInstanceState: Bundle?) {
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
        content.addView(TextView(this).apply {
            text = "Поиск магазинов · OpenStreetMap"
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
        })

        input = EditText(this).apply {
            hint = "Название или категория магазина"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF999999.toInt())
            setSingleLine(true)
        }
        content.addView(input)
        suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        content.addView(suggestions)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleSuggestions(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply {
            text = "Найти"
            setOnClickListener { searchStores() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(Button(this).apply {
            text = "Моё место (GPS)"
            setOnClickListener { locate() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(buttons)

        content.addView(Button(this).apply {
            text = "Указать моё место вручную"
            setOnClickListener { showSetLocationDialog() }
        })

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

        mapPage = MapPage(this, savedInstanceState)
        content.addView(mapPage.view)
        content.addView(TextView(this).apply {
            text = "Результаты поиска"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(10), 0, dp(4))
        })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)

        routeButton = Button(this).apply {
            text = "Построить пеший маршрут"
            isEnabled = false
            setOnClickListener { openWalkingRoute() }
        }
        content.addView(routeButton)

        content.addView(TextView(this).apply {
            text = "Карта: © OpenStreetMap contributors"
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(5), 0, 0)
        })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun scheduleSuggestions(text: String) {
        suggestionTask?.let(handler::removeCallbacks)
        suggestions.removeAllViews()
        val query = text.trim()
        if (query.length < 2) return
        val task = object : Runnable {
            override fun run() {
                val wait = (1200L - (System.currentTimeMillis() - lastSuggestionRequest)).coerceAtLeast(0L)
                if (wait > 0L) {
                    handler.postDelayed(this, wait)
                    return
                }
                lastSuggestionRequest = System.currentTimeMillis()
                executor.execute {
                    try {
                        val found = StoreRepository.suggest(query)
                        runOnUiThread {
                            if (input.text.toString().trim() != query) return@runOnUiThread
                            suggestions.removeAllViews()
                            found.take(6).forEach { store ->
                                val button = Button(this@MainActivity).apply {
                                    setText("${store.name}\n${store.address}")
                                    textSize = 12f
                                    setOnClickListener {
                                        input.setText(store.name)
                                        input.setSelection(input.text.length)
                                        suggestions.removeAllViews()
                                        if (currentLocation != null) searchStores(store.name)
                                        else info.text = "Название выбрано. Теперь укажи своё место вручную, чтобы найти ближайшие магазины."
                                    }
                                }
                                suggestions.addView(button)
                            }
                        }
                    } catch (_: Exception) {
                        // Подсказки необязательны: основной поиск остаётся доступен кнопкой «Найти».
                    }
                }
            }
        }
        suggestionTask = task
        handler.postDelayed(task, 1200L)
    }

    private fun searchStores(forcedQuery: String? = null) {
        val location = currentLocation
        if (location == null) {
            info.text = "Сначала укажи своё место вручную или попробуй GPS."
            return
        }
        val query = (forcedQuery ?: input.text.toString()).trim()
        if (query.isEmpty()) {
            info.text = "Введи название или категорию."
            return
        }
        suggestions.removeAllViews()
        info.text = "Ищем магазины…"
        results.removeAllViews()
        executor.execute {
            try {
                val found = StoreRepository.search(location.latitude, location.longitude, query, this)
                runOnUiThread {
                    info.text = if (found.isEmpty()) "Ничего не найдено. Попробуй другое название." else "Найдено: ${found.size}. Нажми «Выбрать этот магазин»."
                    found.forEach { addStoreCard(it) }
                    mapPage.show(location.latitude, location.longitude, found)
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
            text = "Выбрать этот магазин"
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
        selectedStore = store
        routeButton.isEnabled = true
        info.text = "Сохраняем выбранный магазин…"
        executor.execute {
            val imageFile = File(cacheDir, "widget-map.png")
            imageFile.delete()
            val bitmap = MapTile.make(location.latitude, location.longitude, store.lat, store.lon)
            val mapSaved = if (bitmap != null) {
                val saved = try {
                    imageFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                } catch (_: Exception) { false }
                bitmap.recycle()
                saved && imageFile.exists()
            } else false
            val distance = StoreRepository.distance(location.latitude, location.longitude, store.lat, store.lon)
            getSharedPreferences("chosen", MODE_PRIVATE).edit()
                .putString("name", store.name)
                .putString("address", store.address)
                .putString("distance", formatDistance(distance))
                .putString("map", if (mapSaved) imageFile.absolutePath else null)
                .putString("lat", store.lat.toString())
                .putString("lon", store.lon.toString())
                .apply()
            runOnUiThread {
                StoreWidget.update(this)
                info.text = "Выбран магазин: ${store.name}. Виджет обновлён."
            }
        }
    }

    private fun openWalkingRoute() {
        val location = currentLocation ?: return
        val store = selectedStore ?: return
        val route = "https://www.openstreetmap.org/directions?engine=fossgis_osrm_foot&route=${location.latitude}%2C${location.longitude}%3B${store.lat}%2C${store.lon}"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route)))
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
                            info.text = "Магазин сохранён на этом телефоне."
                            if (location != null) mapPage.show(location.latitude, location.longitude, listOf(store))
                        }
                    } catch (e: Exception) {
                        runOnUiThread { info.text = e.message ?: "Не удалось найти адрес." }
                    }
                }
            }
            .show()
    }

    private fun showSetLocationDialog() {
        val addressInput = EditText(this).apply {
            hint = "Город, улица и дом (например: Казань, Баумана, 10)"
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(addressInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Где ты находишься?")
            .setMessage("Введи город или адрес. Это заменит ошибочные GPS-координаты.")
            .setView(container)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Найти место") { _, _ ->
                val address = addressInput.text.toString().trim()
                if (address.isEmpty()) {
                    info.text = "Введи хотя бы название города."
                    return@setPositiveButton
                }
                info.text = "Определяем указанное место…"
                executor.execute {
                    try {
                        val point = StoreRepository.geocode(address)
                        val manual = Location("manual").apply {
                            latitude = point.first
                            longitude = point.second
                            accuracy = 0f
                            time = System.currentTimeMillis()
                        }
                        getSharedPreferences("manual_location", MODE_PRIVATE).edit()
                            .putString("lat", point.first.toString())
                            .putString("lon", point.second.toString())
                            .putString("label", address)
                            .apply()
                        runOnUiThread { setCurrentLocation(manual, manual = true) }
                    } catch (e: Exception) {
                        runOnUiThread { info.text = e.message ?: "Не удалось найти это место. Уточни адрес." }
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
        info.text = "Получаем новое местоположение…"
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location == null) {
                        info.text = "Не удалось получить новое местоположение. Включи геолокацию и попробуй ещё раз."
                    } else {
                        val age = System.currentTimeMillis() - location.time
                        if (age > 120_000L || age < -30_000L) {
                            info.text = "Получены устаревшие координаты. Подожди немного и нажми «Моё место» ещё раз."
                        } else {
                            getSharedPreferences("manual_location", MODE_PRIVATE).edit().clear().apply()
                            setCurrentLocation(location, manual = false)
                        }
                    }
                }
                .addOnFailureListener { info.text = "Служба геолокации не ответила. Проверь разрешения и GPS." }
        } catch (_: SecurityException) {
            info.text = "Нет разрешения на геолокацию."
        }
    }

    private fun setCurrentLocation(location: Location, manual: Boolean = false) {
        currentLocation = location
        selectedStore = null
        routeButton.isEnabled = false
        info.text = if (manual) {
            "Место задано вручную. GPS не используется. Введи название магазина."
        } else {
            "Координаты получены от телефона. Если точка неверная, задай место вручную."
        }
        mapPage.show(location.latitude, location.longitude, emptyList())
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

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

    override fun onStart() { super.onStart(); mapPage.onStart() }
    override fun onResume() { super.onResume(); mapPage.onResume() }
    override fun onPause() { mapPage.onPause(); super.onPause() }
    override fun onStop() { mapPage.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapPage.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapPage.onSaveInstanceState(outState) }

    override fun onDestroy() {
        suggestionTask?.let(handler::removeCallbacks)
        executor.shutdown()
        mapPage.onDestroy()
        super.onDestroy()
    }
}
