package org.tatrman.kantheon.hebe.tools.mcp

import org.tatrman.kantheon.hebe.config.McpClientServerConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class McpToolFilterTest :
    StringSpec({

        fun createConfig(
            alwaysTools: List<String> = emptyList(),
            dynamicTools: List<String> = emptyList(),
            dynamicKeywords: List<String> = emptyList(),
        ) = McpClientServerConfig(
            name = "test",
            alwaysTools = alwaysTools,
            dynamicTools = dynamicTools,
            dynamicKeywords = dynamicKeywords,
        )

        "always_tools only - user message without matching keyword" {
            val config =
                createConfig(
                    alwaysTools = listOf("read_file", "write_file"),
                    dynamicTools = listOf("search_files"),
                    dynamicKeywords = listOf("search", "find"),
                )
            val allTools = listOf("read_file", "write_file", "search_files")
            val result = McpToolFilter().applicableTools(config, allTools, "hello world")
            result shouldBe listOf("read_file", "write_file")
        }

        "always_tools + dynamic_tools - user message with keyword match" {
            val config =
                createConfig(
                    alwaysTools = listOf("read_file"),
                    dynamicTools = listOf("search_files"),
                    dynamicKeywords = listOf("search", "find"),
                )
            val allTools = listOf("read_file", "search_files")
            val result = McpToolFilter().applicableTools(config, allTools, "please search for files")
            result shouldBe listOf("read_file", "search_files")
        }

        "empty tools returns empty list" {
            val config = createConfig()
            val result = McpToolFilter().applicableTools(config, emptyList(), "hello")
            result shouldBe emptyList()
        }

        "case insensitive keyword matching" {
            val config =
                createConfig(
                    dynamicTools = listOf("search_files"),
                    dynamicKeywords = listOf("search"),
                )
            val allTools = listOf("search_files")
            val result = McpToolFilter().applicableTools(config, allTools, "SEARCH")
            result shouldBe listOf("search_files")
        }

        "tool not in available list is not returned" {
            val config =
                createConfig(
                    alwaysTools = listOf("nonexistent"),
                )
            val allTools = listOf("read_file")
            val result = McpToolFilter().applicableTools(config, allTools, "hello")
            result shouldBe emptyList()
        }

        "dynamic_tools without keywords returns empty for dynamic" {
            val config =
                createConfig(
                    alwaysTools = listOf("a"),
                    dynamicTools = listOf("b"),
                    dynamicKeywords = emptyList(),
                )
            val allTools = listOf("a", "b")
            val result = McpToolFilter().applicableTools(config, allTools, "hello search beta")
            result shouldBe listOf("a")
        }
    })
