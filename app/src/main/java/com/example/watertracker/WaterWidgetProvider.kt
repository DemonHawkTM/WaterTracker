package com.example.watertracker

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WaterWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val repo = WaterRepository(context)
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = repo.preferencesFlow.first()
            val logsStr = prefs[stringPreferencesKey("water_logs")] ?: "[]"
            val target = prefs[repo.TARGET_KEY] ?: 2000
            val todayLogs = repo.getTodayLogs(logsStr)
            val currentIntake = todayLogs.sumOf { it.amountMl }

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_water)
                views.setTextViewText(R.id.widget_intake, "$currentIntake / $target ml")
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
