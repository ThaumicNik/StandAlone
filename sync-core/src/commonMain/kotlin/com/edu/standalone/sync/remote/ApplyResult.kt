package com.edu.standalone.sync.remote

import com.edu.standalone.sync.OperationDelivery

/** Окончательный ответ удалённой стороны; временные ошибки сюда не входят. */
sealed interface ApplyResult {
    val operationId: String

    /** Операция принята; ревизия назначается при первом успешном применении. */
    data class Accepted(
        override val operationId: String,
        val serverRevision: Long
    ) : ApplyResult

    /** Окончательный отказ; автоматический повтор не требуется. */
    data class Rejected(
        override val operationId: String,
        val reason: String
    ) : ApplyResult
}
