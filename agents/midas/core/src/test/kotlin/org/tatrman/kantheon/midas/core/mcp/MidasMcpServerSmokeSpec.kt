package org.tatrman.kantheon.midas.core.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.tatrman.kantheon.midas.core.repository.PositionRepository
import shared.ktor.mcp.McpTelemetry
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

/**
 * Stage 1.4 T4 — boots the embedded MCP server (CIO, installMcpKtorBase +
 * mcpStreamableHttp + the five tools) on a free port and confirms it serves the
 * base `/health` probe. Proves the wiring assembles and starts at runtime (no DB:
 * the position repo is only touched when a tool is invoked).
 */
class MidasMcpServerSmokeSpec :
    StringSpec({

        "the MCP server boots and serves /health" {
            val port = ServerSocket(0).use { it.localPort }
            val tools = MidasTools(mockk<PositionRepository>(relaxed = true), mockk<TransactionLog>(relaxed = true))
            val server = startMidasMcpServer(port, tools, McpTelemetry("midas-core-test", "rest"))
            try {
                var status = -1
                for (attempt in 1..50) {
                    status = runCatching { httpStatus("http://localhost:$port/health") }.getOrDefault(-1)
                    if (status == 200) break
                    Thread.sleep(100)
                }
                status shouldBe 200
            } finally {
                server.stop(100, 500)
            }
        }
    })

private fun httpStatus(url: String): Int {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    return try {
        conn.connectTimeout = 500
        conn.readTimeout = 500
        conn.requestMethod = "GET"
        conn.responseCode
    } finally {
        conn.disconnect()
    }
}
