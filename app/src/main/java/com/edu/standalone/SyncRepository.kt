package com.edu.standalone

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import com.edu.standalone.sync.SyncEngine
import com.edu.standalone.sync.jvm.FileLocalStore
import com.edu.standalone.sync.jvm.SerializedLocalStore
import com.edu.standalone.sync.local.LocalStore
import com.edu.standalone.sync.local.SyncClient
import com.edu.standalone.sync.remote.TransientRemoteException
import com.edu.standalone.sync.retrofit.RetrofitRemoteStore
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Process-wide ownership of endpoint-specific journals. Network calls never hold a local lock. */
object SyncRepository {
    internal class Session(val endpoint: String, val local: SerializedLocalStore, val clientId: String) {
        val key = EndpointIdentity.key(endpoint)
        fun <T> access(action: (LocalStore, SyncClient) -> T): T = local.access {
            action(it, SyncClient(it, clientId, { UUID.randomUUID().toString() }, System::currentTimeMillis))
        }
    }

    private val sessions = mutableMapOf<String, Session>()
    internal val syncLock = Any()

    private fun bindLegacy(prefs: SharedPreferences): String {
        prefs.getString("legacyEndpoint", null)?.let { return it }
        val previous = prefs.getString("endpoint", EndpointIdentity.DEFAULT)!!
        val owner = runCatching { EndpointIdentity.normalize(previous) }.getOrDefault(previous)
        check(prefs.edit().putString("legacyEndpoint", owner).commit())
        return owner
    }

    @Synchronized internal fun session(context: Context): Session {
        val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
        val endpoint = EndpointIdentity.normalize(prefs.getString("endpoint", EndpointIdentity.DEFAULT)!!)
        // Bind legacy files before any endpoint can be changed; never move or discard them.
        val legacy = bindLegacy(prefs)
        return sessions.getOrPut(endpoint) {
            val id = prefs.getString("clientId", null) ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString("clientId", it).commit())
            }
            val path = if (endpoint == legacy) "sync.bin" else "sync-${EndpointIdentity.key(endpoint)}.bin"
            Session(endpoint, SerializedLocalStore(FileLocalStore(File(context.filesDir, path))), id)
        }
    }

    @Synchronized internal fun selectEndpoint(context: Context, value: String) {
        val endpoint = EndpointIdentity.normalize(value)
        val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
        bindLegacy(prefs) // recover invalid old configuration without opening or reassigning its journal
        check(prefs.edit().putString("endpoint", endpoint).commit())
    }

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // Follow-up requests ensure edits arriving at the end of a pass are eventually sent.
        WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = synchronized(SyncRepository.syncLock) {
        val session = try { SyncRepository.session(applicationContext) } catch (_: Exception) { return Result.failure() }
        val prefs = applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
        try {
            val remote = RetrofitRemoteStore(session.endpoint)
            SyncEngine(session.local, remote, session.clientId).synchronize()
            if (remote.getChanges(session.local.getCursor(), 1).remoteChanges.isNotEmpty() ||
                session.local.getPendingOperations(session.clientId).isNotEmpty()) return Result.retry()
            prefs.edit().putLong("lastSync:${session.key}", System.currentTimeMillis())
                .remove("syncError:${session.key}").apply()
            Result.success()
        } catch (_: TransientRemoteException) {
            prefs.edit().putString("syncError:${session.key}", "Сервер недоступен. Повторим попытку.").apply()
            Result.retry()
        } catch (e: Exception) {
            prefs.edit().putString("syncError:${session.key}", "Ошибка синхронизации: ${e.message}").apply()
            Result.failure()
        }
    }
}
