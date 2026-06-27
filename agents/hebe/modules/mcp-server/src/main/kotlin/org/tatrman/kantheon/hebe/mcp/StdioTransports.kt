package org.tatrman.kantheon.hebe.mcp

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

object StdioTransports {
    fun createStdioServerTransport(): StdioServerTransport =
        StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered(),
        )
}
