package ru.magazinryadom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Keeps the selected store route fresh while the app is in the background. */
class RouteLocationService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastLocation: Location? = null
    private var lastRouteLocation: Location? = null
    private var lastNetworkLocation: Location? = null
    private var lastRouteRefreshAt = 0L
    private var lastNetworkRefreshAt = 0L
    @Volatile private var routeRunning = false
    @Volatile private var searchRunning = false
    private var tracking = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val age = System.currentTimeMillis() - location.time
            if (age > 45_000L || age < -15_000L ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && location.isMock)
            ) return
            if (location.accuracy > 40f) return

            val previous = lastLocation
            if (previous != null) {
                val elapsed = (location.time - previous.time).coerceAtLeast(1L)
                val moved = previous.distanceTo(location)
                val allowed = 250.0 + (elapsed / 1000.0) * 55.0 + location.accuracy.coerceAtLeast(0f) * 2.0
                if (moved > allowed) return
            } else {
                // On service startup, compare against the last accepted app GPS fix.
                // This prevents the background service from accepting a one-off airport jump.
                val prefs = getSharedPreferences("chosen", MODE_PRIVATE)
                val oldLat = prefs.getString("gps_lat", null)?.toDoubleOrNull()
                val oldLon = prefs.getString("gps_lon", null)?.toDoubleOrNull()
                val oldTime = prefs.getLong("gps_time", 0L)
                val elapsed = System.currentTimeMillis() - oldTime
                if (oldLat != null && oldLon != null && elapsed in 1L..600_000L) {
                    val baseline = Location("last_accepted_gps").apply {
                        latitude = oldLat
                        longitude = oldLon
                    }
                    val allowed = 250.0 + (elapsed / 1000.0) * 55.0 + location.accuracy.coerceAtLeast(0f) * 2.0
                    if (baseline.distanceTo(location) > allowed) return
                }
            }
            lastLocation = Location(location)
            getSharedPreferences("chosen", MODE_PRIVATE).edit()
                .putString("user_lat", location.latitude.toString())
                .putString("user_lon", location.longitude.toString())
                .putString("gps_lat", location.latitude.toString())
                .putString("gps_lon", location.longitude.toString())
                .putLong("gps_time", System.currentTimeMillis())
                .apply()

            val prefs = getSharedPreferences("chosen", MODE_PRIVATE)
            val network = prefs.getString("network", "").orEmpty()
            val storeLat = prefs.getString("lat", null)?.toDoubleOrNull()
            val storeLon = prefs.getString("lon", null)?.toDoubleOrNull()
            if (network.isBlank()) {
                stopSelf()
                return
            }
            val now = System.currentTimeMillis()
            val target = if (storeLat != null && storeLon != null) {
                Store(
                    prefs.getString("name", network).orEmpty(),
                    prefs.getString("address", "").orEmpty(),
                    storeLat, storeLon,
                    StoreRepository.distance(location.latitude, location.longitude, storeLat, storeLon)
                )
            } else null

            val routeMoved = lastRouteLocation?.distanceTo(location) ?: Float.MAX_VALUE
            if (target != null && routeMoved >= 30f && now - lastRouteRefreshAt >= 25_000L) {
                rebuildRoute(location, target)
            }
            val networkMoved = lastNetworkLocation?.distanceTo(location) ?: Float.MAX_VALUE
            if (networkMoved >= 30f && now - lastNetworkRefreshAt >= 45_000L) {
                lastNetworkLocation = Location(location)
                refreshNearest(location, network)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!tracking) startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateIntervalMillis(3_000L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            locationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            tracking = true
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun refreshNearest(location: Location, network: String) {
        if (searchRunning) return
        searchRunning = true
        lastNetworkRefreshAt = System.currentTimeMillis()
        executor.execute {
            try {
                val nearest = runCatching {
                    StoreRepository.search(location.latitude, location.longitude, network, this).firstOrNull()
                }.getOrNull()
                if (nearest != null) {
                    val prefs = getSharedPreferences("chosen", MODE_PRIVATE)
                    val oldLat = prefs.getString("lat", null)?.toDoubleOrNull()
                    val oldLon = prefs.getString("lon", null)?.toDoubleOrNull()
                    val currentDistance = if (oldLat != null && oldLon != null)
                        StoreRepository.distance(location.latitude, location.longitude, oldLat, oldLon)
                    else Double.MAX_VALUE
                    val candidateDistance = StoreRepository.distance(
                        location.latitude, location.longitude, nearest.lat, nearest.lon
                    )
                    if (oldLat == null || oldLon == null ||
                        (StoreRepository.distance(oldLat, oldLon, nearest.lat, nearest.lon) > 20.0 &&
                            candidateDistance + 75.0 < currentDistance)
                    ) {
                        prefs.edit()
                            .putString("name", nearest.name)
                            .putString("address", nearest.address)
                            .putString("lat", nearest.lat.toString())
                            .putString("lon", nearest.lon.toString())
                            .putString("distance", formatDistance(nearest.distance))
                            .apply()
                        rebuildRoute(location, nearest)
                    }
                }
            } finally {
                searchRunning = false
            }
        }
    }

    private fun rebuildRoute(location: Location, store: Store) {
        if (routeRunning) return
        routeRunning = true
        lastRouteRefreshAt = System.currentTimeMillis()
        lastRouteLocation = Location(location)
        executor.execute {
            try {
                val path = runCatching {
                    StoreRepository.walkingRoute(location.latitude, location.longitude, store.lat, store.lon)
                }.getOrDefault(emptyList())
                val distance = StoreRepository.distance(location.latitude, location.longitude, store.lat, store.lon)
                val imageFile = File(cacheDir, "widget-map.png")
                val bitmap = MapTile.make(this, location.latitude, location.longitude, store.lat, store.lon, path)
                val saved = if (bitmap != null) {
                    try {
                        imageFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 88, it) }
                    } catch (_: Exception) {
                        false
                    } finally {
                        bitmap.recycle()
                    }
                } else false
                val routeMeters = pathLength(path)
                val eta = if (routeMeters > 0.0) ceil(routeMeters / 80.0).toInt().coerceAtLeast(1) else 0
                getSharedPreferences("chosen", MODE_PRIVATE).edit()
                    .putString("user_lat", location.latitude.toString())
                    .putString("user_lon", location.longitude.toString())
                    .putString("distance", formatDistance(distance))
                    .putString("walk_distance", if (routeMeters > 0.0) formatDistance(routeMeters) else "")
                    .putString("walk_eta", if (eta > 0) "~$eta мин пешком" else "")
                    .putString("map", if (saved) imageFile.absolutePath else null)
                    .apply()
                StoreWidget.update(this)
                updateNotification(store.name, distance)
            } finally {
                routeRunning = false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Маршрут до магазина", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Обновление ближайшего магазина и пешего маршрута"
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(storeName: String? = null, distance: Double? = null): Notification {
        val openApp = PendingIntent.getActivity(
            this, 40,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (storeName != null && distance != null) {
            "$storeName · ${formatDistance(distance)}"
        } else {
            "Обновляем ближайший магазин и маршрут"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("Магазин рядом")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("Магазин рядом")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(storeName: String, distance: Double) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(storeName, distance))
    }

    private fun pathLength(points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += StoreRepository.distance(points[i - 1].first, points[i - 1].second, points[i].first, points[i].second)
        }
        return total
    }

    private fun formatDistance(meters: Double): String = if (meters < 1000.0) {
        "${meters.roundToInt()} м"
    } else {
        String.format(Locale.getDefault(), "%.1f км", meters / 1000.0)
    }

    override fun onDestroy() {
        try { locationClient.removeLocationUpdates(callback) } catch (_: Exception) { }
        tracking = false
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ru.magazinryadom.action.START_ROUTE_TRACKING"
        const val ACTION_STOP = "ru.magazinryadom.action.STOP_ROUTE_TRACKING"
        private const val CHANNEL_ID = "route_location_tracking"
        private const val NOTIFICATION_ID = 41
    }
}
