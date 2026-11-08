package com.edu.standalone.sync

import com.edu.standalone.sync.remote.ApplyResult
import com.edu.standalone.sync.remote.InMemoryRemoteStore
import com.edu.standalone.sync.remote.ChangesPage
import com.edu.standalone.sync.remote.RemoteChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Проверка применения операций, повторов и ревизий удалённого хранилища. */
class InMemoryRemoteStoreTest {

    @Test
    fun pagesIncludeSetAndDeleteAndDoNotDuplicateRetries() {
        val store = InMemoryRemoteStore()
        val first = SyncOperation("1", "entity", "client", 1, OperationType.SET, "value", 0)
        val second = SyncOperation("2", "entity", "client", 2, OperationType.DEL, null, 0)
        val third = SyncOperation("3", "entity", "client", 3, OperationType.SET, "new", 0)
        listOf(first, second, third).forEach(store::applyOperation)
        store.applyOperation(first)

        assertEquals(ChangesPage(listOf(RemoteChange(first, 1), RemoteChange(second, 2)), 2), store.getChanges(0, 2))
        assertEquals(ChangesPage(listOf(RemoteChange(third, 3)), 3), store.getChanges(2, 2))
        assertEquals(ChangesPage(emptyList(), 3), store.getChanges(3, 2))
        assertEquals(ChangesPage(emptyList(), 10), store.getChanges(10, 2))
        assertEquals("new", store.get("entity"))
    }

    @Test
    fun emptyStoreReturnsRequestedCursor() {
        val store = InMemoryRemoteStore()
        assertEquals(ChangesPage(emptyList(), 0), store.getChanges(0, 1))
        assertEquals(ChangesPage(emptyList(), 5), store.getChanges(5, 1))
    }

    @Test
    fun invalidPageArgumentsAreRejected() {
        val store = InMemoryRemoteStore()
        assertFailsWith<IllegalArgumentException> { store.getChanges(-1, 1) }
        assertFailsWith<IllegalArgumentException> { store.getChanges(0, 0) }
        assertFailsWith<IllegalArgumentException> { store.getChanges(0, -1) }
    }

    @Test
    fun lifecycleTest(){
        val store = InMemoryRemoteStore()
        var oper = SyncOperation(
            "1",
            "1",
            clientId = "1",
            0,
            OperationType.SET,
            "Test",
            0
        )
        val firstOper = oper
        var result = assertIs<ApplyResult.Accepted>(store.applyOperation(oper))
        assertEquals(1L, result.serverRevision)

        var getResult = store.get("1")
        assertEquals("Test", getResult)

        result = assertIs<ApplyResult.Accepted>(store.applyOperation(oper))
        assertEquals(1L, result.serverRevision)
        oper = SyncOperation(
            "1",
            "1",
            clientId = "1",
            0,
            OperationType.DEL,
            null,
            4
        )
        assertFailsWith<IllegalArgumentException>{
            store.applyOperation(oper)
        }
        oper = SyncOperation(
            "2",
            "1",
            clientId = "1",
            0,
            OperationType.DEL,
            null,
            0
        )

        result = assertIs<ApplyResult.Accepted>(store.applyOperation(oper))
        assertEquals(2L, result.serverRevision)

        getResult = store.get("1")
        assertEquals(null, getResult)

        result = assertIs<ApplyResult.Accepted>(store.applyOperation(firstOper))
        assertEquals(1L, result.serverRevision)

        getResult = store.get("1")
        assertEquals(null, getResult)

        oper = SyncOperation(
            "3",
            "1",
            clientId = "1",
            0,
            OperationType.SET,
            "Test",
            0
        )

        result = assertIs<ApplyResult.Accepted>(store.applyOperation(oper))
        assertEquals(3L, result.serverRevision)

        getResult = store.get("1")
        assertEquals("Test", getResult)

        getResult = store.get("2")
        assertEquals(null, getResult)
    }
}
