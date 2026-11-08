package com.edu.standalone.sync.local

import com.edu.standalone.sync.*
import com.edu.standalone.sync.remote.ChangesPage

/** Sequential store. One owner/client per store; callers serialize all access. */
class InMemoryLocalStore : LocalStore {
    private var confirmed = emptyMap<String, String?>()
    private var operations = emptyList<SyncOperation>()
    private var deliveries = emptyMap<String, OperationDelivery>()
    private var cursor = 0L

    override fun get(entityId: String): String? {
        val overlay = operations.lastOrNull {
            val delivery = deliveries.getValue(it.operationId)
            it.entityId == entityId && (delivery.status == DeliveryStatus.PENDING ||
                (delivery.status == DeliveryStatus.ACKNOWLEDGED && (delivery.serverRevision ?: Long.MAX_VALUE) > cursor))
        }
        return if (overlay != null) overlay.payload else confirmed[entityId]
    }
    override fun getOperations() = operations.toList()
    override fun getDelivery(operationId: String) = deliveries[operationId]
    override fun getCursor() = cursor
    override fun saveLocalOperation(operation: SyncOperation) {
        require(operation.operationId !in deliveries) { "Duplicate operationId" }
        val nextOperations = operations + operation
        val nextDeliveries = deliveries + (operation.operationId to OperationDelivery(operation.operationId))
        operations = nextOperations
        deliveries = nextDeliveries
    }
    override fun getPendingOperations(clientId: String) = operations.filter {
        it.clientId == clientId && deliveries.getValue(it.operationId).status == DeliveryStatus.PENDING
    }.sortedBy { it.localSequence }
    override fun saveDelivery(delivery: OperationDelivery) {
        require(delivery.operationId in deliveries) { "Unknown operationId: ${delivery.operationId}" }
        deliveries = deliveries + (delivery.operationId to delivery)
    }
    override fun applyRemotePage(page: ChangesPage) {
        var revision = cursor
        var next = confirmed
        for (change in page.remoteChanges) {
            require(change.serverRevision > revision) { "Non-increasing server revision" }
            revision = change.serverRevision
            next = next + (change.operation.entityId to change.operation.payload)
        }
        require(page.nextCursor == revision) { "Invalid page cursor" }
        confirmed = next
        cursor = revision
    }
}
