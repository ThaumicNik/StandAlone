package com.edu.standalone.sync

/**
 * Неизменяемая запись аудита одного локального изменения.
 *
 * Повторная отправка использует тот же идентификатор и содержимое операции.
 * Состояние доставки хранится отдельно в [OperationDelivery].
 *
 * @throws IllegalArgumentException если у SET отсутствует содержимое
 * или у DEL содержимое задано.
 */
data class SyncOperation (
    /** Уникальный идентификатор операции, сохраняемый при повторных отправках. */
    val operationId: String,
    /** Идентификатор создаваемой, изменяемой или удаляемой записи. */
    val entityId: String,
    /** Устойчивый идентификатор клиента, создавшего операцию. */
    val clientId: String,
    /** Порядковый номер операции в локальной последовательности данного клиента. */
    val localSequence: Long,
    /** Тип изменения: [OperationType.SET] или [OperationType.DEL]. */
    val type: OperationType,
    /** Полное новое содержимое для SET; для DEL обязательно `null`. */
    val payload: String?,
    /** Время создания в миллисекундах с Unix epoch; не определяет победителя при конфликте. */
    val createdAt: Long
){
    init {
        when(type){
            OperationType.SET ->
                require(payload != null) {
                    "SET sync operations should have not null payload."
                }
            OperationType.DEL ->
                require (payload == null) {
                    "DEL sync operations should have null payload."
                }
        }
    }
}
