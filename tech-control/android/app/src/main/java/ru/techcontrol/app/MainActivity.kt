package ru.techcontrol.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
        setContent { MaterialTheme { TechControlApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TechControlApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tech_control", Context.MODE_PRIVATE) }
    var employee by remember { mutableStateOf(prefs.getString("employee", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var chatId by remember { mutableStateOf(prefs.getString("chat_id", "") ?: "") }
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
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        photos = uris.take(12)
    }

    fun saveSettings() {
        prefs.edit().putString("employee", employee.trim()).putString("token", token.trim())
            .putString("chat_id", chatId.trim()).apply()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Технический контроль") }, actions = {
            TextButton(onClick = { showSettings = !showSettings }) { Text("Настройки") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showSettings) {
                Text("Настройки Telegram", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = employee, onValueChange = { employee = it }, label = { Text("ФИО сотрудника") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Токен Telegram-бота") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = chatId, onValueChange = { chatId = it }, label = { Text("ID общей группы Telegram") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { saveSettings(); status = "Настройки сохранены на этом телефоне" }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки") }
                OutlinedButton(
                    enabled = token.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        status = "Ищу последнее сообщение в группе… Добавьте бота в группу и отправьте туда сообщение."
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try { TelegramApi(context, token.trim(), "").findLatestChatId() }
                                catch (e: Exception) { "Ошибка получения ID: ${e.message ?: "неизвестная ошибка"}" }
                            }
                            if (result.startsWith("CHAT_ID:")) {
                                chatId = result.removePrefix("CHAT_ID:")
                                saveSettings()
                                status = "ID группы получен и сохранён: $chatId"
                            } else status = result
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text("Получить ID группы из Telegram") }
                Text("Токен хранится локально на телефоне. Не передавайте APK с уже заполненным токеном посторонним.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                enabled = !busy && employee.isNotBlank() && token.isNotBlank() && chatId.isNotBlank() && details.isNotBlank(),
                onClick = {
                    saveSettings()
                    busy = true
                    status = "Отправка в Telegram…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try { TelegramApi(context, token.trim(), chatId.trim()).sendReport(employee.trim(), selectedObject, category, equipment.trim(), details.trim(), actionTaken.trim(), priority, photos) }
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
            Text("Перед первым использованием откройте «Настройки», укажите ФИО, токен бота и ID группы Telegram.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private class TelegramApi(private val context: Context, private val token: String, private val chatId: String) {
    private val client = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private val base get() = "https://api.telegram.org/bot$token"

    fun findLatestChatId(): String {
        val request = Request.Builder().url("$base/getUpdates?timeout=0&limit=100").get().build()
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Telegram ${response.code}: ${raw.take(400)}")
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) throw IllegalStateException(root.optString("description", "Telegram API error"))
            val updates = root.optJSONArray("result") ?: JSONArray()
            for (i in updates.length() - 1 downTo 0) {
                val update = updates.optJSONObject(i) ?: continue
                val message = update.optJSONObject("message")
                    ?: update.optJSONObject("edited_message")
                    ?: update.optJSONObject("channel_post")
                    ?: update.optJSONObject("edited_channel_post")
                    ?: continue
                val chat = message.optJSONObject("chat") ?: continue
                val type = chat.optString("type", "")
                val id = chat.optString("id", "")
                if (type != "private" && id.isNotBlank() && id != "null") return@use "CHAT_ID:$id"
            }
            "Telegram не вернул сообщение из группы. Добавьте бота в нужную группу, отправьте там команду или сообщение и повторите. Если у бота установлен webhook, getUpdates недоступен — введите chat_id вручную."
        }
    }

    fun sendReport(employee: String, obj: String, category: String, equipment: String, details: String, measures: String, priority: String, photos: List<Uri>): String {
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru", "RU")).format(Date())
        val text = buildString {
            append("📋 ТЕХНИЧЕСКИЙ ОТЧЁТ\n")
            append("Объект: $obj\nКатегория: $category\nСотрудник: $employee\nВремя: $time\n")
            if (equipment.isNotBlank()) append("Оборудование / участок: $equipment\n")
            if (category == "Неисправность" || category == "Аварийная ситуация") append("Приоритет: $priority\n")
            append("\nОписание:\n$details\n")
            if (measures.isNotBlank()) append("\nДополнительно / принятые меры:\n$measures\n")
        }
        sendText(text)
        photos.forEachIndexed { index, uri -> uploadPhoto(uri, index + 1) }
        return "Отправлено в Telegram. Фото: ${photos.size}."
    }

    private fun sendText(text: String) {
        val body = JSONObject().put("chat_id", chatId).put("text", text)
        val request = Request.Builder().url("$base/sendMessage")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful || !JSONObject(raw).optBoolean("ok", false))
                throw IllegalStateException("Telegram sendMessage ${response.code}: ${raw.take(500)}")
        }
    }

    private fun uploadPhoto(uri: Uri, number: Int) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Не удалось прочитать фотографию №$number")
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val filename = if (mime.contains("png")) "photo_$number.png" else "photo_$number.jpg"
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("photo", filename, bytes.toRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder().url("$base/sendPhoto").post(multipart).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful || !JSONObject(raw).optBoolean("ok", false))
                throw IllegalStateException("Telegram sendPhoto №$number ${response.code}: ${raw.take(500)}")
        }
    }
}
