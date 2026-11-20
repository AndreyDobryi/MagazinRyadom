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
                views.setTextViewText(
                    R.id.widget_network,
                    if (network.isBlank()) "Выбери сеть в приложении" else network
                )

                val mapPath = prefs.getString("map", null)
                val mapBitmap = mapPath?.let { BitmapFactory.decodeFile(it) }
                if (mapBitmap != null) views.setImageViewBitmap(R.id.widget_map, mapBitmap)
                else views.setImageViewResource(R.id.widget_map, android.R.color.darker_gray)

                val openApp = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
