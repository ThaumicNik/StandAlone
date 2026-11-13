package com.edu.standalone.sync.remote

import com.edu.standalone.sync.OperationType
import com.edu.standalone.sync.SyncOperation

/** Удалённое хранилище для последовательных проверок; данные существуют только в памяти. */
class InMemoryRemoteStore : RemoteStore {

    private data class ProcessedOperation(
        val operation: SyncOperation,
        val result: ApplyResult,
    )

    private data class State(
        val records: Map<String, String?> = emptyMap(),
        val operations: Map<String, ProcessedOperation> = emptyMap(),
        val changes: List<RemoteChange> = emptyList(),
        val nextRevision: Long = 1L
    )

    private var state = State()

    internal fun fork() = InMemoryRemoteStore().also { it.state = state }

    override fun applyOperation(operation: SyncOperation): ApplyResult {
        val newRecords: Map<String, String?>
        val newOperations: Map<String, ProcessedOperation>
        var processedOper = state.operations[operation.operationId]

        if (processedOper != null) {
            require(processedOper.operation == operation) {
                "operationId is already associated with a different operation"
            }
            return processedOper.result
        }

        newRecords = when (operation.type) {
            OperationType.SET -> {
                state.records + (operation.entityId to operation.payload)
            }

            OperationType.DEL -> {
                state.records + (operation.entityId to null)
            }
        }

        processedOper = ProcessedOperation(
            operation,
            ApplyResult.Accepted(
                operation.operationId,
                state.nextRevision,
            ),
        )

        newOperations = state.operations + (operation.operationId to processedOper)

        state = State(
            newRecords,
            newOperations,
            state.changes + RemoteChange(operation, state.nextRevision),
            state.nextRevision + 1
        )

        return processedOper.result
    }

    override fun getChanges(
        afterCursor: Long,
        limit: Int
    ): ChangesPage {
        require(afterCursor >= 0L) {
            "afterCursor should be >= 0. Currently: $afterCursor"
        }
        require(limit > 0L) {
            "limit should be > 0. Currently: $limit"
        }
        // Revisions are contiguous and acceptance-ordered. Clamp before converting
        // to Int so a cursor beyond the current history also returns an empty page.
        val start = minOf(afterCursor, state.changes.size.toLong()).toInt()
        val end = minOf(start.toLong() + limit, state.changes.size.toLong()).toInt()
        val changesList = state.changes.subList(start, end).toList()
        return ChangesPage(
            changesList,
            if(changesList.isNotEmpty()) changesList.last().serverRevision else afterCursor
        )
    }

    /** Возвращает текущее содержимое записи или null, если она отсутствует или удалена. */
    fun get(entityId: String): String? {
        return state.records[entityId]
    }
}
