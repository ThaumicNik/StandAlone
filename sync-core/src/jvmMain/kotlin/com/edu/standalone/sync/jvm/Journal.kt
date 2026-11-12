package com.edu.standalone.sync.jvm

import java.io.*
import java.nio.file.*

/** Immutable event files, fsynced before atomic publication. Legacy snapshots remain readable. */
internal class Journal(private val file: File) {
    private val segments = File(file.absolutePath + ".events")
    private var segmentCount = 0
    private val events: MutableList<ByteArray> = (if (file.exists()) DataInputStream(file.inputStream()).use { input ->
        require(input.readInt() == 0x53594E31) { "Unsupported store format" }
        val count = input.readInt(); require(count >= 0)
        List(count) {
            val size = input.readInt(); require(size in 0..16777216)
            ByteArray(size).also { input.readFully(it) }
        }.also { require(input.read() == -1) }
    } else emptyList()).toMutableList()
    init {
        val files = segments.listFiles()?.filter { it.name.endsWith(".event") }?.sortedBy { it.name } ?: emptyList()
        files.forEachIndexed { index, segment ->
            require(segment.name == "%010d.event".format(index)) { "Missing journal segment" }
            require(segment.length() <= 16777216) { "Journal event exceeds 16 MiB" }
            events.add(segment.readBytes())
        }
        segmentCount = files.size
    }
    fun replay(block: (DataInputStream) -> Unit) = events.forEach { block(DataInputStream(it.inputStream())) }
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
        events.add(event)
    }
}
