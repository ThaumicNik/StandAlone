package com.edu.standalone.sync

import com.edu.standalone.sync.local.InMemoryLocalStore
import com.edu.standalone.sync.local.LocalStore
import com.edu.standalone.sync.local.SyncClient
import com.edu.standalone.sync.remote.ApplyResult
import com.edu.standalone.sync.remote.ChangesPage
import com.edu.standalone.sync.remote.InMemoryRemoteStore
import com.edu.standalone.sync.remote.RemoteStore
import com.edu.standalone.sync.remote.TransientRemoteException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Проверки очереди отправки, подтверждений, отказов и повторных попыток. */
class OperationSenderTest {
    private class RecordingRemote(
        private val action: (SyncOperation) -> ApplyResult
    ) : RemoteStore {
        val calls = mutableListOf<SyncOperation>()

        override fun applyOperation(operation: SyncOperation): ApplyResult {
            calls.add(operation)
            return action(operation)
        }

        override fun getChanges(afterCursor: Long, limit: Int): ChangesPage =
            error("Unexpected getChanges call")
    }

    private fun operation(id: String, sequence: Long, clientId: String = "client") =
        SyncOperation(id, "entity", clientId, sequence, OperationType.SET, id, 0L)

    @Test
    fun sendsLifecycleAndSkipsAcknowledgedOperationsOnNextPass() {
        val local = InMemoryLocalStore()
        val server = InMemoryRemoteStore()
        val remote = RecordingRemote { server.applyOperation(it) }
        var counter = 0
        val client = SyncClient(local, "client", { (++counter).toString() }, { 0L })
        val set = client.set("entity", "value")
        val del = client.del("entity")
        val audit = local.getOperations()
        local.saveDelivery(assertNotNull(local.getDelivery(set.operationId)).copy(lastError = "old error"))
        val sender = OperationSender(remote, local, "client")

        sender.sendPending()

        assertEquals(listOf(set, del), remote.calls)
        assertNull(server.get("entity"))
        assertNull(local.get("entity"))
        assertEquals(audit, local.getOperations())
        val deliveries = listOf(set, del).mapIndexed { index, oper ->
            val delivery = assertNotNull(local.getDelivery(oper.operationId))
            assertEquals(DeliveryStatus.ACKNOWLEDGED, delivery.status)
            assertEquals(1, delivery.attemptCount)
            assertEquals(index + 1L, delivery.serverRevision)
            assertNull(delivery.lastError)
            delivery
        }
        assertTrue(local.getPendingOperations("client").isEmpty())

        sender.sendPending()

        assertEquals(listOf(set, del), remote.calls)
        assertEquals(deliveries, listOf(set, del).map { local.getDelivery(it.operationId) })
        assertEquals(audit, local.getOperations())
        assertNull(server.get("entity"))
    }

    @Test
    fun selectsOnlyCurrentClientsPendingOperationsInSequenceOrder() {
        val local = InMemoryLocalStore()
        val first = operation("first", 1)
        val second = operation("second", 2)
        val other = operation("other", 1, "other-client")
        val acknowledged = operation("acknowledged", 3)
        val rejected = operation("rejected", 4)
        listOf(second, other, rejected, acknowledged, first).forEach(local::saveLocalOperation)
        local.saveDelivery(OperationDelivery(acknowledged.operationId, DeliveryStatus.ACKNOWLEDGED))
        local.saveDelivery(OperationDelivery(rejected.operationId, DeliveryStatus.REJECTED))
        val audit = local.getOperations()
        val value = local.get("entity")
        val remote = RecordingRemote { ApplyResult.Accepted(it.operationId, it.localSequence) }

        OperationSender(remote, local, "client").sendPending()

        assertEquals(listOf(first, second), remote.calls)
        assertEquals(OperationDelivery(other.operationId), local.getDelivery(other.operationId))
        assertEquals(0, local.getDelivery(acknowledged.operationId)?.attemptCount)
        assertEquals(0, local.getDelivery(rejected.operationId)?.attemptCount)
        assertEquals(audit, local.getOperations())
        assertEquals(value, local.get("entity"))
    }

