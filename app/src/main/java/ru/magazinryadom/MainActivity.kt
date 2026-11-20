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
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
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
    private var manualLocationMode = false
    private var lastRouteLocation: Location? = null
    private var lastRouteRefreshAt = 0L
    private var routeRefreshRunning = false
    private var isTrackingLocation = false
    private val locationRequestCode = 22
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fresh = result.lastLocation ?: return
            if (manualLocationMode) return
            currentLocation = fresh
            val target = selectedStore
            if (target == null) {
                mapPage.show(fresh.latitude, fresh.longitude, emptyList())
                info.text = "GPS обновлён · точность около ${fresh.accuracy.roundToInt()} м"
                return
            }
            val previous = lastRouteLocation
            val moved = previous?.distanceTo(fresh) ?: Float.MAX_VALUE
            if (moved >= 25f && System.currentTimeMillis() - lastRouteRefreshAt >= 20000L) {
                refreshRouteForMovement(fresh, target)
            }
        }
    }

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
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(0xFF0D0F12.toInt())
        }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(14))
        }

        content.addView(TextView(this).apply {
            text = "МАГАЗИН РЯДОМ"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(0xFFD6B45A.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Найди нужное место"
            textSize = 27f
            setTextColor(0xFFF7F7F7.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3), 0, 0)
        })
        content.addView(TextView(this).apply {
            text = "Ближайшие точки · пешая навигация"
            textSize = 13f
            setTextColor(0xFF9298A3.toInt())
            setPadding(0, 0, 0, dp(14))
        })

        val searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF191D23.toInt(), 18)
        }
        input = EditText(this).apply {
            hint = "Например: аптека, супермаркет"
            setTextColor(0xFFF4F4F4.toInt())
            setHintTextColor(0xFF7D8490.toInt())
            setSingleLine(true)
            textSize = 16f
            setPadding(dp(6), dp(9), dp(6), dp(9))
            background = null
        }
        searchPanel.addView(input)
        suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        searchPanel.addView(suggestions)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleSuggestions(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val findButton = Button(this).apply {
            text = "Найти рядом"
            isAllCaps = false
            setTextColor(0xFF171717.toInt())
            background = rounded(0xFFD6B45A.toInt(), 12)
            setOnClickListener { searchStores() }
        }
        actions.addView(findButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        val gpsButton = Button(this).apply {
            text = "Обновить GPS"
            isAllCaps = false
            setTextColor(0xFFE8EAF0.toInt())
            background = rounded(0xFF303640.toInt(), 12)
            setOnClickListener { locate() }
        }
        actions.addView(gpsButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        searchPanel.addView(actions)
        content.addView(searchPanel)

        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        utilityRow.addView(Button(this).apply {
            text = "Моё место вручную"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { showSetLocationDialog() }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        utilityRow.addView(Button(this).apply {
            text = "+ Добавить точку"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { showAddDialog() }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        content.addView(utilityRow)

        info = TextView(this).apply {
            text = "Определяем местоположение…"
            textSize = 13f
            setTextColor(0xFFD6B45A.toInt())
            setPadding(dp(4), dp(5), dp(4), dp(10))
        }
        content.addView(info)

        val mapCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(0xFF191D23.toInt(), 18)
        }
        mapPage = MapPage(this, savedInstanceState)
        mapCard.addView(mapPage.view)
        content.addView(mapCard)
        content.addView(TextView(this).apply {
            text = "ИНТЕРАКТИВНАЯ КАРТА"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(0xFF9298A3.toInt())
            setPadding(dp(5), dp(7), 0, dp(8))
        })

        val resultsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(7), dp(2), dp(8))
        }
        resultsHeader.addView(TextView(this).apply {
            text = "Рядом с тобой"
            textSize = 19f
            setTextColor(0xFFF4F4F4.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        resultsHeader.addView(TextView(this).apply {
            text = "OSM"
            textSize = 11f
            setTextColor(0xFFD6B45A.toInt())
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(0xFF29251B.toInt(), 20)
        })
        content.addView(resultsHeader)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)

        routeButton = Button(this).apply {
            text = "Открыть пешую навигацию"
            isEnabled = false
            isAllCaps = false
            setTextColor(0xFF171717.toInt())
            background = rounded(0xFFD6B45A.toInt(), 14)
            setOnClickListener { openWalkingRoute() }
        }
        content.addView(routeButton, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        content.addView(TextView(this).apply {
            text = "Карта и данные: © OpenStreetMap contributors. GPS обновляется, пока приложение открыто."
            textSize = 10f
            setTextColor(0xFF777E89.toInt())
            setPadding(dp(3), dp(4), dp(3), 0)
        })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun isRestrictedRetailQuery(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()
        return listOf(
            "красное белое",
            "красное&белое",
            "красное и белое",
            "алкомаркет",
            "винный магазин",
            "магазин алкоголя",
            "алкогольный магазин"
        ).any { normalized.contains(it) }
    }

    private fun rounded(color: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun scheduleSuggestions(text: String) {
        suggestionTask?.let(handler::removeCallbacks)
        suggestions.removeAllViews()
        val query = text.trim()
        if (query.length < 2 || isRestrictedRetailQuery(query)) return
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
        if (isRestrictedRetailQuery(query)) {
            info.text = "Поиск и прокладка маршрута к алкогольным магазинам недоступны. Можно искать обычные супермаркеты, аптеки и другие магазины."
            return
        }
        suggestions.removeAllViews()
        info.text = "Ищем магазины…"
        results.removeAllViews()
        executor.execute {
            try {
                val found = StoreRepository.search(location.latitude, location.longitude, query, this)
                runOnUiThread {
                    info.text = if (found.isEmpty()) "Ничего не найдено. Попробуй другое название." else "Найдено: ${found.size}. Ближайшая точка выбрана автоматически."
                    found.forEach { addStoreCard(it) }
                    mapPage.show(location.latitude, location.longitude, found)
                    if (found.isNotEmpty()) chooseStore(found.first())
                }
            } catch (e: Exception) {
                runOnUiThread { info.text = e.message ?: "Поиск временно недоступен. Повтори позже." }
            }
        }
    }

    private fun addStoreCard(store: Store) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(10))
            background = rounded(0xFF1B2027.toInt(), 15)
        }
        card.addView(TextView(this).apply {
            text = store.name
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        })
        card.addView(TextView(this).apply {
            text = "${store.address}\n${formatDistance(store.distance)} от тебя"
            setTextColor(0xFFB9C0CA.toInt())
            textSize = 12f
        })
        card.addView(Button(this).apply {
            text = "Построить маршрут"
            isAllCaps = false
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
            val walkingPath = try {
                StoreRepository.walkingRoute(location.latitude, location.longitude, store.lat, store.lon)
            } catch (_: Exception) { emptyList() }
            val imageFile = File(cacheDir, "widget-map.png")
            imageFile.delete()
            val bitmap = MapTile.make(location.latitude, location.longitude, store.lat, store.lon, walkingPath)
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
            lastRouteLocation = Location(location)
            lastRouteRefreshAt = System.currentTimeMillis()
            runOnUiThread {
                StoreWidget.update(this)
                mapPage.show(location.latitude, location.longitude, listOf(store), walkingPath)
                info.text = if (walkingPath.size >= 2) {
                    "Ближайшая точка: ${store.name}. Зелёная линия показывает пеший маршрут."
                } else {
                    "Выбрана точка: ${store.name}. Не удалось загрузить линию маршрута; попробуй открыть навигацию."
                }
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
                            startLiveLocation()
                        }
                    }
                }
                .addOnFailureListener { info.text = "Служба геолокации не ответила. Проверь разрешения и GPS." }
        } catch (_: SecurityException) {
            info.text = "Нет разрешения на геолокацию."
        }
    }

    private fun setCurrentLocation(location: Location, manual: Boolean = false) {
        manualLocationMode = manual
        if (manual) stopLiveLocation()
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

    private fun startLiveLocation() {
        if (manualLocationMode || isTrackingLocation || !hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 6000L)
            .setMinUpdateIntervalMillis(3000L)
            .setMinUpdateDistanceMeters(8f)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            isTrackingLocation = true
        } catch (_: SecurityException) {
            info.text = "Нет разрешения на постоянное обновление GPS."
        }
    }

    private fun stopLiveLocation() {
        try { locationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) { }
        isTrackingLocation = false
    }

    private fun refreshRouteForMovement(location: Location, store: Store) {
        if (routeRefreshRunning) return
        routeRefreshRunning = true
        lastRouteRefreshAt = System.currentTimeMillis()
        lastRouteLocation = Location(location)
        executor.execute {
            val path = try {
                StoreRepository.walkingRoute(location.latitude, location.longitude, store.lat, store.lon)
            } catch (_: Exception) { emptyList() }
            val distance = StoreRepository.distance(location.latitude, location.longitude, store.lat, store.lon)
            val imageFile = File(cacheDir, "widget-map.png")
            val bitmap = MapTile.make(location.latitude, location.longitude, store.lat, store.lon, path)
            val saved = if (bitmap != null) {
                try { imageFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 88, it) } }
                catch (_: Exception) { false }
                finally { bitmap.recycle() }
            } else false
            getSharedPreferences("chosen", MODE_PRIVATE).edit()
                .putString("distance", formatDistance(distance))
                .putString("map", if (saved) imageFile.absolutePath else null)
                .apply()
            runOnUiThread {
                routeRefreshRunning = false
                if (selectedStore?.lat == store.lat && selectedStore?.lon == store.lon) {
                    mapPage.show(location.latitude, location.longitude, listOf(store), path)
                    StoreWidget.update(this)
                    info.text = if (path.size >= 2) {
                        "Маршрут обновлён · ${formatDistance(distance)} по прямой до точки"
                    } else {
                        "GPS обновлён, но сервис пешего маршрута пока не ответил."
                    }
                }
            }
        }
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
    override fun onResume() {
        super.onResume()
        mapPage.onResume()
        if (!manualLocationMode && hasLocationPermission()) startLiveLocation()
    }
    override fun onPause() {
        stopLiveLocation()
        mapPage.onPause()
        super.onPause()
    }
    override fun onStop() { mapPage.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapPage.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapPage.onSaveInstanceState(outState) }

    override fun onDestroy() {
        stopLiveLocation()
        suggestionTask?.let(handler::removeCallbacks)
        executor.shutdown()
        mapPage.onDestroy()
        super.onDestroy()
    }
}
