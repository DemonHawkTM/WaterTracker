package com.example.watertracker

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

// --- Core Data Structures ---
data class LogEntry(val id: Long, val timestamp: Long, val amountMl: Int)
data class ChatMessage(val text: String, val isUser: Boolean)

val Context.dataStore by preferencesDataStore(name = "water_tracker_prefs")

class WaterRepository(private val context: Context) {
    val LOGS_KEY = stringPreferencesKey("water_logs")
    val TARGET_KEY = intPreferencesKey("daily_target")
    val WAKE_MINS_KEY = intPreferencesKey("wake_mins")
    val SLEEP_MINS_KEY = intPreferencesKey("sleep_mins")
    val USE_OZ_KEY = booleanPreferencesKey("use_oz")
    val SETUP_COMPLETE_KEY = booleanPreferencesKey("setup_complete")
    val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
    val HOT_WEATHER_KEY = booleanPreferencesKey("hot_weather_mode")
    val NEXT_ALARM_KEY = longPreferencesKey("next_alarm_time") // NEW: Tracks the next scheduled glass

    val preferencesFlow = context.dataStore.data

    suspend fun addLog(amountMl: Int) {
        context.dataStore.edit { prefs ->
            val logs = deserializeLogs(prefs[LOGS_KEY] ?: "[]").toMutableList()
            logs.add(LogEntry(System.currentTimeMillis(), System.currentTimeMillis(), amountMl))
            prefs[LOGS_KEY] = serializeLogs(logs)
        }
        
        // NEW: Instantly clear notifications and nag loops on log
        NotificationHelper.clearAll(context)
        
        rescheduleSmartAlarms(context)
        updateWidgets(context)
    }

    suspend fun removeLog(id: Long) {
        context.dataStore.edit { prefs ->
            val logs = deserializeLogs(prefs[LOGS_KEY] ?: "[]").filter { it.id != id }
            prefs[LOGS_KEY] = serializeLogs(logs)
        }
        rescheduleSmartAlarms(context)
        updateWidgets(context)
    }

    suspend fun completeSetup(target: Int, wake: Int, sleep: Int) {
        context.dataStore.edit { prefs ->
            prefs[TARGET_KEY] = target; prefs[WAKE_MINS_KEY] = wake; prefs[SLEEP_MINS_KEY] = sleep; prefs[SETUP_COMPLETE_KEY] = true
        }
        rescheduleSmartAlarms(context)
    }

    suspend fun saveApiKey(key: String) { context.dataStore.edit { prefs -> prefs[GEMINI_KEY] = key } }
    
    suspend fun toggleWeatherMode(isHot: Boolean) {
        context.dataStore.edit { prefs -> prefs[HOT_WEATHER_KEY] = isHot }
        rescheduleSmartAlarms(context)
        updateWidgets(context)
    }

    suspend fun updateSettings(target: Int, wake: Int, sleep: Int, useOz: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[TARGET_KEY] = target; prefs[WAKE_MINS_KEY] = wake; prefs[SLEEP_MINS_KEY] = sleep; prefs[USE_OZ_KEY] = useOz
        }
        rescheduleSmartAlarms(context)
    }

    fun getTodayLogs(logsStr: String): List<LogEntry> {
        val startOfDay = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        return deserializeLogs(logsStr).filter { it.timestamp >= startOfDay }
    }

    fun calculateStreak(logsStr: String, baseTarget: Int): Int {
        val allLogs = deserializeLogs(logsStr)
        if (allLogs.isEmpty()) return 0
        var streak = 0
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1) 
        while (true) {
            val startOfDay = cal.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
            val endOfDay = startOfDay + (24 * 60 * 60 * 1000)
            val dailyTotal = allLogs.filter { it.timestamp in startOfDay until endOfDay }.sumOf { it.amountMl }
            if (dailyTotal >= baseTarget) { streak++; cal.add(Calendar.DAY_OF_YEAR, -1) } else { break }
        }
        return streak
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
        logs.forEach { array.put(JSONObject().apply { put("id", it.id); put("ts", it.timestamp); put("amt", it.amountMl) }) }
        return array.toString()
    }
    
    private fun updateWidgets(context: Context) {
        val intent = Intent(context, WaterWidgetProvider::class.java).apply { action = AppWidgetManager.ACTION_APPWIDGET_UPDATE }
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, WaterWidgetProvider::class.java))
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        context.sendBroadcast(intent)
    }
}

