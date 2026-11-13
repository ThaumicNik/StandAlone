package com.edu.standalone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edu.standalone.ui.theme.StandAloneTheme
import kotlinx.coroutines.delay

import java.text.DateFormat
import java.util.Date


private val Green = Color(0xFF087D2A)
private val Ink = Color(0xFF17212A)
private val Muted = Color(0xFF63717F)
private val Pale = Color(0xFFE4F5E7)
private val Border = Color(0xFFD9E0E5)

private fun statusLabel(value: String) = when (value) {
    "open", "assigned" -> "Назначена"
    "in_progress" -> "В работе"
    "done" -> "Выполнена"
    else -> value
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("sync", MODE_PRIVATE)
        val model = ViewModelProvider(this)[TaskViewModel::class.java]
        SyncRepository.schedule(applicationContext)
        setContent {
            StandAloneTheme {
                val state by model.state.collectAsState()
                val tasks = state.tasks
                val error = state.error
                val lastSync = state.lastSync
                val syncError = state.syncError
                var selected by rememberSaveable { mutableStateOf<String?>(null) }
                var page by rememberSaveable { mutableStateOf("tasks") }
                var query by rememberSaveable { mutableStateOf("") }
                var searching by rememberSaveable { mutableStateOf(false) }
                var endpoint by rememberSaveable { mutableStateOf(prefs.getString("endpoint", EndpointIdentity.DEFAULT)!!) }
                val create = state.creating
                LaunchedEffect(model) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) { model.refresh(); delay(3000) }
                    }
                }
                val current = tasks.firstOrNull { it.id == selected }
                Scaffold(containerColor = Color.White, bottomBar = {
                    if (selected == null && !create) BottomBar(page) { page = it }
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).background(Color.White).padding(horizontal = 20.dp)) {
                        when {
                            create -> TaskEditor(state, model::closeEditor, model::editDraft, model::saveDraft)
                            current != null -> TaskDetail(current, state.saving, onBack = { selected = null }, onStatus = { status ->
                                model.setStatus(current.id, status)
                            })
                            page == "profile" -> {
                                Spacer(Modifier.height(24.dp)); Text("Профиль", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
                                Spacer(Modifier.height(24.dp)); Text("Подключение к серверу", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Адрес сервера") }, singleLine = true)
                                Spacer(Modifier.height(12.dp))
                                Button(enabled = !state.saving, onClick = {
                                    selected = null; model.selectEndpoint(endpoint)
                                }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Сохранить и синхронизировать") }
                                Spacer(Modifier.height(16.dp)); Text("Для эмулятора: http://10.0.2.2:8080", color = Muted)
                            }
                            else -> {
                                Spacer(Modifier.height(20.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Заявки", Modifier.weight(1f), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
                                    TextButton(onClick = { searching = !searching }) { Text("⌕", fontSize = 30.sp, color = Ink) }
                                    TextButton(onClick = model::createTask) { Text("+", fontSize = 30.sp, color = Green) }
                                }
                                if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Поиск заявок") }, singleLine = true)
                                Spacer(Modifier.height(16.dp))
                                val message = syncError ?: if (lastSync == 0L) "Синхронизация ещё не завершена" else "Синхронизировано: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastSync))}"
                                Surface(onClick = { SyncRepository.schedule(applicationContext) }, shape = RoundedCornerShape(10.dp), color = Pale, border = BorderStroke(1.dp, Green), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("↻", color = Green, fontSize = 25.sp); Spacer(Modifier.width(12.dp)); Text(message, color = Green, modifier = Modifier.weight(1f)); Text("Повторить", color = Green, fontSize = 12.sp)
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                Text("Заявки", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                                Spacer(Modifier.height(12.dp))
                                val visible = tasks.filter { it.title.contains(query, true) || it.address.contains(query, true) || it.id.contains(query, true) }
                                if (visible.isEmpty()) Text(if (tasks.isEmpty()) "Заявок пока нет. Синхронизируйте данные или добавьте заявку." else "Ничего не найдено", color = Muted)
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                                    items(visible, key = { it.id }) { task -> TaskCard(task) { selected = task.id } }
                                }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable private fun TaskCard(task: Task, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Color.White, border = BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("№ ${task.id}", Modifier.weight(1f), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(shape = RoundedCornerShape(50), color = Pale) { Text(statusLabel(task.status), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(8.dp)); Text(task.title, color = Ink, fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp)); Text("●  ${task.address}  ›", color = Muted)
            Spacer(Modifier.height(8.dp)); Text("◷  ${task.time}", color = Muted)
            task.deliveryError?.let { Text("Отклонено сервером: $it", color = MaterialTheme.colorScheme.error) }
            if (task.pending) { Spacer(Modifier.height(8.dp)); Text("Ожидает синхронизации", color = Green, fontSize = 13.sp) }
        }
    }
}
@Composable private fun BottomBar(page: String, onPage: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().background(Color.White).padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("tasks" to "☷\nЗаявки", "profile" to "●\nПрофиль").forEach { (key, label) ->
            TextButton(onClick = { onPage(key) }) { Text(label, color = if (page == key) Green else Muted, lineHeight = 20.sp) }
        }
    }
}
@Composable private fun TaskDetail(task: Task, saving: Boolean, onBack: () -> Unit, onStatus: (String) -> Unit) {
    TextButton(onClick = onBack) { Text("‹  Все заявки", color = Green) }
    Text(task.title, fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
    Spacer(Modifier.height(16.dp)); Text("№ ${task.id}", color = Muted)
    Spacer(Modifier.height(16.dp)); Text("●  ${task.address}", color = Muted)
    Spacer(Modifier.height(12.dp)); Text("◷  ${task.time}", color = Muted)
    Spacer(Modifier.height(20.dp)); Text("Статус: ${statusLabel(task.status)}", fontWeight = FontWeight.SemiBold)
    task.deliveryError?.let { Text("Отклонено сервером: $it", color = MaterialTheme.colorScheme.error) }
    if (task.pending) Text("Изменения ожидают синхронизации", color = Green)
    Spacer(Modifier.height(16.dp))
    listOf("open" to "Назначена", "in_progress" to "В работе", "done" to "Выполнена").forEach { (value, label) ->
        OutlinedButton(onClick = { onStatus(value) }, enabled = !saving && task.status != value, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}
@Composable private fun TaskEditor(state: TaskUiState, onBack: () -> Unit,
    onEdit: (String, String, String) -> Unit, onSave: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack, enabled = !state.saving) { Text("‹  Все заявки", color = Green) }
        Text("Новая заявка", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(state.title, { onEdit(it, state.address, state.time) }, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("Название") })
        OutlinedTextField(state.address, { onEdit(state.title, it, state.time) }, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("Адрес") })
        OutlinedTextField(state.time, { onEdit(state.title, state.address, it) }, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("Время") })
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSave, enabled = !state.saving && state.title.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
            Text(if (state.saving) "Сохранение…" else "Сохранить")
        }
    }
}
