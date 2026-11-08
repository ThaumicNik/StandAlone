package com.edu.standalone.sync

/**
 * Снимок состояния доставки операции [SyncOperation].
 *
 * Новая операция ожидает отправки, имеет ноль попыток и не содержит ошибки.
 * Для обновления состояния создаётся копия через [copy]; запись аудита не меняется.
 *
 * @throws IllegalArgumentException если количество попыток отрицательное.
 */
data class OperationDelivery(
    /** Идентификатор операции аудита, к которой относится состояние доставки. */
    val operationId: String,
    /** Текущее состояние доставки; изначально [DeliveryStatus.PENDING]. */
    val status: DeliveryStatus = DeliveryStatus.PENDING,
    /** Количество попыток отправки; не может быть отрицательным. */
    val attemptCount: Int = 0,
    /** Описание последней ошибки или `null`, если описание отсутствует. */
    val lastError: String? = null,
    /** Ревизия, назначенная сервером этой операции; null до подтверждения. Не является курсором загрузки. */
    val serverRevision: Long? = null
) {
    init {
        require(attemptCount >= 0){
            "attemptCount should be >= 0"
        }
    }
}
