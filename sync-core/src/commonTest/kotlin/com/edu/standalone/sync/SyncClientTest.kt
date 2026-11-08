package com.edu.standalone.sync

import com.edu.standalone.sync.local.InMemoryLocalStore
import com.edu.standalone.sync.local.SyncClient

import kotlin.test.Test
import kotlin.test.assertEquals

/** Проверки локального жизненного цикла записей, аудита и последовательности операций. */
class SyncClientTest {

    @Test
    fun localSequenceStepsTest(){
        var counter = 0L
        val storage = InMemoryLocalStore()
        val client1 = SyncClient(
            storage,
            "Test",
            { (++counter).toString() },
            { 0L }
        )

        val oper1 = client1.set(
            "1",
            "Test1"
        )
        val oper2 = client1.set(
            "2",
            "Test2"
        )
        val oper3 = client1.set(
            "3",
            "Test3"
        )

        assertEquals(1L, oper1.localSequence)
        assertEquals(2L, oper2.localSequence)
        assertEquals(3L, oper3.localSequence)

        val client2 = SyncClient(
            storage,
            "Test",
            { (++counter).toString() },
            { 0L }
        )

        val oper4 = client2.del("3")
        assertEquals(4L, oper4.localSequence)
    }

    @Test
    fun lifecycleTest(){
        var counter = 0L
        val storage = InMemoryLocalStore()
        val client = SyncClient(
            storage,
            "Test",
            { (++counter).toString() },
            { 0L }
        )
        assertEquals(null, client.get("Test"))
        val oper = client.set(
            "Test",
            "Payload"
        )
        assertEquals(1L, oper.localSequence)
        assertEquals("Test", oper.entityId)
        assertEquals("Payload", oper.payload)
        val getResult = client.get("Test")
        assertEquals(oper.payload, getResult)
        client.set("Test", "DifferentPayload")
        assertEquals("DifferentPayload", client.get("Test"))
        client.del("Test")
        assertEquals(null, client.get("Test"))
        client.set("Test", "Payload")
        assertEquals("Payload", client.get("Test"))
        val opers = storage.getOperations()
        assertEquals(4, opers.size)
        for (i in 0..<opers.size) {
            if (i == 2) {
                assertEquals(OperationType.DEL, opers[i].type)
            } else {
                assertEquals(OperationType.SET, opers[i].type)
            }
            assertEquals((i + 1).toLong(), opers[i].localSequence)
            assertEquals(DeliveryStatus.PENDING, storage.getDelivery(opers[i].operationId)!!.status)
        }
    }
}
