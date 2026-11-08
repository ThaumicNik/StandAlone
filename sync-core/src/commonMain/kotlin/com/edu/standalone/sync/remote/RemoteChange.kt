package com.edu.standalone.sync.remote

import com.edu.standalone.sync.SyncOperation

/** Принятая сервером операция и назначенная ей ревизия. */
data class RemoteChange (
    val operation: SyncOperation,
    val serverRevision: Long
)
