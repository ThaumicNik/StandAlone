package com.edu.standalone.sync.remote

/** Временный сбой удалённого вызова; результат применения операции может быть неизвестен. */
class TransientRemoteException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
