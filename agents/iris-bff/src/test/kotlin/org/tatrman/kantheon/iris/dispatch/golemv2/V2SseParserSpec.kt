package org.tatrman.kantheon.iris.dispatch.golemv2

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class V2SseParserSpec :
    StringSpec({

        "parses the happy-path stream, skipping comment frames" {
            val events = V2SseParser.parse(sseFixture("happy-table.sse"))
            events.size shouldBe 9 // 6 node_* + plan_pick + exec_done + envelope (`: ready`/`: ping` skipped)
            events.first().shouldBeInstanceOf<V2StreamEvent.NodeStart>()
            (events.first() as V2StreamEvent.NodeStart).node shouldBe "bootstrap"

            val planPick = events.filterIsInstance<V2StreamEvent.PlanPick>().single()
            planPick.source shouldBe "pattern"
            planPick.patternId shouldBe "revenue-by-month"
            planPick.score shouldBe 0.93

            val exec = events.filterIsInstance<V2StreamEvent.ExecDone>().single()
            exec.rowCount shouldBe 2L
            exec.durationMs shouldBe 812L

            events.last().shouldBeInstanceOf<V2StreamEvent.Envelope>()
        }

        "parses a clarification terminal envelope" {
            val events = V2SseParser.parse(sseFixture("clarification.sse"))
            val env = events.last().shouldBeInstanceOf<V2StreamEvent.Envelope>()
            env.raw.containsKey("pending_clarification") shouldBe true
        }

        "parses an error terminal" {
            val events = V2SseParser.parse(sseFixture("error.sse"))
            val err = events.last().shouldBeInstanceOf<V2StreamEvent.Error>()
            err.code shouldBe "STREAM_ERROR"
        }
    })
