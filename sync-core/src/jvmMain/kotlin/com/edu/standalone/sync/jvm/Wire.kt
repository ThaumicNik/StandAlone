package com.edu.standalone.sync.jvm

import com.edu.standalone.sync.*
import com.edu.standalone.sync.remote.*
import java.io.*

/** Version 1 binary wire codec; strings use length-prefixed UTF-8, never Java serialization. */
object Wire {
    fun text(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1048576)
        out.writeInt(bytes.size); out.write(bytes)
    }
    fun text(input: DataInputStream): String {
        val size = input.readInt(); require(size in 0..1048576)
        return ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
    }
    fun operation(out: DataOutputStream, op: SyncOperation) {
        text(out, op.operationId); text(out, op.entityId); text(out, op.clientId)
        out.writeLong(op.localSequence); out.writeByte(op.type.ordinal)
        if (op.type == OperationType.SET) text(out, op.payload!!)
        out.writeLong(op.createdAt)
    }
    fun operation(input: DataInputStream): SyncOperation {
        val id = text(input); val entity = text(input); val client = text(input)
        val sequence = input.readLong(); val type = OperationType.entries[input.readUnsignedByte()]
        return SyncOperation(id, entity, client, sequence, type,
            if (type == OperationType.SET) text(input) else null, input.readLong())
    }
    fun page(out: DataOutputStream, page: ChangesPage) {
        out.writeInt(page.remoteChanges.size)
        page.remoteChanges.forEach { operation(out, it.operation); out.writeLong(it.serverRevision) }
        out.writeLong(requireNotNull(page.nextCursor))
    }
    fun page(input: DataInputStream): ChangesPage {
        val size = input.readInt(); require(size in 0..10000)
        return ChangesPage(List(size) { RemoteChange(operation(input), input.readLong()) }, input.readLong())
    }
    fun result(out: DataOutputStream, result: ApplyResult) {
        out.writeBoolean(result is ApplyResult.Accepted); text(out, result.operationId)
        when (result) {
            is ApplyResult.Accepted -> out.writeLong(result.serverRevision)
            is ApplyResult.Rejected -> text(out, result.reason)
        }
    }
    fun result(input: DataInputStream): ApplyResult {
        val accepted = input.readBoolean(); val id = text(input)
        return if (accepted) ApplyResult.Accepted(id, input.readLong()) else ApplyResult.Rejected(id, text(input))
    }
    fun encode(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray()
}
