package com.edu.standalone.sync

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Проверки ограничений содержимого операции [SyncOperation]. */
class SyncOperationTest{
    /** Создание SET без содержимого завершается исключением. */
    @Test
    fun nullPayloadForSetIsRejected(){
        assertFailsWith<IllegalArgumentException> {
            val oper = SyncOperation(
                "Test",
                "Test",
                "Test",
                0,
                OperationType.SET,
                null,
                0
            )
        }
    }
}
