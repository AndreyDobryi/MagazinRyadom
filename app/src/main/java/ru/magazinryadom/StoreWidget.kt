package ru.magazinryadom

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews

class StoreWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context)
    }

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StoreWidget::class.java))
            val preferences = context.getSharedPreferences("chosen", Context.MODE_PRIVATE)

            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.store_widget)
                views.setTextViewText(R.id.widget_name, preferences.getString("name", "Выбери магазин"))
                views.setTextViewText(R.id.widget_address, preferences.getString("address", "Адрес появится после выбора"))
                views.setTextViewText(R.id.widget_distance, preferences.getString("distance", "Расстояние"))

                val mapPath = preferences.getString("map", null)
                val mapBitmap = mapPath?.let { BitmapFactory.decodeFile(it) }
                if (mapBitmap != null) {
                    views.setImageViewBitmap(R.id.widget_map, mapBitmap)
                } else {
                    views.setImageViewResource(R.id.widget_map, android.R.color.darker_gray)
                }

                val openApp = Intent(context, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
