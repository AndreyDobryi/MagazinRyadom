package ru.magazinryadom

import android.content.Context
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
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
    val view: FrameLayout
    private val mapView: MapView
    private var followUser = true
    private var cameraInitialized = false
    private var routeFramed = false
    private var recenterButton: TextView? = null
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
        view = FrameLayout(context)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (360 * context.resources.displayMetrics.density).toInt()
        )
        mapView = MapView(context)
        view.addView(mapView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        mapView.onCreate(savedInstanceState)
        val recenter = TextView(context).apply {
            text = "◎"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(35, 42, 52))
            elevation = 8f
            visibility = android.view.View.GONE
            setOnClickListener {
                followUser = true
                visibility = android.view.View.GONE
                pendingUser?.let { point ->
                    val zoom = map?.cameraPosition?.zoom ?: 16.0
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, zoom))
                }
            }
        }
        val buttonSize = (46 * context.resources.displayMetrics.density).toInt()
        val buttonParams = FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.END or Gravity.TOP).apply {
            topMargin = (12 * context.resources.displayMetrics.density).toInt()
            marginEnd = (12 * context.resources.displayMetrics.density).toInt()
        }
        view.addView(recenter, buttonParams)
        recenterButton = recenter
        mapView.getMapAsync { mapboxMap ->
            map = mapboxMap
            mapboxMap.uiSettings.isScrollGesturesEnabled = true
            mapboxMap.uiSettings.isZoomGesturesEnabled = true
            mapboxMap.uiSettings.isRotateGesturesEnabled = true
            mapboxMap.uiSettings.isTiltGesturesEnabled = true
            mapboxMap.uiSettings.isCompassEnabled = true
            mapboxMap.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    followUser = false
                    recenterButton?.visibility = android.view.View.VISIBLE
                }
            }
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
        if (pendingRoute.size >= 2 && !routeFramed) {
            try {
                val bounds = org.maplibre.android.geometry.LatLngBounds.Builder().apply {
                    include(user)
                    pendingRoute.forEach { include(LatLng(it.first, it.second)) }
                    pendingStores.firstOrNull()?.let { include(LatLng(it.lat, it.lon)) }
                }.build()
                activeMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 56))
            } catch (_: Exception) {
                activeMap.animateCamera(CameraUpdateFactory.newLatLngZoom(user, 15.0))
            }
            routeFramed = true
            cameraInitialized = true
        } else if (!cameraInitialized) {
            activeMap.setCameraPosition(CameraPosition.Builder().target(user).zoom(16.0).build())
            cameraInitialized = true
        } else if (followUser) {
            val zoom = activeMap.cameraPosition.zoom.coerceAtLeast(15.0)
            activeMap.animateCamera(CameraUpdateFactory.newLatLngZoom(user, zoom))
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

    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() = mapView.onDestroy()
    fun onLowMemory() = mapView.onLowMemory()
    fun onSaveInstanceState(outState: android.os.Bundle) = mapView.onSaveInstanceState(outState)

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
