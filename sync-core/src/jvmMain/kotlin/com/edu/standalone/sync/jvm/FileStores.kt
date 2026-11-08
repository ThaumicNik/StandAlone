package com.edu.standalone.sync.jvm

import com.edu.standalone.sync.*
import com.edu.standalone.sync.local.*
import com.edu.standalone.sync.remote.*
import java.io.*

/** Durable local adapter. Serialize API access; do not open the same file twice concurrently. */
class FileLocalStore(file: File) : LocalStore {
    private val journal = Journal(file)
    private var memory = InMemoryLocalStore()
    init { journal.replay { apply(memory, it) } }
    private fun apply(store: InMemoryLocalStore, input: DataInputStream) {
        when (input.readByte().toInt()) {
            0 -> store.saveLocalOperation(Wire.operation(input))
            1 -> store.applyRemotePage(Wire.page(input))
            2 -> {
                val id = Wire.text(input); val status = DeliveryStatus.entries[input.readInt()]
                val attempts = input.readInt()
                val error = if (input.readBoolean()) Wire.text(input) else null
                val revision = if (input.readBoolean()) input.readLong() else null
                store.saveDelivery(OperationDelivery(id, status, attempts, error, revision))
            }
            else -> error("Invalid journal event")
        }
        require(input.read() == -1)
    }
    private fun commit(event: ByteArray) {
        val candidate = InMemoryLocalStore()
        journal.replay { apply(candidate, it) }
        apply(candidate, DataInputStream(event.inputStream()))
        journal.append(event)
        memory = candidate
    }
    override fun get(entityId: String) = memory.get(entityId)
    override fun getOperations() = memory.getOperations()
    override fun getDelivery(operationId: String) = memory.getDelivery(operationId)
    override fun getPendingOperations(clientId: String) = memory.getPendingOperations(clientId)
    override fun getCursor() = memory.getCursor()
    override fun saveLocalOperation(operation: SyncOperation) = commit(Wire.encode { it.writeByte(0); Wire.operation(it, operation) })
    override fun applyRemotePage(page: ChangesPage) = commit(Wire.encode { it.writeByte(1); Wire.page(it, page) })
    override fun saveDelivery(delivery: OperationDelivery) = commit(Wire.encode {
        it.writeByte(2); Wire.text(it, delivery.operationId); it.writeInt(delivery.status.ordinal)
        it.writeInt(delivery.attemptCount); it.writeBoolean(delivery.lastError != null)
        delivery.lastError?.let { value -> Wire.text(it, value) }
        it.writeBoolean(delivery.serverRevision != null); delivery.serverRevision?.let(it::writeLong)
    })
}

/** Durable, serialized server acceptance order and deduplication history. */
class FileRemoteStore(file: File) : RemoteStore {
    private val journal = Journal(file)
    private val memory = InMemoryRemoteStore()
    private val known = mutableMapOf<String, SyncOperation>()
    init { journal.replay {
        val operation = Wire.operation(it)
        memory.applyOperation(operation)
        known[operation.operationId] = operation
    } }
    @Synchronized override fun applyOperation(operation: SyncOperation): ApplyResult {
        known[operation.operationId]?.let {
            require(it == operation) { "operationId is already associated with a different operation" }
            return memory.applyOperation(operation)
        }
        // Validate and compute on a candidate so failed disk writes cannot publish a revision.
        val candidate = InMemoryRemoteStore()
        journal.replay { candidate.applyOperation(Wire.operation(it)) }
        val result = candidate.applyOperation(operation)
        journal.append(Wire.encode { Wire.operation(it, operation) })
        memory.applyOperation(operation)
        known[operation.operationId] = operation
        return result
    }
    @Synchronized override fun getChanges(afterCursor: Long, limit: Int) = memory.getChanges(afterCursor, limit)
}
