package com.edu.standalone

import android.content.Context
import androidx.work.*
import com.edu.standalone.sync.SyncEngine
import com.edu.standalone.sync.jvm.FileLocalStore
import com.edu.standalone.sync.local.SyncClient
import com.edu.standalone.sync.remote.TransientRemoteException
import com.edu.standalone.sync.retrofit.RetrofitRemoteStore
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One process-wide owner. Both UI actions and persistent workers use this executor. */
object SyncRepository {
    val executor = Executors.newSingleThreadExecutor()
    private var store: FileLocalStore? = null
    @Synchronized fun <T> access(context: Context, action: (FileLocalStore, SyncClient) -> T): T {
        val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
        val id = prefs.getString("clientId", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("clientId", it).commit())
        }
        val local = store ?: FileLocalStore(File(context.filesDir, "sync.bin")).also { store = it }
        return action(local, SyncClient(local, id, { UUID.randomUUID().toString() }, System::currentTimeMillis))
    }
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = SyncRepository.executor.submit<Result> {
        try {
            SyncRepository.access(applicationContext) { local, client ->
                val endpoint = applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
                    .getString("endpoint", "http://10.0.2.2:8080")!!
                SyncEngine(local, RetrofitRemoteStore(endpoint), client.clientId).synchronize()
                // A bounded pass may leave remote history to download. An empty page confirms catch-up.
                if (RetrofitRemoteStore(endpoint).getChanges(local.getCursor(), 1).remoteChanges.isNotEmpty())
                    return@access Result.retry()
                Result.success()
            }
        } catch (_: TransientRemoteException) { Result.retry() }
        catch (_: Exception) { Result.failure() }
    }.get()
}
