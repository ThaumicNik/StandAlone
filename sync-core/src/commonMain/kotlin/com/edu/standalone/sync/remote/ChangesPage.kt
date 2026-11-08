package com.edu.standalone.sync.remote

/** Страница серверных изменений и позиция для следующего запроса. */
data class ChangesPage (
    val remoteChanges: List<RemoteChange> = emptyList(),
    val nextCursor: Long? = 0L
)
