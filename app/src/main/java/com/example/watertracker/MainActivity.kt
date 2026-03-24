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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.ceil

// --- Core Data Structures ---
data class LogEntry(val id: Long, val timestamp: Long, val amountMl: Int)

val Context.dataStore by preferencesDataStore(name = "water_tracker_prefs")

class WaterRepository(private val context: Context) {
    private val LOGS_KEY = stringPreferencesKey("water_logs")
    val TARGET_KEY = intPreferencesKey("daily_target")
    val WAKE_MINS_KEY = intPreferencesKey("wake_mins")
    val SLEEP_MINS_KEY = intPreferencesKey("sleep_mins")
    val USE_OZ_KEY = booleanPreferencesKey("use_oz")

    val preferencesFlow = context.dataStore.data

    suspend fun addLog(amountMl: Int) {
        context.dataStore.edit { prefs ->
            val logs = deserializeLogs(prefs[LOGS_KEY] ?: "[]").toMutableList()
            logs.add(LogEntry(System.currentTimeMillis(), System.currentTimeMillis(), amountMl))
            prefs[LOGS_KEY] = serializeLogs(logs)
        }
        rescheduleSmartAlarms(context)
    }

    suspend fun removeLog(id: Long) {
        context.dataStore.edit { prefs ->
            val logs = deserializeLogs(prefs[LOGS_KEY] ?: "[]").filter { it.id != id }
            prefs[LOGS_KEY] = serializeLogs(logs)
        }
        rescheduleSmartAlarms(context)
    }

    suspend fun updateSettings(target: Int, wake: Int, sleep: Int, useOz: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[TARGET_KEY] = target
            prefs[WAKE_MINS_KEY] = wake
            prefs[SLEEP_MINS_KEY] = sleep
            prefs[USE_OZ_KEY] = useOz
        }
        rescheduleSmartAlarms(context)
    }

    fun getTodayLogs(logsStr: String): List<LogEntry> {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        return deserializeLogs(logsStr).filter { it.timestamp >= startOfDay }
    }

    fun deserializeLogs(json: String): List<LogEntry> {
        val list = mutableListOf<LogEntry>()
        if (json.isEmpty()) return list
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(LogEntry(obj.getLong("id"), obj.getLong("ts"), obj.getInt("amt")))
        }
        return list
    }

    private fun serializeLogs(logs: List<LogEntry>): String {
        val array = JSONArray()
        logs.forEach {
            array.put(JSONObject().apply { put("id", it.id); put("ts", it.timestamp); put("amt", it.amountMl) })
        }
        return array.toString()
    }
}

// --- Smart Scheduling Logic ---
fun rescheduleSmartAlarms(context: Context) {
    CoroutineScope(Dispatchers.IO).launch {
        val repo = WaterRepository(context)
        // FIX: Using .first() grabs the current snapshot without creating a continuous loop
        val prefs = repo.preferencesFlow.first()
        
        val target = prefs[repo.TARGET_KEY] ?: 2000
        val wakeMins = prefs[repo.WAKE_MINS_KEY] ?: (8 * 60)
        val sleepMins = prefs[repo.SLEEP_MINS_KEY] ?: (22 * 60)
        val logs = repo.getTodayLogs(prefs[stringPreferencesKey("water_logs")] ?: "[]")
        
        val currentIntake = logs.sumOf { it.amountMl }
        val remainingTarget = target - currentIntake

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RescheduleReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Cancel existing alarm
        alarmManager.cancel(pendingIntent)

        if (remainingTarget <= 0) return@launch // Goal met, mute alarms

        val now = Calendar.getInstance()
        val nowMins = (now.get(Calendar.HOUR_OF_DAY) * 60) + now.get(Calendar.MINUTE)
        
        var effectiveSleepMins = sleepMins
        if (sleepMins <= wakeMins) effectiveSleepMins += (24 * 60)
        
        val remainingMinutes = effectiveSleepMins - nowMins
        if (remainingMinutes <= 0) return@launch // Day is over

        val incrementsNeeded = ceil(remainingTarget / 250.0).toInt()
        if (incrementsNeeded <= 0) return@launch

        val intervalMillis = (remainingMinutes * 60 * 1000L) / incrementsNeeded
        
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + intervalMillis,
            pendingIntent
        )
    }
}

