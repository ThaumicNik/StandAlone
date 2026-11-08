package com.edu.standalone.sync

import com.edu.standalone.sync.local.LocalStore
import com.edu.standalone.sync.remote.ApplyResult
import com.edu.standalone.sync.remote.RemoteStore
import com.edu.standalone.sync.remote.TransientRemoteException

/** Последовательно отправляет ожидающие операции одного клиента. */
class OperationSender(
    private val remote: RemoteStore,
    private val local: LocalStore,
    private val clientId: String
) {
    /**
     * Записывает попытку и результат доставки. При временном сбое сохраняет
     * ошибку и передаёт исключение вызывающему коду, остановив проход.
     */
    fun sendPending() {
        val pendingOperations = local.getPendingOperations(clientId)
        for (oper in pendingOperations) {
            val delivery = local.getDelivery(oper.operationId)
            require(delivery != null) {
                "Missing delivery for operationId: ${oper.operationId}"
            }
            var newDelivery = delivery.copy(attemptCount = delivery.attemptCount + 1)
            local.saveDelivery(newDelivery)
            val result = try {
                remote.applyOperation(oper)
            } catch (e: TransientRemoteException) {
                newDelivery = newDelivery.copy(lastError = e.message?: "Temporary remote failure")
                local.saveDelivery(newDelivery)
                throw e
            }
            require(result.operationId == oper.operationId) {
                "Response operationId ${result.operationId} does not match sent operationId ${oper.operationId}"
            }
            when (result) {
                is ApplyResult.Accepted -> {
                    newDelivery = newDelivery.copy(
                        status = DeliveryStatus.ACKNOWLEDGED,
                        lastError = null,
                        serverRevision = result.serverRevision
                    )
                }
                is ApplyResult.Rejected -> {
                    newDelivery = newDelivery.copy(
                        status = DeliveryStatus.REJECTED,
                        lastError = result.reason,
                    )
                }

            }
            local.saveDelivery(newDelivery)
        }
    }
}
