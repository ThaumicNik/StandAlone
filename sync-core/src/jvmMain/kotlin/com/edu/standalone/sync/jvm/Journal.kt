package com.edu.standalone.sync.jvm

import java.io.*
import java.nio.file.*

/** Atomic replacement with fsync before publication. A single process owns each file. */
internal class Journal(private val file: File) {
    private var events: List<ByteArray> = if (file.exists()) DataInputStream(file.inputStream()).use { input ->
        require(input.readInt() == 0x53594E31) { "Unsupported store format" }
        val count = input.readInt(); require(count >= 0)
        List(count) {
            val size = input.readInt(); require(size in 0..16777216)
            ByteArray(size).also { input.readFully(it) }
        }.also { require(input.read() == -1) }
    } else emptyList()
    fun replay(block: (DataInputStream) -> Unit) = events.forEach { block(DataInputStream(it.inputStream())) }
    fun append(event: ByteArray) {
        val next = events + listOf(event)
        file.absoluteFile.parentFile.mkdirs()
        val temp = File(file.absolutePath + ".tmp")
        FileOutputStream(temp).use { stream ->
            val out = DataOutputStream(stream)
            out.writeInt(0x53594E31); out.writeInt(next.size)
            next.forEach { out.writeInt(it.size); out.write(it) }
            out.flush(); stream.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        events = next
    }
}