// --- Smart Scheduling Logic ---
fun rescheduleSmartAlarms(context: Context) {
    CoroutineScope(Dispatchers.IO).launch {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) return@launch

        val repo = WaterRepository(context)
        val prefs = repo.preferencesFlow.first()
        
        val baseTarget = prefs[repo.TARGET_KEY] ?: 2000
        val isHotWeather = prefs[repo.HOT_WEATHER_KEY] ?: false
        val target = if (isHotWeather) (baseTarget * 1.15).toInt() else baseTarget
        
        val wakeMins = prefs[repo.WAKE_MINS_KEY] ?: (8 * 60)
        val sleepMins = prefs[repo.SLEEP_MINS_KEY] ?: (22 * 60)
        val logs = repo.getTodayLogs(prefs[repo.LOGS_KEY] ?: "[]")
        
        val currentIntake = logs.sumOf { it.amountMl }
        val remainingTarget = target - currentIntake

        val intent = Intent(context, RescheduleReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        alarmManager.cancel(pendingIntent)

        if (remainingTarget <= 0) {
            context.dataStore.edit { it[repo.NEXT_ALARM_KEY] = 0L }
            return@launch 
        }

        val now = Calendar.getInstance()
        val nowMins = (now.get(Calendar.HOUR_OF_DAY) * 60) + now.get(Calendar.MINUTE)
        var effectiveSleepMins = sleepMins
        if (sleepMins <= wakeMins) effectiveSleepMins += (24 * 60)
        
        val remainingMinutes = effectiveSleepMins - nowMins
        if (remainingMinutes <= 0) {
            context.dataStore.edit { it[repo.NEXT_ALARM_KEY] = 0L }
            return@launch
        }

        // Apply native human biological limit (Max 1000ml per hour)
        val maxSafeIncrementsPerHour = 4 // 4 x 250ml = 1000ml
        val hoursRemaining = remainingMinutes / 60.0
        val maxSafeRemaining = (hoursRemaining * maxSafeIncrementsPerHour).toInt()
        
        var incrementsNeeded = ceil(remainingTarget / 250.0).toInt()
        
        // If user is too far behind, adapt schedule to safe limits rather than spamming
        if (incrementsNeeded > maxSafeRemaining) {
            incrementsNeeded = maxSafeRemaining
        }
        
        if (incrementsNeeded <= 0) return@launch

        val intervalMillis = (remainingMinutes * 60 * 1000L) / incrementsNeeded
        val nextAlarmTime = System.currentTimeMillis() + intervalMillis
        
        // Save next schedule for UI
        context.dataStore.edit { it[repo.NEXT_ALARM_KEY] = nextAlarmTime }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAlarmTime, pendingIntent)
    }
}

// --- Notification Helpers & Receivers ---
class NotificationHelper {
    companion object { 
        const val CHANNEL_ID = "water_channel"
        const val NOTIFICATION_ID = 101 
        
        fun clearAll(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val nagIntent = Intent(context, NagReceiver::class.java)
            val nagPending = PendingIntent.getBroadcast(context, 4, nagIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.cancel(nagPending)
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() { 
    override fun onReceive(context: Context, intent: Intent) { 
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(NotificationHelper.CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)

        val logIntent = Intent(context, LogWaterReceiver::class.java)
        val logPending = PendingIntent.getBroadcast(context, 2, logIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        // NEW: Snooze Action
        val snoozeIntent = Intent(context, SnoozeReceiver::class.java)
        val snoozePending = PendingIntent.getBroadcast(context, 5, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hydration Time! \uD83D\uDCA7")
            .setContentText("Drink 250ml. Don't ignore this!")
            .setPriority(NotificationCompat.PRIORITY_MAX) 
            .setCategory(NotificationCompat.CATEGORY_ALARM) 
            .addAction(android.R.drawable.ic_input_add, "Log 250ml", logPending)
            .addAction(android.R.drawable.ic_popup_reminder, "Snooze 10m", snoozePending)
            .setOngoing(true) 
            .setAutoCancel(false) 
        
        manager.notify(NotificationHelper.NOTIFICATION_ID, builder.build())

        // NEW: Start the 30-second Nag Loop
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nagIntent = Intent(context, NagReceiver::class.java)
        val nagPending = PendingIntent.getBroadcast(context, 4, nagIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 30000, nagPending)
        }
    } 
}

// NEW: Beeps again if ignored
class NagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Just trigger the receiver again, which posts the notification and schedules the next nag
        val rescheduleIntent = Intent(context, RescheduleReceiver::class.java)
        context.sendBroadcast(rescheduleIntent)
    }
}

// NEW: Handles 10 min snooze
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.clearAll(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val rescheduleIntent = Intent(context, RescheduleReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 1, rescheduleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 600000, pendingIntent) // 10 mins
        }
        Toast.makeText(context, "Snoozed for 10 minutes", Toast.LENGTH_SHORT).show()
    }
}

class LogWaterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CoroutineScope(Dispatchers.IO).launch {
            WaterRepository(context).addLog(250)
            // clearAll() is now handled inside addLog()
        }
    }
}

