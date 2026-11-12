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
import com.edu.standalone.sync.retrofit.RetrofitRemoteStore
import com.edu.standalone.ui.theme.StandAloneTheme

/** Local test demo. All store and HTTP access runs through one serialized worker. */
class MainActivity : ComponentActivity() {
    private val worker = SyncRepository.executor
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("sync", MODE_PRIVATE)
        SyncRepository.schedule(applicationContext)
        setContent {
            StandAloneTheme {
                var entity by remember { mutableStateOf("note") }
                var payload by remember { mutableStateOf("") }
                var endpoint by remember { mutableStateOf(preferences.getString("endpoint", "http://10.0.2.2:8080")!!) }
                var status by remember { mutableStateOf("Ready вЂ” local changes work offline") }
                var busy by remember { mutableStateOf(false) }
                fun submit(action: (FileLocalStore, SyncClient) -> String) {
                    busy = true
                    worker.execute {
                        val result = runCatching {
                            SyncRepository.access(applicationContext, action)
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
                                submit { _, client -> client.set(entity, payload); SyncRepository.schedule(applicationContext); "Saved locally" }
                            }) { Text("SET") }
                            Button(enabled = !busy, onClick = {
                                submit { _, client -> client.get(entity) ?: "Absent or deleted" }
                            }) { Text("GET") }
                            Button(enabled = !busy && entity.isNotBlank(), onClick = {
                                submit { _, client -> client.del(entity); SyncRepository.schedule(applicationContext); "Deleted locally" }
                            }) { Text("DEL") }
                        }
                        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Local server URL") }, enabled = !busy)
                        Button(enabled = !busy, onClick = {
                            preferences.edit().putString("endpoint", endpoint).apply()
                            SyncRepository.schedule(applicationContext)
                            submit { local, client ->
                                SyncEngine(local, RetrofitRemoteStore(endpoint), client.clientId).synchronize()
                                "Synchronized. Cursor ${local.getCursor()}; pending ${local.getPendingOperations(client.clientId).size}"
                            }
                        }) { Text("Synchronize") }
                        Button(enabled = !busy, onClick = {
                            submit { local, client -> local.getOperations().joinToString("\n") {
                                "${it.localSequence} ${it.type} ${local.getDelivery(it.operationId)}"
                            }.ifEmpty { "No operations" } }
                        }) { Text("Audit and delivery") }
                        Text(status)
                    }
                }
            }
        }
    }

}
