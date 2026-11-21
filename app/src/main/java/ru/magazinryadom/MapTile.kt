package ru.magazinryadom

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/** Creates a multi-tile map preview for the launcher widget. */
object MapTile {
    private const val WIDTH = 512
    private const val HEIGHT = 280
    // Reuse nearby raster tiles instead of repeatedly downloading the same OSM tiles.
    private val tileCache = object : LruCache<String, Bitmap>(3072) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun make(
        userLat: Double,
        userLon: Double,
        storeLat: Double,
        storeLon: Double,
        route: List<Pair<Double, Double>> = emptyList()
    ): Bitmap? {
        if (!valid(userLat, userLon) || !valid(storeLat, storeLon)) return null
        return try {
            // The launcher widget must always show the user's own position at its center.
            // The store and route are drawn relative to this point, never the midpoint of the route.
            val centerLat = userLat
            val centerLon = userLon
            val straightDistance = distanceMeters(userLat, userLon, storeLat, storeLon)
            val zoom = when {
                straightDistance > 8000.0 -> 12
                straightDistance > 4000.0 -> 14
                straightDistance > 1800.0 -> 15
                else -> 16
            }
            val centerX = worldX(centerLon, zoom)
            val centerY = worldY(centerLat, zoom)
            val centerTileX = floor(centerX / 256.0).toInt()
            val centerTileY = floor(centerY / 256.0).toInt()
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Match the light OSM map used by the interactive MapLibre screen.
            canvas.drawColor(Color.rgb(232, 235, 230))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val maxTile = 1 shl zoom

            // A 3x3 tile mosaic gives the widget useful context around both points.
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val tx = centerTileX + dx
                    val ty = centerTileY + dy
                    if (ty !in 0 until maxTile) continue
                    val wrappedX = ((tx % maxTile) + maxTile) % maxTile
                    val tile = downloadTile(zoom, wrappedX, ty) ?: continue
                    val left = (WIDTH / 2.0 + tx * 256.0 - centerX).toFloat()
                    val top = (HEIGHT / 2.0 + ty * 256.0 - centerY).toFloat()
                    canvas.drawBitmap(tile, null, RectF(left, top, left + 256f, top + 256f), paint)
                    tile.recycle()
                }
            }

            // Draw the actual returned walking geometry when available.
            if (route.size >= 2) {
                val path = Path()
                route.forEachIndexed { index, point ->
                    if (!valid(point.first, point.second)) return@forEachIndexed
                    val x = (WIDTH / 2.0 + worldX(point.second, zoom) - centerX).toFloat()
                    val y = (HEIGHT / 2.0 + worldY(point.first, zoom) - centerY).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 10f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(path, paint)
                paint.color = Color.rgb(30, 174, 99)
                paint.strokeWidth = 6f
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            }

            drawMarker(canvas, paint, screenX(userLon, centerX, zoom), screenY(userLat, centerY, zoom), Color.rgb(45, 125, 235))
            drawMarker(canvas, paint, screenX(storeLon, centerX, zoom), screenY(storeLat, centerY, zoom), Color.rgb(218, 165, 32))
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun drawMarker(canvas: Canvas, paint: Paint, x: Float, y: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 11f, paint)
        paint.color = color
        canvas.drawCircle(x, y, 8f, paint)
    }

    private fun downloadTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        synchronized(tileCache) {
            tileCache.get(key)?.let { cached ->
                return cached.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
        val hosts = listOf(
            "https://tile.openstreetmap.org/$z/$x/$y.png",
            "https://tile.openstreetmap.de/$z/$x/$y.png"
        )
        for (tileUrl in hosts) {
            val connection = URL(tileUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 3500
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "MagazinRyadom/1.9.1 (Android; OpenStreetMap client)")
                connection.setRequestProperty("Accept", "image/png,image/*")
                if (connection.responseCode in 200..299) {
                    val decoded = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    if (decoded != null) {
                        synchronized(tileCache) { tileCache.put(key, decoded) }
                        return decoded.copy(Bitmap.Config.ARGB_8888, false)
                    }
                }
            } catch (_: Exception) {
                // Try the secondary public OSM raster host.
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun valid(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -85.0..85.0 && lon in -180.0..180.0

    private fun worldX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * 256.0 * (1 shl zoom)
    private fun worldY(lat: Double, zoom: Int): Double {
        val radians = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val mercator = ln(tan(radians) + 1.0 / cos(radians))
        return (1.0 - mercator / PI) / 2.0 * 256.0 * (1 shl zoom)
    }
    private fun screenX(lon: Double, centerX: Double, zoom: Int) = (WIDTH / 2.0 + worldX(lon, zoom) - centerX).toFloat()
    private fun screenY(lat: Double, centerY: Double, zoom: Int) = (HEIGHT / 2.0 + worldY(lat, zoom) - centerY).toFloat()
    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val x = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 6371000.0 * 2.0 * kotlin.math.asin(kotlin.math.sqrt(x.coerceIn(0.0, 1.0)))
    }
}
