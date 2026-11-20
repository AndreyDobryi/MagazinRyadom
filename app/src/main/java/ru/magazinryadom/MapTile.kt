package ru.magazinryadom

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Creates a multi-tile map preview for the launcher widget. */
object MapTile {
    private const val ZOOM = 16
    private const val WIDTH = 512
    private const val HEIGHT = 280

    fun make(
        userLat: Double,
        userLon: Double,
        storeLat: Double,
        storeLon: Double,
        route: List<Pair<Double, Double>> = emptyList()
    ): Bitmap? {
        if (!valid(userLat, userLon) || !valid(storeLat, storeLon)) return null
        return try {
            val centerLat = (userLat + storeLat) / 2.0
            val centerLon = (userLon + storeLon) / 2.0
            val centerX = worldX(centerLon)
            val centerY = worldY(centerLat)
            val centerTileX = floor(centerX / 256.0).toInt()
            val centerTileY = floor(centerY / 256.0).toInt()
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(35, 39, 45))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val maxTile = 1 shl ZOOM

            // A 3x3 tile mosaic gives the widget useful context around both points.
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val tx = centerTileX + dx
                    val ty = centerTileY + dy
                    if (ty !in 0 until maxTile) continue
                    val wrappedX = ((tx % maxTile) + maxTile) % maxTile
                    val tile = downloadTile(ZOOM, wrappedX, ty) ?: continue
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
                    val x = (WIDTH / 2.0 + worldX(point.second) - centerX).toFloat()
                    val y = (HEIGHT / 2.0 + worldY(point.first) - centerY).toFloat()
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

            drawMarker(canvas, paint, screenX(userLon, centerX), screenY(userLat, centerY), Color.rgb(45, 125, 235))
            drawMarker(canvas, paint, screenX(storeLon, centerX), screenY(storeLat, centerY), Color.rgb(218, 67, 67))
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
        val connection = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.setRequestProperty("User-Agent", "MagazinRyadom/1.6.0 (Android)")
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun valid(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -85.0..85.0 && lon in -180.0..180.0

    private fun worldX(lon: Double): Double = (lon + 180.0) / 360.0 * 256.0 * (1 shl ZOOM)
    private fun worldY(lat: Double): Double {
        val radians = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val mercator = ln(tan(radians) + 1.0 / cos(radians))
        return (1.0 - mercator / PI) / 2.0 * 256.0 * (1 shl ZOOM)
    }
    private fun screenX(lon: Double, centerX: Double) = (WIDTH / 2.0 + worldX(lon) - centerX).toFloat()
    private fun screenY(lat: Double, centerY: Double) = (HEIGHT / 2.0 + worldY(lat) - centerY).toFloat()
}
