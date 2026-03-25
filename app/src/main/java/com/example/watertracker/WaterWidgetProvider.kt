package com.example.watertracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WaterWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_LOG_WATER = "com.example.watertracker.LOG_WATER"
        const val EXTRA_AMOUNT = "amount"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val repo = WaterRepository(context)
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = repo.preferencesFlow.first()
            val logsStr = prefs[repo.LOGS_KEY] ?: "[]"
            val baseTarget = prefs[repo.TARGET_KEY] ?: 2000
            val isHotWeather = prefs[repo.HOT_WEATHER_KEY] ?: false
            val target = if (isHotWeather) (baseTarget * 1.15).toInt() else baseTarget
            val todayLogs = repo.getTodayLogs(logsStr)
            val currentIntake = todayLogs.sumOf { it.amountMl }

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_water)
                views.setTextViewText(R.id.widget_intake, "$currentIntake / $target ml")

                // Wire up the +250ml Button
                val intent250 = Intent(context, WaterWidgetProvider::class.java).apply {
                    action = ACTION_LOG_WATER
                    putExtra(EXTRA_AMOUNT, 250)
                }
                val pending250 = PendingIntent.getBroadcast(context, 250, intent250, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.btn_widget_250, pending250)

                // Wire up the +500ml Button
                val intent500 = Intent(context, WaterWidgetProvider::class.java).apply {
                    action = ACTION_LOG_WATER
                    putExtra(EXTRA_AMOUNT, 500)
                }
                val pending500 = PendingIntent.getBroadcast(context, 500, intent500, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.btn_widget_500, pending500)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    // This catches the button clicks in the background
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_LOG_WATER) {
            val amount = intent.getIntExtra(EXTRA_AMOUNT, 0)
            if (amount > 0) {
                CoroutineScope(Dispatchers.IO).launch {
                    WaterRepository(context).addLog(amount)
                }
            }
        }
    }
}
