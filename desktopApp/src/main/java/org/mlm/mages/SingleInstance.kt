package org.mlm.mages

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

private const val LOCK_FILE_NAME = "single_instance.lock"
private const val FORWARD_PORT = 45931

class SingleInstance(lockDir: Path) {

    val isFirstInstance: Boolean

    private val serverSocket: ServerSocket?
    private val lockChannel: FileChannel?

    init {
        Files.createDirectories(lockDir)
        val channel = FileChannel.open(
            lockDir.resolve(LOCK_FILE_NAME),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE
        )
        val acquired = try {
            channel.tryLock() != null
        } catch (_: Throwable) {
            // Locking is not verifiable here; don't block startup.
            true
        }
        if (acquired) {
            isFirstInstance = true
            lockChannel = channel
            serverSocket = runCatching {
                ServerSocket(FORWARD_PORT, 50, InetAddress.getLoopbackAddress())
            }.getOrNull()
        } else {
            channel.close()
            isFirstInstance = false
            lockChannel = null
            serverSocket = null
        }
    }

    fun forwardArgs(args: Array<String>): Boolean = runCatching {
        Socket(InetAddress.getLoopbackAddress(), FORWARD_PORT).use { socket ->
            BufferedWriter(OutputStreamWriter(socket.getOutputStream())).use { writer ->
                args.forEach { writer.appendLine(it) }
                writer.flush()
            }
        }
        true
    }.getOrDefault(false)

    fun startReceiving(onArgs: (List<String>) -> Unit) {
        val server = serverSocket ?: return
        thread(isDaemon = true, name = "mages-instance-forward") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                val lines = BufferedReader(InputStreamReader(client.getInputStream())).readLines()
                client.close()
                if (lines.isNotEmpty()) onArgs(lines)
            }
        }
    }
}
