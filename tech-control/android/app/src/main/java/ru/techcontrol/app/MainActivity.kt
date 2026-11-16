package ru.techcontrol.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

private val objectsList = listOf("ОРВХ", "ЭЛ70", "ЭЛ19")
private val categories = listOf(
    "Ежедневный отчёт", "Неисправность", "Выполненные работы",
    "Осмотр оборудования", "Заявка на материалы", "Аварийная ситуация"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderNotifications.createChannel(this)
        setContent { MaterialTheme { TechControlApp() } }
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
    var equipment by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var actionTaken by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("Обычный") }
    var photos by remember { mutableStateOf(listOf<Uri>()) }
    var showSettings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Заполните форму и отправьте отчёт в Telegram") }
    var reminderEnabled by remember { mutableStateOf(prefs.getBoolean("reminder_enabled", true)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && reminderEnabled) ReminderScheduler.schedule(context)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (reminderEnabled) ReminderScheduler.schedule(context)
    }
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        photos = uris.take(12)
    }

    fun saveSettings() {
        prefs.edit().putString("employee", employee.trim()).putString("relay_url", relayUrl.trim())
            .putString("app_key", appKey.trim()).apply()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Технический контроль") }, actions = {
            TextButton(onClick = { showSettings = !showSettings }) { Text("Настройки") }
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
                Button(onClick = { saveSettings(); status = "Настройки сохранены на этом телефоне" }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки") }
                Text("Токен Telegram и ID группы будут храниться только на сервере. В приложение вводятся адрес сервера и отдельный ключ доступа.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
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
            OutlinedTextField(value = equipment, onValueChange = { equipment = it }, label = { Text(if (category == "Заявка на материалы") "Материал / оборудование" else "Оборудование / участок") }, modifier = Modifier.fillMaxWidth())
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
            OutlinedButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Добавить фотографии (${photos.size}/12)") }
            if (photos.isNotEmpty()) Text("Выбрано фото: ${photos.size}")
            Button(
                enabled = !busy && employee.isNotBlank() && relayUrl.startsWith("https://") && appKey.isNotBlank() && details.isNotBlank(),
                onClick = {
                    saveSettings()
                    busy = true
                    status = "Отправка в Telegram…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try { TelegramRelayApi(context, relayUrl.trim(), appKey.trim()).sendReport(employee.trim(), selectedObject, category, equipment.trim(), details.trim(), actionTaken.trim(), priority, photos) }
                            catch (e: Exception) { "Ошибка отправки: ${e.message ?: "неизвестная ошибка"}" }
                        }
                        status = result
                        busy = false
                        if (result.startsWith("Отправлено")) {
                            details = ""; actionTaken = ""; equipment = ""; photos = emptyList(); priority = "Обычный"
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Отправить в Telegram") }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = if (status.startsWith("Ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("Перед первым использованием откройте «Настройки», укажите ФИО, адрес сервера и ключ доступа.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private class TelegramRelayApi(private val context: Context, private val endpoint: String, private val appKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    fun sendReport(employee: String, obj: String, category: String, equipment: String, details: String, measures: String, priority: String, photos: List<Uri>): String {
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru", "RU")).format(Date())
        val reportText = buildString {
            append("📋 ТЕХНИЧЕСКИЙ ОТЧЁТ\\n")
            append("Объект: $obj\\nКатегория: $category\\nСотрудник: $employee\\nВремя: $time\\n")
            if (equipment.isNotBlank()) append("Оборудование / участок: $equipment\\n")
            if (category == "Неисправность" || category == "Аварийная ситуация") append("Приоритет: $priority\\n")
            append("\\nОписание:\\n$details\\n")
            if (measures.isNotBlank()) append("\\nДополнительно / принятые меры:\\n$measures\\n")
        }
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("employee", employee)
            .addFormDataPart("object", obj)
            .addFormDataPart("category", category)
            .addFormDataPart("equipment", equipment)
            .addFormDataPart("details", details)
            .addFormDataPart("measures", measures)
            .addFormDataPart("priority", priority)
            .addFormDataPart("report_text", reportText)
        photos.forEachIndexed { index, uri ->
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Не удалось прочитать фотографию №${index + 1}")
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val filename = if (mime.contains("png")) "photo_${index + 1}.png" else "photo_${index + 1}.jpg"
            builder.addFormDataPart("photo", filename, bytes.toRequestBody(mime.toMediaType()))
        }
        val url = endpoint.trimEnd('/') + "/api/report"
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $appKey")
            .post(builder.build()).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                val reason = json.optString("error").ifBlank { raw.take(400) }
                throw IllegalStateException("Сервер ${response.code}: $reason")
            }
            return "Отправлено в Telegram через сервер. Фото: ${photos.size}."
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
