package com.edu.standalone.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Проверки допустимого количества попыток в [OperationDelivery]. */
class OperationDeliveryTest{
    /** Отрицательное количество попыток отклоняется при создании состояния доставки. */
    @Test
    fun negativeAttemptCountIsRejected() {
        assertFailsWith<IllegalArgumentException>{
            OperationDelivery(
                "Test",
                attemptCount = -1
            )
        }
    }

    /** Ноль является допустимой нижней границей количества попыток. */
    @Test
    fun zeroAttemptCountIsAllowed() {
        val delivery = OperationDelivery(
            "Test",
            attemptCount = 0
        )
        assertEquals(0, delivery.attemptCount)
    }
}
