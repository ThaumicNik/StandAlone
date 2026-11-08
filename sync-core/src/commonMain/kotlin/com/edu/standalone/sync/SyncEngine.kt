package com.edu.standalone.sync

import com.edu.standalone.sync.local.LocalStore
import com.edu.standalone.sync.remote.RemoteStore

/** One serialized pull/send/pull pass. Retry after transient failure with the same store. */
class SyncEngine(private val local: LocalStore, private val remote: RemoteStore,
                 private val clientId: String, private val pageSize: Int = 100) {
    init { require(pageSize > 0) }
    fun synchronize() {
        download()
        OperationSender(remote, local, clientId).sendPending()
        download()
    }
    private fun download() {
        while (true) {
            val page = remote.getChanges(local.getCursor(), pageSize)
            require(page.remoteChanges.size <= pageSize)
            local.applyRemotePage(page)
            if (page.remoteChanges.isEmpty()) return
        }
    }
}
