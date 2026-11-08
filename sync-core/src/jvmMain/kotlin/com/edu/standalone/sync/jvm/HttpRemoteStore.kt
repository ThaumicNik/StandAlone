package com.edu.standalone.sync.jvm

import com.edu.standalone.sync.SyncOperation
import com.edu.standalone.sync.remote.*
import java.io.*
import java.net.*

/** Blocking HTTP adapter. Call on a worker thread, with serialized access to its local store. */
class HttpRemoteStore(private val baseUrl: String) : RemoteStore {
    override fun applyOperation(operation: SyncOperation): ApplyResult = request("/operations", Wire.encode {
        Wire.operation(it, operation)
    }) { Wire.result(it) }
    override fun getChanges(afterCursor: Long, limit: Int): ChangesPage {
        require(afterCursor >= 0 && limit in 1..10000)
        return request("/changes?after=$afterCursor&limit=$limit", null) { Wire.page(it) }
    }
    private fun <T> request(path: String, body: ByteArray?, decode: (DataInputStream) -> T): T {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/vnd.standalone.sync-v1")
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status >= 500 || status == 408 || status == 429) throw TransientRemoteException("HTTP $status")
            require(status == 200) { "Protocol error: HTTP $status" }
            return DataInputStream(connection.inputStream).use(decode)
        } catch (e: IOException) {
            throw TransientRemoteException("HTTP exchange failed", e)
        } finally { connection.disconnect() }
    }
}
