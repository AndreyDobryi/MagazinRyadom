package ru.magazinryadom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Интерактивная векторная карта на базе бесплатного OpenFreeMap. */
class MapPage(context: Context, savedInstanceState: android.os.Bundle? = null) {
    val view: FrameLayout
    private val mapView: MapView
    private var followUser = true
    private var cameraInitialized = false
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
        userMarkerIcon = markerIcon(context, Color.rgb(35, 125, 245))
        storeMarkerIcon = markerIcon(context, Color.rgb(230, 160, 35))
        view = FrameLayout(context)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (360 * context.resources.displayMetrics.density).toInt()
        )
        mapView = MapView(context)
        // Keep map gestures inside the MapView instead of the surrounding ScrollView.
        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_POINTER_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> mapView.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> mapView.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        view.addView(mapView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Familiar floating controls: return to location and adjust zoom.
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val density = context.resources.displayMetrics.density
        fun addControl(symbol: String, description: String, action: () -> Unit): TextView {
            val button = TextView(context).apply {
                text = symbol
                contentDescription = description
                textSize = if (symbol == "◎") 25f else 27f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(40, 50, 62))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke((1 * density).toInt().coerceAtLeast(1), Color.rgb(225, 229, 234))
                }
                elevation = 7 * density
                setOnClickListener { action() }
            }
            controls.addView(button, LinearLayout.LayoutParams(
                (46 * density).toInt(), (46 * density).toInt()
            ).apply { bottomMargin = (8 * density).toInt() })
            return button
        }

        recenterButton = addControl("◎", "Вернуться к моему местоположению") {
            followUser = true
            recenterButton?.visibility = View.GONE
            pendingUser?.let { point ->
                val zoom = (map?.cameraPosition?.zoom ?: 17.3).coerceAtLeast(17.3)
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, zoom))
            }
        }.apply { visibility = View.GONE }

        addControl("+", "Приблизить карту") {
            map?.let { activeMap ->
                activeMap.animateCamera(CameraUpdateFactory.zoomIn())
            }
        }
        addControl("−", "Отдалить карту") {
            map?.let { activeMap ->
                activeMap.animateCamera(CameraUpdateFactory.zoomOut())
            }
        }

        val controlsParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP
        ).apply {
            topMargin = (12 * density).toInt()
            marginEnd = (12 * density).toInt()
        }
        view.addView(controls, controlsParams)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapboxMap ->
            map = mapboxMap
            mapboxMap.uiSettings.isScrollGesturesEnabled = true
            mapboxMap.uiSettings.isZoomGesturesEnabled = true
            // Keep north at the top so the map is easier to read while navigating.
            mapboxMap.uiSettings.isRotateGesturesEnabled = true
            mapboxMap.uiSettings.isTiltGesturesEnabled = false
            mapboxMap.uiSettings.isCompassEnabled = true
            mapboxMap.uiSettings.isLogoEnabled = true
            mapboxMap.uiSettings.isAttributionEnabled = true
            mapboxMap.addOnCameraMoveStartedListener { reason ->
                if (reason == 1) {
                    followUser = false
                    recenterButton?.visibility = View.VISIBLE
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
            mapboxMap.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/bright")) {
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
            activeMap.addPolyline(
                PolylineOptions().addAll(points)
                    .color(Color.rgb(35, 170, 95))
                    .width(6f)
            )
        }
        pendingStores.forEach { store ->
            val marker = MarkerOptions()
                .position(LatLng(store.lat, store.lon))
                .title(store.name)
                .snippet(store.address)
            storeMarkerIcon?.let { marker.icon(it) }
            activeMap.addMarker(marker)
        }
        // Keep the user's position close-up; never zoom out to fit the entire route.
        if (!cameraInitialized) {
            activeMap.setCameraPosition(CameraPosition.Builder().target(user).zoom(17.3).build())
            cameraInitialized = true
        } else if (followUser) {
            val zoom = activeMap.cameraPosition.zoom.coerceAtLeast(17.3)
            activeMap.animateCamera(CameraUpdateFactory.newLatLngZoom(user, zoom))
        }
    }

    private fun markerIcon(context: Context, color: Int): Icon {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(32f, 32f, 18f, paint)
        paint.color = color
        canvas.drawCircle(32f, 32f, 14f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(32f, 32f, 5f, paint)
        return IconFactory.getInstance(context).fromBitmap(bitmap)
    }

    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() = mapView.onDestroy()
    fun onLowMemory() = mapView.onLowMemory()
    fun onSaveInstanceState(outState: android.os.Bundle) = mapView.onSaveInstanceState(outState)
}
