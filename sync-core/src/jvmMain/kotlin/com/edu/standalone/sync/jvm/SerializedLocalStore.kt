package com.edu.standalone.sync.jvm

import com.edu.standalone.sync.OperationDelivery
import com.edu.standalone.sync.SyncOperation
import com.edu.standalone.sync.local.LocalStore
import com.edu.standalone.sync.remote.ChangesPage

/**
 * Serializes individual local transactions, allowing remote calls between them.
 * All callers must use this wrapper. Use [access] for compound local edits such
 * as reading a record, allocating its sequence, and saving its replacement.
 * Only one synchronization pass may run against this wrapper at a time.
 */
class SerializedLocalStore(private val delegate: LocalStore) : LocalStore {
    @Synchronized fun <T> access(action: (LocalStore) -> T): T = action(delegate)
    override fun get(entityId: String) = access { it.get(entityId) }
    override fun getEntityIds() = access { it.getEntityIds() }
    override fun getCursor() = access { it.getCursor() }
    override fun nextSequence(clientId: String) = access { it.nextSequence(clientId) }
    override fun getOperations() = access { it.getOperations() }
    override fun getDelivery(operationId: String) = access { it.getDelivery(operationId) }
    override fun getPendingOperations(clientId: String) = access { it.getPendingOperations(clientId) }
    override fun saveLocalOperation(operation: SyncOperation) = access { it.saveLocalOperation(operation) }
    override fun saveDelivery(delivery: OperationDelivery) = access { it.saveDelivery(delivery) }
    override fun applyRemotePage(page: ChangesPage) = access { it.applyRemotePage(page) }
}