    @Test
    fun emptyQueueMakesNoRemoteCalls() {
        val remote = RecordingRemote { error("Unexpected remote call") }
        OperationSender(remote, InMemoryLocalStore(), "client").sendPending()
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun remoteFailureStopsQueueAndAllowsRetry() {
        val local = InMemoryLocalStore()
        val first = operation("first", 1)
        val second = operation("second", 2)
        listOf(first, second).forEach(local::saveLocalOperation)
        val failure = IllegalStateException("Connection unavailable")
        var fail = true
        val remote = RecordingRemote {
            // The attempt must already be recorded when the remote call begins.
            assertEquals(DeliveryStatus.PENDING, local.getDelivery(it.operationId)?.status)
            assertTrue(assertNotNull(local.getDelivery(it.operationId)).attemptCount > 0)
            if (fail) throw failure
            ApplyResult.Accepted(it.operationId, it.localSequence)
        }
        val sender = OperationSender(remote, local, "client")

        assertSame(failure, assertFailsWith<IllegalStateException> { sender.sendPending() })
        assertEquals(listOf(first), remote.calls)
        assertEquals(OperationDelivery(first.operationId, attemptCount = 1), local.getDelivery(first.operationId))
        assertEquals(OperationDelivery(second.operationId), local.getDelivery(second.operationId))

        fail = false
        sender.sendPending()

        assertEquals(listOf(first, first, second), remote.calls)
        assertEquals(2, local.getDelivery(first.operationId)?.attemptCount)
        assertEquals(1, local.getDelivery(second.operationId)?.attemptCount)
        assertTrue(local.getPendingOperations("client").isEmpty())
    }

    @Test
    fun transientFailureBeforeOrAfterServerApplicationIsRetriedSafely() {
        for (responseLost in listOf(false, true)) {
            val local = InMemoryLocalStore()
            val first = operation("first", 1)
            val second = operation("second", 2)
            listOf(first, second).forEach(local::saveLocalOperation)
            val audit = local.getOperations()
            val server = InMemoryRemoteStore()
            val failure = TransientRemoteException("Connection unavailable", null)
            var fail = true
            val remote = RecordingRemote { oper ->
                if (fail) {
                    if (responseLost) server.applyOperation(oper)
                    throw failure
                }
                server.applyOperation(oper)
            }
            val sender = OperationSender(remote, local, "client")

            assertSame(failure, assertFailsWith<TransientRemoteException> { sender.sendPending() })
            assertEquals(listOf(first), remote.calls)
            assertEquals(OperationDelivery(first.operationId, attemptCount = 1, lastError = "Connection unavailable"), local.getDelivery(first.operationId))
            assertEquals(OperationDelivery(second.operationId), local.getDelivery(second.operationId))
            assertEquals(if (responseLost) first.payload else null, server.get("entity"))
            assertEquals(audit, local.getOperations())

            fail = false
            sender.sendPending()

            assertEquals(listOf(first, first, second), remote.calls)
            assertEquals(OperationDelivery(first.operationId, DeliveryStatus.ACKNOWLEDGED, 2, serverRevision = 1L), local.getDelivery(first.operationId))
            assertEquals(OperationDelivery(second.operationId, DeliveryStatus.ACKNOWLEDGED, 1, serverRevision = 2L), local.getDelivery(second.operationId))
            assertEquals(second.payload, server.get("entity"))
            assertEquals(audit, local.getOperations())
            assertTrue(local.getPendingOperations("client").isEmpty())
        }
    }

    @Test
    fun failureSavingTransientErrorStopsQueueAndPropagatesStorageError() {
        val store = InMemoryLocalStore()
        val first = operation("first", 1)
        val second = operation("second", 2)
        listOf(first, second).forEach(store::saveLocalOperation)
        val failure = IllegalStateException("Error write failed")
        val local = object : LocalStore by store {
            override fun saveDelivery(delivery: OperationDelivery) {
                if (delivery.lastError != null) throw failure
                store.saveDelivery(delivery)
            }
        }
        val remote = RecordingRemote { throw TransientRemoteException("Unavailable", null) }

        assertSame(failure, assertFailsWith<IllegalStateException> {
            OperationSender(remote, local, "client").sendPending()
        })
        assertEquals(listOf(first), remote.calls)
        assertEquals(OperationDelivery(first.operationId, attemptCount = 1), store.getDelivery(first.operationId))
        assertEquals(OperationDelivery(second.operationId), store.getDelivery(second.operationId))
    }

    @Test
    fun rejectedResponseIsSavedAndQueueContinuesWithoutRetryingRejection() {
        val local = InMemoryLocalStore()
        val first = operation("first", 1)
        val second = operation("second", 2)
        listOf(first, second).forEach(local::saveLocalOperation)
        val remote = RecordingRemote {
            if (it == first) ApplyResult.Rejected(it.operationId, "Denied")
            else ApplyResult.Accepted(it.operationId, 1L)
        }
        val audit = local.getOperations()
        val sender = OperationSender(remote, local, "client")

        sender.sendPending()

        val rejected = OperationDelivery(first.operationId, DeliveryStatus.REJECTED, 1, "Denied")
        val accepted = OperationDelivery(second.operationId, DeliveryStatus.ACKNOWLEDGED, 1, serverRevision = 1L)
        assertEquals(listOf(first, second), remote.calls)
        assertEquals(rejected, local.getDelivery(first.operationId))
        assertEquals(accepted, local.getDelivery(second.operationId))
        assertTrue(local.getPendingOperations("client").isEmpty())
        assertEquals(audit, local.getOperations())
        assertEquals(second.payload, local.get("entity"))

        sender.sendPending()

        assertEquals(listOf(first, second), remote.calls)
        assertEquals(rejected, local.getDelivery(first.operationId))
        assertEquals(accepted, local.getDelivery(second.operationId))
    }

    @Test
    fun rejectionSaveFailureStopsQueueAndLeavesOperationPending() {
        val store = InMemoryLocalStore()
        val first = operation("first", 1)
        val second = operation("second", 2)
        listOf(first, second).forEach(store::saveLocalOperation)
        val failure = IllegalStateException("Rejection write failed")
        val local = object : LocalStore by store {
            override fun saveDelivery(delivery: OperationDelivery) {
                if (delivery.status == DeliveryStatus.REJECTED) throw failure
                store.saveDelivery(delivery)
            }
        }
        val remote = RecordingRemote { ApplyResult.Rejected(it.operationId, "Denied") }

        assertSame(failure, assertFailsWith<IllegalStateException> {
            OperationSender(remote, local, "client").sendPending()
        })

        assertEquals(listOf(first), remote.calls)
        assertEquals(OperationDelivery(first.operationId, attemptCount = 1), store.getDelivery(first.operationId))
        assertEquals(OperationDelivery(second.operationId), store.getDelivery(second.operationId))
    }

    @Test
    fun mismatchedResponseIdStopsQueueForBothResponseTypes() {
        for (response in listOf(ApplyResult.Accepted("wrong-id", 1), ApplyResult.Rejected("wrong-id", "Denied"))) {
            val local = InMemoryLocalStore()
            val first = operation("first", 1)
            val second = operation("second", 2)
            listOf(first, second).forEach(local::saveLocalOperation)
            val remote = RecordingRemote { response }

            assertFailsWith<IllegalArgumentException> { OperationSender(remote, local, "client").sendPending() }

            assertEquals(listOf(first), remote.calls)
            assertEquals(OperationDelivery(first.operationId, attemptCount = 1), local.getDelivery(first.operationId))
            assertEquals(OperationDelivery(second.operationId), local.getDelivery(second.operationId))
        }
    }

    @Test
    fun missingDeliveryStopsBeforeSending() {
        val store = InMemoryLocalStore()
        val first = operation("first", 1)
        store.saveLocalOperation(first)
        val local = object : LocalStore by store {
            override fun getDelivery(operationId: String): OperationDelivery? = null
        }
        val remote = RecordingRemote { error("Unexpected remote call") }

        assertFailsWith<IllegalArgumentException> { OperationSender(remote, local, "client").sendPending() }

        assertTrue(remote.calls.isEmpty())
        assertEquals(OperationDelivery(first.operationId), store.getDelivery(first.operationId))
    }

    @Test
    fun attemptSaveFailureStopsBeforeSending() {
        val store = InMemoryLocalStore()
        val first = operation("first", 1)
        store.saveLocalOperation(first)
        val failure = IllegalStateException("Local write failed")
        val local = object : LocalStore by store {
            override fun saveDelivery(delivery: OperationDelivery) { throw failure }
        }
        val remote = RecordingRemote { error("Unexpected remote call") }

        assertSame(failure, assertFailsWith<IllegalStateException> {
            OperationSender(remote, local, "client").sendPending()
        })

        assertTrue(remote.calls.isEmpty())
        assertEquals(OperationDelivery(first.operationId), store.getDelivery(first.operationId))
    }

    @Test
    fun acknowledgementSaveFailureStopsQueueAndRetryKeepsOriginalServerRevision() {
        val store = InMemoryLocalStore()
        val first = operation("first", 1)
        val second = operation("second", 2)
        listOf(first, second).forEach(store::saveLocalOperation)
        val audit = store.getOperations()
        val failure = IllegalStateException("Acknowledgement write failed")
        var fail = true
        val local = object : LocalStore by store {
            override fun saveDelivery(delivery: OperationDelivery) {
                if (fail && delivery.status == DeliveryStatus.ACKNOWLEDGED) throw failure
                store.saveDelivery(delivery)
            }
        }
        val server = InMemoryRemoteStore()
        val remote = RecordingRemote { server.applyOperation(it) }
        val sender = OperationSender(remote, local, "client")

        assertSame(failure, assertFailsWith<IllegalStateException> { sender.sendPending() })
        assertEquals(listOf(first), remote.calls)
        assertEquals(first.payload, server.get("entity"))
        assertEquals(OperationDelivery(first.operationId, attemptCount = 1), store.getDelivery(first.operationId))
        assertEquals(OperationDelivery(second.operationId), store.getDelivery(second.operationId))

        fail = false
        sender.sendPending()

        assertEquals(listOf(first, first, second), remote.calls)
        assertEquals(1L, store.getDelivery(first.operationId)?.serverRevision)
        assertEquals(2L, store.getDelivery(second.operationId)?.serverRevision)
        assertEquals(2, store.getDelivery(first.operationId)?.attemptCount)
        assertEquals(second.payload, server.get("entity"))
        assertTrue(store.getPendingOperations("client").isEmpty())
        assertEquals(audit, store.getOperations())
    }

    @Test
    fun operationsCreatedDuringSendingWaitForNextPass() {
        val local = InMemoryLocalStore()
        val first = operation("first", 1)
        val next = operation("next", 2)
        local.saveLocalOperation(first)
        val remote = RecordingRemote {
            if (it == first) local.saveLocalOperation(next)
            ApplyResult.Accepted(it.operationId, it.localSequence)
        }
        val sender = OperationSender(remote, local, "client")

        sender.sendPending()
        assertEquals(listOf(first), remote.calls)
        assertEquals(listOf(next), local.getPendingOperations("client"))

        sender.sendPending()
        assertEquals(listOf(first, next), remote.calls)
        assertTrue(local.getPendingOperations("client").isEmpty())
    }
}
