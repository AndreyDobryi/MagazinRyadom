package ru.magazinryadom

import android.content.Context
import android.view.ViewGroup
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.PolylineOptions
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/** Нативная карта MapLibre. Базовый стиль использует растровые тайлы OSM. */
class MapPage(context: Context, savedInstanceState: android.os.Bundle? = null) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var styleReady = false
    private var pendingUser: LatLng? = null
    private var pendingStores: List<Store> = emptyList()
    private var pendingRoute: List<Pair<Double, Double>> = emptyList()
    private var pointPickListener: ((Double, Double) -> Unit)? = null
    private var userMarkerIcon: Icon? = null
    private var storeMarkerIcon: Icon? = null

    init {
        MapLibre.getInstance(context.applicationContext)
        userMarkerIcon = markerIcon(context, Color.rgb(45, 125, 235))
        storeMarkerIcon = markerIcon(context, Color.rgb(218, 165, 32))
        view = MapView(context)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (360 * context.resources.displayMetrics.density).toInt()
        )
        view.onCreate(savedInstanceState)
        view.getMapAsync { mapboxMap ->
            map = mapboxMap
            mapboxMap.uiSettings.isScrollGesturesEnabled = true
            mapboxMap.uiSettings.isZoomGesturesEnabled = true
            mapboxMap.uiSettings.isRotateGesturesEnabled = true
            mapboxMap.uiSettings.isTiltGesturesEnabled = true
            mapboxMap.uiSettings.isCompassEnabled = true
            mapboxMap.addOnMapClickListener { point ->
                val listener = pointPickListener
                if (listener != null) {
                    pointPickListener = null
                    listener(point.latitude, point.longitude)
                    true
                } else false
            }
            mapboxMap.setStyle(Style.Builder().fromJson(styleJson())) {
                styleReady = true
                renderPending()
            }
        }
    }

    fun pickPointOnce(listener: (Double, Double) -> Unit) { pointPickListener = listener }
    fun cancelPointPick() { pointPickListener = null }

    fun show(latitude: Double, longitude: Double, stores: List<Store>, route: List<Pair<Double, Double>> = emptyList()) {
        pendingUser = LatLng(latitude, longitude)
        pendingStores = stores
        pendingRoute = route
        renderPending()
    }

    private fun renderPending() {
        val activeMap = map ?: return
        if (!styleReady) return
        val user = pendingUser ?: return
        activeMap.clear()
        val userMarker = MarkerOptions().position(user).title("Вы здесь")
        userMarkerIcon?.let { userMarker.icon(it) }
        activeMap.addMarker(userMarker)
        if (pendingRoute.size >= 2) {
            val points = pendingRoute.map { LatLng(it.first, it.second) }
            activeMap.addPolyline(PolylineOptions().addAll(points).color(Color.rgb(35, 170, 95)).width(6f))
        }
        pendingStores.forEach { store ->
            val marker = MarkerOptions()
                .position(LatLng(store.lat, store.lon))
                .title(store.name)
                .snippet(store.address)
            storeMarkerIcon?.let { marker.icon(it) }
            activeMap.addMarker(marker)
        }
        val target = pendingStores.firstOrNull()?.let { LatLng(it.lat, it.lon) } ?: user
        if (pendingRoute.size >= 2) {
            try {
                val bounds = org.maplibre.android.geometry.LatLngBounds.Builder().apply {
                    include(user)
                    pendingRoute.forEach { include(LatLng(it.first, it.second)) }
                    pendingStores.firstOrNull()?.let { include(LatLng(it.lat, it.lon)) }
                }.build()
                activeMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48))
            } catch (_: Exception) {
                activeMap.setCameraPosition(CameraPosition.Builder().target(target).zoom(14.0).build())
            }
        } else {
            activeMap.setCameraPosition(CameraPosition.Builder()
                .target(target)
                .zoom(if (pendingStores.isEmpty()) 14.5 else 14.0)
                .build())
        }
    }

    private fun markerIcon(context: Context, color: Int): Icon {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(32f, 32f, 17f, paint)
        paint.color = color
        canvas.drawCircle(32f, 32f, 13f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(32f, 32f, 4f, paint)
        return IconFactory.getInstance(context).fromBitmap(bitmap)
    }

    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onDestroy() = view.onDestroy()
    fun onLowMemory() = view.onLowMemory()
    fun onSaveInstanceState(outState: android.os.Bundle) = view.onSaveInstanceState(outState)

    private fun styleJson(): String = """
        {
          "version": 8,
          "name": "OpenStreetMap raster",
          "sources": {
            "osm": {
              "type": "raster",
              "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
              "tileSize": 256,
              "attribution": "© OpenStreetMap contributors"
            }
          },
          "layers": [
            {"id": "osm-raster", "type": "raster", "source": "osm", "minzoom": 0, "maxzoom": 19}
          ]
        }
    """.trimIndent()
}