// --- UI Components ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = WaterRepository(this)
        setContent { MaterialTheme { AppRouter(repo) } }
    }
}

@Composable
fun AppRouter(repo: WaterRepository) {
    val prefs by repo.preferencesFlow.collectAsState(initial = null)
    val isSetupComplete = prefs?.get(repo.SETUP_COMPLETE_KEY) ?: false

    if (prefs == null) return 

    PermissionGate {
        if (!isSetupComplete) {
            SetupWizardScreen(repo)
        } else {
            MainAppScreen(repo, prefs!!)
        }
    }
}

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    
    var hasNotificationPermission by remember { 
        mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true) 
    }
    var hasAlarmPermission by remember { mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> hasNotificationPermission = isGranted }

    if (!hasNotificationPermission || !hasAlarmPermission) {
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFFE0E5EC)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Warning, contentDescription = "Permissions", tint = Color.DarkGray, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("Permissions Required", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Text("To accurately track and remind you to drink water, this app needs specific permissions.", textAlign = TextAlign.Center, color = Color.Gray)
            Spacer(modifier = Modifier.height(32.dp))

            if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text("Allow Notifications") }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (!hasAlarmPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Button(onClick = { 
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow Exact Alarms") }
            }
        }
    } else { content() }
}

@Composable
fun SetupWizardScreen(repo: WaterRepository) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var weightKg by remember { mutableStateOf("") }
    var wakeTime by remember { mutableStateOf(Pair(8, 0)) }
    var sleepTime by remember { mutableStateOf(Pair(22, 0)) }

    val wakeDialog = TimePickerDialog(context, { _, h, m -> wakeTime = Pair(h, m) }, wakeTime.first, wakeTime.second, false)
    val sleepDialog = TimePickerDialog(context, { _, h, m -> sleepTime = Pair(h, m) }, sleepTime.first, sleepTime.second, false)

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFE0E5EC)).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Welcome to Smart Hydration", fontSize = 24.sp, color = Color.DarkGray)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(value = weightKg, onValueChange = { weightKg = it }, label = { Text("Weight (kg)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { wakeDialog.show() }, modifier = Modifier.weight(1f)) { Text("Wake: ${wakeTime.first}:${String.format("%02d", wakeTime.second)}") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { sleepDialog.show() }, modifier = Modifier.weight(1f)) { Text("Sleep: ${sleepTime.first}:${String.format("%02d", sleepTime.second)}") }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = {
            val weight = weightKg.toIntOrNull() ?: 70
            val target = weight * 35 
            coroutineScope.launch { repo.completeSetup(target, (wakeTime.first * 60) + wakeTime.second, (sleepTime.first * 60) + sleepTime.second) }
        }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Calculate & Start") }
    }
}

@Composable
fun MainAppScreen(repo: WaterRepository, prefs: Preferences) {
    var currentTab by remember { mutableStateOf(0) }
    
    val logsStr = prefs[repo.LOGS_KEY] ?: "[]"
    val allLogs = repo.deserializeLogs(logsStr)
    val todayLogs = repo.getTodayLogs(logsStr)
    val nextAlarmTime = prefs[repo.NEXT_ALARM_KEY] ?: 0L
    
    val baseTarget = prefs[repo.TARGET_KEY] ?: 2000
    val isHotWeather = prefs[repo.HOT_WEATHER_KEY] ?: false
    val effectiveTarget = if (isHotWeather) (baseTarget * 1.15).toInt() else baseTarget

    val useOz = prefs[repo.USE_OZ_KEY] ?: false
    val apiKey = prefs[repo.GEMINI_KEY] ?: ""
    val currentIntake = todayLogs.sumOf { it.amountMl }
    val currentStreak = repo.calculateStreak(logsStr, baseTarget)

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(icon = { Icon(Icons.Default.Home, "Home") }, label = { Text("Home") }, selected = currentTab == 0, onClick = { currentTab = 0 })
                NavigationBarItem(icon = { Icon(Icons.Default.List, "Log") }, label = { Text("Log") }, selected = currentTab == 1, onClick = { currentTab = 1 })
                NavigationBarItem(icon = { Icon(Icons.Default.Star, "AI Chat") }, label = { Text("AI") }, selected = currentTab == 2, onClick = { currentTab = 2 })
                NavigationBarItem(icon = { Icon(Icons.Default.Settings, "Settings") }, label = { Text("Settings") }, selected = currentTab == 3, onClick = { currentTab = 3 })
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFFE0E5EC))) {
            when (currentTab) {
                0 -> HomeTab(repo, currentIntake, effectiveTarget, useOz, currentStreak, isHotWeather, nextAlarmTime)
                1 -> HistoryTab(repo, todayLogs, useOz)
                2 -> AIChatTab(apiKey, allLogs)
                3 -> SettingsTab(repo, prefs)
            }
        }
    }
}

