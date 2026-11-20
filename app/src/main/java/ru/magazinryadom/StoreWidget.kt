package ru.magazinryadom

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.RemoteViews

class StoreWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = update(context)

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StoreWidget::class.java))
            val prefs = context.getSharedPreferences("chosen", Context.MODE_PRIVATE)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.store_widget)
                val network = prefs.getString("network", "").orEmpty()
                val name = prefs.getString("name", "").orEmpty()
                val address = prefs.getString("address", "").orEmpty()
                val distance = prefs.getString("distance", "").orEmpty()
                val walkDistance = prefs.getString("walk_distance", "").orEmpty()
                val eta = prefs.getString("walk_eta", "").orEmpty()
                views.setTextViewText(R.id.widget_network, if (network.isBlank()) "Сеть не выбрана · открой приложение" else "СЕТЬ: $network")
                views.setTextViewText(R.id.widget_name, name.ifBlank { "Выбери сеть в приложении" })
                views.setTextViewText(R.id.widget_address, address.ifBlank { "Здесь появится адрес ближайшего филиала" })
                views.setTextViewText(R.id.widget_distance, if (walkDistance.isNotBlank()) "Пешком по маршруту: $walkDistance" else if (distance.isNotBlank()) "По прямой: $distance" else "Расстояние появится после поиска")
                views.setTextViewText(R.id.widget_eta, if (eta.isNotBlank() && distance.isNotBlank()) "$eta · по прямой $distance" else eta.ifBlank { "Пешее время появится после построения маршрута" })
                views.setTextViewText(R.id.widget_status, if (name.isBlank()) "ОЖИДАНИЕ" else "БЛИЖАЙШИЙ")

                val mapPath = prefs.getString("map", null)
                val mapBitmap = mapPath?.let { BitmapFactory.decodeFile(it) }
                if (mapBitmap != null) views.setImageViewBitmap(R.id.widget_map, mapBitmap)
                else views.setImageViewResource(R.id.widget_map, android.R.color.darker_gray)

                val openApp = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val openPending = PendingIntent.getActivity(context, 10, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_root, openPending)
                views.setOnClickPendingIntent(R.id.widget_map, openPending)

                val refreshIntent = Intent(context, MainActivity::class.java).apply {
                    putExtra("widget_refresh", true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val refreshPending = PendingIntent.getActivity(context, 11, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_refresh, refreshPending)

                val fromLat = prefs.getString("user_lat", null)
                val fromLon = prefs.getString("user_lon", null)
                val toLat = prefs.getString("lat", null)
                val toLon = prefs.getString("lon", null)
                val routeIntent = if (fromLat != null && fromLon != null && toLat != null && toLon != null) {
                    val url = "https://www.openstreetmap.org/directions?engine=fossgis_osrm_foot&route=$fromLat%2C$fromLon%3B$toLat%2C$toLon"
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                } else openApp
                val routePending = PendingIntent.getActivity(context, 12, routeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_route, routePending)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