// --- Notification Helpers & Receivers ---
class NotificationHelper(private val context: Context) {
    companion object { const val CHANNEL_ID = "water_channel"; const val NOTIFICATION_ID = 101 }
    fun showWaterNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)

        val logIntent = Intent(context, LogWaterReceiver::class.java)
        val logPending = PendingIntent.getBroadcast(context, 2, logIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val dismissIntent = Intent(context, DismissReceiver::class.java)
        val dismissPending = PendingIntent.getBroadcast(context, 3, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hydration Time!")
            .setContentText("Drink 250ml to stay on track.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_input_add, "Log 250ml", logPending)
            .setDeleteIntent(dismissPending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

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
            WaterRepository(context).addLog(250)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NotificationHelper.NOTIFICATION_ID)
        }
    }
}

// --- UI Components ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = WaterRepository(this)
        setContent { MaterialTheme { MainAppScreen(repo) } }
    }
}

@Composable
fun MainAppScreen(repo: WaterRepository) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(0) }
    val prefs by repo.preferencesFlow.collectAsState(initial = null)
    
    val logsStr = prefs?.get(stringPreferencesKey("water_logs")) ?: "[]"
    val allLogs = repo.deserializeLogs(logsStr)
    val todayLogs = repo.getTodayLogs(logsStr)
    val target = prefs?.get(repo.TARGET_KEY) ?: 2000
    val useOz = prefs?.get(repo.USE_OZ_KEY) ?: false

    val currentIntake = todayLogs.sumOf { it.amountMl }

    // Permissions
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(icon = { Icon(Icons.Default.Home, "Home") }, label = { Text("Home") }, selected = currentTab == 0, onClick = { currentTab = 0 })
                NavigationBarItem(icon = { Icon(Icons.Default.List, "Log") }, label = { Text("Log") }, selected = currentTab == 1, onClick = { currentTab = 1 })
                NavigationBarItem(icon = { Icon(Icons.Default.DateRange, "Chart") }, label = { Text("Chart") }, selected = currentTab == 2, onClick = { currentTab = 2 })
                NavigationBarItem(icon = { Icon(Icons.Default.Settings, "Settings") }, label = { Text("Settings") }, selected = currentTab == 3, onClick = { currentTab = 3 })
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFFE0E5EC))) {
            when (currentTab) {
                0 -> HomeTab(repo, currentIntake, target, useOz)
                1 -> HistoryTab(repo, todayLogs, useOz)
                2 -> ChartTab(allLogs)
                3 -> SettingsTab(repo, prefs)
            }
        }
    }
}

@Composable
fun HomeTab(repo: WaterRepository, currentIntake: Int, target: Int, useOz: Boolean) {
    val coroutineScope = rememberCoroutineScope()
    val displayIntake = if (useOz) (currentIntake * 0.033814).toInt() else currentIntake
    val displayTarget = if (useOz) (target * 0.033814).toInt() else target
    val unit = if (useOz) "oz" else "ml"

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.size(250.dp).shadow(12.dp, RoundedCornerShape(150.dp)).background(Color(0xFFE0E5EC), RoundedCornerShape(150.dp)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$displayIntake / $displayTarget $unit", fontSize = 32.sp, color = Color(0xFF4A90E2))
                Text("Today's Intake", color = Color.Gray)
            }
        }
        Spacer(modifier = Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { coroutineScope.launch { repo.addLog(150) } }) { Text("+150ml Cup") }
            Button(onClick = { coroutineScope.launch { repo.addLog(250) } }) { Text("+250ml Glass") }
            Button(onClick = { coroutineScope.launch { repo.addLog(500) } }) { Text("+500ml Bottle") }
        }
    }
}

