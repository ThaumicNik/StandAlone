package com.edu.standalone.sync

import com.edu.standalone.sync.local.*
import com.edu.standalone.sync.remote.*
import kotlin.test.*

class SyncEngineTest {
    private var ids = 0
    private fun client(store: LocalStore, id: String) = SyncClient(store, id, { "op-${++ids}" }, { 0L })
    @Test fun twoClientsConvergeWithDeleteAndRestore() {
        val server = InMemoryRemoteStore(); val a = InMemoryLocalStore(); val b = InMemoryLocalStore()
        val ca = client(a, "a"); val cb = client(b, "b")
        ca.set("x", "a"); cb.set("x", "b")
        SyncEngine(a, server, "a", 1).synchronize(); SyncEngine(b, server, "b", 1).synchronize()
        SyncEngine(a, server, "a", 1).synchronize()
        assertEquals("b", ca.get("x")); assertEquals(ca.get("x"), cb.get("x"))
        ca.del("x"); SyncEngine(a, server, "a").synchronize(); SyncEngine(b, server, "b").synchronize()
        assertNull(cb.get("x"))
        cb.set("x", "restored"); SyncEngine(b, server, "b").synchronize(); SyncEngine(a, server, "a").synchronize()
        assertEquals("restored", ca.get("x")); assertEquals(4L, a.getCursor())
        assertEquals(2, a.getOperations().size)
    }
    @Test fun inboundNeverErasesPendingAndDoesNotCreateAudit() {
        val server = InMemoryRemoteStore(); val local = InMemoryLocalStore()
        client(local, "local").del("x")
        server.applyOperation(SyncOperation("remote", "x", "r", 1, OperationType.SET, "old", 0))
        local.applyRemotePage(server.getChanges(0, 10))
        assertNull(local.get("x")); assertEquals(1, local.getOperations().size)
    }
    @Test fun rejectedOverlayFallsBackToConfirmed() {
        val local = InMemoryLocalStore(); val server = InMemoryRemoteStore()
        server.applyOperation(SyncOperation("r", "x", "r", 1, OperationType.SET, "confirmed", 0))
        local.applyRemotePage(server.getChanges(0, 10))
        val op = client(local, "a").set("x", "invalid")
        local.saveDelivery(OperationDelivery(op.operationId, DeliveryStatus.REJECTED, 1, "invalid"))
        assertEquals("confirmed", local.get("x")); assertEquals(op, local.getOperations().single())
    }
    @Test fun malformedPageCommitsNeitherRecordsNorCursor() {
        val local = InMemoryLocalStore()
        val op = SyncOperation("r", "x", "r", 1, OperationType.SET, "data", 0)
        assertFailsWith<IllegalArgumentException> { local.applyRemotePage(ChangesPage(listOf(RemoteChange(op, 1)), 2)) }
        assertNull(local.get("x")); assertEquals(0L, local.getCursor())
    }
    @Test fun editCreatedDuringSendSurvivesOldAcknowledgement() {
        val local = InMemoryLocalStore(); val server = InMemoryRemoteStore(); val c = client(local, "a")
        c.set("x", "first")
        val remote = object : RemoteStore by server {
            override fun applyOperation(operation: SyncOperation): ApplyResult {
                val result = server.applyOperation(operation)
                c.set("x", "new")
                return result
            }
        }
        SyncEngine(local, remote, "a").synchronize()
        assertEquals("new", c.get("x")); assertEquals(1, local.getPendingOperations("a").size)
        SyncEngine(local, server, "a").synchronize()
        assertEquals("new", server.get("x")); assertEquals(2L, local.getCursor())
    }
    @Test fun acknowledgementRevisionNeverSkipsOtherClientChanges() {
        val server = InMemoryRemoteStore(); val local = InMemoryLocalStore(); val c = client(local, "a")
        server.applyOperation(SyncOperation("other", "other", "b", 1, OperationType.SET, "value", 0))
        c.set("x", "mine"); OperationSender(server, local, "a").sendPending()
        assertEquals(0L, local.getCursor())
        SyncEngine(local, server, "a", 1).synchronize()
        assertEquals("value", c.get("other")); assertEquals(2L, local.getCursor())
    }
}
