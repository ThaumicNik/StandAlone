package com.edu.standalone.sync

import com.edu.standalone.sync.jvm.FileLocalStore
import com.edu.standalone.sync.jvm.SerializedLocalStore
import com.edu.standalone.sync.local.InMemoryLocalStore
import com.edu.standalone.sync.local.SyncClient
import com.edu.standalone.sync.remote.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class HardeningTest {
    @Test fun localEditCompletesWhileNetworkResponseIsBlocked() {
        val local = SerializedLocalStore(InMemoryLocalStore())
        val remote = InMemoryRemoteStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            local.access { SyncClient(it, "a", { "first" }, { 0 }).set("task", "first") }
            val pass = pool.submit {
                SyncEngine(local, object : RemoteStore by remote {
                    override fun applyOperation(operation: SyncOperation): ApplyResult {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        return remote.applyOperation(operation)
                    }
                }, "a").synchronize()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pool.submit {
                local.access { SyncClient(it, "a", { "second" }, { 0 }).set("task", "newer") }
            }.get(2, TimeUnit.SECONDS)
            assertEquals("newer", local.get("task"))
            release.countDown()
            pass.get(5, TimeUnit.SECONDS)
            assertEquals("newer", local.get("task"))
            assertEquals(listOf("second"), local.getPendingOperations("a").map { it.operationId })
            SyncEngine(local, remote, "a").synchronize()
            assertEquals("newer", remote.get("task"))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun emptyPollsDoNotGrowJournalAndStillValidateCursor() {
        val dir = Files.createTempDirectory("empty-polls").toFile()
        try {
            val file = File(dir, "local")
            val local = FileLocalStore(file)
            val remote = InMemoryRemoteStore()
            repeat(10) { SyncEngine(local, remote, "a").synchronize() }
            assertFalse(File(file.path + ".events").exists())
            remote.applyOperation(SyncOperation("r", "task", "b", 1, OperationType.SET, "value", 0))
            SyncEngine(local, remote, "a").synchronize()
            val count = File(file.path + ".events").listFiles()!!.size
            repeat(10) { SyncEngine(local, remote, "a").synchronize() }
            assertEquals(count, File(file.path + ".events").listFiles()!!.size)
            assertFailsWith<IllegalArgumentException> { local.applyRemotePage(ChangesPage(emptyList(), 2)) }
            assertEquals(1L, FileLocalStore(file).getCursor())
        } finally { dir.deleteRecursively() }
    }

    @Test fun sequenceIndexAndEntityOverlayRecoverFromHistory() {
        val dir = Files.createTempDirectory("sequence-index").toFile()
        try {
            val file = File(dir, "local")
            val local = FileLocalStore(file)
            local.saveLocalOperation(SyncOperation("high", "task", "a", 50, OperationType.SET, "old", 0))
            local.saveLocalOperation(SyncOperation("low", "task", "a", 2, OperationType.SET, "latest", 0))
            local.saveLocalOperation(SyncOperation("other", "other", "b", 90, OperationType.SET, "other", 0))
            local.saveDelivery(OperationDelivery("low", DeliveryStatus.REJECTED, lastError = "denied"))
            val reopened = FileLocalStore(file)
            assertEquals(51L, reopened.nextSequence("a"))
            assertEquals(91L, reopened.nextSequence("b"))
            assertEquals("old", reopened.get("task"))
            assertEquals(51L, SyncClient(reopened, "a", { "next" }, { 0 }).set("task", "new").localSequence)
            assertEquals("new", FileLocalStore(file).get("task"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun remotePagesHandleLargeCursorsLimitsAndDeduplication() {
        val remote = InMemoryRemoteStore()
        val op = SyncOperation("one", "task", "a", 1, OperationType.SET, "value", 0)
        remote.applyOperation(op); remote.applyOperation(op)
        remote.applyOperation(op.copy(operationId = "two", type = OperationType.DEL, payload = null))
        assertEquals(listOf(2L), remote.getChanges(1, Int.MAX_VALUE).remoteChanges.map { it.serverRevision })
        assertTrue(remote.getChanges(Long.MAX_VALUE, Int.MAX_VALUE).remoteChanges.isEmpty())
        assertEquals(Long.MAX_VALUE, remote.getChanges(Long.MAX_VALUE, 1).nextCursor)
    }
}
