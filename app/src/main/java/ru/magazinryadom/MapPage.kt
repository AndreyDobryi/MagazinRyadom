package ru.magazinryadom

import android.content.Context
import android.view.ViewGroup
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.MarkerOptions

/** Нативная карта MapLibre. Базовый стиль использует растровые тайлы OSM. */
class MapPage(context: Context, savedInstanceState: android.os.Bundle? = null) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var styleReady = false
    private var pendingUser: LatLng? = null
    private var pendingStores: List<Store> = emptyList()

    init {
        MapLibre.getInstance(context.applicationContext)
        view = MapView(context)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (230 * context.resources.displayMetrics.density).toInt()
        )
        view.onCreate(savedInstanceState)
        view.getMapAsync { mapboxMap ->
            map = mapboxMap
            mapboxMap.setStyle(Style.Builder().fromJson(styleJson())) {
                styleReady = true
                renderPending()
            }
        }
    }

    fun show(latitude: Double, longitude: Double, stores: List<Store>) {
        pendingUser = LatLng(latitude, longitude)
        pendingStores = stores
        renderPending()
    }

    private fun renderPending() {
        val activeMap = map ?: return
        if (!styleReady) return
        val user = pendingUser ?: return
        activeMap.clear()
        activeMap.addMarker(MarkerOptions().position(user).title("Вы здесь"))
        pendingStores.forEach { store ->
            activeMap.addMarker(
                MarkerOptions()
                    .position(LatLng(store.lat, store.lon))
                    .title(store.name)
                    .snippet(store.address)
            )
        }
        val target = pendingStores.firstOrNull()?.let { LatLng(it.lat, it.lon) } ?: user
        activeMap.setCameraPosition(CameraPosition.Builder()
            .target(target)
            .zoom(if (pendingStores.isEmpty()) 14.5 else 13.5)
            .build())
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
