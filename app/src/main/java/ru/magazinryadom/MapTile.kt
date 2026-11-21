package ru.magazinryadom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/**
 * Renders the widget preview with the exact OpenFreeMap style used by MapPage.
 * Android widgets display a bitmap, so this is a fresh MapLibre snapshot.
 */
object MapTile {
    private const val WIDTH = 512
    private const val HEIGHT = 280
    private const val STYLE_URL = "https://tiles.openfreemap.org/styles/bright"

    fun make(
        context: Context,
        userLat: Double,
        userLon: Double,
        storeLat: Double,
        storeLon: Double,
        route: List<Pair<Double, Double>> = emptyList()
    ): Bitmap? {
        if (!valid(userLat, userLon) || !valid(storeLat, storeLon)) return null

        val distance = distanceMeters(userLat, userLon, storeLat, storeLon)
        val zoom = when {
            distance > 8000.0 -> 12.0
            distance > 4000.0 -> 13.5
            distance > 1800.0 -> 14.5
            distance > 900.0 -> 15.5
            else -> 16.5
        }

        val latch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>(null)
        val snapshotterRef = AtomicReference<MapSnapshotter?>(null)

        val startSnapshot = Runnable {
            try {
                val appContext = context.applicationContext
                MapLibre.getInstance(appContext)
                val styleUrls = listOf(
                    STYLE_URL,
                    "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"
                )
                fun startStyleAttempt(index: Int) {
                    if (index >= styleUrls.size) {
                        latch.countDown()
                        return
                    }
                    try {
                        val options = MapSnapshotter.Options(WIDTH, HEIGHT)
                            .withStyleBuilder(Style.Builder().fromUri(styleUrls[index]))
                            .withCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(userLat, userLon))
                                    .zoom(zoom)
                                    .build()
                            )
                            .withLogo(false)
                            .withAttribution(false)
                        val snapshotter = MapSnapshotter(appContext, options)
                        snapshotterRef.set(snapshotter)
                        snapshotter.start(
                            { snapshot ->
                                try {
                                    val bitmap = snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                    drawRouteAndMarkers(
                                        snapshot, bitmap, userLat, userLon, storeLat, storeLon, route
                                    )
                                    result.set(bitmap)
                                    latch.countDown()
                                } catch (_: Exception) {
                                    if (index + 1 < styleUrls.size) Handler(Looper.getMainLooper()).post { startStyleAttempt(index + 1) }
                                    else latch.countDown()
                                }
                            },
                            {
                                if (index + 1 < styleUrls.size) Handler(Looper.getMainLooper()).post { startStyleAttempt(index + 1) }
                                else latch.countDown()
                            }
                        )
                    } catch (_: Exception) {
                        if (index + 1 < styleUrls.size) Handler(Looper.getMainLooper()).post { startStyleAttempt(index + 1) }
                        else latch.countDown()
                    }
                }
                startStyleAttempt(0)
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        // MapSnapshotter must be created and started on the Android main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startSnapshot.run()
        } else {
            Handler(Looper.getMainLooper()).post(startSnapshot)
        }

        val completed = try {
            latch.await(25, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!completed) {
            Handler(Looper.getMainLooper()).post {
                try { snapshotterRef.get()?.cancel() } catch (_: Exception) { }
            }
        }
        return result.get()
    }

    private fun drawRouteAndMarkers(
        snapshot: org.maplibre.android.snapshotter.MapSnapshot,
        bitmap: Bitmap,
        userLat: Double,
        userLon: Double,
        storeLat: Double,
        storeLon: Double,
        route: List<Pair<Double, Double>>
    ) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val routePoints = route.mapNotNull { point ->
            if (valid(point.first, point.second)) {
                snapshot.pixelForLatLng(LatLng(point.first, point.second))
            } else null
        }
        if (routePoints.size >= 2) {
            val path = Path().apply {
                moveTo(routePoints.first().x, routePoints.first().y)
                routePoints.drop(1).forEach { lineTo(it.x, it.y) }
            }
            paint.color = Color.WHITE
            paint.strokeWidth = 10f
            canvas.drawPath(path, paint)
            paint.color = Color.rgb(35, 170, 95)
            paint.strokeWidth = 6f
            canvas.drawPath(path, paint)
        }

        drawMarker(canvas, paint, snapshot.pixelForLatLng(LatLng(userLat, userLon)).x,
            snapshot.pixelForLatLng(LatLng(userLat, userLon)).y, Color.rgb(35, 125, 245))
        val storePoint = snapshot.pixelForLatLng(LatLng(storeLat, storeLon))
        drawMarker(canvas, paint, storePoint.x, storePoint.y, Color.rgb(230, 160, 35))
    }

    private fun drawMarker(canvas: Canvas, paint: Paint, x: Float, y: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 12f, paint)
        paint.color = color
        canvas.drawCircle(x, y, 8.5f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 3f, paint)
    }

    private fun valid(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -85.0..85.0 && lon in -180.0..180.0

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val x = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 6371000.0 * 2.0 * kotlin.math.asin(kotlin.math.sqrt(x.coerceIn(0.0, 1.0)))
    }
}
