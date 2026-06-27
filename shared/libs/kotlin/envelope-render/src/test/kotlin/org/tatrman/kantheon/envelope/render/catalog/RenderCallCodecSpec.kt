package org.tatrman.kantheon.envelope.render.catalog

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.core.spec.style.StringSpec

class RenderCallCodecSpec :
    StringSpec({

        "decodes a plaintext tool call" {
            RenderCallCodec
                .parse("""{"tool":"RenderPlaintext","text":"ahoj"}""")
                .shouldBeInstanceOf<RenderCall.Plaintext>()
                .text shouldBe "ahoj"
        }

        "decodes a table tool call with content and details, ignoring unknown keys" {
            val raw =
                """
                {"tool":"RenderTable","text":null,"content":[{"A":1}],
                 "details":{"alternateColors":"Rows","headers":[{"name":"A","title":"A"}]},
                 "extraneous":true}
                """.trimIndent()
            val call = RenderCallCodec.parse(raw).shouldBeInstanceOf<RenderCall.Table>()
            call.text shouldBe null
            call.details.alternateColors shouldBe "Rows"
            call.content.toString() shouldBe """[{"A":1}]"""
        }

        "decodes a chart tool call, accepting `intent` or legacy `details`" {
            val viaIntent =
                RenderCallCodec
                    .parse("""{"tool":"RenderChart","content":[{"x":1}],"intent":{"kind":"line","x":"x","y":["v"]}}""")
                    .shouldBeInstanceOf<RenderCall.Chart>()
            viaIntent.intent.kind shouldBe "line"

            val viaDetails =
                RenderCallCodec
                    .parse("""{"tool":"RenderChart","content":[{"x":1}],"details":{"kind":"bar","x":"x","y":["v"]}}""")
                    .shouldBeInstanceOf<RenderCall.Chart>()
            viaDetails.intent.kind shouldBe "bar"
        }

        "strips ```json code fences" {
            RenderCallCodec
                .parse("```json\n{\"tool\":\"RenderPlaintext\",\"text\":\"x\"}\n```")
                .shouldBeInstanceOf<RenderCall.Plaintext>()
        }

        "a missing tool field is NO_TOOL_CALL" {
            shouldThrow<FormatToolException> {
                RenderCallCodec.parse("""{"text":"orphan"}""")
            }.reason shouldBe FormatToolException.Reason.NO_TOOL_CALL
        }

        "non-JSON is SCHEMA_INVALID; a non-object is NO_TOOL_CALL" {
            shouldThrow<FormatToolException> { RenderCallCodec.parse("not json at all") }
                .reason shouldBe FormatToolException.Reason.SCHEMA_INVALID
            shouldThrow<FormatToolException> { RenderCallCodec.parse("[1,2,3]") }
                .reason shouldBe FormatToolException.Reason.NO_TOOL_CALL
        }

        "an unrecognised tool name is UNKNOWN_TOOL" {
            shouldThrow<FormatToolException> {
                RenderCallCodec.parse("""{"tool":"RenderHologram","text":"x"}""")
            }.reason shouldBe FormatToolException.Reason.UNKNOWN_TOOL
        }

        "malformed args are SCHEMA_INVALID" {
            // RenderTable without `content`.
            shouldThrow<FormatToolException> {
                RenderCallCodec.parse("""{"tool":"RenderTable","text":"x"}""")
            }.reason shouldBe FormatToolException.Reason.SCHEMA_INVALID
            // RenderChart intent missing required `x`.
            shouldThrow<FormatToolException> {
                RenderCallCodec.parse("""{"tool":"RenderChart","content":[],"intent":{"kind":"line"}}""")
            }.reason shouldBe FormatToolException.Reason.SCHEMA_INVALID
        }
    })
