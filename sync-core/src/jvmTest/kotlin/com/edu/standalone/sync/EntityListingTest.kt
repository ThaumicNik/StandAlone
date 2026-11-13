package com.edu.standalone.sync

import com.edu.standalone.sync.jvm.FileLocalStore
import com.edu.standalone.sync.local.SyncClient
import com.edu.standalone.sync.remote.InMemoryRemoteStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntityListingTest {
    @Test fun downloadedAndLocalIdsSurviveReopenAndDeletion() {
        val file = Files.createTempDirectory("entity-list").resolve("journal").toFile()
        val remote = InMemoryRemoteStore()
        val remoteStore = com.edu.standalone.sync.local.InMemoryLocalStore()
        val remoteClient = SyncClient(remoteStore, "remote", { "remote-op" }, { 0 })
        remote.applyOperation(remoteClient.set("task:server", "server"))
        val local = FileLocalStore(file)
        val client = SyncClient(local, "local", { "local-op" }, { 0 })
        client.set("task:local", "local")
        SyncEngine(local, remote, client.clientId).synchronize()
        val reopened = FileLocalStore(file)
        assertEquals(setOf("task:server", "task:local"), reopened.getEntityIds())
        assertEquals("server", reopened.get("task:server"))
        val deleting = SyncClient(reopened, "local", { "delete-op" }, { 0 })
        deleting.del("task:server")
        assertNull(reopened.get("task:server"))
        assertEquals(setOf("task:server", "task:local"), reopened.getEntityIds())
    }
}
