package org.tatrman.kantheon.iris.dispatch.golem

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.core.spec.style.StringSpec
import org.tatrman.kantheon.golem.v1.Status

/**
 * Golem's SSE wire (`agents/golem` `SseAnswer`). The load-bearing difference from /v2 is the
 * terminal frame: it carries a whole `ConversationalResponse`, not a bare envelope.
 */
class GolemSseParserSpec :
    StringSpec({

        "parses the full frame sequence of a completed turn" {
            val events = GolemSseParser.parse(GolemFixtures.sseBody(GolemFixtures.response()))

            events.map { it::class.simpleName } shouldBe
                listOf("NodeStart", "NodeDone", "PlanPick", "ExecDone", "Turn")
            (events[0] as GolemV1Event.NodeStart).node shouldBe "compose"
            (events[2] as GolemV1Event.PlanPick).source shouldBe "PATTERN"
            (events[3] as GolemV1Event.ExecDone).rowCount shouldBe 72L
        }

        "the terminal frame decodes into a ConversationalResponse, not a bare envelope" {
            val response =
                GolemFixtures.response(
                    status = Status.STATUS_CLARIFICATION,
                    envelopes = listOf(GolemFixtures.clarificationEnvelope("rt-1")),
                )
            val terminal =
                GolemSseParser.parse(GolemFixtures.sseBody(response)).last().shouldBeInstanceOf<GolemV1Event.Turn>()

            terminal.response.status shouldBe Status.STATUS_CLARIFICATION
            terminal.response.envelopesCount shouldBe 1
            terminal.response.golemId shouldBe "golem-hartland"
        }

        "`: ready` / `: ping` keepalives are not events" {
            GolemSseParser.parse(": ready\n\n: ping\n\n: ping\n\n") shouldBe emptyList()
        }

        "an undecodable terminal frame becomes a terminal error, never a dropped turn" {
            // Dropping it would leave the stream with no terminal event at all — which the
            // user sees as a silent, empty answer bubble.
            val event = GolemSseParser.parse("event: envelope\ndata: {\"status\":\n\n").single()

            event.shouldBeInstanceOf<GolemV1Event.Error>().code shouldBe "GOLEM_BAD_ENVELOPE"
        }

        "unknown event names are skipped rather than failing the stream" {
            val body = "event: some_future_frame\ndata: {}\n\nevent: node_done\ndata: {\"node\":\"execute\"}\n\n"

            GolemSseParser
                .parse(body)
                .single()
                .shouldBeInstanceOf<GolemV1Event.NodeDone>()
                .node shouldBe "execute"
        }

        "tolerates CRLF line endings and multi-`data:` frames" {
            val body = "event: error\r\ndata: {\"code\":\"BOOM\",\r\ndata: \"message\":\"kaput\"}\r\n\r\n"
            val event = GolemSseParser.parse(body).single().shouldBeInstanceOf<GolemV1Event.Error>()

            event.code shouldBe "BOOM"
            event.message shouldBe "kaput"
        }
    })
