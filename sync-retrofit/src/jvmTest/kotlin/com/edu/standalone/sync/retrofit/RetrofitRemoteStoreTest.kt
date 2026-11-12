package com.edu.standalone.sync.retrofit

import com.edu.standalone.server.SyncServer
import com.edu.standalone.sync.*
import com.edu.standalone.sync.jvm.FileLocalStore
import com.edu.standalone.sync.local.SyncClient
import com.edu.standalone.sync.remote.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class RetrofitRemoteStoreTest {
    @Test fun offlineRetryAndTwoClientRoundTrip() {
        val dir = Files.createTempDirectory("retrofit-sync").toFile()
        try {
            val local = FileLocalStore(dir.resolve("a.bin"))
            val other = FileLocalStore(dir.resolve("b.bin"))
            val op = SyncClient(local, "a", { "op-1" }, { 0L }).set("task:one", "pump")
            SyncServer(dir.resolve("server.bin"), 0).use { server ->
                val remote = RetrofitRemoteStore("http://127.0.0.1:${server.port}")
                val loseResponse = object : RemoteStore by remote {
                    override fun applyOperation(operation: SyncOperation): ApplyResult {
                        remote.applyOperation(operation)
                        throw TransientRemoteException("Simulated lost response")
                    }
                }
                assertFailsWith<TransientRemoteException> { SyncEngine(local, loseResponse, "a").synchronize() }
                assertEquals(DeliveryStatus.PENDING, local.getDelivery(op.operationId)?.status)
                val recovered = FileLocalStore(dir.resolve("a.bin"))
                SyncEngine(recovered, remote, "a", 1).synchronize()
                SyncEngine(other, remote, "b", 1).synchronize()
                assertEquals(DeliveryStatus.ACKNOWLEDGED, recovered.getDelivery(op.operationId)?.status)
                assertEquals("pump", other.get("task:one"))
                assertEquals(1, remote.getChanges(0, 10).remoteChanges.size)
                assertEquals(1L, recovered.getCursor())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun httpStatusClassificationAndNetworkFailure() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val status = java.util.concurrent.atomic.AtomicInteger(503)
        server.createContext("/") { it.sendResponseHeaders(status.get(), -1); it.close() }
        server.start()
        val remote = RetrofitRemoteStore("http://127.0.0.1:${server.address.port}/")
        try {
            for (code in listOf(408, 429, 503)) {
                status.set(code)
                assertFailsWith<TransientRemoteException> { remote.getChanges(0, 1) }
            }
            status.set(400)
            assertFailsWith<IllegalArgumentException> { remote.getChanges(0, 1) }
        } finally { server.stop(0) }
        assertFailsWith<TransientRemoteException> { remote.getChanges(0, 1) }
    }
}
