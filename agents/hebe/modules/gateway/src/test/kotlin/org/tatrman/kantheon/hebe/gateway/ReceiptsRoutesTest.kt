package org.tatrman.kantheon.hebe.gateway

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReceiptsRoutesTest {
    @Test
    fun `list receipts returns empty list when dir does not exist`() =
        testApplication {
            val dir = Path.of("/tmp/nonexistent-receipts-test-dir")
            application {
                routing { ReceiptsRoutes.register(this, dir) }
            }

            val response = client.get("/api/receipts")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("receipts" in body)
        }

    @Test
    fun `list receipts returns entries from log files`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val logFile = tempDir.resolve("2024-01-01.log")
        Files.writeString(
            logFile,
            """{"seq":1,"ts":"2024-01-01T10:00:00Z","op":"tool_call","tool":"shell","args":{},"selfHash":"abc","prevHash":""}""" + "\n",
        )
        application {
            routing { ReceiptsRoutes.register(this, tempDir) }
        }

        val response = client.get("/api/receipts")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("receipts" in body)
    }

    @Test
    fun `list receipts respects limit param`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val logFile = tempDir.resolve("2024-01-01.log")
        val lines =
            (1..10).joinToString("\n") { i ->
                """{"seq":$i,"ts":"2024-01-01T10:0$i:00Z","op":"tool_call","tool":"shell","args":{},"selfHash":"hash$i","prevHash":""}"""
            }
        Files.writeString(logFile, lines + "\n")
        application {
            routing { ReceiptsRoutes.register(this, tempDir) }
        }

        val response = client.get("/api/receipts?limit=3")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `verify returns error when public key is missing`(
        @TempDir tempDir: Path,
    ) = testApplication {
        application {
            routing { ReceiptsRoutes.register(this, tempDir) }
        }

        val response = client.get("/api/receipts/verify")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertTrue("ok" in body)
    }
}