@Composable
fun HistoryTab(repo: WaterRepository, logs: List<LogEntry>, useOz: Boolean) {
    val coroutineScope = rememberCoroutineScope()
    val unit = if (useOz) "oz" else "ml"
    
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("Today's Logs", fontSize = 24.sp, modifier = Modifier.padding(bottom = 16.dp)) }
        items(logs.reversed()) { log ->
            val cal = Calendar.getInstance().apply { timeInMillis = log.timestamp }
            val timeString = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            val displayAmt = if (useOz) (log.amountMl * 0.033814).toInt() else log.amountMl
            
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("$timeString - $displayAmt $unit", fontSize = 18.sp)
                    IconButton(onClick = { coroutineScope.launch { repo.removeLog(log.id) } }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                    }
                }
            }
        }
    }
}

@Composable
fun ChartTab(allLogs: List<LogEntry>) {
    val cal = Calendar.getInstance()
    val dailyTotals = FloatArray(7) { 0f }
    
    for (i in 6 downTo 0) {
        val startOfDay = cal.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val endOfDay = startOfDay + (24 * 60 * 60 * 1000)
        dailyTotals[6 - i] = allLogs.filter { it.timestamp in startOfDay until endOfDay }.sumOf { it.amountMl }.toFloat()
        cal.add(Calendar.DAY_OF_YEAR, -1)
    }
    
    val maxTotal = (dailyTotals.maxOrNull() ?: 1f).coerceAtLeast(1f)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
        Text("Last 7 Days (ml)", fontSize = 24.sp, modifier = Modifier.padding(bottom = 32.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(250.dp)) {
            val barWidth = size.width / 14
            val spacing = size.width / 7
            dailyTotals.forEachIndexed { index, total ->
                val barHeight = (total / maxTotal) * size.height
                drawRoundRect(
                    color = Color(0xFF4A90E2),
                    topLeft = Offset(x = (index * spacing) + (spacing / 4), y = size.height - barHeight),
                    size = Size(width = barWidth, height = barHeight),
                    cornerRadius = CornerRadius(10f, 10f)
                )
            }
        }
    }
}

@Composable
fun SettingsTab(repo: WaterRepository, prefs: Preferences?) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var targetVol by remember { mutableStateOf((prefs?.get(repo.TARGET_KEY) ?: 2000).toString()) }
    var useOz by remember { mutableStateOf(prefs?.get(repo.USE_OZ_KEY) ?: false) }
    
    val wakeMins = prefs?.get(repo.WAKE_MINS_KEY) ?: 480
    var wakeTime by remember { mutableStateOf(Pair(wakeMins / 60, wakeMins % 60)) }
    
    val sleepMins = prefs?.get(repo.SLEEP_MINS_KEY) ?: 1320
    var sleepTime by remember { mutableStateOf(Pair(sleepMins / 60, sleepMins % 60)) }

    val wakeDialog = TimePickerDialog(context, { _, h, m -> wakeTime = Pair(h, m) }, wakeTime.first, wakeTime.second, false)
    val sleepDialog = TimePickerDialog(context, { _, h, m -> sleepTime = Pair(h, m) }, sleepTime.first, sleepTime.second, false)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", fontSize = 24.sp, modifier = Modifier.padding(bottom = 16.dp))
        
        OutlinedTextField(value = targetVol, onValueChange = { targetVol = it }, label = { Text("Daily Target (ml)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Use Ounces (oz) in UI")
            Switch(checked = useOz, onCheckedChange = { useOz = it })
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedCard(modifier = Modifier.weight(1f).clickable { wakeDialog.show() }) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Wake", color = Color.Gray)
                    Text(String.format("%02d:%02d", wakeTime.first, wakeTime.second), fontSize = 20.sp)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            OutlinedCard(modifier = Modifier.weight(1f).clickable { sleepDialog.show() }) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sleep", color = Color.Gray)
                    Text(String.format("%02d:%02d", sleepTime.first, sleepTime.second), fontSize = 20.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                val t = targetVol.toIntOrNull() ?: 2000
                val w = (wakeTime.first * 60) + wakeTime.second
                val s = (sleepTime.first * 60) + sleepTime.second
                coroutineScope.launch { repo.updateSettings(t, w, s, useOz) }
                Toast.makeText(context, "Settings Saved & Alarms Updated", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Save Settings") }
    }
}
