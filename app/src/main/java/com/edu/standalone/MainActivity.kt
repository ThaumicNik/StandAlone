package com.edu.standalone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edu.standalone.sync.*
import com.edu.standalone.sync.local.SyncClient
import com.edu.standalone.sync.jvm.*
import com.edu.standalone.ui.theme.StandAloneTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Local test demo. All store and HTTP access runs through one serialized worker. */
class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("sync", MODE_PRIVATE)
        val clientId = preferences.getString("clientId", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("clientId", it).commit()
        }
        // Initialized on the worker so disk recovery does not block UI input.
        var store: FileLocalStore? = null
        setContent {
            StandAloneTheme {
                var entity by remember { mutableStateOf("note") }
                var payload by remember { mutableStateOf("") }
                var endpoint by remember { mutableStateOf(preferences.getString("endpoint", "http://10.0.2.2:8080")!!) }
                var status by remember { mutableStateOf("Ready — local changes work offline") }
                var busy by remember { mutableStateOf(false) }
                fun submit(action: (FileLocalStore, SyncClient) -> String) {
                    busy = true
                    worker.execute {
                        val result = runCatching {
                            val local = store ?: FileLocalStore(File(filesDir, "sync.bin")).also { store = it }
                            val client = SyncClient(local, clientId, { UUID.randomUUID().toString() }, { System.currentTimeMillis() })
                            action(local, client)
                        }.getOrElse { "Failed: ${it.message}. Saved operations remain available for retry." }
                        runOnUiThread { status = result; busy = false }
                    }
                }
                Scaffold { padding ->
                    Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Offline sync demo", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(entity, { entity = it }, label = { Text("Record ID") }, enabled = !busy)
                        OutlinedTextField(payload, { payload = it }, label = { Text("Content") }, enabled = !busy)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && entity.isNotBlank(), onClick = {
                                submit { _, client -> client.set(entity, payload); "Saved locally" }
                            }) { Text("SET") }
                            Button(enabled = !busy, onClick = {
                                submit { _, client -> client.get(entity) ?: "Absent or deleted" }
                            }) { Text("GET") }
                            Button(enabled = !busy && entity.isNotBlank(), onClick = {
                                submit { _, client -> client.del(entity); "Deleted locally" }
                            }) { Text("DEL") }
                        }
                        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Local server URL") }, enabled = !busy)
                        Button(enabled = !busy, onClick = {
                            preferences.edit().putString("endpoint", endpoint).apply()
                            submit { local, _ ->
                                SyncEngine(local, HttpRemoteStore(endpoint), clientId).synchronize()
                                "Synchronized. Cursor ${local.getCursor()}; pending ${local.getPendingOperations(clientId).size}"
                            }
                        }) { Text("Synchronize") }
                        Button(enabled = !busy, onClick = {
                            submit { local, _ -> local.getOperations().joinToString("\n") {
                                "${it.localSequence} ${it.type} ${local.getDelivery(it.operationId)}"
                            }.ifEmpty { "No operations" } }
                        }) { Text("Audit and delivery") }
                        Text(status)
                    }
                }
            }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}