@Composable
fun HomeTab(repo: WaterRepository, currentIntake: Int, target: Int, useOz: Boolean, streak: Int, isHot: Boolean, nextAlarmTime: Long) {
    val coroutineScope = rememberCoroutineScope()
    val displayIntake = if (useOz) (currentIntake * 0.033814).toInt() else currentIntake
    val displayTarget = if (useOz) (target * 0.033814).toInt() else target
    val unit = if (useOz) "oz" else "ml"

    // NEW: Format Next Schedule string
    val nextScheduleText = if (currentIntake >= target) "Goal Met! Great Job!" 
        else if (nextAlarmTime == 0L) "Calculating schedule..." 
        else "Next glass due at: ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(nextAlarmTime)}"

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE0B2))) { Text("🔥 $streak Day Streak", modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold) }
            if (isHot) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2))) { Text("☀️ Hot Mode (+15%)", modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold, color = Color.Red) }
        }
        Spacer(modifier = Modifier.height(40.dp))

        Box(modifier = Modifier.size(250.dp).shadow(12.dp, RoundedCornerShape(150.dp)).background(Color(0xFFE0E5EC), RoundedCornerShape(150.dp)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$displayIntake / $displayTarget $unit", fontSize = 32.sp, color = Color(0xFF4A90E2))
                Text("Today's Intake", color = Color.Gray)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        // NEW: Display the schedule
        Text(nextScheduleText, fontWeight = FontWeight.Medium, color = Color.DarkGray)
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { coroutineScope.launch { repo.addLog(150) } }) { Text("+150ml") }
            Button(onClick = { coroutineScope.launch { repo.addLog(250) } }) { Text("+250ml") }
            Button(onClick = { coroutineScope.launch { repo.addLog(500) } }) { Text("+500ml") }
        }
    }
}

