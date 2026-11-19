package ru.magazinryadom

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class StoreWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateOne(context, manager, it) }
    }

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StoreWidget::class.java))
            ids.forEach { updateOne(context, manager, it) }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE)
            val name = prefs.getString("name", "Выбери магазин") ?: "Выбери магазин"
            val distance = prefs.getString("distance", "Открой приложение") ?: "Открой приложение"
            val address = prefs.getString("address", "") ?: ""
            val views = RemoteViews(context.packageName, R.layout.store_widget)
            views.setTextViewText(R.id.widget_store, name)
            val detail = if (address.isBlank()) distance else "$distance • $address"
            views.setTextViewText(R.id.widget_distance, detail)
            val intent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_action, intent)
            views.setOnClickPendingIntent(R.id.widget_store, intent)
            manager.updateAppWidget(id, views)
        }
    }
}
