package com.edu.standalone.sync.local

import com.edu.standalone.sync.OperationType
import com.edu.standalone.sync.SyncOperation

/** Локальный API чтения и изменения записей с регистрацией операций аудита. */
class SyncClient (
    private val storage: LocalStore,
    val clientId: String,
    private val idSource: () -> String,
    private val timeSource: () -> Long
) {

    private fun getNextStepLocalSequence(): Long{
        val operations = storage.getOperations()
        val output: Long = operations
            .asSequence()
            .filter { clientId == it.clientId }
            .maxOfOrNull { it.localSequence } ?: 0L
        return output + 1
    }

    /** Читает локальную запись без создания операции аудита. */
    fun get(entityID: String): String? {
        return storage.get(entityID)
    }


    /** Создаёт или заменяет запись и сохраняет операцию для доставки. */
    fun set(entityId: String, payload: String): SyncOperation{
        val oper = SyncOperation(
            idSource(),
            entityId,
            clientId,
            getNextStepLocalSequence(),
            OperationType.SET,
            payload,
            timeSource()
        )
        storage.saveLocalOperation(oper)
        return oper
    }

    /** Отмечает запись удалённой и сохраняет операцию для доставки. */
    fun del(entityId: String): SyncOperation{
        val oper = SyncOperation(
            idSource(),
            entityId,
            clientId,
            getNextStepLocalSequence(),
            OperationType.DEL,
            null,
            timeSource()
        )
        storage.saveLocalOperation(oper)
        return oper
    }
}
