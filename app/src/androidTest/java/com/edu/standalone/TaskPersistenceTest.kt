package com.edu.standalone

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Device tests: private files/preferences avoid changing the demo user's data. */
class TaskPersistenceTest {
    private class TestApplication(base: Context, private val directory: File) : Application() {
        private val prefix = UUID.randomUUID().toString()
        init { attachBaseContext(base) }
        override fun getFilesDir() = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$prefix-$name", mode)
    }

    @Test fun switchingEndpointsPreservesIndependentPendingHistories() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, UUID.randomUUID().toString()).also { it.mkdirs() }
        val context = TestApplication(base, directory)
        val first = "https://example.test/${UUID.randomUUID()}/a"
        val second = "https://example.test/${UUID.randomUUID()}/b"
        context.getSharedPreferences("sync", 0).edit().putString("endpoint", first).commit()
        try {
            val a = SyncRepository.session(context)
            a.access { _, client -> client.set("task:one", "first") }
            SyncRepository.selectEndpoint(context, second)
            val b = SyncRepository.session(context)
            assertNull(b.local.get("task:one"))
            assertEquals(0L, b.local.getCursor())
            b.access { _, client -> client.set("task:one", "second") }
            SyncRepository.selectEndpoint(context, first)
            assertSame(a, SyncRepository.session(context))
            assertEquals("first", a.local.get("task:one"))
            assertEquals("second", b.local.get("task:one"))
            assertEquals(1, a.local.getPendingOperations(a.clientId).size)
            assertTrue(File(directory, "sync.bin.events").isDirectory)
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedSaveKeepsDraftAndSavedStateRestoresIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        // A regular file in place of the files directory reliably causes persistence failure.
        val directory = File(base.cacheDir, UUID.randomUUID().toString()).also { it.writeText("blocked") }
        val context = TestApplication(base, directory)
        context.getSharedPreferences("sync", 0).edit()
            .putString("endpoint", "https://example.test/${UUID.randomUUID()}").commit()
        val saved = SavedStateHandle()
        lateinit var model: TaskViewModel
        val store = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                model = TaskViewModel(context, saved)
                store.put("test", model)
                model.createTask()
                model.editDraft("Inspect pump", "Street 1", "10:00")
                model.saveDraft()
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (model.state.value.saving && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(model.state.value.saving)
            assertNotNull(model.state.value.error)
            assertTrue(model.state.value.creating)
            assertEquals("Inspect pump", model.state.value.title)
            instrumentation.runOnMainSync {
                val restored = TaskViewModel(context, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
                store.put("restored", restored)
                assertTrue(restored.state.value.creating)
                assertEquals("Inspect pump", restored.state.value.title)
                assertEquals("Street 1", restored.state.value.address)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            directory.delete()
        }
    }
}
