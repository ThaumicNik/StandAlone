package com.edu.standalone.sync.remote

import com.edu.standalone.sync.SyncOperation

/** Контракт удалённой стороны синхронизации. */
interface RemoteStore {
    /**
     * Применить операцию и вернуть её окончательный результат.
     *
     * Повтор идентичной операции возвращает исходный результат без повторного
     * изменения данных и без новой ревизии. Тот же operationId с другим
     * содержимым является ошибкой протокола.
     *
     * Временный сбой передаётся через [TransientRemoteException]: результат
     * применения может быть неизвестен. Клиент сохраняет операцию для повтора
     * с тем же содержимым и operationId.
     */
    fun applyOperation(operation: SyncOperation): ApplyResult

    /**
     * Возвращает до [limit] принятых изменений с ревизиями строго больше
     * [afterCursor], упорядоченных по возрастанию ревизии. Начальный курсор — 0;
     * [afterCursor] неотрицателен, [limit] положителен. Нарушение этих условий
     * приводит к IllegalArgumentException.
     *
     * Следующий курсор равен ревизии последнего выданного изменения или
     * [afterCursor], если страница пуста. В выдачу входят SET и DEL. Чтение
     * не меняет курсор клиента: он сохраняется после применения изменений.
     * Временный сбой передаётся через [TransientRemoteException].
     */
    fun getChanges(afterCursor: Long, limit: Int): ChangesPage
}
