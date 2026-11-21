package ru.magazinryadom

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Build
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
import com.google.android.gms.location.CurrentLocationRequest
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
    private var selectedNetwork: String = ""
    private var networkRefreshRunning = false
    private var lastNetworkRefreshAt = 0L
    private var lastNetworkLocation: Location? = null
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
            // Reject obviously old fixes; never move the map back to a cached airport coordinate.
            val age = System.currentTimeMillis() - fresh.time
            if (age > 45_000L || age < -15_000L || fresh.accuracy > 100f || isMockLocation(fresh)) return
            if (isGpsJump(fresh)) {
                info.text = "GPS выдал скачок координат. Игнорируем эту точку и ждём подтверждения реального местоположения."
                return
            }
            currentLocation = fresh
            getSharedPreferences("chosen", MODE_PRIVATE).edit()
                .putString("user_lat", fresh.latitude.toString())
                .putString("user_lon", fresh.longitude.toString())
                .putString("gps_lat", fresh.latitude.toString())
                .putString("gps_lon", fresh.longitude.toString())
                .putLong("gps_time", System.currentTimeMillis())
                .apply()
            val target = selectedStore
            if (target == null) {
                mapPage.show(fresh.latitude, fresh.longitude, emptyList())
                info.text = "Местоположение обновлено · точность ±${fresh.accuracy.roundToInt()} м"
                val savedNetwork = getSharedPreferences("chosen", MODE_PRIVATE).getString("network", null)
                if (selectedNetwork.isBlank() && !savedNetwork.isNullOrBlank()) {
                    input.setText(savedNetwork)
                    searchStores(savedNetwork)
                }
                return
            }
            val previous = lastRouteLocation
            val moved = previous?.distanceTo(fresh) ?: Float.MAX_VALUE
            val now = System.currentTimeMillis()
            if (moved >= 30f && now - lastRouteRefreshAt >= 25000L) {
                refreshRouteForMovement(fresh, target)
            }
            val networkMoved = lastNetworkLocation?.distanceTo(fresh) ?: Float.MAX_VALUE
            if (selectedNetwork.isNotBlank() && networkMoved >= 100f && now - lastNetworkRefreshAt >= 120000L) {
                lastNetworkLocation = Location(fresh)
                refreshNearestNetwork(fresh, selectedNetwork)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi(savedInstanceState)
        // Each launch starts with a fresh GPS request. A previously entered
        // manual point must never silently replace the phone's current location.
        locate()
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
            text = "Найди ближайший магазин"
            textSize = 27f
            setTextColor(0xFFF7F7F7.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3), 0, 0)
        })
        content.addView(TextView(this).apply {
            text = "Выбери сеть ниже или введи название. Ближайший филиал появится на карте."
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
            hint = "Например: Пятёрочка, Магнит, Перекрёсток"
            setTextColor(0xFFF4F4F4.toInt())
            setHintTextColor(0xFF7D8490.toInt())
            setSingleLine(true)
            textSize = 16f
            setPadding(dp(6), dp(9), dp(6), dp(9))
            background = null
        }
        val quickScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Пятёрочка", "Магнит", "Перекрёсток", "Дикси", "Лента", "ВкусВилл",
            "Чижик", "Верный", "Монетка", "Светофор", "Ашан", "METRO",
            "Спортмастер", "Детский мир", "Fix Price", "DNS", "М.Видео",
            "Эльдорадо", "Золотое Яблоко", "Подружка", "Лемана ПРО", "OBI",
            "Петрович", "Максидом", "Самокат", "Яндекс Лавка", "Аптека"
        ).forEach { network ->
            val chip = Button(this).apply {
                text = network
                isAllCaps = false
                textSize = 12f
                minHeight = dp(38)
                setPadding(dp(10), 0, dp(10), 0)
                setTextColor(0xFFE9D49A.toInt())
                background = rounded(0xFF29251B.toInt(), 18)
                setOnClickListener {
                    input.setText(network)
                    input.setSelection(input.text.length)
                    searchStores(network)
                }
            }
            quickRow.addView(chip, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
        }
        quickScroll.addView(quickRow)
        searchPanel.addView(quickScroll)
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
            text = "СИНЯЯ ТОЧКА — ТЫ · ЗОЛОТАЯ — МАГАЗИН · ЗЕЛЁНАЯ ЛИНИЯ — ПЕШИЙ МАРШРУТ"
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
            text = "Ближайшие филиалы сети"
            textSize = 19f
            setTextColor(0xFFF4F4F4.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        resultsHeader.addView(TextView(this).apply {
            text = "ПОИСК ПО СЕТИ"
            textSize = 11f
            setTextColor(0xFFD6B45A.toInt())
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(0xFF29251B.toInt(), 20)
        })
        content.addView(resultsHeader)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)

        routeButton = Button(this).apply {
            text = "Открыть маршрут в навигаторе"
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
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return listOf(
            "красное белое",
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
        if (query.isEmpty()) return

        // Suggestions are network names, not individual geocoded branches.
        val networks = listOf(
            "Пятёрочка", "Магнит", "Магнит Косметик", "Магнит Аптека", "Перекрёсток",
            "Дикси", "Лента", "Ашан", "ВкусВилл", "Чижик", "Верный", "Монетка",
            "Светофор", "О'КЕЙ", "METRO", "Самокат", "Спортмастер", "Детский мир",
            "Fix Price", "DNS", "М.Видео", "Эльдорадо", "Золотое Яблоко", "Подружка",
            "Лемана ПРО", "OBI", "Петрович", "Максидом", "Касторама", "Яндекс Лавка",
            "Wildberries", "Ozon", "Kari", "Gloria Jeans", "Familia", "Л'Этуаль",
            "РИВ ГОШ", "Читай-город", "Буквоед", "Комус", "ВсеИнструменты.ру",
            "585 Золотой", "SUNLIGHT", "Леонардо", "Четыре Лапы", "Зоозавр",
            "Petshop", "Эконика", "Befree", "Sela", "HENDERSON", "Горздрав",
            "Ригла", "36,6", "Супермаркет", "Аптека"
        )
        fun key(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ").trim()
        val normalizedQuery = key(query)
        val matches = networks.filter { key(it).contains(normalizedQuery) || normalizedQuery.contains(key(it)) }
        val exact = networks.firstOrNull { key(it) == normalizedQuery }
        val choices = (matches + if (exact == null) listOf(query) else emptyList()).distinct().take(5)
        choices.forEach { network ->
            val button = Button(this).apply {
                setText("Искать сеть: $network")
                isAllCaps = false
                textSize = 13f
                setOnClickListener {
                    input.setText(network)
                    input.setSelection(input.text.length)
                    suggestions.removeAllViews()
                    searchStores(network)
                }
            }
            suggestions.addView(button)
        }
    }

    private fun searchStores(forcedQuery: String? = null) {
        mapPage.cancelPointPick()
        val location = currentLocation
        if (location == null) {
            info.text = "Ожидаем точное местоположение. Нажми «Обновить GPS», если оно не появится."
            return
        }
        val query = (forcedQuery ?: input.text.toString()).trim()
        if (query.isEmpty()) {
            info.text = "Сначала введи название сети магазинов. Например, «Магнит»."
            return
        }
        if (isRestrictedRetailQuery(query)) {
            info.text = "Поиск этой сети недоступен. Можно выбрать другую сеть или обычную категорию магазинов."
            return
        }
        selectedNetwork = query
        selectedStore = null
        routeButton.isEnabled = false
        getSharedPreferences("chosen", MODE_PRIVATE).edit()
            .putString("network", query)
            .remove("name").remove("address").remove("distance").remove("walk_distance").remove("walk_eta").remove("map").remove("lat").remove("lon")
            .apply()
        StoreWidget.update(this)
        suggestions.removeAllViews()
        info.text = "Ищем филиалы сети «$query» в радиусе 12 км…"
        results.removeAllViews()
        results.addView(TextView(this).apply {
            text = "Ищем филиалы по бренду и названию. Это может занять несколько секунд."
            textSize = 12f
            setTextColor(0xFF9298A3.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(10))
        })
        executor.execute {
            try {
                val found = StoreRepository.search(location.latitude, location.longitude, query, this)
                // Final local ranking is recalculated against the exact location captured for this search.
                val nearestFirst = found
                    .map { it.copy(distance = StoreRepository.distance(location.latitude, location.longitude, it.lat, it.lon)) }
                    .sortedBy { it.distance }
                runOnUiThread {
                    results.removeAllViews()
                    if (nearestFirst.isEmpty()) {
                        info.text = "В радиусе 12 км филиалы «$query» не найдены. Попробуй другое написание или обнови GPS."
                        mapPage.show(location.latitude, location.longitude, emptyList())
                    } else {
                        info.text = "Найдено филиалов: ${nearestFirst.size}. Выбран ближайший — ${nearestFirst.first().name} (${formatDistance(nearestFirst.first().distance)} от тебя). На экране показаны ближайшие 10."
                        nearestFirst.take(10).forEach { addStoreCard(it) }
                        mapPage.show(location.latitude, location.longitude, nearestFirst.take(10))
                        chooseStore(nearestFirst.first(), query)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    results.removeAllViews()
                    info.text = e.message ?: "Поиск временно недоступен. Попробуй ещё раз."
                    results.addView(TextView(this).apply {
                        text = "Проверь название сети и подключение к интернету. Можно попробовать ещё раз или выбрать другую сеть."
                        textSize = 12f
                        setTextColor(0xFF9298A3.toInt())
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    })
                }
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
            text = "${store.address}\n${formatDistance(store.distance)} по прямой от тебя · пешее время уточняется по маршруту"
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

    private fun chooseStore(store: Store, network: String? = null) {
        val location = currentLocation ?: run {
            info.text = "Сначала определи своё местоположение."
            locate()
            return
        }
        selectedStore = store
        if (!network.isNullOrBlank()) selectedNetwork = network
        routeButton.isEnabled = true
        info.text = "Сохраняем выбранный магазин…"
        executor.execute {
            var routeFailure: String? = null
            val walkingPath = try {
                StoreRepository.walkingRoute(location.latitude, location.longitude, store.lat, store.lon)
            } catch (e: Exception) {
                routeFailure = e.message ?: "Неизвестная ошибка сервиса маршрутов"
                emptyList()
            }
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
            val routeMeters = pathLength(walkingPath)
            val etaMinutes = if (routeMeters > 0) kotlin.math.ceil(routeMeters / 80.0).toInt().coerceAtLeast(1) else 0
            getSharedPreferences("chosen", MODE_PRIVATE).edit()
                .putString("user_lat", location.latitude.toString())
                .putString("user_lon", location.longitude.toString())
                .putString("walk_distance", if (routeMeters > 0) formatDistance(routeMeters) else "")
                .putString("walk_eta", if (etaMinutes > 0) "~$etaMinutes мин пешком" else "")
                .putString("name", store.name)
                .putString("network", selectedNetwork)
                .putString("address", store.address)
                .putString("distance", formatDistance(distance))
                .putString("map", if (mapSaved) imageFile.absolutePath else null)
                .putString("lat", store.lat.toString())
                .putString("lon", store.lon.toString())
                .apply()
            lastRouteLocation = Location(location)
            lastNetworkLocation = Location(location)
            lastRouteRefreshAt = System.currentTimeMillis()
            runOnUiThread {
                StoreWidget.update(this)
                mapPage.show(location.latitude, location.longitude, listOf(store), walkingPath)
                info.text = if (walkingPath.size >= 2) {
                    "Ближайшая точка: ${store.name}. Пешком ${formatDistance(pathLength(walkingPath))} · около ${kotlin.math.ceil(pathLength(walkingPath) / 80.0).toInt()} мин. Зелёная линия — маршрут."
                } else {
                    "Магазин выбран: ${store.name}. Линия маршрута не загрузилась: ${routeFailure ?: "сервис временно недоступен"}. Можно открыть маршрут в навигаторе."
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
        val nameInput = EditText(this).apply { hint = "Название точки"; setSingleLine(true) }
        val addressInput = EditText(this).apply { hint = "Адрес (необязательно, если выберешь точку на карте)" }
        fields.addView(nameInput)
        fields.addView(addressInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Добавить свою точку")
            .setMessage("Можно найти адрес автоматически или просто поставить точку на карте.")
            .setView(fields)
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Выбрать на карте", null)
            .setPositiveButton("Найти адрес и сохранить", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val address = addressInput.text.toString().trim()
                if (name.isBlank()) {
                    info.text = "Сначала укажи название точки."
                    return@setOnClickListener
                }
                dialog.dismiss()
                info.text = "Режим выбора точки: прокрути к карте и нажми на нужное место."
                mapPage.pickPointOnce { lat, lon ->
                    info.text = "Сохраняем точку и уточняем адрес…"
                    executor.execute {
                        val label = address.ifBlank { runCatching { StoreRepository.reverseGeocode(lat, lon) }.getOrNull() ?: "Точка на карте" }
                        StoreRepository.save(this, name, label, lat, lon)
                        val user = currentLocation
                        val distance = if (user == null) 0.0 else StoreRepository.distance(user.latitude, user.longitude, lat, lon)
                        val store = Store(name, label, lat, lon, distance)
                        runOnUiThread {
                            addStoreCard(store)
                            if (user != null) mapPage.show(user.latitude, user.longitude, listOf(store))
                            info.text = "Точка «$name» сохранена · $label"
                        }
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val address = addressInput.text.toString().trim()
                if (name.isBlank() || address.isBlank()) {
                    info.text = "Для поиска адреса заполни название и адрес. Или выбери точку на карте."
                    return@setOnClickListener
                }
                info.text = "Ищем адрес…"
                executor.execute {
                    try {
                        val point = StoreRepository.geocode(address)
                        StoreRepository.save(this, name, address, point.first, point.second)
                        val user = currentLocation
                        val distance = if (user == null) 0.0 else StoreRepository.distance(user.latitude, user.longitude, point.first, point.second)
                        val store = Store(name, address, point.first, point.second, distance)
                        runOnUiThread {
                            dialog.dismiss()
                            addStoreCard(store)
                            info.text = "Точка «$name» сохранена."
                            if (user != null) mapPage.show(user.latitude, user.longitude, listOf(store))
                        }
                    } catch (e: Exception) {
                        runOnUiThread { info.text = e.message ?: "Не удалось найти адрес. Попробуй выбрать точку на карте." }
                    }
                }
            }
        }
        dialog.show()
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
        val dialog = AlertDialog.Builder(this)
            .setTitle("Указать своё местоположение")
            .setMessage("Введи адрес или выбери точку прямо на карте. Это поможет, если GPS ошибается.")
            .setView(container)
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Выбрать на карте", null)
            .setPositiveButton("Найти адрес", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                info.text = "Выбор местоположения: прокрути к карте и нажми на своё место."
                mapPage.pickPointOnce { lat, lon ->
                    info.text = "Уточняем адрес выбранной точки…"
                    executor.execute {
                        val label = runCatching { StoreRepository.reverseGeocode(lat, lon) }.getOrNull() ?: "Точка выбрана на карте"
                        val manual = Location("manual_map").apply {
                            latitude = lat; longitude = lon; accuracy = 0f; time = System.currentTimeMillis()
                        }
                        getSharedPreferences("manual_location", MODE_PRIVATE).edit()
                            .putString("lat", lat.toString())
                            .putString("lon", lon.toString())
                            .putString("label", label)
                            .apply()
                        runOnUiThread {
                            setCurrentLocation(manual, manual = true)
                            info.text = "Местоположение задано: $label"
                            val savedNetwork = getSharedPreferences("chosen", MODE_PRIVATE).getString("network", null)
                            if (!savedNetwork.isNullOrBlank()) {
                                input.setText(savedNetwork)
                                searchStores(savedNetwork)
                            }
                        }
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = addressInput.text.toString().trim()
                if (address.isEmpty()) {
                    info.text = "Введи адрес или выбери точку на карте."
                    return@setOnClickListener
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
                        runOnUiThread {
                            dialog.dismiss()
                            setCurrentLocation(manual, manual = true)
                            val savedNetwork = getSharedPreferences("chosen", MODE_PRIVATE).getString("network", null)
                            if (!savedNetwork.isNullOrBlank()) {
                                input.setText(savedNetwork)
                                searchStores(savedNetwork)
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { info.text = e.message ?: "Не удалось найти адрес. Попробуй выбрать точку на карте." }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun restoreManualLocationOrLocate() {
        val prefs = getSharedPreferences("manual_location", MODE_PRIVATE)
        val lat = prefs.getString("lat", null)?.toDoubleOrNull()
        val lon = prefs.getString("lon", null)?.toDoubleOrNull()
        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
            val saved = Location("manual_saved").apply {
                latitude = lat
                longitude = lon
                accuracy = 0f
                time = System.currentTimeMillis()
            }
            setCurrentLocation(saved, manual = true)
            info.text = "Используем выбранную тобой точку: ${prefs.getString("label", "место")}. Чтобы проверить GPS, нажми «Обновить GPS»."
            val network = getSharedPreferences("chosen", MODE_PRIVATE).getString("network", null)
            if (!network.isNullOrBlank()) {
                input.setText(network)
                searchStores(network)
            }
        } else {
            locate()
        }
    }

    private fun locate() {
        mapPage.cancelPointPick()
        // Explicit GPS refresh exits manual mode and discards any old in-memory fix.
        manualLocationMode = false
        currentLocation = null
        selectedStore = null
        routeButton.isEnabled = false
        lastRouteLocation = null
        lastNetworkLocation = null
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        info.text = "Определяем местоположение через Wi-Fi и мобильную сеть…"
        try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(0L)
                .setDurationMillis(25000L)
                .build()
            locationClient.getCurrentLocation(request, null)
                .addOnSuccessListener { location ->
                    if (location == null) {
                        useSavedManualLocationOrWait("Телефон пока не получил GPS. Проверь геолокацию или задай точку вручную.")
                    } else {
                        val age = System.currentTimeMillis() - location.time
                        if (age > 45000L || age < -15000L || location.accuracy > 100f || isMockLocation(location) || isGpsJump(location)) {
                            useSavedManualLocationOrWait("Сетевое местоположение пока неточное или координаты подозрительные. Ждём более надёжную точку; можно указать место вручную.")
                            startLiveLocation()
                        } else {
                            getSharedPreferences("manual_location", MODE_PRIVATE).edit().clear().apply()
                            getSharedPreferences("chosen", MODE_PRIVATE).edit()
                                .putString("gps_lat", location.latitude.toString())
                                .putString("gps_lon", location.longitude.toString())
                                .putLong("gps_time", System.currentTimeMillis())
                                .apply()
                            setCurrentLocation(location, manual = false)
                            startLiveLocation()
                            val savedNetwork = getSharedPreferences("chosen", MODE_PRIVATE).getString("network", null)
                            if (!savedNetwork.isNullOrBlank()) {
                                input.setText(savedNetwork)
                                searchStores(savedNetwork)
                            }
                        }
                    }
                }
                .addOnFailureListener { useSavedManualLocationOrWait("Геолокация не ответила. Проверь разрешения и включён ли GPS.") }
        } catch (_: SecurityException) {
            info.text = "Нет разрешения на геолокацию. Разреши доступ в настройках Android."
        }
    }

    /**
     * Rejects implausible jumps from the last accepted GPS fix. This prevents a
     * single bad fused/network location (for example, a distant airport) from
     * replacing a stable position and rebuilding the route there.
     */
    private fun isMockLocation(location: Location): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && location.isMock

    private fun isGpsJump(location: Location): Boolean {
        val prefs = getSharedPreferences("chosen", MODE_PRIVATE)
        val oldLat = prefs.getString("gps_lat", null)?.toDoubleOrNull() ?: return false
        val oldLon = prefs.getString("gps_lon", null)?.toDoubleOrNull() ?: return false
        val oldTime = prefs.getLong("gps_time", 0L)
        val elapsed = System.currentTimeMillis() - oldTime
        if (elapsed !in 1L..600_000L) return false
        val old = Location("last_accepted_gps").apply {
            latitude = oldLat
            longitude = oldLon
        }
        val moved = old.distanceTo(location).toDouble()
        val allowed = 1500.0 + (elapsed / 1000.0) * 45.0 +
            location.accuracy.coerceAtLeast(0f) * 2.0
        return moved > allowed
    }

    private fun useSavedManualLocationOrWait(message: String) {
        // Do not silently reuse a manual point after GPS failure: it may be an old
        // address or a location from a previous trip. Keep waiting for a fresh fix.
        currentLocation = null
        info.text = message
        startLiveLocation()
    }

    private fun setCurrentLocation(location: Location, manual: Boolean = false) {
        manualLocationMode = manual
        if (manual) stopLiveLocation()
        currentLocation = location
        getSharedPreferences("chosen", MODE_PRIVATE).edit()
            .putString("user_lat", location.latitude.toString())
            .putString("user_lon", location.longitude.toString())
            .apply()
        selectedStore = null
        routeButton.isEnabled = false
        info.text = if (manual) {
            "Место задано вручную. GPS не используется. Введи название магазина."
        } else {
            "GPS получен · точность ±${location.accuracy.roundToInt()} м. Ищем рядом с этой точкой."
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
            val routeMeters = pathLength(path)
            val etaMinutes = if (routeMeters > 0) kotlin.math.ceil(routeMeters / 80.0).toInt().coerceAtLeast(1) else 0
            getSharedPreferences("chosen", MODE_PRIVATE).edit()
                .putString("user_lat", location.latitude.toString())
                .putString("user_lon", location.longitude.toString())
                .putString("walk_distance", if (routeMeters > 0) formatDistance(routeMeters) else "")
                .putString("walk_eta", if (etaMinutes > 0) "~$etaMinutes мин пешком" else "")
                .putString("distance", formatDistance(distance))
                .putString("map", if (saved) imageFile.absolutePath else null)
                .apply()
            runOnUiThread {
                routeRefreshRunning = false
                if (selectedStore?.lat == store.lat && selectedStore?.lon == store.lon) {
                    mapPage.show(location.latitude, location.longitude, listOf(store), path)
                    StoreWidget.update(this)
                    info.text = if (path.size >= 2) {
                        "Маршрут обновлён · пешком ${formatDistance(pathLength(path))} · примерно ${kotlin.math.ceil(pathLength(path) / 80.0).toInt()} мин"
                    } else {
                        "GPS обновлён, но сервис пешего маршрута пока не ответил."
                    }
                }
            }
        }
    }

    private fun refreshNearestNetwork(location: Location, network: String) {
        if (networkRefreshRunning) return
        networkRefreshRunning = true
        lastNetworkRefreshAt = System.currentTimeMillis()
        executor.execute {
            val nearest = runCatching {
                StoreRepository.search(location.latitude, location.longitude, network, this).firstOrNull()
            }.getOrNull()
            runOnUiThread {
                networkRefreshRunning = false
                if (selectedNetwork == network && nearest != null) {
                    val current = selectedStore
                    if (current == null || current.lat != nearest.lat || current.lon != nearest.lon) {
                        chooseStore(nearest, network)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("widget_refresh", false) == true) locate()
    }

    @Deprecated("Deprecated in Android API; retained for compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationRequestCode) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) locate()
            else info.text = "Разреши доступ к местоположению в настройках."
        }
    }

    private fun pathLength(points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += StoreRepository.distance(points[i - 1].first, points[i - 1].second, points[i].first, points[i].second)
        }
        return total
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
