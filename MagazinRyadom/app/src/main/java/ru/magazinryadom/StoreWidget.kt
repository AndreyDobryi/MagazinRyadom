package ru.magazinryadom

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews

class StoreWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StoreWidget::class.java))
            val prefs = context.getSharedPreferences("store_data", Context.MODE_PRIVATE)
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.store_widget)
                val name = prefs.getString("name", null)
                val address = prefs.getString("address", null)
                val distance = prefs.getString("distance", null)
                views.setTextViewText(R.id.widget_name, name ?: "Открой приложение")
                views.setTextViewText(R.id.widget_address, address ?: "Найди магазин, чтобы увидеть его здесь")
                views.setTextViewText(R.id.widget_distance, distance ?: "Расстояние появится после поиска")
                val bitmapPath = prefs.getString("map_path", null)
                if (bitmapPath != null) {
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(bitmapPath)
                        if (bitmap != null) views.setImageViewBitmap(R.id.widget_map, bitmap)
                    } catch (_: Exception) {}
                }
                val intent = Intent(context, MainActivity::class.java)
                val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_root, pending)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
