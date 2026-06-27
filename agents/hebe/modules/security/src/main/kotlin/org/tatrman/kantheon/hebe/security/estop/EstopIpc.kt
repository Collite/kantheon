package org.tatrman.kantheon.hebe.security.estop

import java.nio.channels.ServerSocketChannel
import java.nio.file.Path

object EstopIpc {
    private const val SOCKET_NAME = ".estop.sock"

    fun getSocketPath(dataDir: Path): Path = dataDir.resolve(SOCKET_NAME)

    fun sendStop(socketPath: Path): Boolean {
        return try {
            val address = java.net.UnixDomainSocketAddress.of(socketPath)
            java.net.Socket().use { socket ->
                socket.connect(address)
                socket.getOutputStream().use { out ->
                    out.write("STOP\n".toByteArray())
                    out.flush()
                }
                socket.getInputStream().use { input ->
                    val response = input.readBytes().toString(Charsets.UTF_8).trim()
                    return response == "OK"
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun startServer(
        socketPath: Path,
        onStop: () -> Unit,
    ) {
        val server = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        try {
            java.nio.file.Files
                .deleteIfExists(socketPath)
            server.bind(java.net.UnixDomainSocketAddress.of(socketPath))
            while (true) {
                try {
                    val socket = server.accept()
                    socket.use { s ->
                        val data =
                            s
                                .socket()
                                .getInputStream()
                                .readBytes()
                                .toString(Charsets.UTF_8)
                        if (data.trim() == "STOP") {
                            onStop()
                            s.socket().getOutputStream().use { out ->
                                out.write("OK\n".toByteArray())
                                out.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        } finally {
            server.close()
        }
    }
}
