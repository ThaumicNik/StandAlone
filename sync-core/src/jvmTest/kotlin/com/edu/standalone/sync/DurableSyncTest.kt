package com.edu.standalone.sync

import com.edu.standalone.sync.local.*
import com.edu.standalone.sync.remote.*
import com.edu.standalone.sync.jvm.*
import com.edu.standalone.server.SyncServer
import java.nio.file.Files
import kotlin.test.*

class DurableSyncTest {
    @Test fun concurrentServerRetriesApplyOnlyOnce() {
        val dir = Files.createTempDirectory("concurrent-server").toFile()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val store = FileRemoteStore(java.io.File(dir, "server.bin"))
            val op = SyncOperation("shared", "x", "a", 1, OperationType.SET, "value", 0)
            val results = (1..12).map { pool.submit<ApplyResult> { store.applyOperation(op) } }.map { it.get() }
            assertTrue(results.all { it == ApplyResult.Accepted("shared", 1) })
            assertEquals(1, store.getChanges(0, 100).remoteChanges.size)
        } finally { pool.shutdownNow(); dir.deleteRecursively() }
    }
    @Test fun failedDiskCommitPublishesNothing() {
        val dir = Files.createTempDirectory("failed-commit").toFile()
        try {
            val file = java.io.File(dir, "local.bin")
            val local = FileLocalStore(file)
            val client = SyncClient(local, "a", { "one" }, { 0L })
            java.io.File(dir, "local.bin.tmp").mkdir()
            assertFailsWith<java.io.IOException> { client.set("x", "unsaved") }
            assertNull(local.get("x")); assertTrue(local.getOperations().isEmpty())
            assertEquals(0L, FileLocalStore(file).getCursor())
        } finally { dir.deleteRecursively() }
    }
    @Test fun restartRestoresPendingCursorAuditAndSequence() {
        val file = Files.createTempDirectory("sync-test").resolve("local.bin").toFile()
        try {
            val remote = InMemoryRemoteStore()
            var local = FileLocalStore(file)
            var client = SyncClient(local, "a", { "one" }, { 0L })
            client.set("x", "offline")
            local = FileLocalStore(file)
            assertEquals("offline", local.get("x")); assertEquals(1, local.getPendingOperations("a").size)
            SyncEngine(local, remote, "a", 1).synchronize()
            local = FileLocalStore(file)
            assertEquals(1L, local.getCursor()); assertEquals(DeliveryStatus.ACKNOWLEDGED, local.getDelivery("one")?.status)
            client = SyncClient(local, "a", { "two" }, { 0L })
            assertEquals(2L, client.del("x").localSequence)
            assertNull(FileLocalStore(file).get("x"))
        } finally { file.parentFile.deleteRecursively() }
    }
    @Test fun durableServerPreservesOriginalRevisionAfterRestart() {
        val file = Files.createTempDirectory("server-test").resolve("remote.bin").toFile()
        try {
            val op = SyncOperation("one", "x", "a", 1, OperationType.SET, "data", 0)
            val result = FileRemoteStore(file).applyOperation(op)
            val reopened = FileRemoteStore(file)
            assertEquals(result, reopened.applyOperation(op))
            assertEquals(1, reopened.getChanges(0, 10).remoteChanges.size)
            assertFailsWith<IllegalArgumentException> { reopened.applyOperation(op.copy(payload = "different")) }
        } finally { file.parentFile.deleteRecursively() }
    }
    @Test fun realHttpRoundTripAndServerRestart() {
        val dir = Files.createTempDirectory("http-sync").toFile()
        try {
            val a = FileLocalStore(java.io.File(dir, "a.bin")); val b = FileLocalStore(java.io.File(dir, "b.bin"))
            val ca = SyncClient(a, "a", { "one" }, { 0L }); ca.set("x", "offline")
            var port: Int
            SyncServer(java.io.File(dir, "server.bin"), 0).use { server ->
                port = server.port
                val http = HttpRemoteStore("http://127.0.0.1:$port")
                SyncEngine(a, http, "a", 1).synchronize(); SyncEngine(b, http, "b", 1).synchronize()
                assertEquals("offline", b.get("x"))
            }
            SyncServer(java.io.File(dir, "server.bin"), 0).use { server ->
                val http = HttpRemoteStore("http://127.0.0.1:${server.port}")
                assertEquals(ApplyResult.Accepted("one", 1), http.applyOperation(a.getOperations().single()))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun responseLossPersistsRetryAcrossReopen() {
        val dir = Files.createTempDirectory("response-loss").toFile()
        try {
            val file = java.io.File(dir, "local.bin"); var local = FileLocalStore(file)
            val remote = FileRemoteStore(java.io.File(dir, "server.bin"))
            SyncClient(local, "a", { "one" }, { 0L }).set("x", "data")
            val losing = object : RemoteStore by remote {
                override fun applyOperation(operation: SyncOperation): ApplyResult {
                    remote.applyOperation(operation); throw TransientRemoteException("lost response")
                }
            }
            assertFailsWith<TransientRemoteException> { SyncEngine(local, losing, "a").synchronize() }
            local = FileLocalStore(file)
            assertEquals(DeliveryStatus.PENDING, local.getDelivery("one")?.status)
            SyncEngine(local, remote, "a").synchronize()
            assertEquals(2, local.getDelivery("one")?.attemptCount)
            assertEquals(1L, local.getCursor()); assertEquals(1, local.getOperations().size)
        } finally { dir.deleteRecursively() }
    }
}
