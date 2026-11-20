package ru.magazinryadom

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

object MapTile {
    fun make(userLat: Double, userLon: Double, storeLat: Double, storeLon: Double): Bitmap? {
        return try {
            val zoom = 15
            val tileCount = 1 shl zoom
            fun worldX(lon: Double): Double = (lon + 180.0) / 360.0 * 256.0 * tileCount
            fun worldY(lat: Double): Double {
                val radians = Math.toRadians(lat.coerceIn(-85.0, 85.0))
                val mercator = ln(tan(radians) + 1.0 / cos(radians))
                return (1.0 - mercator / PI) / 2.0 * 256.0 * tileCount
            }

            val userX = worldX(userLon)
            val userY = worldY(userLat)
            val tileX = floor(userX / 256.0).toInt()
            val tileY = floor(userY / 256.0).toInt()
            val connection = URL("https://tile.openstreetmap.org/$zoom/$tileX/$tileY.png")
                .openConnection() as HttpsURLConnection
            connection.setRequestProperty("User-Agent", "MagazinRyadom/1.4 (Android app)")
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            val tile = connection.inputStream.use { BitmapFactory.decodeStream(it) }
            connection.disconnect()

            val bitmap = Bitmap.createBitmap(512, 220, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(235, 235, 235))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (tile != null) {
                val left = (256.0 + tileX * 256.0 - userX).toFloat()
                val top = (110.0 + tileY * 256.0 - userY).toFloat()
                canvas.drawBitmap(tile, null, Rect(left.toInt(), top.toInt(), (left + 256).toInt(), (top + 256).toInt()), paint)
            }

            paint.color = Color.rgb(35, 105, 220)
            canvas.drawCircle(256f, 110f, 9f, paint)
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawCircle(256f, 110f, 9f, paint)
            paint.style = Paint.Style.FILL

            val storeX = (256.0 + worldX(storeLon) - userX).toFloat()
            val storeY = (110.0 + worldY(storeLat) - userY).toFloat()
            if (storeX in 0f..512f && storeY in 0f..220f) {
                paint.color = Color.rgb(205, 55, 55)
                canvas.drawCircle(storeX, storeY, 8f, paint)
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawCircle(storeX, storeY, 8f, paint)
            }
            tile?.recycle()
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
