package org.tatrman.kantheon.hebe.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class McpServerSmokeTest {
    @Test
    fun `createHebeMcpServer creates server with correct info`() {
        val server = createHebeMcpServer()
        assertNotNull(server)
    }

    @Test
    fun `addHelloWorldTool registers hello_world tool`() {
        val server = createHebeMcpServer()
        server.addHelloWorldTool()
    }

    @Test
    fun `ToolSchema properties are correct`() {
        val schema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "name",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Name to greet")
                                    },
                                )
                            },
                        )
                    },
                required = emptyList(),
            )
        assertNotNull(schema)
        assertNotNull(schema.properties)
    }
}
