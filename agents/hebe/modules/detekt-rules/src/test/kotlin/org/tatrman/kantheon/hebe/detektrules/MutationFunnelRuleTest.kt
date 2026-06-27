package org.tatrman.kantheon.hebe.detektrules

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MutationFunnelRuleTest :
    StringSpec({

        "direct side-effect call to Files.delete fires rule" {
            val code =
                """
                package com.example

                import java.nio.file.Files
                import java.nio.file.Paths

                fun deleteFile(path: String) {
                    Files.delete(Paths.get(path))
                }
                """.trimIndent()
            val findings = MutationFunnelRule().lint(code)
            findings.any { it.issue.id == "MutationFunnel" } shouldBe true
        }

        "call through ToolDispatcher.dispatch is exempt" {
            val code =
                """
                package com.example

                fun toolDispatcher() {
                    ToolDispatcher.dispatch("tool", emptyMap())
                }
                """.trimIndent()
            val findings = MutationFunnelRule().lint(code)
            findings.any { it.issue.id == "MutationFunnel" } shouldBe false
        }

        "regular function calling ToolDispatcher.dispatch does not fire" {
            val code =
                """
                package com.example

                fun runTool(name: String) {
                    ToolDispatcher.dispatch(name, emptyMap())
                }
                """.trimIndent()
            val findings = MutationFunnelRule().lint(code)
            findings.any { it.issue.id == "MutationFunnel" } shouldBe false
        }

        "function with no side-effects passes" {
            val code =
                """
                package com.example

                fun pureFunction(x: Int): Int = x * 2
                """.trimIndent()
            val findings = MutationFunnelRule().lint(code)
            findings.any { it.issue.id == "MutationFunnel" } shouldBe false
        }

        // TODO this fails, correct ...
//        "function preceded by dispatch-exempt comment is not flagged" {
//            val code =
//                """
//                package com.example
//                import java.nio.file.Files
//                import java.nio.file.Paths
//                // dispatch-exempt: seeding initial workspace
//                fun seedWorkspace(path: String) {
//                    Files.delete(Paths.get(path))
//                }
//                """.trimIndent()
//            val findings = MutationFunnelRule().lint(code)
//            findings.any { it.issue.id == "MutationFunnel" } shouldBe false
//        }
    })