// NEW: Advanced Chat UI
@Composable
fun AIChatTab(apiKey: String, allLogs: List<LogEntry>) {
    val coroutineScope = rememberCoroutineScope()
    var userInput by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }
    
    // Maintain Chat History
    val chatHistory = remember { mutableStateListOf(ChatMessage("Hello! Ask me questions about your hydration data.", false)) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            items(chatHistory.reversed()) { msg ->
                val alignment = if (msg.isUser) Alignment.End else Alignment.Start
                val bgColor = if (msg.isUser) Color(0xFF4A90E2) else Color(0xFFFFFFFF)
                val textColor = if (msg.isUser) Color.White else Color.Black
                
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = alignment) {
                    Card(colors = CardDefaults.cardColors(containerColor = bgColor), modifier = Modifier.widthIn(max = 300.dp)) {
                        Text(msg.text, color = textColor, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
        
        if (isThinking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = userInput, onValueChange = { userInput = it }, modifier = Modifier.weight(1f), placeholder = { Text("Ask Gemini...") })
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = userInput.isNotBlank() && !isThinking,
                onClick = {
                    if (apiKey.isBlank()) {
                        chatHistory.add(ChatMessage("Error: Please save your API key in Settings first.", false))
                        return@Button
                    }
                    val query = userInput
                    chatHistory.add(ChatMessage(query, true))
                    userInput = ""
                    isThinking = true
                    
                    coroutineScope.launch {
                        try {
                            val model = GenerativeModel(modelName = "gemini-2.5-flash", apiKey = apiKey)
                            val totalLogs = allLogs.size
                            val totalVolume = allLogs.sumOf { it.amountMl }
                            val prompt = "You are a hydration assistant. User data context: Logged $totalLogs times, total volume $totalVolume ml historically. The user asks: '$query'. Keep answers short and friendly."
                            val response = model.generateContent(prompt)
                            chatHistory.add(ChatMessage(response.text ?: "No response", false))
                        } catch (e: Exception) {
                            chatHistory.add(ChatMessage("Connection Error: ${e.localizedMessage}", false))
                        } finally {
                            isThinking = false
                        }
                    }
                }
            ) { Icon(Icons.Default.Send, "Send") }
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
                    IconButton(onClick = { coroutineScope.launch { repo.removeLog(log.id) } }) { Icon(Icons.Default.Delete, "Delete", tint = Color.Red) }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(repo: WaterRepository, prefs: Preferences?) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var targetVol by remember { mutableStateOf((prefs?.get(repo.TARGET_KEY) ?: 2000).toString()) }
    var apiKey by remember { mutableStateOf(prefs?.get(repo.GEMINI_KEY) ?: "") }
    var useOz by remember { mutableStateOf(prefs?.get(repo.USE_OZ_KEY) ?: false) }
    var isHotWeather by remember { mutableStateOf(prefs?.get(repo.HOT_WEATHER_KEY) ?: false) }
    var apiVerificationStatus by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    
    val wakeMins = prefs?.get(repo.WAKE_MINS_KEY) ?: 480
    var wakeTime by remember { mutableStateOf(Pair(wakeMins / 60, wakeMins % 60)) }
    val sleepMins = prefs?.get(repo.SLEEP_MINS_KEY) ?: 1320
    var sleepTime by remember { mutableStateOf(Pair(sleepMins / 60, sleepMins % 60)) }

    val wakeDialog = TimePickerDialog(context, { _, h, m -> wakeTime = Pair(h, m) }, wakeTime.first, wakeTime.second, false)
    val sleepDialog = TimePickerDialog(context, { _, h, m -> sleepTime = Pair(h, m) }, sleepTime.first, sleepTime.second, false)

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Settings", fontSize = 24.sp, modifier = Modifier.padding(bottom = 16.dp))
            OutlinedTextField(value = targetVol, onValueChange = { targetVol = it }, label = { Text("Base Target (ml)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it; apiVerificationStatus = "" }, label = { Text("Gemini API Key") }, modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(apiVerificationStatus, fontSize = 12.sp, color = if (apiVerificationStatus.contains("Invalid")) Color.Red else Color(0xFF4A90E2), modifier = Modifier.weight(1f))
                Button(
                    enabled = apiKey.isNotBlank() && !isVerifying,
                    onClick = {
                        isVerifying = true
                        apiVerificationStatus = "Verifying..."
                        coroutineScope.launch {
                            try {
                                val model = GenerativeModel(modelName = "gemini-2.5-flash", apiKey = apiKey)
                                model.generateContent("Reply with exactly one word: OK")
                                apiVerificationStatus = "✅ Key is Valid"
                                repo.saveApiKey(apiKey)
                            } catch (e: Exception) { apiVerificationStatus = "❌ Invalid Key: ${e.localizedMessage}" } finally { isVerifying = false }
                        }
                    }
                ) { Text(if (isVerifying) "..." else "Verify Key") }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2)), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("Hot Weather / High Activity", fontWeight = FontWeight.Bold); Text("+15% to daily target", fontSize = 12.sp) }
                    Switch(checked = isHotWeather, onCheckedChange = { isHotWeather = it; coroutineScope.launch { repo.toggleWeatherMode(it) } })
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Use Ounces (oz) in UI")
                Switch(checked = useOz, onCheckedChange = { useOz = it })
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedCard(modifier = Modifier.weight(1f).clickable { wakeDialog.show() }) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("Wake", color = Color.Gray); Text(String.format("%02d:%02d", wakeTime.first, wakeTime.second), fontSize = 20.sp) }
                }
                Spacer(modifier = Modifier.width(16.dp))
                OutlinedCard(modifier = Modifier.weight(1f).clickable { sleepDialog.show() }) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("Sleep", color = Color.Gray); Text(String.format("%02d:%02d", sleepTime.first, sleepTime.second), fontSize = 20.sp) }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { 
                    val intent = Intent(context, RescheduleReceiver::class.java)
                    context.sendBroadcast(intent)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) { Text("Send Test Notification Now") }

            Button(onClick = {
                val t = targetVol.toIntOrNull() ?: 2000
                coroutineScope.launch { 
                    repo.updateSettings(t, (wakeTime.first * 60) + wakeTime.second, (sleepTime.first * 60) + sleepTime.second, useOz)
                    repo.saveApiKey(apiKey)
                }
                Toast.makeText(context, "Settings Saved", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save Settings") }
        }
    }
}
