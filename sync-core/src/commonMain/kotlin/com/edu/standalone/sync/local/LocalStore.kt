package com.edu.standalone.sync.local

import com.edu.standalone.sync.OperationDelivery
import com.edu.standalone.sync.SyncOperation
import com.edu.standalone.sync.remote.ChangesPage

/** Контракт локального хранилища данных **/
interface LocalStore {
    /** Highest stored sequence plus one. Call and save under the same ownership boundary. */
    fun nextSequence(clientId: String): Long =
        (getOperations().filter { it.clientId == clientId }.maxOfOrNull { it.localSequence } ?: 0L) + 1
    /** IDs known locally, including deleted records. Use get() to read visible values. */
    fun getEntityIds(): Set<String> = error("Listing is unsupported by this adapter")
    /** Download cursor; pages and cursor must commit atomically. */
    fun getCursor(): Long = 0L

    fun applyRemotePage(page: ChangesPage) { error("Downloading is unsupported by this adapter") }
    /**  Прочитать содержимое. null, если запись отсутствует или удалена **/
    fun get(entityId: String): String?

    /**  Применить локальную операцию и сохранить аудит и доставку
     * Для SET: сохранить payload как текущее содержимое записи
     * Для DEL: оставить отметку удаления, скрыть запись от GET
     * Добавляет операцию в аудит
     * При успешном сохранении создаёт состояние добавки OperationDelivery
     * Действует атомарно, при ошибке изменений не происходит
     * Отклоняет повторные вызовы для одного OperationId
     */
    fun saveLocalOperation(operation: SyncOperation)

    /**  Получить аудит в порядке локального сохранения **/
    fun getOperations(): List<SyncOperation>

    /**  Получить состояние доставки. null если операция неизвестна **/
    fun getDelivery(operationId: String): OperationDelivery?

    /** Ожидающие операции клиента в порядке localSequence. */
    fun getPendingOperations(clientId: String): List<SyncOperation>

    /** Сохраняет состояние доставки существующей операции; неизвестный идентификатор отклоняется. */
    fun saveDelivery(delivery: OperationDelivery)
}
