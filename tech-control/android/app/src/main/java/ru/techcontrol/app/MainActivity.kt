package ru.techcontrol.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private val AppDarkColors = darkColorScheme(
    primary = Color(0xFF55C8FF), onPrimary = Color(0xFF002B40),
    secondary = Color(0xFF4B9DFF), onSecondary = Color(0xFF001D36),
    background = Color(0xFF08121F), onBackground = Color(0xFFE7F3FF),
    surface = Color(0xFF101F30), onSurface = Color(0xFFE7F3FF),
    surfaceVariant = Color(0xFF1B3046), onSurfaceVariant = Color(0xFFB6CBE0),
    outline = Color(0xFF54718B), error = Color(0xFFFF7777)
)

private val objectsList = listOf("ОРВХ", "ЭЛ70", "ЭЛ19")
private val categories = listOf(
    "Ежедневный отчёт", "Неисправность", "Выполненные работы",
    "Осмотр оборудования", "Заявка на материалы", "Аварийная ситуация"
)
private fun saveReportToHistory(context: Context, employee: String, obj: String, category: String, reportText: String) {
    val prefs = context.getSharedPreferences("tech_control", Context.MODE_PRIVATE)
    val items = try { JSONArray(prefs.getString("report_history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    val entry = JSONObject().apply {
        put("date", SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru", "RU")).format(Date()))
        put("employee", employee)
        put("object", obj)
        put("category", category)
        put("text", reportText)
    }
    val updated = JSONArray().apply {
        put(entry)
        for (i in 0 until minOf(items.length(), 199)) put(items.getJSONObject(i))
    }
    prefs.edit().putString("report_history", updated.toString()).apply()
}

private fun attendanceKey(date: String) = "attendance_" + date
private fun currentDayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
private fun recordAttendance(context: Context, field: String): String {
    val prefs = context.getSharedPreferences("tech_control", Context.MODE_PRIVATE)
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    val key = attendanceKey(currentDayKey())
    val data = try { JSONObject(prefs.getString(key, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    data.put(field, time)
    prefs.edit().putString(key, data.toString()).apply()
    return time
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderNotifications.createChannel(this)
        setContent { MaterialTheme(colorScheme = AppDarkColors) { TechControlApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TechControlApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tech_control", Context.MODE_PRIVATE) }
    var employee by remember { mutableStateOf(prefs.getString("employee", "") ?: "") }
    var relayUrl by remember { mutableStateOf(prefs.getString("relay_url", "") ?: "") }
    var appKey by remember { mutableStateOf(prefs.getString("app_key", "") ?: "") }
    var selectedObject by remember { mutableStateOf(objectsList.first()) }
    var category by remember { mutableStateOf(categories.first()) }
    var details by remember { mutableStateOf("") }
    var actionTaken by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("Обычный") }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var arrivalTime by remember { mutableStateOf("") }
    var departureTime by remember { mutableStateOf("") }
    var shiftActive by remember { mutableStateOf(prefs.getBoolean("shift_active", false)) }
    var reportAndLeave by remember { mutableStateOf(false) }
    var historyRefresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pendingRequestId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Заполните форму и отправьте отчёт в Telegram") }
    var reminderEnabled by remember { mutableStateOf(prefs.getBoolean("reminder_enabled", true)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && reminderEnabled) ReminderScheduler.schedule(context)
    }
    LaunchedEffect(Unit) {
        val dayData = try { JSONObject(prefs.getString(attendanceKey(currentDayKey()), "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        arrivalTime = dayData.optString("arrival", "")
        departureTime = dayData.optString("departure", "")
        if (!prefs.contains("shift_active")) {
            shiftActive = arrivalTime.isNotBlank() && departureTime.isBlank()
            prefs.edit().putBoolean("shift_active", shiftActive).apply()
        } else {
            shiftActive = prefs.getBoolean("shift_active", false)
        }
        prefs.edit().remove("token").remove("chat_id").apply()
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (reminderEnabled) ReminderScheduler.schedule(context)
    }
    val scope = rememberCoroutineScope()

    fun saveSettings() {
        prefs.edit().putString("employee", employee.trim()).putString("relay_url", relayUrl.trim())
            .putString("app_key", appKey.trim()).apply()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Технический контроль") }, actions = {
            TextButton(onClick = { showHistory = !showHistory; showSettings = false; historyRefresh++ }) { Text(if (showHistory) "Отчёт" else "История") }
            TextButton(onClick = { showSettings = !showSettings; showHistory = false }) { Text("Настройки") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showSettings) {
                Text("Настройки отправки", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Напоминать об отчёте в 17:00")
                        Text("Ежедневное уведомление в конце смены", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = reminderEnabled, onCheckedChange = { enabled ->
                        reminderEnabled = enabled
                        prefs.edit().putBoolean("reminder_enabled", enabled).apply()
                        if (enabled) ReminderScheduler.schedule(context) else ReminderScheduler.cancel(context)
                    })
                }
                OutlinedTextField(value = employee, onValueChange = { employee = it }, label = { Text("ФИО сотрудника") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = relayUrl, onValueChange = { relayUrl = it }, label = { Text("Адрес сервера отправки") }, placeholder = { Text("https://ваш-сервер.workers.dev") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = appKey, onValueChange = { appKey = it }, label = { Text("Ключ доступа приложения") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(onClick = { saveSettings(); status = "Настройки сохранены на этом телефоне"; showSettings = false }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки") }
                Text("Токен Telegram и ID группы будут храниться только на сервере. В приложение вводятся адрес сервера и отдельный ключ доступа.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }

            if (showHistory) {
                Text("История на этом телефоне", style = MaterialTheme.typography.titleLarge)
                Text("Отправленные отчёты", style = MaterialTheme.typography.titleMedium)
                val reportItems = remember(historyRefresh) {
                    try { JSONArray(prefs.getString("report_history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
                }
                if (reportItems.length() == 0) Text("Отправленных отчётов пока нет.")
                for (i in 0 until reportItems.length()) {
                    val item = reportItems.optJSONObject(i) ?: continue
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.optString("date"), style = MaterialTheme.typography.labelMedium)
                            Text(item.optString("object") + " · " + item.optString("category"), style = MaterialTheme.typography.titleSmall)
                            Text("Сотрудник: " + item.optString("employee"), style = MaterialTheme.typography.bodySmall)
                            Text(item.optString("text"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                HorizontalDivider()
                Text("Учёт рабочего времени", style = MaterialTheme.typography.titleMedium)
                val attendanceRecords: List<Pair<String, JSONObject>> = remember(historyRefresh) {
                    prefs.all.entries
                        .filter { entry -> entry.key.startsWith("attendance_") }
                        .sortedByDescending { entry -> entry.key.removePrefix("attendance_") }
                        .mapNotNull { entry ->
                            try {
                                val day = entry.key.removePrefix("attendance_")
                                val data = JSONObject(entry.value as? String ?: "{}")
                                Pair(day, data)
                            } catch (_: Exception) { null }
                        }
                }
                if (attendanceRecords.isEmpty()) Text("Отметок прихода и ухода пока нет.")
                attendanceRecords.forEach { (day, data) ->
                    val formatted = try {
                        SimpleDateFormat("dd.MM.yyyy", Locale("ru", "RU")).format(
                            SimpleDateFormat("yyyyMMdd", Locale.US).parse(day)!!
                        )
                    } catch (_: Exception) { day }
                    Text(formatted + " — приход: " + data.optString("arrival", "не отмечен") +
                        "; уход: " + data.optString("departure", "не отмечен"))
                }
            } else if (!showSettings) {
                Text("Учёт рабочего времени", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy && !shiftActive && employee.isNotBlank() && relayUrl.startsWith("https://") && appKey.isNotBlank(),
                        onClick = {
                            busy = true
                            status = "Отправка отметки прихода…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                        TelegramRelayApi(context, relayUrl.trim(), appKey.trim()).sendAttendance(
                                            UUID.randomUUID().toString(), employee.trim(), selectedObject, "arrival", time
                                        )
                                        recordAttendance(context, "arrival")
                                        prefs.edit().putBoolean("shift_active", true).apply()
                                        "Приход в $time отправлен в Telegram."
                                    } catch (e: Exception) { "Ошибка отправки отметки: ${e.message ?: "неизвестная ошибка"}" }
                                }
                                arrivalTime = try { JSONObject(prefs.getString(attendanceKey(currentDayKey()), "{}") ?: "{}").optString("arrival", "") } catch (_: Exception) { "" }
                                if (result.startsWith("Приход в ")) shiftActive = true
                                historyRefresh++
                                status = result
                                busy = false
                            }
                        }, modifier = Modifier.weight(1f)
                    ) { Text(if (shiftActive) "Приход отмечен" else "Отметить приход") }
                    OutlinedButton(
                        enabled = !busy && shiftActive && employee.isNotBlank() && relayUrl.startsWith("https://") && appKey.isNotBlank(),
                        onClick = {
                            busy = true
                            status = "Отправка отметки ухода…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                        TelegramRelayApi(context, relayUrl.trim(), appKey.trim()).sendAttendance(
                                            UUID.randomUUID().toString(), employee.trim(), selectedObject, "departure", time
                                        )
                                        recordAttendance(context, "departure")
                                        prefs.edit().putBoolean("shift_active", false).apply()
                                        "Уход в $time отправлен в Telegram."
                                    } catch (e: Exception) { "Ошибка отправки отметки: ${e.message ?: "неизвестная ошибка"}" }
                                }
                                departureTime = try { JSONObject(prefs.getString(attendanceKey(currentDayKey()), "{}") ?: "{}").optString("departure", "") } catch (_: Exception) { "" }
                                if (result.startsWith("Уход в ")) {
                                    shiftActive = false
                                    arrivalTime = ""
                                    departureTime = ""
                                }
                                historyRefresh++
                                status = result
                                busy = false
                            }
                        }, modifier = Modifier.weight(1f)
                    ) { Text(if (shiftActive) "Отметить уход" else "Сначала отметьте приход") }
                }
                Text("Новый отчёт", style = MaterialTheme.typography.titleLarge)
                Text("Объект")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                objectsList.forEachIndexed { index, item ->
                    SegmentedButton(selected = selectedObject == item, onClick = { selectedObject = item }, shape = SegmentedButtonDefaults.itemShape(index, objectsList.size)) { Text(item) }
                }
            }
            Text("Категория")
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(value = category, onValueChange = {}, readOnly = true, label = { Text("Категория отчёта") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    categories.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { category = item; expanded = false }) }
                }
            }
            OutlinedTextField(value = details, onValueChange = { details = it }, label = { Text(when (category) {
                "Ежедневный отчёт" -> "Что сделано за смену"
                "Неисправность" -> "Описание неисправности"
                "Выполненные работы" -> "Какие работы выполнены"
                "Осмотр оборудования" -> "Результат осмотра"
                "Заявка на материалы" -> "Что требуется и в каком количестве"
                else -> "Описание аварийной ситуации"
            }) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = actionTaken, onValueChange = { actionTaken = it }, label = { Text("Дополнительная информация / принятые меры (необязательно)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            if (category == "Неисправность" || category == "Аварийная ситуация") {
                Text("Приоритет")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Низкий", "Обычный", "Высокий", "Критический").forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p) })
                    }
                }
            }
            if (category == "Ежедневный отчёт" && shiftActive) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = reportAndLeave, onCheckedChange = { reportAndLeave = it })
                    Text("После отправки отчёта отметить уход и завершить смену")
                }
            }
            Button(
                enabled = !busy && employee.isNotBlank() && relayUrl.startsWith("https://") && appKey.isNotBlank() && details.isNotBlank(),
                onClick = {
                    saveSettings()
                    busy = true
                    status = "Отправка в Telegram…"
                    val requestId = pendingRequestId ?: UUID.randomUUID().toString().also { pendingRequestId = it }
                    scope.launch {
                        var reportWasSent = false
                        val closeShiftAfterReport = reportAndLeave && shiftActive && category == "Ежедневный отчёт"
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val api = TelegramRelayApi(context, relayUrl.trim(), appKey.trim())
                                val reportText = api.sendReport(requestId, employee.trim(), selectedObject, category, details.trim(), actionTaken.trim(), priority)
                                saveReportToHistory(context, employee.trim(), selectedObject, category, reportText)
                                reportWasSent = true
                                if (closeShiftAfterReport) {
                                    try {
                                        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                        api.sendAttendance(UUID.randomUUID().toString(), employee.trim(), selectedObject, "departure", time)
                                        recordAttendance(context, "departure")
                                        prefs.edit().putBoolean("shift_active", false).apply()
                                        "Отчёт отправлен. Уход в $time также отмечен, смена завершена."
                                    } catch (e: Exception) {
                                        "Отчёт отправлен и сохранён, но отметка ухода не прошла: ${e.message ?: "ошибка"}"
                                    }
                                } else {
                                    "Отправлено в Telegram и сохранено в истории."
                                }
                            } catch (e: Exception) { "Ошибка отправки: ${e.message ?: "неизвестная ошибка"}" }
                        }
                        status = result
                        busy = false
                        if (reportWasSent) {
                            pendingRequestId = null
                            historyRefresh++
                            details = ""; actionTaken = ""; priority = "Обычный"
                            reportAndLeave = false
                            if (closeShiftAfterReport && result.startsWith("Отчёт отправлен. Уход")) {
                                shiftActive = false
                                arrivalTime = ""
                                departureTime = ""
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Отправить в Telegram") }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = if (status.startsWith("Ошибка") || status.contains("но отметка ухода не прошла")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("Перед первым использованием откройте «Настройки», укажите ФИО, адрес сервера и ключ доступа.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private class TelegramRelayApi(private val context: Context, private val endpoint: String, private val appKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun sendAttendance(requestId: String, employee: String, obj: String, event: String, time: String) {
        val payload = JSONObject().apply {
            put("request_id", requestId)
            put("employee", employee)
            put("object", obj)
            put("event", event)
            put("time", time)
        }
        val request = Request.Builder()
            .url(endpoint.trimEnd('/') + "/api/attendance")
            .header("Authorization", "Bearer $appKey")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                val reason = json.optString("error").ifBlank { raw.take(400) }
                throw IllegalStateException("Сервер ${response.code}: $reason")
            }
        }
    }

    fun sendReport(requestId: String, employee: String, obj: String, category: String, details: String, measures: String, priority: String): String {
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru", "RU")).format(Date())
        val hashtag = when (category) {
            "Ежедневный отчёт" -> "#ЕжедневныйОтчёт"
            "Неисправность" -> "#Неисправность"
            "Выполненные работы" -> "#ВыполненныеРаботы"
            "Осмотр оборудования" -> "#ОсмотрОборудования"
            "Заявка на материалы" -> "#ЗаявкаНаМатериалы"
            "Аварийная ситуация" -> "#АварийнаяСитуация"
            else -> "#Отчёт"
        }
        val reportText = buildString {
            append("📋 ТЕХНИЧЕСКИЙ ОТЧЁТ\n")
            append("Объект: $obj\nКатегория: $category $hashtag\nСотрудник: $employee\nВремя: $time\n")
            if (category == "Неисправность" || category == "Аварийная ситуация") append("Приоритет: $priority\n")
            append("\nОписание:\n$details\n")
            if (measures.isNotBlank()) append("\nДополнительно / принятые меры:\n$measures\n")
        }
        val payload = JSONObject().apply {
            put("request_id", requestId)
            put("report_text", reportText)
        }
        val request = Request.Builder()
            .url(endpoint.trimEnd('/') + "/api/report")
            .header("Authorization", "Bearer $appKey")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                val reason = json.optString("error").ifBlank { raw.take(400) }
                throw IllegalStateException("Сервер ${response.code}: $reason")
            }
            return reportText
        }
    }
}

private object ReminderScheduler {
    private const val REQUEST_CODE = 1700

    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences("tech_control", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("reminder_enabled", true)) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ShiftReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 17)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ShiftReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
        pending.cancel()
    }
}

private class ShiftReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("tech_control", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("reminder_enabled", true)) return
        ReminderNotifications.createChannel(context)
        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            val notification = NotificationCompat.Builder(context, ReminderNotifications.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Техконтроль — конец смены")
                .setContentText("Не забудьте заполнить и отправить отчёт за смену в Telegram.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Смена заканчивается. Откройте приложение «Техконтроль», заполните ежедневный отчёт и отправьте его в общую группу Telegram."))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ReminderNotifications.NOTIFICATION_ID, notification)
        }
        ReminderScheduler.schedule(context)
    }
}

private object ReminderNotifications {
    const val CHANNEL_ID = "shift_report_reminder"
    const val NOTIFICATION_ID = 1700

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Напоминания об отчёте", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Ежедневное напоминание отправить отчёт в конце смены" }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

private class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) ReminderScheduler.schedule(context)
    }
}
