@file:Suppress("TooGenericExceptionCaught")

package org.tatrman.kantheon.hebe.cli.daemon

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.slf4j.LoggerFactory

class PidFile private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun close() {
        try {
            lock.release()
            channel.close()
            Files.deleteIfExists(path)
        } catch (e: Exception) {
            log.warn("Failed to release pid file {}: {}", path, e.message)
        }
    }

    companion object {
        fun acquire(path: Path): PidFile {
            Files.createDirectories(path.parent)
            val channel =
                FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            val lock =
                channel.tryLock()
                    ?: run {
                        val existingPid =
                            try {
                                Files.readString(path).trim()
                            } catch (_: IOException) {
                                "unknown"
                            }
                        channel.close()
                        System.err.println("hebe is already running (pid=$existingPid). Aborting.")
                        kotlin.system.exitProcess(1)
                    }
            val pid = ProcessHandle.current().pid()
            channel.truncate(0)
            channel.write(java.nio.ByteBuffer.wrap("$pid\n".toByteArray()))
            return PidFile(path, channel, lock)
        }
    }
}
