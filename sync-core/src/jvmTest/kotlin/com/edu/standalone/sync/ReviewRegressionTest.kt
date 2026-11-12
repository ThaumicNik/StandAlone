package com.edu.standalone.sync

import com.edu.standalone.sync.jvm.*
import com.edu.standalone.sync.local.*
import com.edu.standalone.sync.remote.*
import java.nio.file.Files
import kotlin.test.*

class ReviewRegressionTest {
    @Test fun legacySnapshotAndNewSegmentsRecoverTogether() {
        val dir = Files.createTempDirectory("legacy-store").toFile()
        try {
            val file = java.io.File(dir, "local")
            val operation = SyncOperation("old", "first", "a", 1, OperationType.SET, "legacy", 0)
            val event = Wire.encode { it.writeByte(0); Wire.operation(it, operation) }
            java.io.DataOutputStream(file.outputStream()).use {
                it.writeInt(0x53594E31); it.writeInt(1); it.writeInt(event.size); it.write(event)
            }
            val local = FileLocalStore(file)
            SyncClient(local, "a", { "new" }, { 0 }).set("second", "new")
            // Unpublished staging files are ignored after a crash.
            java.io.File(file.path + ".tmp").writeText("partial")
            val reopened = FileLocalStore(file)
            assertEquals("legacy", reopened.get("first"))
            assertEquals("new", reopened.get("second"))
            assertEquals(2, reopened.getOperations().size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun largeHistoryIsByteBoundedAndReopens() {
        val dir = Files.createTempDirectory("large-history").toFile()
        try {
            val remote = FileRemoteStore(java.io.File(dir, "remote"))
            repeat(18) { remote.applyOperation(SyncOperation("op$it", "e$it", "a", it.toLong(), OperationType.SET, "x".repeat(1048576), 0)) }
            val localFile = java.io.File(dir, "local")
            val local = FileLocalStore(localFile)
            assertTrue(remote.getChanges(0, 100).remoteChanges.size < 18)
            SyncEngine(local, remote, "b").synchronize()
            assertEquals(18L, FileLocalStore(localFile).getCursor())
            assertEquals("x".repeat(1048576), FileLocalStore(localFile).get("e17"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun oversizedPageDoesNotPublishOrDamageStore() {
        val dir = Files.createTempDirectory("oversized-page").toFile()
        try {
            val file = java.io.File(dir, "local")
            val local = FileLocalStore(file)
            val changes = (1..17).map { RemoteChange(SyncOperation("op$it", "e$it", "a", it.toLong(), OperationType.SET, "x".repeat(1048576), 0), it.toLong()) }
            assertFailsWith<IllegalArgumentException> { local.applyRemotePage(ChangesPage(changes, 17)) }
            assertEquals(0L, local.getCursor())
            assertEquals(0L, FileLocalStore(file).getCursor())
        } finally { dir.deleteRecursively() }
    }
    @Test fun growingFeedStillAllowsUpload() {
        val local = InMemoryLocalStore()
        SyncClient(local, "a", { "mine" }, { 0 }).set("mine", "value")
        var uploaded = false
        val remote = object : RemoteStore {
            override fun getChanges(afterCursor: Long, limit: Int): ChangesPage {
                val revision = afterCursor + 1
                return ChangesPage(listOf(RemoteChange(SyncOperation("remote$revision", "other", "b", revision, OperationType.SET, "value", 0), revision)), revision)
            }
            override fun applyOperation(operation: SyncOperation): ApplyResult {
                uploaded = true
                return ApplyResult.Accepted(operation.operationId, 100)
            }
        }
        SyncEngine(local, remote, "a", 1, 2).synchronize()
        assertTrue(uploaded)
        assertEquals(4L, local.getCursor())
    }
}
