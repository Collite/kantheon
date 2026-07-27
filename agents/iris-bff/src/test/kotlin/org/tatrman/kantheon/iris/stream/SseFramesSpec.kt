package org.tatrman.kantheon.iris.stream

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SseFramesSpec :
    StringSpec({

        fun frames(body: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            SseFrameAccumulator.consume(body) { event, data -> out += event to data }
            return out
        }

        "splits on blank lines and drops comment keepalives" {
            frames(": ready\n\nevent: a\ndata: {\"x\":1}\n\n: ping\n\nevent: b\ndata: {}\n\n") shouldContainExactly
                listOf("a" to """{"x":1}""", "b" to "{}")
        }

        "joins multi-`data:` lines with a newline, per the SSE spec" {
            frames("event: a\ndata: line one\ndata: line two\n\n") shouldContainExactly
                listOf("a" to "line one\nline two")
        }

        "strips exactly one space after the colon — not surrounding whitespace" {
            // A full trim would corrupt whitespace-significant payloads.
            frames("event: a\ndata:  padded \n\n") shouldContainExactly listOf("a" to " padded ")
        }

        "flush emits a frame left unterminated by the stream closing" {
            val out = mutableListOf<Pair<String, String>>()
            val acc = SseFrameAccumulator { event, data -> out += event to data }
            acc.onLine("event: a")
            acc.onLine("data: {}")
            out.size shouldBe 0
            acc.flush()

            out shouldContainExactly listOf("a" to "{}")
        }

        "data with no event name is not a frame" {
            frames("data: orphaned\n\n") shouldContainExactly emptyList()
        }
    })
