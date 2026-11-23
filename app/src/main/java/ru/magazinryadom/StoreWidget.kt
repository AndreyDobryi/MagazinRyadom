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
        fun update(context: Context) = updateInternal(context, updateMap = true)

        /** Refresh text/distance without decoding or replacing the cached map image. */
        fun updateLocation(context: Context) = updateInternal(context, updateMap = false)

        private fun updateInternal(context: Context, updateMap: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StoreWidget::class.java))
            val prefs = context.getSharedPreferences("chosen", Context.MODE_PRIVATE)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.store_widget)
                val network = prefs.getString("network", "").orEmpty()
                views.setTextViewText(
                    R.id.widget_network,
                    if (network.isBlank()) "Выбери сеть в приложении" else network
                )
                val storeName = prefs.getString("name", "").orEmpty()
                val address = prefs.getString("address", "").orEmpty()
                val distance = prefs.getString("distance", "").orEmpty()
                val details = when {
                    storeName.isBlank() -> "Ближайший магазин ещё не выбран"
                    address.isBlank() && distance.isBlank() -> storeName
                    else -> listOf(
                        storeName,
                        address,
                        if (distance.isNotBlank()) "От тебя: $distance по прямой" else ""
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                }
                views.setTextViewText(R.id.widget_store_details, details)

                if (updateMap) {
                    val mapPath = prefs.getString("map", null)
                    val mapBitmap = mapPath?.let { BitmapFactory.decodeFile(it) }
                    if (mapBitmap != null) views.setImageViewBitmap(R.id.widget_map, mapBitmap)
                    else views.setImageViewResource(R.id.widget_map, android.R.color.darker_gray)
                }

                // The launcher widget is a snapshot; tapping its map opens the live,
                // draggable MapLibre map and asks the app to refresh the current location.
                val openApp = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("widget_refresh", true)
                }
                val openPending = PendingIntent.getActivity(
                    context, 10, openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, openPending)
                views.setOnClickPendingIntent(R.id.widget_map, openPending)

                manager.updateAppWidget(id, views)
            }
        }
    }
}
