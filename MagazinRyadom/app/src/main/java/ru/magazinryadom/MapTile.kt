package ru.magazinryadom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

object MapTile {
    fun create(userLat: Double, userLon: Double, storeLat: Double, storeLon: Double): Bitmap? {
        return try {
            val zoom = 15
            val n = 1 shl zoom
            fun tileX(lon: Double) = ((lon + 180.0) / 360.0 * n).toInt()
            fun tileY(lat: Double): Int {
                val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
                return ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * n).toInt()
            }
            val ux = tileX(userLon); val uy = tileY(userLat)
            val url = URL("https://tile.openstreetmap.org/$zoom/$ux/$uy.png")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "MagazinRyadom/1.1")
            val tile = conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            conn.disconnect()
            val output = Bitmap.createBitmap(512, 220, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.rgb(225, 228, 222))
            if (tile != null) {
                canvas.drawBitmap(tile, null, Rect(0, 0, 512, 220), paint)
            }
            val scale = 256.0 * n
            fun px(lon: Double) = ((lon + 180.0) / 360.0 * scale - ux * 256.0 + 256.0).toFloat()
            fun py(lat: Double): Float {
                val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
                val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * scale
                return (y - uy * 256.0 + 110.0).toFloat()
            }
            val userX = 256f; val userY = 110f
            val storeX = px(storeLon); val storeY = py(storeLat)
            paint.color = Color.rgb(38, 111, 224)
            canvas.drawCircle(userX, userY, 9f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(userX, userY, 4f, paint)
            paint.color = Color.rgb(210, 65, 55)
            canvas.drawCircle(storeX, storeY, 8f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(storeX, storeY, 3f, paint)
            tile?.recycle()
            output
        } catch (_: Exception) {
            null
        }
    }
}
