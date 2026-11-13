package com.edu.standalone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.edu.standalone.sync.DeliveryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

internal data class Task(val id: String, val json: String, val pending: Boolean, val deliveryError: String?) {
    private val data = JSONObject(json)
    val title = data.optString("title").ifBlank { "Без названия" }
    val address = data.optString("address").ifBlank { "Адрес не указан" }
    val time = data.optString("time").ifBlank { "Время не указано" }
    val status = data.optString("status", "open")
}

internal data class TaskUiState(
    val tasks: List<Task> = emptyList(), val error: String? = null,
    val lastSync: Long = 0, val syncError: String? = null, val saving: Boolean = false,
    val creating: Boolean = false, val title: String = "", val address: String = "", val time: String = ""
)

/** Owns asynchronous operations; drafts survive recreation and saved-state restoration. */
class TaskViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private var activeEndpoint = runCatching {
        EndpointIdentity.normalize(application.getSharedPreferences("sync", Application.MODE_PRIVATE)
            .getString("endpoint", EndpointIdentity.DEFAULT)!!)
    }.getOrDefault(EndpointIdentity.DEFAULT)
    private fun draftKey(field: String) = "draft:${EndpointIdentity.key(activeEndpoint)}:$field"
    private val mutableState = MutableStateFlow(TaskUiState(
        creating = saved[draftKey("creating")] ?: false, title = saved[draftKey("title")] ?: "",
        address = saved[draftKey("address")] ?: "", time = saved[draftKey("time")] ?: ""
    ))
    internal val state = mutableState.asStateFlow()
    private val context get() = getApplication<Application>()
    private var refreshJob: Job? = null

    internal fun editDraft(title: String, address: String, time: String) {
        saved[draftKey("title")] = title; saved[draftKey("address")] = address; saved[draftKey("time")] = time
        mutableState.update { it.copy(title = title, address = address, time = time) }
    }

    internal fun createTask() {
        if (state.value.saving) return
        saved[draftKey("creating")] = true
        mutableState.update { it.copy(creating = true, error = null) }
    }

    internal fun closeEditor() {
        if (state.value.saving) return
        saved[draftKey("creating")] = false
        mutableState.update { it.copy(creating = false) }
    }

    internal fun refresh() {
        if (refreshJob?.isActive == true) return
        val endpoint = activeEndpoint
        refreshJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val session = SyncRepository.session(context)
                    val tasks = session.access { local, client ->
                        val operations = local.getOperations()
                        val last = operations.associateBy { it.entityId }
                        val pending = local.getPendingOperations(client.clientId).map { it.entityId }.toSet()
                        local.getEntityIds().filter { it.startsWith("task:") }.mapNotNull { id ->
                            val delivery = last[id]?.let { local.getDelivery(it.operationId) }
                            val rejected = delivery?.takeIf { it.status == DeliveryStatus.REJECTED }?.lastError
                            local.get(id)?.let { payload ->
                                runCatching { Task(id.removePrefix("task:"), payload, id in pending, rejected) }.getOrNull()
                            }
                        }.sortedBy { it.id }
                    }
                    val prefs = context.getSharedPreferences("sync", Application.MODE_PRIVATE)
                    Triple(tasks, prefs.getLong("lastSync:${session.key}", 0), prefs.getString("syncError:${session.key}", null))
                }
                if (endpoint == activeEndpoint)
                    mutableState.update { it.copy(tasks = snapshot.first, lastSync = snapshot.second, syncError = snapshot.third) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(error = "Не удалось прочитать заявки: ${e.message}") }
            }
        }
    }

    internal fun saveDraft() {
        val draft = state.value
        if (draft.saving || draft.title.isBlank()) return
        // Persist the ID before the write. Retrying after an interrupted callback
        // replaces this task rather than creating a second task.
        val id = saved.get<String>(draftKey("id")) ?: UUID.randomUUID().toString().also { saved[draftKey("id")] = it }
        save {
            SyncRepository.session(context).access { _, client ->
                client.set("task:$id", JSONObject().put("title", draft.title.trim())
                    .put("address", draft.address.trim()).put("time", draft.time.trim()).put("status", "open").toString())
            }
        }
    }

    internal fun setStatus(id: String, status: String) = save(closeDraft = false) {
        SyncRepository.session(context).access { _, client ->
            val latest = requireNotNull(client.get("task:$id")) { "Заявка удалена" }
            client.set("task:$id", JSONObject(latest).put("status", status).toString())
        }
    }

    private fun save(closeDraft: Boolean = true, action: () -> Unit) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                if (closeDraft) {
                    saved[draftKey("creating")] = false
                    saved.remove<String>(draftKey("id"))
                    editDraft("", "", "")
                }
                mutableState.update { it.copy(saving = false, creating = if (closeDraft) false else it.creating) }
                SyncRepository.schedule(context)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(saving = false, error = "Не удалось сохранить заявку: ${e.message}") }
            }
        }
    }

    internal fun selectEndpoint(value: String) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { SyncRepository.selectEndpoint(context, value) }
                activeEndpoint = EndpointIdentity.normalize(value)
                mutableState.update { TaskUiState(
                    creating = saved[draftKey("creating")] ?: false, title = saved[draftKey("title")] ?: "",
                    address = saved[draftKey("address")] ?: "", time = saved[draftKey("time")] ?: ""
                ) }
                refreshJob?.join()
                refresh()
                SyncRepository.schedule(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(saving = false, error = e.message ?: "Некорректный адрес сервера") }
            }
        }
    }
}
