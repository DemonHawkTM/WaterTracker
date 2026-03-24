package com.example.watertracker

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.ceil

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

// --- Math Logic ---
fun calculateIntervalMillis(targetVolume: Int, wakeHour: Int, wakeMin: Int, sleepHour: Int, sleepMin: Int): Long {
    val increments = ceil(targetVolume / 250.0).toInt()
    if (increments <= 0) return 0L

    val wakeTimeInMinutes = (wakeHour * 60) + wakeMin
    var sleepTimeInMinutes = (sleepHour * 60) + sleepMin

    if (sleepTimeInMinutes <= wakeTimeInMinutes) {
        sleepTimeInMinutes += (24 * 60) // Handle sleeping past midnight
    }

    val activeDurationMillis = (sleepTimeInMinutes - wakeTimeInMinutes) * 60 * 1000L
    return activeDurationMillis / increments
}

// --- User Interface ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = WaterRepository(this)
        setContent {
            MaterialTheme { WaterTrackerScreen(repository) }
        }
    }
}

@Composable
fun WaterTrackerScreen(repository: WaterRepository) {
    val context = LocalContext.current
    val intake by repository.dailyIntake.collectAsState(initial = 0)
    
    // UI State
    var targetVolume by remember { mutableStateOf("2000") }
    var wakeTime by remember { mutableStateOf(Pair(8, 0)) } // 8:00 AM default
    var sleepTime by remember { mutableStateOf(Pair(22, 0)) } // 10:00 PM default

    // Permission Request Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) Toast.makeText(context, "Notifications are required for reminders", Toast.LENGTH_LONG).show()
    }

    // Ask for permission on first launch (Android 13+)
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Time Picker Dialogs
    val wakePickerDialog = TimePickerDialog(
        context, { _, hour, min -> wakeTime = Pair(hour, min) },
        wakeTime.first, wakeTime.second, false
    )
    val sleepPickerDialog = TimePickerDialog(
        context, { _, hour, min -> sleepTime = Pair(hour, min) },
        sleepTime.first, sleepTime.second, false
    )

    val backgroundColor = Color(0xFFE0E5EC)

    Column(
        modifier = Modifier.fillMaxSize().background(backgroundColor).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Daily Intake Circle
        Box(
            modifier = Modifier.size(200.dp).shadow(8.dp, RoundedCornerShape(100.dp)).background(backgroundColor, RoundedCornerShape(100.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$intake ml", fontSize = 36.sp, color = Color(0xFF4A90E2))
                Text("Logged Today", fontSize = 14.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Target Input
        OutlinedTextField(
            value = targetVolume,
            onValueChange = { targetVolume = it },
            label = { Text("Daily Target (ml)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Time Selectors
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedCard(modifier = Modifier.weight(1f).clickable { wakePickerDialog.show() }) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Wake Time", color = Color.Gray)
                    Text(String.format("%02d:%02d", wakeTime.first, wakeTime.second), fontSize = 20.sp)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            OutlinedCard(modifier = Modifier.weight(1f).clickable { sleepPickerDialog.show() }) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sleep Time", color = Color.Gray)
                    Text(String.format("%02d:%02d", sleepTime.first, sleepTime.second), fontSize = 20.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Start Tracker Button
        Button(
            onClick = {
                val target = targetVolume.toIntOrNull() ?: 2000
                val interval = calculateIntervalMillis(target, wakeTime.first, wakeTime.second, sleepTime.first, sleepTime.second)
                
                // Schedule the first alarm
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, RescheduleReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + interval,
                    pendingIntent
                )
                
                Toast.makeText(context, "Tracker Started! Next reminder in ${interval / (1000 * 60)} minutes.", Toast.LENGTH_LONG).show()
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Start Tracker", fontSize = 18.sp)
        }
    }
}
