package com.example.watertracker

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// --- DataStore Setup ---
val Context.dataStore by preferencesDataStore(name = "water_tracker")

class WaterRepository(private val context: Context) {
    private val INTAKE_KEY = intPreferencesKey("daily_intake")
    val dailyIntake: Flow<Int> = context.dataStore.data.map { it[INTAKE_KEY] ?: 0 }
    suspend fun logWater(amount: Int = 250) {
        context.dataStore.edit { preferences ->
            val current = preferences[INTAKE_KEY] ?: 0
            preferences[INTAKE_KEY] = current + amount
        }
    }
}

// --- Notification Helper ---
class NotificationHelper(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "water_channel"
        const val NOTIFICATION_ID = 101
    }
    fun showWaterNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Water Reminders", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)

        val logIntent = Intent(context, LogWaterReceiver::class.java)
        val logPending = PendingIntent.getBroadcast(context, 2, logIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val dismissIntent = Intent(context, DismissReceiver::class.java)
        val dismissPending = PendingIntent.getBroadcast(context, 3, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Time to Hydrate!")
            .setContentText("Drink 250ml of water to stay on track.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_input_add, "Log 250ml", logPending)
            .setDeleteIntent(dismissPending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

// --- Broadcast Receivers ---
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val rescheduleIntent = Intent(context, RescheduleReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 1, rescheduleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 30000, pendingIntent)
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper(context).showWaterNotification()
    }
}

class LogWaterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CoroutineScope(Dispatchers.IO).launch {
            WaterRepository(context).logWater(250)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NotificationHelper.NOTIFICATION_ID)
        }
    }
}

// --- User Interface ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = WaterRepository(this)
        
        // Trigger a test notification when the app opens so you can see it working
        NotificationHelper(this).showWaterNotification()

        setContent {
            MaterialTheme { WaterTrackerScreen(repository) }
        }
    }
}

@Composable
fun WaterTrackerScreen(repository: WaterRepository) {
    val intake by repository.dailyIntake.collectAsState(initial = 0)
    val backgroundColor = Color(0xFFE0E5EC) 

    Column(
        modifier = Modifier.fillMaxSize().background(backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Daily Intake", fontSize = 24.sp, color = Color.DarkGray)
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier.size(200.dp).shadow(8.dp, RoundedCornerShape(100.dp)).background(backgroundColor, RoundedCornerShape(100.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("$intake ml", fontSize = 36.sp, color = Color(0xFF4A90E2))
        }
    }
}
