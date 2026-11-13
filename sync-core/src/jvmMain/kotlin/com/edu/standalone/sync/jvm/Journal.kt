package com.edu.standalone.sync.jvm

import java.io.*
import java.nio.file.*

/** Immutable event files, fsynced before atomic publication. Legacy snapshots remain readable. */
internal class Journal(private val file: File) {
    private val segments = File(file.absolutePath + ".events")
    private var segmentCount = 0
    fun replay(block: (DataInputStream) -> Unit) {
        // Stream legacy events and segments one at a time instead of retaining
        // another full encoded copy of history alongside the decoded store.
        if (file.exists()) DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 0x53594E31) { "Unsupported store format" }
            val count = input.readInt(); require(count >= 0)
            repeat(count) {
                val size = input.readInt(); require(size in 0..16777216)
                val bytes = ByteArray(size).also { input.readFully(it) }
                DataInputStream(bytes.inputStream()).use(block)
            }
            require(input.read() == -1)
        }
        val files = segments.listFiles()?.filter { it.name.endsWith(".event") }?.sortedBy { it.name } ?: emptyList()
        files.forEachIndexed { index, segment ->
            require(segment.name == "%010d.event".format(index)) { "Missing journal segment" }
            require(segment.length() <= 16777216) { "Journal event exceeds 16 MiB" }
            DataInputStream(segment.inputStream().buffered()).use(block)
        }
        segmentCount = files.size
    }
    fun append(event: ByteArray) {
        require(event.size <= 16777216) { "Journal event exceeds 16 MiB" }
        check(segments.isDirectory || segments.mkdirs()) { "Cannot create journal directory" }
        val temp = File(file.absolutePath + ".tmp")
        FileOutputStream(temp).use { stream ->
            val out = DataOutputStream(stream)
            out.write(event)
            out.flush(); stream.fd.sync()
        }
        val target = File(segments, "%010d.event".format(segmentCount))
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        segmentCount++
    }
}
